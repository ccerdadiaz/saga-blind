package sagablind

import sagablind.core.SagaId

// ── SagaContext ───────────────────────────────────────────────────────────────
// Scoped access to the current SagaId.
// Uses Java 22+ ScopedValue — same approach as saga-graph.
// Propagates automatically across threads including Future fork branches.
//
// For Java 17/21 compatibility, InheritableThreadLocal is an alternative
// with limitations in virtual thread environments.

object SagaContext:

  private val _sagaId: ScopedValue[SagaId] = ScopedValue.newInstance()

  def run[A](id: SagaId)(block: => A): A =
    ScopedValue.where(_sagaId, id).call(() => block)

  def current: Option[SagaId] =
    if _sagaId.isBound then Some(_sagaId.get()) else None
