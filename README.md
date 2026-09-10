# saga-blind

> "In the kingdom of the blind, the one-eyed man is the king."

**saga-blind** is a SAGA orchestration runtime that executes business logic it has never seen.

This project exists because the combination of ideas it explores is interesting to build and worth understanding — not because it solves a problem better than existing tools. It is an honest aggregation of known theory and practice, pushing some of those ideas a little further than usual and seeing what happens.

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture](doc/architecture.md) | System layers, components, data flows |
| [goblin-recruit](doc/goblin-recruit.md) | Demo saga — arms and enlists a goblin |

## Videos

| Video | Description |
|-------|-------------|
| [saga.mp4](doc/saga.mp4) | The engine — loading a definition and processing a request |
| [dsl-tour.mp4](doc/dsl-tour.mp4) | How a single DSL line becomes a step dependency |
| [okv-tour.mp4](doc/okv-tour.mp4) | The OKV pool filling step by step |
| [lifo-tour.mp4](doc/lifo-tour.mp4) | LIFO compensation when a step fails |
| [zh-tour.mp4](doc/zh-tour.mp4) | ZombieHunter — TTL detection and recovery |
| [saga-blind-console-demo.mp4](doc/saga-blind-console-demo.mp4) | Live demo — engine, launch, logs, shutdown |

## The problem

Distributed transactions are hard. You can't lock resources across services.
You can't roll back what already happened in another process. And when a
service goes silent — not failing, just *silent* — you don't know if it acted
or not.

## What it does

saga-blind runs as a service. It starts with an empty registry and waits. When a `.saga` definition file appears in its watched folder, it registers the saga by name. When a client sends a launch request with a name and some initial data, the engine loads the jar declared in the definition, resolves the parameter mappings, and runs the saga.

The engine never knows what the steps do. It only knows:

- There is a WAL. Every state transition is written to disk before it happens.
- There is a pool. Steps deposit outputs into it; the engine extracts inputs from it.
- There is compensation. If a mandatory step fails, the engine compensates in reverse order.
- There is a ZombieHunter. If a step exceeds its TTL, it reconstructs state from the WAL and retries.

Everything else — the steps, the logic, the data — arrives at runtime.

## Module structure

```
saga-blind-core         — engine, WalStore trait, SagaLogger trait
saga-blind-store-sqlite — SqliteWalStore implementation
saga-blind-examples     — goblin-world demo with logback and SQLite
```

## How it works

**1. Register a saga definition**

Drop a `.saga` file in the watched folder:

```yaml
saga: goblin-recruit
jar: ./libs/goblin-services.jar

steps:
  - id: measurements
    kind: mandatory
    class: sagablind.goblin.MeasurementsService
    inputs:
      - param: goblinId
        from: __init__/goblinId

  - parallel:
    - id: smithy
      kind: mandatory
      class: sagablind.goblin.SmithyService
      inputs:
        - param: armLength
          from: measurements/armLength
      compensate:
        - param: weaponId
          from: smithy/weaponId

    - id: boots
      kind: mandatory
      class: sagablind.goblin.BootsService
      inputs:
        - param: footSize
          from: measurements/footSize
      compensate:
        - param: bootId
          from: boots/bootId

  - id: getHat
    kind: mandatory
    class: sagablind.goblin.HatService
    inputs:
      - param: goblinId
        from: __init__/goblinId
      - param: head
        from: measurements/head
    compensate:
      - param: hatSerialNumber
        from: getHat/hatSerialNumber

  - id: enlist
    kind: mandatory
    class: sagablind.goblin.EnlistService
    inputs:
      - param: goblinId
        from: __init__/goblinId
      - param: weaponType
        from: smithy/weaponType
      - param: bootSize
        from: boots/bootSize
      - param: hatSerialNumber
        from: getHat/hatSerialNumber
    compensate:
      - param: enlistmentId
        from: enlist/enlistmentId
```

The engine picks it up immediately and registers it by name. No restart needed.

**2. Launch an instance**

```bash
POST /sagas/launch
{
  "definition": "goblin-recruit",
  "params": { "goblinId": "G-042" }
}

→ 202 { "sagaId": "a3f2c1d8-...", "status": "launched" }
```

**3. Follow the logs**

```
10:05:07.612 INFO  [engine] [a3f2c1d8] saga 'goblin-recruit' started
10:05:07.615 INFO  [engine] [a3f2c1d8] → 'measurements' {goblinId:"G-042"}
10:05:07.929 INFO  [engine] [a3f2c1d8] ← 'measurements' done {head:58, armLength:42, footSize:38}
10:05:07.931 INFO  [engine] [a3f2c1d8] ⇉ parallel [smithy, boots]
10:05:07.950 INFO  [engine] [a3f2c1d8] → 'smithy' {armLength:42}
10:05:07.950 INFO  [engine] [a3f2c1d8] → 'boots' {footSize:38}
10:05:08.465 INFO  [engine] [a3f2c1d8] ⇇ parallel [smithy, boots] joined
10:05:09.296 INFO  [engine] [a3f2c1d8] saga 'goblin-recruit' done
```

Filter by sagaId to trace any instance: `grep "a3f2c1d8" saga-blind.log`

**4. Query and control**

```bash
GET  /sagas/available              registered saga names (Playing only)
GET  /sagas                        all instances with status
GET  /sagas/definitions            all definitions with lifecycle status
POST /sagas/definitions/:name/stop stop — no new instances, in-flight run to completion
DELETE /sagas/definitions/:name    remove (only when stopped and empty)
POST /engine/shutdown              soft shutdown — waits for in-flight instances
```

## Running goblin-world

```bash
cd examples
sbt "examples/run"

# in another terminal
curl -s -X POST http://localhost:7777/sagas/launch \
  -H "Content-Type: application/json" \
  -d '{"definition":"goblin-recruit","params":{"goblinId":"G-042"}}'
```

## Parameter mapping

Data flows between steps through the pool. The `from` expression follows the pattern `owner/key.jsonPath`:

```
__init__/goblinId               full value from launch params
measurements/head               key deposited by measurements step
A/candidateCollection[1]        array element
getHat/output.serialNumber      nested field via JSONPath
```

## The jar contract

```scala
trait SagaStepProvider:
  def stepId: String
  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]]
  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit]
```

The jar receives named parameters and returns named outputs. It knows nothing about the pool, the WAL, or the orchestration around it.

## Risk

- **No type safety at runtime.** Steps exchange opaque JSON values.
- **Dynamic class loading is a security surface.** saga-blind assumes a trusted deployment environment.
- **Compensation depends on what steps deposit.** If a step fails before depositing, the engine cannot extract compensation parameters.
- **Memory pressure under load.** One `URLClassLoader` per saga instance.

## Configuration

```
SAGA_BLIND_DB       SQLite database path     (default: saga-blind.db)
SAGA_BLIND_WATCH    watched definitions dir  (default: ./definitions)
SAGA_BLIND_HOST     HTTP bind host           (default: 0.0.0.0)
SAGA_BLIND_PORT     HTTP port                (default: 7777)
```

## Relation to saga-graph

saga-blind is built with the knowledge of [saga-graph](https://github.com/ccerdadiaz/saga-graph), but free of its hard initial restrictions. The key difference: saga-blind does not require compensation parameters to be known before execution — they are resolved from the OKV at compensation time.

## Status

Implemented and tested (76 tests):

- WAL store (SQLite, injectable)
- OKV pool with WAL-first persistence
- Dynamic jar loading with classloader isolation
- DSL parser with input/compensate parameter mappings
- Semantic validator — detects forward references at load time
- Step kinds: mandatory, optional, bestEffort
- Parallel step execution
- LIFO compensation with parameter resolution from the pool
- ZombieHunter — TTL detection, forward retry, LIFO recovery
- Soft shutdown with WAL verification
- FileWatcher — hot folder, no restart needed
- Definition lifecycle — Playing / Stopped / Removed
- Structured logging — sagaId in every log line
- goblin-world demo — live engine with logback

## License

Apache 2.0

## Reliability — WAL-before-action

Every state transition is written to the WAL before the action it describes takes place. If the process dies at any point, the WAL contains enough information to reconstruct exactly what happened and what needs to happen next.

For each step execution the sequence is:

```
1. insertStep(Registered)     ← persisted before calling the jar
2. ParamExtractor.resolve()   ← reads from OKV
3. provider.execute(args)     ← calls the jar
4. pool.depositDelta(outputs) ← writes outputs to OKV + WAL
5. updateStep(Done | Failed)  ← persisted after the jar returns
```

If the process dies between steps 1 and 3, the step is in `Registered` state in the WAL. The ZombieHunter will detect it when its TTL expires, reconstruct the OKV pool and the jar from the WAL, and attempt a forward retry.

If the process dies between steps 3 and 5, the step output may or may not have been deposited. The ZombieHunter treats it as `Unknown` and retries — the jar must be idempotent for this to be safe.

The same WAL-before-action principle applies at the saga level:

```
insertSaga(Running)     ← before executing any step
updateSaga(Done)        ← after all steps complete
updateSaga(Compensated) ← after LIFO compensation completes
updateSaga(NeedsReview) ← if compensation itself fails
```

This means a saga is never lost. It is either in a terminal state or in a state the ZombieHunter knows how to recover.
