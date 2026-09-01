# saga-blind

> "In the kingdom of the blind, the one-eyed man is the king."  
> — and here, the one-eyed man is the WAL.

**saga-blind** is a SAGA orchestration runtime that executes business logic it has never seen.

This project exists because the combination of ideas it explores is interesting to build and worth understanding — not because it solves a problem better than existing tools. It is an honest aggregation of known theory and practice, pushing some of those ideas a little further than usual and seeing what happens.

→ [Architecture diagrams](doc/architecture.md)

## What it does

saga-blind runs as a service. It starts with an empty registry and waits. When a `.saga` definition file appears in its watched folder, it registers the saga by name. When a client sends a launch request with a name and some initial data, the engine loads the jar declared in the definition, resolves the parameter mappings, and runs the saga.

The engine never knows what the steps do. It only knows:

- There is a WAL. Every state transition is written to disk before it happens.
- There is a pool. Steps deposit outputs into it; the engine extracts inputs from it.
- There is compensation. If a mandatory step fails, the engine compensates in reverse order.

Everything else — the steps, the logic, the data — arrives at runtime.

## How it works

**1. Register a saga definition**

Drop a `.saga` file in the watched folder:

```yaml
# definitions/armour-yourself.saga

saga: armour-yourself
jar: /services/goblin-armour.jar

steps:
  - id: measurements
    kind: mandatory
    class: com.goblin.MeasurementsService
    inputs:
      - param: goblinId
        from: __init__/goblinId

  - parallel:
    - id: smithy
      kind: mandatory
      class: com.goblin.SmithyService
      inputs:
        - param: armLength
          from: measurements/result.armLength
    - id: boots
      kind: mandatory
      class: com.goblin.BootsService
      inputs:
        - param: footSize
          from: measurements/result.footSize

  - id: getHat
    kind: mandatory
    class: com.goblin.HatService
    inputs:
      - param: goblinId
        from: __init__/goblinId
      - param: headPerimeter
        from: measurements/result.head
    compensate:
      - param: hatSerialNumber
        from: getHat/output.serialNumber
```

The engine picks it up immediately and registers it by name. No restart needed.

**2. Launch an instance**

```bash
POST /sagas/launch
{
  "definition": "armour-yourself",
  "params": {
    "goblinId": "G-042",
    "allergyProfile": "none"
  }
}

→ 202 { "sagaId": "...", "status": "launched" }
```

The params become the initial contents of the saga's data pool, owned by `__init__`.

**3. Query and control**

```bash
GET  /sagas/available                        registered saga names (Playing only)
GET  /sagas                                  all instances with status
GET  /sagas/definitions                      all definitions with lifecycle status
POST /sagas/definitions/:name/pause          pause — instances finish current step, no new launches
POST /sagas/definitions/:name/continue       resume after pause
POST /sagas/definitions/:name/stop           stop — compensates all in-flight instances
DELETE /sagas/definitions/:name              remove definition (only when stopped and empty)
```

## Parameter mapping

Data flows between steps through the pool. The DSL declares exactly which pool values go to which method parameters — the jar knows nothing about the pool.

The `from` expression follows the pattern `owner/key.jsonPath`:

```
__init__/goblinId                   full value of goblinId from launch params
measurements/result.head            field 'head' inside measurements output
A/candidateCollection[1]            second element of A's candidateCollection
getHat/output.serialNumber          field inside getHat's output object
```

The engine resolves these mappings before each call. If a mapping references a key that has not been deposited yet, the step fails with a clear error. Since the pool is append-only and keys have exactly one owner, the engine can also validate mappings at load time — a step cannot reference an owner that appears later in the definition.

## The pool

Each saga instance has an isolated **Owner Key Value (OKV)** pool.

- Every key has exactly one owner — the step that deposited it.
- No two steps can claim the same key. Enforced at both application and database level.
- Steps in a parallel block can only read keys deposited before the block — not from their siblings.
- The full pool is persisted to the WAL as deltas after each step. If the process dies, the pool survives.

## The jar contract

Every step class in the jar implements one trait:

```scala
trait SagaStepProvider:
  def stepId: String

  // args: resolved from the OKV using the DSL input mappings
  // returns: outputs to deposit in the OKV under this step's ownership
  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]]

  // args: resolved from the OKV using the DSL compensate mappings
  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit]
```

The jar is pure business logic. It receives named parameters and returns named outputs. It does not know about the pool, the WAL, or the orchestration around it.

The engine loads the jar at launch time using a dedicated `URLClassLoader` per saga instance — full class isolation between concurrent sagas. Each classloader is released when its saga completes.

## The service registry

saga-blind maintains a local registry of available saga definitions. Clients refer to sagas by name only. The engine resolves the name to a definition internally — where the definition lives on disk is not the client's concern.

Definition lifecycle:

```
Playing → Pause  → Playing (continue)
Playing → Stop   → (compensates in-flight) → Stopped → Remove
```

Modifying a `.saga` file while instances are in flight is not supported.
The engine does not version saga definitions — a mid-flight change would
create two versions of the same saga running concurrently with no way to
reconcile them. To update a definition: stop the saga, wait for in-flight
instances to finish, delete the file, and drop the new version.
The cost of versioning outweighs the convenience of hot-modification.

## Semantic validation

When a `.saga` file is loaded, the engine validates that every `from` expression references an owner that has already executed at that point in the definition. A step cannot read from an owner that appears later — this is detected at load time, before any step runs.

## Risk

This is not a safe design by default:

- **No type safety at runtime.** Steps exchange data through a pool of opaque JSON values. Wrong keys or types are not caught until execution.
- **Dynamic class loading is a security surface.** Any jar the definition points to will be loaded and executed. saga-blind assumes a trusted deployment environment.
- **Compensation depends on what steps deposit.** If a step fails before depositing its outputs, the engine cannot extract compensation parameters. The WAL records the failure; the resource may be lost.
- **Memory pressure under load.** One `URLClassLoader` per saga instance means one copy of the jar's classes per running saga. With many concurrent sagas and large jars, heap pressure is real. The engine exposes JVM stats for this purpose.

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
- Dynamic jar loading with classloader isolation and JVM observability
- DSL parser with input/compensate parameter mappings
- Semantic validator — detects forward references at load time
- HTTP runtime with FileWatcher and service registry
- Definition lifecycle — pause, continue, stop, remove
- LIFO compensation with parameter resolution from the pool

ZombieHunter, soft shutdown, and the demo project are next.

The goblins are being armed.

## License

Apache 2.0
