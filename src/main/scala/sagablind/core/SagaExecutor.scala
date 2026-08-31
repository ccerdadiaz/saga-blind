package sagablind.core

import sagablind.loader.SagaStepProvider
import sagablind.pool.{OkvPool, PersistentOkvPool}
import sagablind.store.WalStore
import sagablind.loader.JarLoader

import scala.concurrent.{Future, Await, ExecutionContext}
import scala.concurrent.duration.*

// ── SagaExecutor ─────────────────────────────────────────────────────────────
// Executes a single saga instance.
// Persists every state transition to WAL before acting — WAL-before-action.

class SagaExecutor(store: WalStore, jarLoader: JarLoader):

  def execute(
    sagaId:     SagaId,
    definition: SagaDefinition,
    providers:  Map[String, SagaStepProvider],
    pool:       PersistentOkvPool,
  ): Either[String, Unit] =

    store.insertSaga(sagaId, definition.id, SagaStatus.Running)

    val result = definition.steps.foldLeft(Right(()): Either[String, Unit]):
      case (Left(err), _)  => Left(err)
      case (Right(()), element) =>
        element match
          case SagaElement.Single(descriptor)   => executeStep(sagaId, descriptor, providers, pool)
          case SagaElement.Parallel(steps)      => executeParallel(sagaId, steps, providers, pool)

    result match
      case Right(()) =>
        store.updateSagaStatus(sagaId, SagaStatus.Done)
        jarLoader.release(sagaId)
        Right(())
      case Left(err) =>
        store.updateSagaStatus(sagaId, SagaStatus.Failed)
        jarLoader.release(sagaId)
        Left(err)

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
