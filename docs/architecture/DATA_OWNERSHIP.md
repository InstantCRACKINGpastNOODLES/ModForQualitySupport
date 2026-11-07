# ELSTAR TICKET — DATA CARD & DATA OWNERSHIP MATRIX
**Mod ID:** `elstarticket` | **Environment:** Minecraft 1.21.1 / NeoForge 21.1.264 / TLM 1.5.3  
**Audit Date:** 2026-11-05 | **Mode:** READ-ONLY Structural Intelligence Audit

---

## 1. Authoritative Data Ownership Matrix

| Data Structure | Authoritative Owner | Logical Side | Server Authoritative? | Serialization Mechanism | Sync Mechanism | Multiple Writers? |
|---|---|---|---|---|---|---|
| **`WORKER_OWNER`** | Server Entity Attachment | Server | Yes | UUID NBT (`serializeNBT`) | Internal query / Menu open validation | No (written once at binding) |
| **`WORKER_PROFILE`** | Server Entity Attachment | Server | Yes | `WorkerProfile.serializeNBT` | Menu slot data sync & custom packets | **YES** (`WorkerOrderSession`, `WorkerExecutionLoop`, `WorkerManagementMenu`) |
| **`WORKER_STORAGE`** | Server Entity Attachment | Server | Yes | `WorkerStorage.serializeNBT` (15 slots) | `AbstractContainerMenu.broadcastChanges` | **CRITICAL YES** (Menu, Loop tool swapper, Miner controller, Farmer controller, Delivery adapter) |
| **`SHELTER_STATE`** | Server Entity Attachment | Server | Yes | `CompoundTag` NBT | Internal server evaluation | Minor (`WorkerBehavior`, `WorkerExecutionLoop`) |
| **`RescueSavedData`** | Server World `DimensionDataStorage` | Server | Yes | `SavedData.save(CompoundTag)` | `RescueAlertPayload` network packet | No (`RescueServerManager` only) |
| **`JobReservationRegistry`** | Server Runtime Memory | Server | Yes | Ephemeral (Runtime only, not saved) | N/A (Server-only locks) | **YES** (Concurrent worker threads/ticks) |
| **`WorkerOrderSession`** | Client & Server Session | Both | Hybrid (Server validates commits) | Ephemeral / Held on ticket stack | `OrderSessionSyncPayload` | **YES** (Client edits, Server commits) |
| **`MinerJobData`** | Sub-struct within Profile / Ticket | Server | Yes | CompoundTag NBT | `MinerConfigSubmitPayload` / `Response` | Yes (Menu config vs Controller cursor) |

---

## 2. In-Depth State Lifecycle Analysis

### 2.1 `WORKER_STORAGE` (Highest Risk Structure)
- **Authoritative Owner:** The living `Mob` entity on the logical server holds the attachment instance.
- **Read Locations:**
  1. `WorkerExecutionLoop.swapMiningTool(Mob, WorkerStorage, BlockState)`: Scans all 15 slots for candidate tools.
  2. `WorkerExecutionLoop.deliverOutputs(Mob, WorkerStorage, BlockPos)`: Reads slots to identify transferable items vs tools.
  3. `MinerJobController.tickMining`: Reads available capacity before breaking blocks.
  4. `FarmerJobController.tickFarming`: Reads available seeds for replanting.
  5. `WorkerManagementMenu`: Reads slots 0-14 to populate GUI container slots.
- **Write Locations:**
  1. `WorkerStorage.setItem(int, ItemStack)`: Direct slot replacement.
  2. `WorkerStorage.removeItem(int, int)`: Decrements stack size and marks dirty.
  3. `WorkerStorage.removeItemNoUpdate(int)`: Extracts stack without notifying listeners.
  4. `WorkerStorage.clearContent()`: Clears all 15 slots to `ItemStack.EMPTY`.
  5. `WorkerManagementMenu.quickMoveStack`: Moves stacks between player inventory and WorkerStorage slots.
  6. `WorkerExecutionLoop.swapMiningTool`: Modifies source slot and main hand.
- **Risk Assessment:** High collision hazard between `WorkerManagementMenu` (user moving items) and `WorkerExecutionLoop` (tool swapper extracting items concurrently).

---

### 2.2 `WORKER_PROFILE`
- **Authoritative Owner:** Attached to worker entity via `ExampleMod.WORKER_PROFILE`.
- **Read Locations:**
  - `WorkerExecutionLoop.processWorker` (inspects current `WorkerJob` and `WorkerStatus`).
  - `MinerJobController` & `FarmerJobController` (reads worksite coords and mining parameters).
  - `WorkerManagementMenu` (reads worker statistics, status, name).
  - `RescueServerManager` (checks if worker is stuck or taking critical damage).
- **Write Locations:**
  - `WorkerProfile.setJob(WorkerJob)`: Modifies job assignment.
  - `WorkerProfile.setStatus(WorkerStatus)`: Updates running, paused, or stuck state.
  - `WorkerProfile.setWorksitePos(BlockPos)`: Sets origin anchor for job controllers.
- **Risk Assessment:** Moderate concurrency risk; if status is modified asynchronously without locking, tick dispatcher may skip execution steps.

---

### 2.3 `JobReservationRegistry`
- **Authoritative Owner:** Static server-level registry instance.
- **Scope:** Ephemeral runtime locks on world `BlockPos` and specific inventory item types.
- **Read/Write Cycle:**
  - Worker queries `hasReservation(BlockPos)` before pathfinding.
  - Worker acquires lock via `claimReservation(UUID, BlockPos, Duration)`.
  - Lock automatically expires after timeout ticks or is released upon job step completion.
- **Risk Assessment:** Memory leak risk if workers unload or die before releasing reservations; mitigated by tick-based timeout pruning.

---

## 3. Client/Server Boundary Enforcement

| Operation | Logical Side | Authority Rule |
|---|---|---|
| **Inventory Slot Click** | Client triggers | Server validates and mutates authoritative `WorkerStorage` |
| **Job Selection / Config** | Client GUI (`MinerJobConfigScreen`) | Sent via `MinerConfigSubmitPayload`; Server verifies permissions before writing to `MinerJobData` |
| **Worker Unstuck Request** | Client HUD button | Sent via `RescueResolutionRequestPayload`; Server verifies player ownership and path safety before teleporting |
| **Tether Line Rendering** | Client (`BindingTicketTetherRenderer`) | Uses synced positions from `OrderSessionSyncPayload`; purely cosmetic, no gameplay authority |
| **Tool Durability Check** | Server (`EquipmentCapabilityPolicy`) | Server authoritative; client only displays tooltip durability |
