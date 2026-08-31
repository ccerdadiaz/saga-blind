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

class SagaExecutor(store: WalStore, jarLoader: JarLoader, registry: SagaServiceRegistry):

  def execute(
    sagaId:     SagaId,
    definition: SagaDefinition,
    providers:  Map[String, SagaStepProvider],
    pool:       PersistentOkvPool,
  ): Either[String, Unit] =

    store.insertSaga(sagaId, definition.id, SagaStatus.Running)

    val result = runSteps(sagaId, definition, providers, pool)

    result match
      case Right(()) =>
        store.updateSagaStatus(sagaId, SagaStatus.Done)
        jarLoader.release(sagaId)
        Right(())
      case Left(err) =>
        store.updateSagaStatus(sagaId, SagaStatus.Failed)
        jarLoader.release(sagaId)
        Left(err)

  private def runSteps(
    sagaId:     SagaId,
    definition: SagaDefinition,
    providers:  Map[String, SagaStepProvider],
    pool:       PersistentOkvPool,
  ): Either[String, Unit] =
    definition.steps.foldLeft(Right(()): Either[String, Unit]):
      case (Left(err), _) => Left(err)
      case (Right(()), element) =>
        // check definition status before each step — pause or stop if instructed
        registry.statusOf(definition.id) match
          case Some(DefinitionStatus.Paused) =>
            store.updateSagaStatus(sagaId, SagaStatus.PausedBetweenSteps)
            waitForContinue(definition.id)
            store.updateSagaStatus(sagaId, SagaStatus.Running)
            executeElement(sagaId, element, providers, pool)
          case Some(DefinitionStatus.Stopped) =>
            store.updateSagaStatus(sagaId, SagaStatus.Compensated)
            Left(s"Saga '${definition.id}' stopped — instance ${sagaId.value} compensated")
          case _ =>
            executeElement(sagaId, element, providers, pool)

  private def executeElement(
    sagaId:    SagaId,
    element:   SagaElement,
    providers: Map[String, SagaStepProvider],
    pool:      PersistentOkvPool,
  ): Either[String, Unit] =
    element match
      case SagaElement.Single(descriptor)  => executeStep(sagaId, descriptor, providers, pool)
      case SagaElement.Parallel(steps)     => executeParallel(sagaId, steps, providers, pool)

  private def executeStep(
    sagaId:     SagaId,
    descriptor: StepDescriptor,
    providers:  Map[String, SagaStepProvider],
    pool:       PersistentOkvPool,
  ): Either[String, Unit] =
    providers.get(descriptor.id) match
      case None =>
        Left(s"No provider found for step '${descriptor.id}'")
      case Some(provider) =>
        store.insertStep(sagaId, descriptor.id, descriptor.kind, StepStatus.Registered)
        provider.execute(pool.memory) match
          case Right(()) =>
            store.updateStepStatus(sagaId, descriptor.id, StepStatus.Done)
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
  ): Either[String, Unit] =
    given ExecutionContext = ExecutionContext.global
    val futures = steps.map: descriptor =>
      Future(executeStep(sagaId, descriptor, providers, pool))
    val results = Await.result(Future.sequence(futures), 30.seconds)
    results.collectFirst { case Left(err) => Left(err) }.getOrElse(Right(()))

  /** Block until the definition returns to Playing.
   *  Polls every 500ms — simple and sufficient for this use case. */
  private def waitForContinue(sagaName: String): Unit =
    while registry.statusOf(sagaName).contains(DefinitionStatus.Paused) do
      Thread.sleep(500)
