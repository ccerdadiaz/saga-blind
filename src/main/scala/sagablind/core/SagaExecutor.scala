package sagablind.core

import sagablind.control.SagaServiceRegistry
import sagablind.loader.{JarLoader, SagaStepProvider}
import sagablind.pool.PersistentOkvPool
import sagablind.store.WalStore

import scala.concurrent.{Future, Await, ExecutionContext}
import scala.concurrent.duration.*

// ── SagaExecutor ─────────────────────────────────────────────────────────────
// Executes a single saga instance.
// Checks definition status between steps — pauses or stops as instructed.
// WAL-before-action on every state transition.
//
// Parameter binding: the engine extracts args from the OKV using ParamExtractor
// before calling execute/compensate. The jar knows nothing about the OKV.

class SagaExecutor(store: WalStore, jarLoader: JarLoader, registry: SagaServiceRegistry):

  def execute(
    sagaId:     SagaId,
    definition: SagaDefinition,
    providers:  Map[String, SagaStepProvider],
    pool:       PersistentOkvPool,
  ): Either[String, Unit] =

    // validate semantic correctness before touching the WAL
    SagaValidator.validate(definition) match
      case Left(err) => return Left(s"Saga '${definition.id}' failed validation:\n$err")
      case Right(()) => ()

    store.insertSaga(sagaId, definition.id, SagaStatus.Running)

    // track executed steps for LIFO compensation
    val executed = scala.collection.mutable.ListBuffer.empty[StepDescriptor]

    val result = runSteps(sagaId, definition, providers, pool, executed)

    result match
      case Right(()) =>
        store.updateSagaStatus(sagaId, SagaStatus.Done)
        jarLoader.release(sagaId)
        Right(())
      case Left(err) =>
        compensateLIFO(sagaId, executed.toList.reverse, providers, pool)
        store.updateSagaStatus(sagaId, SagaStatus.Compensated)
        jarLoader.release(sagaId)
        Left(err)

  private def runSteps(
    sagaId:     SagaId,
    definition: SagaDefinition,
    providers:  Map[String, SagaStepProvider],
    pool:       PersistentOkvPool,
    executed:   scala.collection.mutable.ListBuffer[StepDescriptor],
  ): Either[String, Unit] =
    definition.steps.foldLeft(Right(()): Either[String, Unit]):
      case (Left(err), _) => Left(err)
      case (Right(()), element) =>
        registry.statusOf(definition.id) match
          case Some(DefinitionStatus.Paused) =>
            store.updateSagaStatus(sagaId, SagaStatus.PausedBetweenSteps)
            waitForContinue(definition.id)
            store.updateSagaStatus(sagaId, SagaStatus.Running)
            executeElement(sagaId, element, providers, pool, executed)
          case Some(DefinitionStatus.Stopped) =>
            Left(s"Saga '${definition.id}' stopped by operator")
          case _ =>
            executeElement(sagaId, element, providers, pool, executed)

  private def executeElement(
    sagaId:   SagaId,
    element:  SagaElement,
    providers: Map[String, SagaStepProvider],
    pool:     PersistentOkvPool,
    executed: scala.collection.mutable.ListBuffer[StepDescriptor],
  ): Either[String, Unit] =
    element match
      case SagaElement.Single(descriptor)  =>
        executeStep(sagaId, descriptor, providers, pool, executed)
      case SagaElement.Parallel(steps)     =>
        executeParallel(sagaId, steps, providers, pool, executed)

  private def executeStep(
    sagaId:     SagaId,
    descriptor: StepDescriptor,
    providers:  Map[String, SagaStepProvider],
    pool:       PersistentOkvPool,
    executed:   scala.collection.mutable.ListBuffer[StepDescriptor],
  ): Either[String, Unit] =
    providers.get(descriptor.id) match
      case None =>
        Left(s"No provider found for step '${descriptor.id}'")
      case Some(provider) =>
        store.insertStep(sagaId, descriptor.id, descriptor.kind, StepStatus.Registered)

        // extract args from OKV using input mappings
        ParamExtractor.resolve(descriptor.inputMappings, pool.memory) match
          case Left(err) =>
            store.updateStepStatus(sagaId, descriptor.id, StepStatus.Failed)
            descriptor.kind match
              case StepKind.Mandatory  => Left(s"Step '${descriptor.id}' param extraction failed: $err")
              case _                   => Right(())
          case Right(args) =>
            provider.execute(args) match
              case Right(outputs) =>
                // deposit outputs into OKV under this step's ownership
                pool.depositDelta(descriptor.id, outputs) match
                  case Left(err) =>
                    store.updateStepStatus(sagaId, descriptor.id, StepStatus.Failed)
                    Left(s"Step '${descriptor.id}' output deposit failed: $err")
                  case Right(()) =>
                    store.updateStepStatus(sagaId, descriptor.id, StepStatus.Done)
                    executed += descriptor
                    Right(())
              case Left(err) =>
                store.updateStepStatus(sagaId, descriptor.id, StepStatus.Failed)
                descriptor.kind match
                  case StepKind.Mandatory  => Left(s"Mandatory step '${descriptor.id}' failed: ${err.getMessage}")
                  case StepKind.Optional   => Right(())
                  case StepKind.BestEffort => Right(())

  private def executeParallel(
    sagaId:    SagaId,
    steps:     List[StepDescriptor],
    providers: Map[String, SagaStepProvider],
    pool:      PersistentOkvPool,
    executed:  scala.collection.mutable.ListBuffer[StepDescriptor],
  ): Either[String, Unit] =
    given ExecutionContext = ExecutionContext.global
    val futures = steps.map: descriptor =>
      Future(executeStep(sagaId, descriptor, providers, pool, executed))
    val results = Await.result(Future.sequence(futures), 30.seconds)
    results.collectFirst { case Left(err) => Left(err) }.getOrElse(Right(()))

  // ── Compensation LIFO ─────────────────────────────────────────────────────

  private def compensateLIFO(
    sagaId:    SagaId,
    steps:     List[StepDescriptor],  // already reversed
    providers: Map[String, SagaStepProvider],
    pool:      PersistentOkvPool,
  ): Unit =
    steps.foreach: descriptor =>
      providers.get(descriptor.id).foreach: provider =>
        ParamExtractor.resolve(descriptor.compensateMappings, pool.memory) match
          case Left(err) =>
            println(s"[SagaExecutor] compensation param extraction failed for '${descriptor.id}': $err")
          case Right(args) =>
            provider.compensate(args) match
              case Right(()) => ()
              case Left(err) =>
                println(s"[SagaExecutor] compensation failed for '${descriptor.id}': ${err.getMessage}")

  private def waitForContinue(sagaName: String): Unit =
    while registry.statusOf(sagaName).contains(DefinitionStatus.Paused) do
      Thread.sleep(500)
