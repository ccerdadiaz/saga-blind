# goblin-recruit

Arms and enlists a goblin for battle.

Demonstrates: sequential steps, parallel block, param mapping between steps, LIFO compensation with args from the OKV.

→ Live demo: [saga-blind-console-demo.mp4](saga-blind-console-demo.mp4)

## Saga flow

```mermaid
flowchart TD
    INIT([POST /sagas/launch\ngoblinId: G-042])

    INIT --> measurements

    measurements["measurements\nMeasurementsService\n───────────────\nin:  goblinId\nout: head, armLength, footSize"]

    measurements --> PAR

    PAR{parallel}

    PAR --> smithy
    PAR --> boots

    smithy["smithy\nSmithyService\n───────────────\nin:  armLength\nout: weaponId, weaponType"]

    boots["boots\nBootsService\n───────────────\nin:  footSize\nout: bootId, bootSize"]

    smithy --> JOIN
    boots  --> JOIN

    JOIN{join}

    JOIN --> getHat

    getHat["getHat\nHatService\n───────────────\nin:  goblinId, head\nout: hatSerialNumber\n⚠ expensive compensation"]

    getHat --> enlist

    enlist["enlist\nEnlistService\n───────────────\nin:  goblinId, weaponType\n     bootSize, hatSerialNumber\nout: enlistmentId"]

    enlist --> DONE([saga Done\nG-042 ready for battle])

    style INIT    fill:#dbeafe,stroke:#93c5fd,color:#1e40af
    style DONE    fill:#dcfce7,stroke:#86efac,color:#166534
    style PAR     fill:#fef9c3,stroke:#fde047,color:#854d0e
    style JOIN    fill:#fef9c3,stroke:#fde047,color:#854d0e
    style getHat  fill:#fff7ed,stroke:#fdba74,color:#9a3412
```

## Compensation (LIFO)

→ See also: [lifo-tour.mp4](lifo-tour.mp4)

If any mandatory step fails, compensation runs in reverse:

```mermaid
flowchart RL
    enlist_c["↩ enlist\ncancel enlistment\nargs: enlistmentId"]
    getHat_c["↩ getHat\nreturn helmet\nargs: hatSerialNumber"]
    par_c["↩ smithy + boots\nmelt weapon, return boots\nargs: weaponId, bootId"]
    meas_c["↩ measurements\ndiscard measurements\nargs: —"]
    COMP([saga Compensated])

    enlist_c --> getHat_c --> par_c --> meas_c --> COMP

    style COMP fill:#fce7f3,stroke:#f9a8d4,color:#9d174d
```

## OKV data flow

→ See also: [okv-tour.mp4](okv-tour.mp4)

Each step reads from owners that already executed and deposits its own outputs.

```mermaid
flowchart LR
    INIT["__init__\ngoblinId"]

    INIT -->|goblinId| M["measurements\nhead · armLength · footSize"]

    M -->|armLength| S["smithy\nweaponId · weaponType"]
    M -->|footSize|  B["boots\nbootId · bootSize"]
    M -->|head|      H
    INIT -->|goblinId| H["getHat\nhatSerialNumber"]

    S -->|weaponType|      E["enlist\nenlistmentId"]
    B -->|bootSize|        E
    H -->|hatSerialNumber| E
    INIT -->|goblinId|     E
```

## Step parameters

| step | kind | inputs (from OKV) | outputs (to OKV) | compensate args |
|------|------|-------------------|------------------|-----------------|
| measurements | mandatory | `__init__/goblinId` | head, armLength, footSize | — |
| smithy | mandatory | `measurements/armLength` | weaponId, weaponType | `smithy/weaponId` |
| boots | mandatory | `measurements/footSize` | bootId, bootSize | `boots/bootId` |
| getHat | mandatory | `__init__/goblinId`, `measurements/head` | hatSerialNumber | `getHat/hatSerialNumber` |
| enlist | mandatory | `__init__/goblinId`, `smithy/weaponType`, `boots/bootSize`, `getHat/hatSerialNumber` | enlistmentId | `enlist/enlistmentId` |
