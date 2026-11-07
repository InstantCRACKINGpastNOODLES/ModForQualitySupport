# ELSTAR TICKET — PROJECT STRUCTURE INVENTORY
**Mod ID:** `elstarticket` (Package: `com.example.examplemod`)  
**Environment:** Minecraft 1.21.1 | NeoForge 21.1.264 | Java 21 | Touhou Little Maid 1.5.3  
**Audit Date:** 2026-11-05 | **Mode:** READ-ONLY Structural Intelligence Audit

---

## 1. Executive Summary & Package Layout

The mod codebase consists of **58 Java source files** (`src/main/java/com/example/examplemod/`) and **36 Java test files** (`src/test/java/com/example/examplemod/`). All production classes currently reside within a single package root (`com.example.examplemod`), though architecturally they divide into 12 functional subsystems.

### Logical Subsystem Distribution

| Subsystem | Primary Responsibilities | Key Classes |
|---|---|---|
| **Core & Registries** | Mod lifecycle, NeoForge registrations, config | `ExampleMod`, `ExampleModClient`, `Config` |
| **Worker Identity & Profile** | Worker entity attachment, profile, jobs, status | `WorkerProfile`, `WorkerJob`, `WorkerJobs`, `WorkerStatus`, `WorkerBehavior`, `WorkerTarget` |
| **Worker Storage & Capabilities** | 15-slot worker inventory, tool capability checks | `WorkerStorage`, `JobToolRequirement`, `MiningToolCapability`, `EquipmentCapabilityPolicy` |
| **Execution Engine** | Central server tick handler, task loop, tool swapping | `WorkerExecutionLoop` (Monolith: 1,524 lines) |
| **Order Authoring & Binding** | Binding Ticket item, HUD overlays, tether rendering | `WorkerOrderSession`, `BindingTicketItem`, `BindingTicketHUD`, `BindingTicketTetherRenderer` |
| **Mining Subsystem** | 3D mine planning, execution, payload configs | `MinerJobController` (1,464 lines), `MinerJobData`, `MinerResourcePreflight`, `MinerJobConfigScreen`, 3 payloads |
| **Farming Subsystem** | Unified crop harvesting, replanting, validation | `FarmerJobController`, `FarmerJobData`, `CropRegistry`, `CropAdapter`, `CropHarvestTarget`, `CropSelectionMode`, `CropOperationResult` |
| **Job Queue & Reservations** | Multi-step jobs, item/block reservation registry | `Job`, `JobStep`, `JobQueue`, `JobDependency`, `JobAdapter`, `JobItemReservation`, `JobReservationRegistry`, `ExecutionState`, `JobStatus` |
| **Spatial & World Adapters** | Safe worksites, storage chests, capability adapters | `WorksiteAdapter`, `StorageDestinationAdapter`, `JobSafetyPolicy`, `WorkerJobCapability`, `WorkerInteraction`, `WorkerInteractionRegistry` |
| **UI & Menus** | Container menus, GUI screens, sync slots | `WorkerManagementMenu`, `WorkerManagementScreen`, `WorkOrderScreen` |
| **Rescue & Safety** | Entity tracking, stuck recovery, saved data | `RescueServerManager`, `RescueWaypointManager`, `RescueSavedData`, 2 rescue payloads |
| **Mod Integrations** | Touhou Little Maid bridge, JourneyMap bridge | `TouhouLittleMaidCompat`, `JourneyMapBridge` |

---

## 2. Type Classifications

### 2.1 Enums
1. `WorkerJob`: Standard worker jobs (`IDLE`, `MOVE`, `MINING`, `FARMING`, `HARVEST`).
2. `WorkerStatus`: Lifecycle statuses (`IDLE`, `RUNNING`, `PAUSED`, `FINISHED`, `FAILED`, `STUCK`, `RETURNING`).
3. `CropSelectionMode`: Mode for crop targeting (`ALL_MATURE`, `SPECIFIC_CROP`, `REPLANT_ONLY`).
4. `MiningToolCapability`: Mining capability rating (`NONE`, `BASIC`, `OPTIMAL`, `HAZARDOUS`).
5. `JobStatus`: State of execution steps (`PENDING`, `IN_PROGRESS`, `BLOCKED`, `COMPLETED`, `CANCELLED`).
6. `ExecutionState`: Work order execution state (`UNASSIGNED`, `INITIALIZING`, `ACTIVE`, `SUSPENDED`, `ABORTED`).

### 2.2 Records & Structs
1. `CropHarvestTarget`: BlockPos, crop identifier, mature status, drop estimates.
2. `CropOperationResult`: Success boolean, harvested drops, replanted flag, error code.
3. `JobStep`: Step ID, step type, target position, required tools/items, timeout ticks.
4. `JobDependency`: Prerequisite step IDs and satisfaction condition.
5. `JobItemReservation`: Worker UUID, item stack/tag filter, reserved quantity, expiration tick.
6. `MinerResourcePreflight`: Target block, tool tier requirement, expected drops, energy/durability cost.
7. `WorkerInteraction`: Interaction point, predicate, interaction action, priority.

### 2.3 Network Payloads (NeoForge CustomPacketPayload)
All registered in `ExampleMod.registerPackets` using NeoForge's `PayloadRegistrar`:
1. `OrderSessionSyncPayload` (`elstarticket:order_session_sync`): Synchronizes active work order session data between client tether/HUD and server.
2. `RescueAlertPayload` (`elstarticket:rescue_alert`): Notifies client of worker distress, critical health, or stuck status.
3. `RescueResolutionRequestPayload` (`elstarticket:rescue_resolution_request`): Dispatched from client to trigger teleport/unstuck rescue action.
4. `OpenMinerConfigPayload` (`elstarticket:open_miner_config`): Requests opening the miner configuration GUI.
5. `MinerConfigSubmitPayload` (`elstarticket:miner_config_submit`): Client submits boundary, mining mode, and target ore filters.
6. `MinerConfigResponsePayload` (`elstarticket:miner_config_response`): Server acknowledges or rejects miner configuration changes.

### 2.4 Data Attachments (NeoForge AttachmentType)
Registered in `ExampleMod.ATTACHMENT_TYPES`:
1. `WORKER_OWNER`: `UUID` (stores player owner UUID on the worker entity).
2. `WORKER_PROFILE`: `WorkerProfile` (stores worker name, active job, status, target coordinates, statistics).
3. `WORKER_STORAGE`: `WorkerStorage` (authoritative 15-slot internal container on worker entity).
4. `SHELTER_STATE`: `CompoundTag` / Custom state (shelter and hazard avoidance status).

### 2.5 Event Listeners & Tick Handlers
- `ExampleMod.onServerTick` (`ServerTickEvent.Post`): Drives `WorkerExecutionLoop.onServerTick(event.getServer())` every tick.
- `ExampleMod.onEntityInteract` (`PlayerInteractEvent.EntityInteract`): Intercepts clicks on TLM Maids with `BindingTicketItem` or empty hand to open `WorkerManagementMenu`.
- `ExampleMod.onPlayerLoggedIn` (`PlayerEvent.PlayerLoggedInEvent`): Syncs pending sessions and rescue waypoints.
- `ExampleMod.onChunkUnload` (`ChunkEvent.Unload`): Notifies `RescueServerManager` and `JobReservationRegistry`.

---

## 3. Comprehensive Class Inventory

### 3.1 Core & Setup
#### `ExampleMod`
- **Path:** `src/main/java/com/example/examplemod/ExampleMod.java`
- **Responsibility:** Mod entry point (`@Mod("elstarticket")`), registry initialization (Items, Menus, Attachments, Network packets, Event bus hooks).
- **Key Methods:** `ExampleMod(IEventBus)`, `registerPackets(PayloadRegistrar)`, `onServerTick(ServerTickEvent.Post)`, `onEntityInteract(PlayerInteractEvent.EntityInteract)`.
- **Dependencies:** `WorkerExecutionLoop`, `BindingTicketItem`, `WorkerManagementMenu`, NeoForge registries.

#### `ExampleModClient`
- **Path:** `src/main/java/com/example/examplemod/ExampleModClient.java`
- **Responsibility:** Client-side registration: Screen registrations (`WorkerManagementScreen`, `WorkOrderScreen`, `MinerJobConfigScreen`), HUD renderers, client event hooks.
- **Key Methods:** `initClient()`, `registerScreens()`.

#### `Config`
- **Path:** `src/main/java/com/example/examplemod/Config.java`
- **Responsibility:** Mod configuration values (durability safety limits, max worksite radius, tick intervals).

---

### 3.2 Worker Profile & State
#### `WorkerProfile`
- **Path:** `src/main/java/com/example/examplemod/WorkerProfile.java`
- **Responsibility:** Persistent profile data attached to worker entities via `WORKER_PROFILE`. Holds worker UUID, name, current `WorkerJob`, `WorkerStatus`, worksite `BlockPos`, storage `BlockPos`, last active tick.
- **Key Methods:** `serializeNBT()`, `deserializeNBT(CompoundTag)`, `setJob(WorkerJob)`, `setStatus(WorkerStatus)`.

#### `WorkerJobs` / `WorkerJob`
- **Path:** `src/main/java/com/example/examplemod/WorkerJob.java`, `WorkerJobs.java`
- **Responsibility:** Job enum definitions and factory methods for binding job tasks to worker profiles.

#### `WorkerStatus`
- **Path:** `src/main/java/com/example/examplemod/WorkerStatus.java`
- **Responsibility:** Enum representing runtime lifecycle states of the worker.

#### `WorkerBehavior`
- **Path:** `src/main/java/com/example/examplemod/WorkerBehavior.java`
- **Responsibility:** Determines AI behavioral modifiers (fleeing hazards, returning to shelter, food consumption thresholds).

#### `WorkerTarget`
- **Path:** `src/main/java/com/example/examplemod/WorkerTarget.java`
- **Responsibility:** Represents active spatial target (BlockPos, Entity, or Area) with arrival distance tolerances.

---

### 3.3 Worker Storage & Inventory
#### `WorkerStorage`
- **Path:** `src/main/java/com/example/examplemod/WorkerStorage.java`
- **Responsibility:** 15-slot internal storage attached to worker entity via `WORKER_STORAGE`. Implements `Container` and NeoForge `INBTSerializable<CompoundTag>`.
- **Key Methods:** `getItem(int)`, `setItem(int, ItemStack)`, `removeItem(int, int)`, `removeItemNoUpdate(int)`, `clearContent()`, `serializeNBT()`, `deserializeNBT()`.
- **Hazards:** Mutated by menu container synchronization, tool equipping loops, and deposit routines.

#### `JobToolRequirement`
- **Path:** `src/main/java/com/example/examplemod/JobToolRequirement.java`
- **Responsibility:** Evaluates whether a given `ItemStack` satisfies the tool requirements of a job (e.g. pickaxe tier for mining, hoe/shears for farming).

#### `MiningToolCapability`
- **Path:** `src/main/java/com/example/examplemod/MiningToolCapability.java`
- **Responsibility:** Inspects tool attributes (harvest level, durability, enchantments) to grade tool suitability.

#### `EquipmentCapabilityPolicy`
- **Path:** `src/main/java/com/example/examplemod/EquipmentCapabilityPolicy.java`
- **Responsibility:** Defines rules for safe tool swapping, ensuring durability thresholds are respected before assigning tools.

---

### 3.4 Execution & Dispatch
#### `WorkerExecutionLoop`
- **Path:** `src/main/java/com/example/examplemod/WorkerExecutionLoop.java`
- **Responsibility:** Central orchestration engine (1,524 lines). Ticked every server tick from `ExampleMod.onServerTick`. Manages worker discovery, pathfinding invocation, tool swapping (`swapMiningTool`), job dispatching (`MinerJobController`, `FarmerJobController`), output delivery, and unstuck checks.
- **Key Methods:**
  - `onServerTick(MinecraftServer)`
  - `processWorker(ServerLevel, Entity, WorkerProfile)`
  - `swapMiningTool(Mob, WorkerStorage, BlockState)`
  - `tryEnsureToolEquippedForJob(...)`
  - `deliverOutputs(Mob, WorkerStorage, BlockPos)`
- **Key Fields:** Internal caches of active workers, tick counters, pending transfers.

---

### 3.5 Mining Subsystem
#### `MinerJobController`
- **Path:** `src/main/java/com/example/examplemod/MinerJobController.java`
- **Responsibility:** 3D bounding box mining controller (1,464 lines). Generates mining path, breaks blocks, collects drops into temporary buffer or WorkerStorage, triggers tool repair/replacement, handles cave-ins and liquids.
- **Key Methods:** `tickMining(Mob, MinerJobData, WorkerStorage)`, `findNextBlock()`, `mineBlock()`.

#### `MinerJobData`
- **Path:** `src/main/java/com/example/examplemod/MinerJobData.java`
- **Responsibility:** NBT-serializable state of a mining job: worksite min/max corners, current scanning cursor, selected block filter, delivery chest position.

#### `MinerResourcePreflight`
- **Path:** `src/main/java/com/example/examplemod/MinerResourcePreflight.java`
- **Responsibility:** Pre-scan verification of blocks in the mining volume to verify required tool tiers and estimate drop volume.

#### `MinerJobConfigScreen`
- **Path:** `src/main/java/com/example/examplemod/MinerJobConfigScreen.java`
- **Responsibility:** Client GUI for setting 3D mining boundary dimensions, direction, and ore filtering options.

---

### 3.6 Farming Subsystem
#### `FarmerJobController`
- **Path:** `src/main/java/com/example/examplemod/FarmerJobController.java`
- **Responsibility:** Executes farm scanning, crop harvesting, seed extraction, and immediate replanting using `CropRegistry`.
- **Key Methods:** `tickFarming(Mob, FarmerJobData, WorkerStorage)`, `scanFarmland()`, `harvestCrop()`, `replantCrop()`.

#### `FarmerJobData`
- **Path:** `src/main/java/com/example/examplemod/FarmerJobData.java`
- **Responsibility:** State of a farming job: farmland perimeter, crop filters, seed reserve counts.

#### `CropRegistry` & `CropAdapter`
- **Path:** `src/main/java/com/example/examplemod/CropRegistry.java`, `CropAdapter.java`
- **Responsibility:** Modular registry mapping crop blocks (Vanilla, modded) to maturity predicates, harvested drops, and replanting item requirements.

---

### 3.7 Job Queue & Reservations
#### `JobQueue`, `Job`, `JobStep`, `JobDependency`
- **Paths:** `JobQueue.java`, `Job.java`, `JobStep.java`, `JobDependency.java`
- **Responsibility:** Flexible multi-step job pipeline enabling chained tasks (e.g. Move -> Mine -> Deposit -> Re-equip).

#### `JobReservationRegistry` & `JobItemReservation`
- **Paths:** `JobReservationRegistry.java`, `JobItemReservation.java`
- **Responsibility:** Locks chests, blocks, or specific inventory items to prevent concurrent workers from claiming identical resources.

---

### 3.8 Spatial & Storage Adapters
#### `WorksiteAdapter`
- **Path:** `src/main/java/com/example/examplemod/WorksiteAdapter.java`
- **Responsibility:** Spatial math for bounding boxes, sphere ranges, line-of-sight checks, and chunk loading validations.

#### `StorageDestinationAdapter`
- **Path:** `src/main/java/com/example/examplemod/StorageDestinationAdapter.java`
- **Responsibility:** Wrapper around `IItemHandler` capabilities for target delivery chests. Performs insertion simulation before moving items.

---

### 3.9 Container Menus & UI
#### `WorkerManagementMenu`
- **Path:** `src/main/java/com/example/examplemod/WorkerManagementMenu.java`
- **Responsibility:** Server-client synced container menu (`AbstractContainerMenu`). Hosts 15 slots mapped directly to the worker entity's `WorkerStorage`, plus player inventory and hotbar.
- **Key Methods:** `quickMoveStack(Player, int)`, `broadcastChanges()`, `removed(Player)`.

#### `WorkerManagementScreen`
- **Path:** `src/main/java/com/example/examplemod/WorkerManagementScreen.java`
- **Responsibility:** Client screen rendering the 15 WorkerStorage slots, worker health/food gauges, and job configuration buttons.

#### `WorkOrderScreen`
- **Path:** `src/main/java/com/example/examplemod/WorkOrderScreen.java`
- **Responsibility:** Interactive visual work order authoring screen.

---

### 3.10 Rescue & Mod Integrations
#### `RescueServerManager`, `RescueSavedData`, `RescueWaypointManager`
- **Paths:** `RescueServerManager.java`, `RescueSavedData.java`, `RescueWaypointManager.java`
- **Responsibility:** Monitors worker safety; saves distressed worker locations to world data (`RescueSavedData`); sends rescue alerts to owner clients.

#### `TouhouLittleMaidCompat`
- **Path:** `src/main/java/com/example/examplemod/TouhouLittleMaidCompat.java`
- **Responsibility:** Reflection/soft-dependency bridge to Touhou Little Maid (`EntityMaid`). Accesses Maid backpack, task modes, and favors.

#### `JourneyMapBridge`
- **Path:** `src/main/java/com/example/examplemod/JourneyMapBridge.java`
- **Responsibility:** Optional client integration to display worker waypoints on JourneyMap minimap/fullscreen map.
