package sagablind.core

import sagablind.control.SagaControl
import sagablind.loader.SagaStepProvider
import sagablind.pool.PersistentOkvPool
import sagablind.store.WalStore
import sagablind.{SagaLogger, BoundLogger, SagaContext}
import org.slf4j.MDC

import scala.concurrent.{Future, Await, ExecutionContext}
import scala.concurrent.duration.*

// ── SagaExecutor ─────────────────────────────────────────────────────────────
// Factory — creates a SagaExecution per saga instance.
// Stateless: all execution state lives in SagaExecution.

class SagaExecutor(store: WalStore, registry: SagaControl, logger: SagaLogger = SagaLogger.noOp):

  def execute(
    sagaId:     SagaId,
    definition: SagaDefinition,
    providers:  Map[String, SagaStepProvider],
    pool:       PersistentOkvPool,
  ): Either[String, Unit] =
    SagaExecution(sagaId, definition, providers, pool, store, registry, logger).run()

// ── SagaExecution ─────────────────────────────────────────────────────────────
// One instance per saga execution.
// Owns the executed steps list — no mutable state travels between methods.
//
// TODO good-first-issue: move SagaValidator.validate to SagaControl.publish
// TODO good-first-issue idiomatic-scala: replace mutable executed with
//   runSteps returning Either[String, List[StepDescriptor]]

private class SagaExecution(
  sagaId:     SagaId,
  definition: SagaDefinition,
  providers:  Map[String, SagaStepProvider],
  pool:       PersistentOkvPool,
  store:      WalStore,
  registry:   SagaControl,
  logger:     SagaLogger,
):
  private val log      = logger.forComponent("engine")
  private val executed = scala.collection.mutable.ListBuffer.empty[StepDescriptor]

  def run(): Either[String, Unit] =
    SagaValidator.validate(definition) match
      case Left(err) => return Left(s"Saga '${definition.id}' failed validation:\n$err")
      case Right(()) => ()

    MDC.put("sagaId", sagaId.value.take(8))
    store.insertSaga(sagaId, definition.id, SagaStatus.Running)
    log.info(s"saga '${definition.id}' started")

    val result = runSteps()

    result match
      case Right(()) =>
        store.updateSagaStatus(sagaId, SagaStatus.Done)
        log.info(s"saga '${definition.id}' done")
        MDC.remove("sagaId")
        Right(())
      case Left(err) =>
        compensateLIFO()
        store.updateSagaStatus(sagaId, SagaStatus.Compensated)
        log.warn(s"saga '${definition.id}' compensated — $err")
        MDC.remove("sagaId")
        Left(err)

  private def runSteps(): Either[String, Unit] =
    definition.steps.foldLeft(Right(()): Either[String, Unit]):
      case (Left(err), _) => Left(err)
      case (Right(()), element) =>
        registry.statusOf(definition.id) match
          case Some(DefinitionStatus.Stopped) =>
            Left(s"Saga '${definition.id}' stopped by operator")
          case _ =>
            executeElement(element)

  private def executeElement(element: SagaElement): Either[String, Unit] =
    element match
      case SagaElement.Single(descriptor) => executeStep(descriptor)
      case SagaElement.Parallel(steps)    => executeParallel(steps)

  private def executeStep(descriptor: StepDescriptor): Either[String, Unit] =
    providers.get(descriptor.id) match
      case None =>
        Left(s"No provider found for step '${descriptor.id}'")
      case Some(provider) =>
        store.insertStep(sagaId, descriptor.id, descriptor.kind, StepStatus.Registered)
        ParamExtractor.resolve(descriptor.inputMappings, pool.memory) match
          case Left(err) =>
            store.updateStepStatus(sagaId, descriptor.id, StepStatus.Failed)
            descriptor.kind match
              case StepKind.Mandatory => Left(s"Step '${descriptor.id}' param extraction failed: $err")
              case _                  => Right(())
          case Right(args) =>
            val argsStr = args.map((k, v) => s"$k:${v.toString.take(20)}").mkString(", ")
            log.info(s"→ '${descriptor.id}' {$argsStr}")
            provider.execute(args) match
              case Right(outputs) =>
                pool.depositDelta(descriptor.id, outputs) match
                  case Left(err) =>
                    store.updateStepStatus(sagaId, descriptor.id, StepStatus.Failed)
                    Left(s"Step '${descriptor.id}' output deposit failed: $err")
                  case Right(()) =>
                    store.updateStepStatus(sagaId, descriptor.id, StepStatus.Done)
                    val outStr = outputs.map((k, v) => s"$k:${v.toString.take(20)}").mkString(", ")
                    log.info(s"← '${descriptor.id}' done {$outStr}")
                    executed += descriptor
                    Right(())
              case Left(err) =>
                store.updateStepStatus(sagaId, descriptor.id, StepStatus.Failed)
                log.warn(s"← '${descriptor.id}' failed: ${err.getMessage}")
                descriptor.kind match
                  case StepKind.Mandatory  => Left(s"Mandatory step '${descriptor.id}' failed: ${err.getMessage}")
                  case StepKind.Optional   => Right(())
                  case StepKind.BestEffort => Right(())

  private def executeParallel(steps: List[StepDescriptor]): Either[String, Unit] =
    given ExecutionContext = ExecutionContext.global
    val sid = sagaId.value.take(8)
    log.info(s"⇉ parallel [${steps.map(_.id).mkString(", ")}]")
    val futures = steps.map: descriptor =>
      Future(executeStepInParallel(descriptor, sid))
    val results = Await.result(Future.sequence(futures), 30.seconds)
    log.info(s"⇇ parallel [${steps.map(_.id).mkString(", ")}] joined")
    results.collectFirst { case Left(err) => Left(err) }.getOrElse(Right(()))

  private def compensateLIFO(): Unit =
    executed.toList.reverse.foreach: descriptor =>
      providers.get(descriptor.id).foreach: provider =>
        ParamExtractor.resolve(descriptor.compensateMappings, pool.memory) match
          case Left(err) =>
            log.warn(s"compensation param extraction failed for '${descriptor.id}': $err")
          case Right(args) =>
            log.info(s"↩ compensating '${descriptor.id}'")
            provider.compensate(args) match
              case Right(()) => log.info(s"↩ '${descriptor.id}' compensated")
              case Left(err) => log.warn(s"↩ '${descriptor.id}' compensation failed: ${err.getMessage}")

  // Called from parallel Futures — ScopedValue does not propagate to ExecutionContext.global
  // so sagaId is passed explicitly for logging.
  private def executeStepInParallel(descriptor: StepDescriptor, sid: String): Either[String, Unit] =
    providers.get(descriptor.id) match
      case None =>
        Left(s"No provider found for step '${descriptor.id}'")
      case Some(provider) =>
        store.insertStep(sagaId, descriptor.id, descriptor.kind, StepStatus.Registered)
        ParamExtractor.resolve(descriptor.inputMappings, pool.memory) match
          case Left(err) =>
            store.updateStepStatus(sagaId, descriptor.id, StepStatus.Failed)
            descriptor.kind match
              case StepKind.Mandatory => Left(s"Step '${descriptor.id}' param extraction failed: $err")
              case _                  => Right(())
          case Right(args) =>
            val argsStr = args.map((k, v) => s"$k:${v.toString.take(20)}").mkString(", ")
            log.info(s"[$sid] → '${descriptor.id}' {$argsStr}")
            provider.execute(args) match
              case Right(outputs) =>
                pool.depositDelta(descriptor.id, outputs) match
                  case Left(err) =>
                    store.updateStepStatus(sagaId, descriptor.id, StepStatus.Failed)
                    Left(s"Step '${descriptor.id}' output deposit failed: $err")
                  case Right(()) =>
                    store.updateStepStatus(sagaId, descriptor.id, StepStatus.Done)
                    val outStr = outputs.map((k, v) => s"$k:${v.toString.take(20)}").mkString(", ")
                    log.info(s"[$sid] ← '${descriptor.id}' done {$outStr}")
                    executed += descriptor
                    Right(())
              case Left(err) =>
                store.updateStepStatus(sagaId, descriptor.id, StepStatus.Failed)
                log.warn(s"[$sid] ← '${descriptor.id}' failed: ${err.getMessage}")
                descriptor.kind match
                  case StepKind.Mandatory  => Left(s"Mandatory step '${descriptor.id}' failed: ${err.getMessage}")
                  case StepKind.Optional   => Right(())
                  case StepKind.BestEffort => Right(())
