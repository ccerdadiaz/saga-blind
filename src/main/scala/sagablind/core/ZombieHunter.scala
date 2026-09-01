package sagablind.core

import sagablind.dsl.SagaDslParser
import sagablind.loader.{JarLoader, SagaStepProvider}
import sagablind.pool.PersistentOkvPool
import sagablind.store.{WalStore, StepRow, SagaRow}

import scala.concurrent.duration.*

// ── ZombieHunter ─────────────────────────────────────────────────────────────
// Scans the WAL periodically for instances the engine could not finish.
// Operates as a companion to the engine — same WAL, different trigger.
//
// Engine:       triggered by HTTP request
// ZombieHunter: triggered by anomaly detection (state in WAL)
//
// Started and stopped together with the engine as a pair.
// On shutdown, ZH stops immediately — no final iteration.
// Instances left in Unknown state are picked up on next engine start.
//
// ZH never touches:
//   PausedBetweenSteps — operator decision
//   Stopped            — operator decision
//   Done               — terminal correct
//   Compensated        — terminal correct
//   Failed             — engine already initiated compensation
//
// ZH acts on:
//   Running with Unknown steps — step exceeded TTL
//
// Policy (forward-first):
//   Unknown → attempt forward retry using reconstructed pool + jar
//           → if fails → compensate LIFO using reconstructed pool + jar
//
// Note: on shutdown, in-flight instances may be left in non-terminal state.
// This is an accepted risk — ZH will recover them on next start.

class ZombieHunter(
  store:     WalStore,
  jarLoader: JarLoader,
  stepTtl:   Duration = 30.seconds,
  scanEvery: Duration = 10.seconds,
  onAlert:   String => Unit = msg => System.err.println(s"[ZombieHunter] ALERT: $msg"),
):
  private var running = false

  def start(): Unit =
    running = true
    val thread = Thread(() => loop(), "saga-blind-zombie-hunter")
    thread.setDaemon(true)
    thread.start()
    println(s"[ZombieHunter] started — ttl=${stepTtl} scan=${scanEvery}")

  def stop(): Unit =
    running = false
    println("[ZombieHunter] stopped")

  private def loop(): Unit =
    while running do
      Thread.sleep(scanEvery.toMillis)
      try scan()
      catch case e: Exception =>
        println(s"[ZombieHunter] scan error: ${e.getMessage}")

  // ── Scan ──────────────────────────────────────────────────────────────────

  private def scan(): Unit =
    val now = java.time.Instant.now()
    store.allSagas()
      .filter(_.status == SagaStatus.Running)
      .foreach: saga =>
        val steps = store.stepsFor(saga.sagaId)

        // mark steps that exceeded TTL as Unknown
        steps
          .filter(_.status == StepStatus.Registered)
          .filter(s => exceededTtl(s.startedAt, now))
          .foreach: step =>
            println(s"[ZombieHunter] '${step.stepId}' in '${saga.sagaId.value}' exceeded TTL — Unknown")
            store.updateStepStatus(saga.sagaId, step.stepId, StepStatus.Unknown)

        // handle Unknown steps
        val unknownSteps = store.stepsFor(saga.sagaId).filter(_.status == StepStatus.Unknown)
        if unknownSteps.nonEmpty then
          handleUnknown(saga, unknownSteps)

  private def exceededTtl(startedAt: String, now: java.time.Instant): Boolean =
    try
      val started = java.time.Instant.parse(startedAt)
      java.time.Duration.between(started, now).toMillis > stepTtl.toMillis
    catch case _ => false

  // ── Recovery ──────────────────────────────────────────────────────────────

  private def handleUnknown(saga: SagaRow, unknownSteps: List[StepRow]): Unit =
    println(s"[ZombieHunter] recovering '${saga.sagaId.value}' — ${unknownSteps.size} Unknown step(s)")

    // reconstruct definition from WAL
    val definition = SagaDslParser.parse(saga.definition) match
      case Left(err) =>
        onAlert(s"Cannot parse definition for '${saga.sagaId.value}': $err")
        return
      case Right(d) => d

    // reconstruct pool from WAL
    val pool = PersistentOkvPool(saga.sagaId, store)
    pool.restore() match
      case Left(err) =>
        onAlert(s"Cannot restore pool for '${saga.sagaId.value}': $err")
        return
      case Right(()) => ()

    // load jar and instantiate providers
    val allDescriptors = definition.steps.flatMap:
      case SagaElement.Single(d)    => List(d)
      case SagaElement.Parallel(ds) => ds

    val providers = jarLoader.load(saga.sagaId, definition.jarPath, allDescriptors) match
      case Left(err) =>
        onAlert(s"Cannot load jar for '${saga.sagaId.value}': $err")
        return
      case Right(p) => p

    // attempt forward retry for each Unknown step
    val recovered = unknownSteps.forall: step =>
      val descriptor = allDescriptors.find(_.id == step.stepId)
      descriptor.flatMap(d => providers.get(d.id)) match
        case None =>
          onAlert(s"No provider for '${step.stepId}' in '${saga.sagaId.value}'")
          false
        case Some(provider) =>
          descriptor.get.inputMappings match
            case mappings =>
              ParamExtractor.resolve(mappings, pool.memory) match
                case Left(err) =>
                  println(s"[ZombieHunter] forward retry param extraction failed: $err — will compensate")
                  false
                case Right(args) =>
                  provider.execute(args) match
                    case Right(outputs) =>
                      pool.depositDelta(step.stepId, outputs)
                      store.updateStepStatus(saga.sagaId, step.stepId, StepStatus.Done)
                      println(s"[ZombieHunter] forward retry succeeded for '${step.stepId}'")
                      true
                    case Left(err) =>
                      println(s"[ZombieHunter] forward retry failed for '${step.stepId}': ${err.getMessage} — will compensate")
                      false

    if !recovered then
      compensateLIFO(saga, allDescriptors, providers, pool)

  // ── LIFO Compensation ─────────────────────────────────────────────────────

  private def compensateLIFO(
    saga:        SagaRow,
    descriptors: List[StepDescriptor],
    providers:   Map[String, SagaStepProvider],
    pool:        PersistentOkvPool,
  ): Unit =
    println(s"[ZombieHunter] compensating LIFO for '${saga.sagaId.value}'")

    val doneSteps = store.stepsFor(saga.sagaId)
      .filter(_.status == StepStatus.Done)
      .sortBy(_.startedAt)
      .reverse

    doneSteps.foreach: step =>
      descriptors.find(_.id == step.stepId).foreach: descriptor =>
        providers.get(descriptor.id).foreach: provider =>
          ParamExtractor.resolve(descriptor.compensateMappings, pool.memory) match
            case Left(err) =>
              onAlert(s"Compensation param extraction failed for '${step.stepId}': $err")
            case Right(args) =>
              provider.compensate(args) match
                case Right(()) =>
                  println(s"[ZombieHunter] compensated '${step.stepId}'")
                case Left(err) =>
                  onAlert(s"Compensation failed for '${step.stepId}': ${err.getMessage}")

    store.updateSagaStatus(saga.sagaId, SagaStatus.Compensated)
    jarLoader.release(saga.sagaId)
