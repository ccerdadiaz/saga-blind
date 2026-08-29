# saga-blind

> "In the kingdom of the blind, the one-eyed man is the king."  

**saga-blind** is a dynamic SAGA orchestration runtime that executes business logic it has never seen.

The engine is blind to domain. It does not know what a `smithy` is, or what a `measurements` step does, or what data flows between them. It only knows three things:

- There is a WAL. Every state transition is durable before it happens.
- There is a data pool. Every step reads from it and writes to it.
- There is compensation. If anything fails, the engine knows how to go back.

Everything else — the steps, the logic, the data — arrives at runtime, in a jar, described by a DSL the engine reads from a watched folder.

## Why this exists

Honestly? Because it was an interesting problem to build.

The idea of a saga engine that knows nothing about the business logic it orchestrates — that loads steps, data contracts, and compensation rules at runtime from jars is architecturally provocative. Whether it is also useful in production is a question this project exists to answer.

The secondary goal is to find out how far you can push dynamic class loading and a shared data pool before the design breaks down. There are known failure modes. We are building this to understand them, not to pretend they do not exist.

And, obviously, the Dark Lord will eventually be satisfied.

## Risk

This is not a safe design. It comes with real dangers that cannot be fully mitigated:

- **No type safety at runtime.** Steps exchange data through a pool of opaque JSON values. A step that deposits the wrong type, the wrong key, or nothing at all will not be caught until execution — and by then, some actions may already be irreversible.
- **Dynamic class loading is a security surface.** Any jar dropped in the watched folder will be loaded and executed. In an uncontrolled environment, this is a serious vulnerability. saga-blind assumes a trusted deployment context.
- **Compensation depends on what steps deposit.** If a step fails before depositing its compensation arguments, the engine cannot roll back that step. The WAL will record the failure; the resource may be lost.
- **The jar is a black box.** The engine cannot verify that a step does what its descriptor claims. A lying descriptor is a runtime explosion waiting to happen.

These are not bugs to be fixed. They are the price of the flexibility this design provides. Use accordingly.

## How it works

```
/inbox/goblin-campaign.saga   ← drop a DSL definition here
/libs/goblin-services.jar     ← drop the implementation here

saga-blind picks them up, loads the jar, builds the graph,
starts the saga — without restarting, without recompiling.
```

The engine was running before it knew what a goblin was. The same engine, without modification, can orchestrate completely different sagas by dropping different jars and DSL files.

## The pool

Each saga instance has an isolated **Owner Key Value (OKV) blackboard** — a shared data pool where steps deposit and consume data.

- Every key has exactly one owner — the step that deposited it.
- No two steps can claim the same key. The engine enforces this structurally.
- The full pool is persisted in the WAL as deltas. If the process dies, the pool survives.

Sequential steps can consume what previous steps deposited. Parallel steps deposit independently. The join waits for all owners to report — not in order, but complete.

## Compensation

Compensation parameters are declared as **JSONPath extractors** over the pool:

```yaml
steps:
  - id: smithy
    class: com.goblin.SmithyService
    compensationExtractors:
      - key: weaponId
        path: "$.weapon.id"
        type: UUID
```

The engine extracts `weaponId` from the pool after `smithy` executes, persists it in the WAL, and uses it to compensate if needed — without knowing what a weapon is.

## The DSL

```
saga: goblin-campaign
jar: /libs/goblin-services.jar

steps:
  mandatory: measurements
  parallel:
    - smithy
    - boots
  optional: portrait
  bestEffort: notification
```

Drop it in `/inbox`. The engine does the rest.

## Control

```
saga-blind list              # running, done, failed sagas
saga-blind stop  <sagaId>    # pause
saga-blind start <sagaId>    # resume
saga-blind drop  <sagaId>    # remove
```

## Relation to saga-graph

saga-blind uses [saga-graph](https://github.com/ccerdadiaz/saga-graph) as its compensation engine.
saga-graph handles the WAL, the LIFO compensation, and the ZombieHunter recovery.
saga-blind handles everything the engine should not need to know.

They are separate projects. saga-graph runs fine without saga-blind.
saga-blind would not exist without saga-graph.

## Status

Early design phase. The goblins are being recruited.

## License

Apache 2.0
