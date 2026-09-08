# saga-blind — Architecture

## Videos

| Video | Description |
|-------|-------------|
| [saga.mp4](saga.mp4) | The engine — loading a definition and processing a request |
| [dsl-tour.mp4](dsl-tour.mp4) | How a single DSL line becomes a step dependency |
| [okv-tour.mp4](okv-tour.mp4) | The OKV pool filling step by step |
| [lifo-tour.mp4](lifo-tour.mp4) | LIFO compensation when a step fails |
| [zh-tour.mp4](zh-tour.mp4) | ZombieHunter — TTL detection and recovery |
| [saga-blind-console-demo.mp4](saga-blind-console-demo.mp4) | Live demo — engine, launch, logs, shutdown |

---

## 1. Module structure

```mermaid
graph TD
    core["saga-blind-core\nengine · WalStore trait · SagaLogger"]
    sqlite["saga-blind-store-sqlite\nSqliteWalStore implements WalStore"]
    examples["saga-blind-examples\ngoblin-world · logback · SqliteWalStore injected"]

    core --> sqlite
    core --> examples
    sqlite --> examples
```

The engine (`core`) has no database driver and no logging implementation.
Both are injected by the caller — `examples` injects `SqliteWalStore` and `Slf4jLogger`.

---

## 2. System layers

```mermaid
graph TD
    Client(["Client\nHTTP"])
    FW["FileWatcher\nwatches definitions/"]
    SC["SagaControl\nname → definition + status"]
    SV["SagaValidator\nsemantic owner check at load time"]
    RT["SagaRuntime\nlaunch · list · control"]
    EX["SagaExecutor factory\n→ SagaExecution per instance"]
    PE["ParamExtractor\nowner/key.jsonPath → args"]
    JL["JarLoader\nURLClassLoader per saga instance"]
    PP["PersistentOkvPool\nOKV blackboard"]
    WS["WalStore trait\ninjected implementation"]
    ZH["ZombieHunter\nTTL detection · forward retry · LIFO"]
    LOG["SagaLogger\ninjected — noOp · stdout · Slf4jLogger"]

    Client -->|"POST /sagas/launch\n{definition, params}"| RT
    Client -->|"GET /sagas\nPOST stop\nDELETE\nPOST /engine/shutdown"| RT

    FW -->|"publish / withdraw"| SC
    FW -->|"validate on publish"| SV
    SC -->|"resolve name"| RT

    RT --> EX
    RT --> JL
    RT --> PP

    EX -->|"resolve mappings"| PE
    PE -->|"owner/key.jsonPath"| PP
    EX -->|"execute(args)\ncompensate(args)"| JL
    EX -->|"deposit outputs"| PP
    PP -->|"WAL-first writes"| WS
    EX -->|"step status transitions"| WS
    ZH -->|"scan · recover"| WS
    ZH -->|"load jar + pool"| JL

    LOG -.->|"injected into"| RT
    LOG -.->|"injected into"| EX
    LOG -.->|"injected into"| ZH
    LOG -.->|"injected into"| FW
```

---

## 3. Saga launch flow

```mermaid
sequenceDiagram
    actor Client
    participant SC as SagaControl
    participant JL as JarLoader
    participant PP as PersistentOkvPool
    participant PE as ParamExtractor
    participant EX as SagaExecution
    participant WAL as WalStore

    Client->>SC: get("goblin-recruit") → Saga
    Client->>SC: check status == Playing

    Client->>JL: load(sagaId, jarPath, steps)
    JL-->>Client: Map[stepId → SagaStepProvider]

    Client->>PP: init(params)
    PP->>WAL: deposit(__init__, params)

    Client->>EX: run()

    loop for each step
        EX->>WAL: insertStep(Registered)
        EX->>PE: resolve(inputMappings, pool)
        PE-->>EX: Map[param → value]
        EX->>JL: provider.execute(args)
        JL-->>EX: Map[key → value] (outputs)
        EX->>PP: depositDelta(stepId, outputs)
        PP->>WAL: persist delta
        EX->>WAL: updateStep(Done | Failed)
    end

    alt mandatory step failed
        loop LIFO — executed steps in reverse
            EX->>PE: resolve(compensateMappings, pool)
            PE-->>EX: Map[param → value]
            EX->>JL: provider.compensate(args)
        end
        EX->>WAL: updateSaga(Compensated)
    else all steps done
        EX->>WAL: updateSaga(Done)
    end
```

---

## 4. Parameter mapping syntax

```
owner/key.jsonPath
```

| expression | owner | key | jsonPath | example |
|---|---|---|---|---|
| `__init__/goblinId` | `__init__` | `goblinId` | — | full value from launch params |
| `measurements/result.head` | `measurements` | `result` | `.head` | field inside object |
| `A/candidateCollection[1]` | `A` | `candidateCollection` | `[1]` | array element |
| `getHat/output.serialNumber` | `getHat` | `output` | `.serialNumber` | nested field |

The engine resolves these mappings before each call. If a mapping references a key that has not been deposited yet, the step fails with a clear error.

**Semantic validation at load time:** a step cannot reference an owner that appears later in the definition. Steps in a parallel block cannot reference their siblings. Detected at publish time, before any instance runs.

**Self-reference in compensate:** a step's `compensateMappings` may reference its own id — because when compensation runs, the step has already executed and deposited its outputs.

---

## 5. OKV pool data flow

→ See also: [okv-tour.mp4](okv-tour.mp4)

```mermaid
graph LR
    INIT(["__init__\nparams from launch"])

    A["stepA\nout: keyA1 · keyA2"]
    B["stepB\nout: keyB1"]
    C["stepC (parallel)\nout: keyC1"]
    D["stepD (parallel)\nout: keyD1"]
    E["stepE\nout: keyE1"]

    POOL[("OKV Pool\nowner / key / value")]
    WAL[("WalStore\npersisted as deltas")]

    INIT -->|"initParam"| POOL
    POOL -->|"__init__/initParam"| A
    A -->|"keyA1 · keyA2"| POOL
    POOL -->|"stepA/keyA1"| B
    POOL -->|"stepA/keyA2"| C
    POOL -->|"stepA/keyA2"| D
    B -->|"keyB1"| POOL
    C -->|"keyC1"| POOL
    D -->|"keyD1"| POOL
    POOL -->|"stepB/keyB1\nstepC/keyC1\nstepD/keyD1"| E
    E -->|"keyE1"| POOL
    POOL -->|"delta per step\nWAL-first"| WAL
```

---

## 6. Definition lifecycle

```mermaid
stateDiagram-v2
    [*] --> Playing : FileWatcher publishes .saga
    Playing --> Stopped : POST /stop\nin-flight instances run to completion
    Stopped --> [*] : DELETE\nonly when no instances remain

    note right of Playing
        Accepts new instances.
        FileWatcher can publish
        multiple definitions
        independently.
    end note

    note right of Stopped
        No new instances.
        In-flight instances
        finish forward (not compensated).
        Operator decides when to DELETE.
    end note
```

---

## 7. ZombieHunter recovery

→ See also: [zh-tour.mp4](zh-tour.mp4)

```mermaid
flowchart TD
    SCAN["scan WAL\nevery N seconds"]
    TTL{"step TTL\nexceeded?"}
    UNKNOWN["mark Unknown"]
    RECONSTRUCT["reconstruct pool + jar\nfrom WAL"]
    RETRY{"forward\nretry"}
    LIFO["LIFO compensation"]
    COMP(["saga → Compensated"])
    DONE(["saga → Done"])

    SCAN --> TTL
    TTL -->|yes| UNKNOWN
    TTL -->|no| SCAN
    UNKNOWN --> RECONSTRUCT
    RECONSTRUCT --> RETRY
    RETRY -->|success| DONE
    RETRY -->|fail| LIFO
    LIFO --> COMP

    style DONE fill:#dcfce7,stroke:#86efac,color:#166534
    style COMP fill:#fce7f3,stroke:#f9a8d4,color:#9d174d
```

ZombieHunter never touches: `Stopped` · `Done` · `Compensated` · `Failed`

---

## 8. Log format

```
HH:mm:ss.SSS  LEVEL  [thread]  [logger]  [component]  [sagaId]  message
```

```
10:05:07.612 INFO  [XNIO-1]   [sagablind] [engine] [a3f2c1d8] saga 'my-saga' started
10:05:07.615 INFO  [XNIO-1]   [sagablind] [engine] [a3f2c1d8] → 'stepA' {paramX:"value"}
10:05:07.929 INFO  [XNIO-1]   [sagablind] [engine] [a3f2c1d8] ← 'stepA' done {keyA1:58, keyA2:42}
10:05:07.931 INFO  [XNIO-1]   [sagablind] [engine] [a3f2c1d8] ⇉ parallel [stepC, stepD]
10:05:07.950 INFO  [global-1] [sagablind] [engine] [a3f2c1d8] → 'stepC' {paramY:42}
10:05:07.950 INFO  [global-2] [sagablind] [engine] [a3f2c1d8] → 'stepD' {paramZ:38}
10:05:08.172 INFO  [XNIO-1]   [sagablind] [engine] [a3f2c1d8] ⇇ parallel [stepC, stepD] joined
10:05:09.004 INFO  [XNIO-1]   [sagablind] [engine] [a3f2c1d8] saga 'my-saga' done
```

Filter by sagaId to get the full history of one instance:
```bash
grep "a3f2c1d8" saga-blind.log
```

---

## 9. Dependencies

```mermaid
graph LR
    core["saga-blind-core"]
    sqlite["saga-blind-store-sqlite"]
    examples["saga-blind-examples"]

    ujson["ujson\nJSON pool values"]
    jp["jayway-jsonpath\nowner/key.jsonPath resolution"]
    cask["cask\nHTTP server"]
    xerial["sqlite-jdbc\nSqliteWalStore"]
    logback["logback-classic\ngoblin-world logging"]

    core --> ujson
    core --> jp
    core --> cask
    sqlite --> core
    sqlite --> xerial
    examples --> core
    examples --> sqlite
    examples --> logback
```

`core` has no database driver and no logging implementation.
Both are compile-time dependencies of `store-sqlite` and `examples` respectively.
