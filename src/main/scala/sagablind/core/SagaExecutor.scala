package sagablind.core

import sagablind.control.SagaControl
import sagablind.loader.SagaStepProvider
import sagablind.pool.PersistentOkvPool
import sagablind.store.WalStore

import scala.concurrent.{Future, Await, ExecutionContext}
import scala.concurrent.duration.*

// ── SagaExecutor ─────────────────────────────────────────────────────────────
// Factory — creates a SagaExecution per saga instance.
// Stateless: all execution state lives in SagaExecution.

class SagaExecutor(store: WalStore, registry: SagaControl):

  def execute(
    sagaId:     SagaId,
    definition: SagaDefinition,
    providers:  Map[String, SagaStepProvider],
    pool:       PersistentOkvPool,
  ): Either[String, Unit] =
    SagaExecution(sagaId, definition, providers, pool, store, registry).run()

// ── SagaExecution ─────────────────────────────────────────────────────────────
// One instance per saga execution.
// Owns the executed steps list — no mutable state travels between methods.
//
// WAL-before-action on every state transition.
// Parameter binding via ParamExtractor — the jar knows nothing about the OKV.
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
):
  private val executed = scala.collection.mutable.ListBuffer.empty[StepDescriptor]

  def run(): Either[String, Unit] =
    SagaValidator.validate(definition) match
      case Left(err) => return Left(s"Saga '${definition.id}' failed validation:\n$err")
      case Right(()) => ()

    store.insertSaga(sagaId, definition.id, SagaStatus.Running)

    val result = runSteps()

    result match
      case Right(()) =>
        store.updateSagaStatus(sagaId, SagaStatus.Done)
        Right(())
      case Left(err) =>
        compensateLIFO()
        store.updateSagaStatus(sagaId, SagaStatus.Compensated)
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
            provider.execute(args) match
              case Right(outputs) =>
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

  private def executeParallel(steps: List[StepDescriptor]): Either[String, Unit] =
    given ExecutionContext = ExecutionContext.global
    val futures = steps.map(descriptor => Future(executeStep(descriptor)))
    val results = Await.result(Future.sequence(futures), 30.seconds)
    results.collectFirst { case Left(err) => Left(err) }.getOrElse(Right(()))

  private def compensateLIFO(): Unit =
    executed.toList.reverse.foreach: descriptor =>
      providers.get(descriptor.id).foreach: provider =>
        ParamExtractor.resolve(descriptor.compensateMappings, pool.memory) match
          case Left(err) =>
            println(s"[SagaExecution] compensation param extraction failed for '${descriptor.id}': $err")
          case Right(args) =>
            provider.compensate(args) match
              case Right(()) => ()
              case Left(err) =>
                println(s"[SagaExecution] compensation failed for '${descriptor.id}': ${err.getMessage}")
