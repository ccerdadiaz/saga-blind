# saga-blind

> "In the kingdom of the blind, the one-eyed man is the king."  
> — and here, the one-eyed man is the WAL.

**saga-blind** is a SAGA orchestration runtime that executes business logic it has never seen.

This project exists because the combination of ideas it explores is interesting to build and worth understanding — not because it solves a problem better than existing tools. It is an honest aggregation of known theory and practice, pushing some of those ideas a little further than usual and seeing what happens.

→ [Architecture diagrams](doc/architecture.md)

## What it does

saga-blind runs as a service. It starts with an empty registry and waits. When a `.saga` definition file appears in its watched folder, it registers the saga by name. When a client sends a launch request with a name and some initial data, the engine loads the jar declared in the definition, instantiates the steps, and runs the saga.

The engine never knows what the steps do. It only knows:

- There is a WAL. Every state transition is written to disk before it happens.
- There is a pool. Steps read from it and write to it. That is how they communicate.
- There is compensation. If a mandatory step fails, the engine knows how to go back.

Everything else — the steps, the logic, the data — arrives at runtime.

## How it works

**1. Register a saga definition**

Drop a `.saga` file in the watched folder:

```
# definitions/armour-yourself.saga

saga: armour-yourself
jar: /services/goblin-armour.jar

steps:
  mandatory: measurements
  parallel:
    - smithy
    - boots
  optional: portrait
  bestEffort: notification
```

The engine picks it up immediately and registers it by name. No restart needed.

**2. Launch an instance**

```bash
POST /sagas/launch
{
  "definition": "armour-yourself",
  "params": {
    "allergyProfile": "none",
    "estimatedAge": 47,
    "skinColour": "green"
  }
}

→ 202 { "sagaId": "...", "status": "launched" }
```

The params become the initial contents of the saga's data pool, owned by `__init__`. Steps read what they need from the pool and deposit their outputs back — that is how data flows between steps without the engine knowing what it means.

**3. Query instances**

```bash
GET /sagas/available   → registered saga names
GET /sagas             → all instances with status
```

## The pool

Each saga instance has an isolated **Owner Key Value (OKV)** pool — a shared data space where steps deposit and consume values.

- Every key has exactly one owner — the step that deposited it.
- No two steps can claim the same key. The engine enforces this structurally, at both the application and database level.
- The full pool is persisted to the WAL as deltas after each step. If the process dies, the pool survives and can be reconstructed.

Sequential steps consume what previous steps deposited. Parallel steps deposit independently. The join waits for all to report — not in order, but complete.

Values in the pool are arbitrary JSON — nested objects, arrays of objects, whatever the step produces. The engine does not interpret them.

## Compensation

Compensation parameters are declared as JSONPath extractors in the step descriptor:

```scala
CompensationExtractor(key = "weaponId", path = "$.weapon.id", argType = ArgType.UUID)
```

After a step executes, the engine extracts the declared values from the pool and persists them in the WAL. If compensation is needed, those values are what gets passed to the compensate method — without the engine knowing what a weapon is.

## The jar contract

Every step class in the jar implements one trait:

```scala
trait SagaStepProvider:
  def descriptor: StepDescriptor         // declares id, kind, compensation extractors
  def execute(pool: OkvPool): Either[Throwable, Unit]
  def compensate(args: Map[String, String]): Either[Throwable, Unit]
```

The engine loads the jar at launch time using a dedicated `URLClassLoader` per saga instance — full class isolation between concurrent sagas. Each classloader is released when its saga completes.

## The service registry

saga-blind maintains a local registry of available saga definitions — populated by the FileWatcher as `.saga` files appear or disappear. Clients refer to sagas by name only; the engine resolves the name to a definition internally. Where the definition lives on disk is not the client's concern.

## Risk

This is not a safe design by default. Some of the dangers are inherent to the approach:

- **No type safety at runtime.** Steps exchange data through a pool of opaque JSON values. A step that deposits the wrong key or the wrong type will not be caught until execution.
- **Dynamic class loading is a security surface.** Any jar the definition points to will be loaded and executed. saga-blind assumes a trusted deployment environment.
- **Compensation depends on what steps deposit.** If a step fails before depositing its compensation arguments, the engine cannot roll back that step. The WAL records the failure; the resource may be lost.
- **Memory pressure under load.** One `URLClassLoader` per saga instance means one copy of the jar's classes per running saga. With many concurrent sagas and large jars, heap pressure is real and worth monitoring. The engine exposes JVM stats for this purpose.

These are not defects to be fixed later. They are the known cost of the flexibility the design provides.

## Configuration

```
SAGA_BLIND_DB       SQLite database path     (default: saga-blind.db)
SAGA_BLIND_WATCH    watched definitions dir  (default: ./definitions)
SAGA_BLIND_HOST     HTTP bind host           (default: 0.0.0.0)
SAGA_BLIND_PORT     HTTP port                (default: 7777)
```

## Relation to saga-graph

saga-blind is built on top of [saga-graph](https://github.com/ccerdadiaz/saga-graph), which provides the WAL, LIFO compensation, and ZombieHunter recovery.

saga-graph works fine without saga-blind. saga-blind would not exist without saga-graph.

## Status

Core layers implemented and tested:
- WAL store (SQLite)
- OKV pool with persistence and WAL-first writes
- Dynamic jar loading with classloader isolation
- DSL parser
- HTTP runtime with FileWatcher and service registry

Compensation execution, ZombieHunter forward-first recovery, and control endpoints are next.

The goblins are being armed.

## License

Apache 2.0
