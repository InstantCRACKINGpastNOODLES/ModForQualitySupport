# ELSTAR TICKET — CORE ARCHITECTURE MAPS
**Mod ID:** `elstarticket` | **Environment:** Minecraft 1.21.1 / NeoForge 21.1.264 / TLM 1.5.3  
**Audit Date:** 2026-11-05 | **Mode:** READ-ONLY Structural Intelligence Audit

---

## 1. Map A: Worker Creation and Lifecycle

```mermaid
stateDiagram-v2
    [*] --> WildEntity: Spawned in World (Maid / Mob)
    WildEntity --> BindingSession: Player uses BindingTicketItem on Entity
    BindingSession --> RegisteredWorker: Attachments written (OWNER, PROFILE, STORAGE)
    RegisteredWorker --> IdleState: WorkerStatus.IDLE
    
    IdleState --> RunningState: Work Order assigned (WorkerStatus.RUNNING)
    RunningState --> PausedState: Player presses Pause in Menu (WorkerStatus.PAUSED)
    PausedState --> RunningState: Player resumes
    
    RunningState --> StuckState: Pathfinding fails / obstructed (WorkerStatus.STUCK)
    StuckState --> RunningState: Unstuck routine succeeds / teleports
    StuckState --> RescueState: Timeout expires (RescueServerManager alerts player)
    
    RunningState --> ReturningState: Storage full / job finished (WorkerStatus.RETURNING)
    ReturningState --> IdleState: Deposit complete
    
    RunningState --> FinishedState: Worksite depleted / quota met (WorkerStatus.FINISHED)
    FinishedState --> IdleState: Acknowledged by player
```

### Lifecycle Implementation Details
1. **Binding & Initialization (`BindingTicketItem.java`):**
   - Player right-clicks an entity (`EntityMaid` or compatible `Mob`).
   - Checks ownership permissions: if unowned or owned by player, attaches `WORKER_OWNER` with player's UUID.
   - Instantiates and attaches `WORKER_PROFILE` (default `WorkerJob.IDLE`, `WorkerStatus.IDLE`).
   - Instantiates and attaches `WORKER_STORAGE` (fresh 15-slot `WorkerStorage` instance).
2. **Server Tick Pipeline (`WorkerExecutionLoop.java`):**
   - Iterates through all loaded levels in `onServerTick(MinecraftServer)`.
   - Queries entities with `WORKER_PROFILE` attachment.
   - Dispatches entity execution based on current `WorkerJob` and `WorkerStatus`.
3. **Unloading & Serialization:**
   - On chunk unload or world save, NeoForge invokes `serializeNBT()` on `WORKER_PROFILE` and `WORKER_STORAGE`.
   - On chunk reload, attachments are restored from entity NBT via `deserializeNBT()`.

---

## 2. Map B: Worker Identity, Ownership, and Selection

```mermaid
flowchart TD
    Player[Player Entity] -->|Right-Click with Ticket| Item[BindingTicketItem]
    Item -->|Query Attachment| OwnerAtt[WORKER_OWNER Attachment]
    Item -->|Active Session| WOS[WorkerOrderSession]
    
    subgraph EntityState [Worker Mob Entity]
        OwnerAtt -->|Owner UUID Verification| AuthPass{Authorized?}
        AuthPass -- Yes --> Profile[WORKER_PROFILE Attachment]
        AuthPass -- No --> Reject[Cancel Action / Error Message]
        Profile --> Storage[WORKER_STORAGE Attachment]
        Profile --> Status[WorkerStatus]
        Profile --> Job[WorkerJob]
    end
    
    WOS -->|Client Sync Packet| OSSP[OrderSessionSyncPayload]
    OSSP -->|HUD / Visuals| HUD[BindingTicketHUD & TetherRenderer]
```

### Identity and Selection Rules
- **Ownership Verification:** Only the player whose UUID matches `WORKER_OWNER` (or server operators) can open the management menu or issue work orders.
- **Session Selection (`WorkerOrderSession`):** A client-side state machine tracks the currently selected worker UUID. The active tether line (`BindingTicketTetherRenderer`) connects player hand to worker entity.
- **Multi-Worker Isolation:** Each worker possesses its own independent `WorkerProfile` and `WorkerStorage` attachment instances on the entity.

---

## 3. Map C: Inventory and Item Management

```mermaid
flowchart LR
    subgraph MaidEntity [Worker / Maid Entity]
        MainHand[Main Hand Slot<br/>EquipmentSlot.MAINHAND]
        Backpack[Maid Inventory / Backpack<br/>EntityMaid / IItemHandler]
        WStorage[WorkerStorage<br/>15 Dedicated Slots]
    end
    
    subgraph JobExecution [Job Loop]
        TempDrops[Collected Drops Buffer]
        ToolSelector[Tool Evaluation & Swapper]
    end
    
    subgraph WorldStorage [World Containers]
        Chest[Delivery Chest<br/>StorageDestinationAdapter]
    end
    
    ToolSelector <-->|swapMiningTool| WStorage
    ToolSelector <-->|Equip / Unequip| MainHand
    TempDrops -->|Collect Loot| WStorage
    WStorage -->|deliverOutputs| Chest
    Backpack -.->|Optional food / favor access| MaidEntity
```

### Inventory Boundary Matrix
- **Maid Main Hand (`EquipmentSlot.MAINHAND`):** The active tool used by Minecraft AI to break blocks or harvest crops.
- **WorkerStorage (15 slots):** Dedicated container attachment. Holds запасные (reserve) tools, harvest seeds, and mined blocks before delivery.
- **Maid Internal Inventory / Backpack:** Managed by Touhou Little Maid mod. Elstar Ticket primarily operates on `WorkerStorage` to avoid corrupting Maid maid-sugar / favor / bauble slots.
- **Item Reservations (`JobReservationRegistry`):** Prevents cross-worker contention for target chests or specific slot contents during delivery cycles.

---

## 4. Map D: Work Order and Job Lifecycle

```mermaid
sequenceDiagram
    autonumber
    actor Player
    participant UI as WorkOrderScreen / Menu
    participant Session as WorkerOrderSession
    participant Profile as WorkerProfile
    participant Loop as WorkerExecutionLoop
    participant Controller as Miner/Farmer Controller
    
    Player->>UI: Configure Worksite & Storage Pos
    UI->>Session: Commit Work Order
    Session->>Profile: setJob(WorkerJob.MINING)
    Session->>Profile: setStatus(WorkerStatus.RUNNING)
    Note over Loop: Next Server Tick
    Loop->>Profile: Query Active Job
    Loop->>Loop: tryEnsureToolEquippedForJob()
    Loop->>Controller: tickMining() / tickFarming()
    Controller-->>Loop: Step Complete / Needs Delivery
    Loop->>Profile: Update Progress / Status
```

### State Transitions
- **Assignment:** Validated against worksite bounds and destination chest availability via `MinerResourcePreflight`.
- **Execution:** Driven by `WorkerExecutionLoop.processWorker()`. Every 10-20 ticks, controllers re-evaluate target blocks.
- **Pause/Resume:** Player toggles status via `WorkerManagementMenu`. Worker halts pathfinding and retains current tool in Main Hand.
- **Completion/Depletion:** Controller signals completion; worker navigates to storage chest, deposits remaining outputs, resets to `WorkerJob.IDLE`.

---

## 5. Map E: Mining Execution Subsystem

```mermaid
flowchart TD
    Start[MinerJobController.tickMining] --> Preflight[MinerResourcePreflight.checkTarget]
    Preflight --> BlockScan[Scan 3D Bounding Box]
    BlockScan --> FoundBlock{Target Block Found?}
    FoundBlock -- No --> Complete[Signal Mining Complete]
    FoundBlock -- Yes --> CheckTool{Proper Tool Equipped?}
    
    CheckTool -- No --> Swap[WorkerExecutionLoop.swapMiningTool]
    Swap --> Equipped{Tool Equipped?}
    Equipped -- No --> PauseNoTool[WorkerStatus.PAUSED - No Tool]
    Equipped -- Yes --> MoveToBlock
    CheckTool -- Yes --> MoveToBlock[Pathfind within Reach]
    
    MoveToBlock --> Mine[Mine Block & Break]
    Mine --> Gather[Gather Drops into WorkerStorage]
    Gather --> CheckFull{WorkerStorage Full?}
    CheckFull -- Yes --> Deliver[Deliver to Storage Chest]
    CheckFull -- No --> NextTick[Yield to Next Tick]
```

### Critical Mining Invariants
- Mining operates within min/max coordinates configured in `MinerJobData`.
- Preflight evaluates block hardness and harvest level requirement against available tools in `WorkerStorage`.
- Drops are inserted into `WorkerStorage`; if storage is full, state shifts to `WorkerStatus.RETURNING` for deposit.

---

## 6. Map F: Farming Execution Subsystem

```mermaid
flowchart TD
    Start[FarmerJobController.tickFarming] --> Scan[Scan Farmland Radius]
    Scan --> CheckMature{Mature Crop Found?}
    CheckMature -- No --> Wait[Idle / Wait for Growth]
    CheckMature -- Yes --> Move[Pathfind to Farmland]
    
    Move --> Harvest[Harvest Crop via CropAdapter]
    Harvest --> ExtractSeed[Extract Seed for Replanting]
    ExtractSeed --> Replant[Immediate Replant on Farmland]
    Replant --> StoreSurplus[Store Surplus Crops in WorkerStorage]
    StoreSurplus --> CheckFull{Storage Full?}
    CheckFull -- Yes --> Deliver[Deliver to Storage Chest]
    CheckFull -- No --> NextCrop[Scan Next Crop]
```

### Critical Farming Invariants
- `CropRegistry` and `CropAdapter` define maturity properties for each crop block.
- Replanting is atomic: seed is extracted from harvest drops before surplus is sent to `WorkerStorage`.
- Hoe durability is monitored via `EquipmentCapabilityPolicy`.

---

## 7. Map G: Delivery and Output Storage

```mermaid
flowchart TD
    Trigger[Trigger: WorkerStorage Full or Job Finished] --> FindChest[Query StorageDestinationAdapter]
    FindChest --> ReachCheck{Within Interaction Range?}
    ReachCheck -- No --> Pathfind[Pathfind to Chest Position]
    ReachCheck -- Yes --> AcquireLock[JobReservationRegistry.acquireLock]
    
    AcquireLock --> Inspect[Inspect Chest IItemHandler Capability]
    Inspect --> TransferLoop[Transfer Output Stacks from WorkerStorage]
    TransferLoop --> ReleaseLock[JobReservationRegistry.releaseLock]
    ReleaseLock --> Return[Return to Worksite / Set IDLE]
```

### Delivery Guarantees
- Reserve tools (pickaxes, hoes, shears) must be excluded from chest delivery. Only collected resources and surplus items are deposited.
- Destination chest must implement `IItemHandler` (Vanilla chests, modded barrels/crates).

---

## 8. Map H: Menu, Screen, and Network Interaction

```mermaid
sequenceDiagram
    autonumber
    actor Player
    participant Screen as WorkerManagementScreen
    participant Menu as WorkerManagementMenu
    participant Net as NeoForge Network
    participant Entity as Worker Entity (Server)
    
    Player->>Entity: Right-Click Worker Entity
    Entity->>Player: openMenu(WorkerManagementMenu)
    Note over Menu,Screen: Sync 15 WorkerStorage Slots + Player Inventory
    Player->>Screen: Move Items / Click Buttons
    Screen->>Menu: Slot Click / quickMoveStack()
    Menu->>Entity: Mutate WORKER_STORAGE Attachment
    Menu-->>Screen: broadcastChanges() (Slot Updates)
    Player->>Screen: Close Screen
    Screen->>Menu: removed(Player)
```

---

## 9. Map I: Mod Integration Adapters (TLM & External)

- **Touhou Little Maid (`TouhouLittleMaidCompat.java`):**
  - Uses reflection/interface inspection on `EntityMaid`.
  - Reads maid state without overriding TLM's core task schedule.
- **JourneyMap (`JourneyMapBridge.java`):**
  - Optional client display: registers worker positions as temporary waypoints.
- **Future Integration Hooks:**
  - `StorageDestinationAdapter` already leverages standard NeoForge `Capabilities.ItemHandler.BLOCK`, enabling compatibility with Create item vaults, Refined Storage / AE2 interface chests, and modded storage drawers without hard dependencies.
