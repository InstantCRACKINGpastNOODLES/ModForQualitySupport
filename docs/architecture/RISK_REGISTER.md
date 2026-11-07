# ELSTAR TICKET — RISK & COMPLEXITY REGISTER
**Mod ID:** `elstarticket` | **Environment:** Minecraft 1.21.1 / NeoForge 21.1.264 / TLM 1.5.3  
**Audit Date:** 2026-11-05 | **Mode:** READ-ONLY Structural Intelligence Audit

---

## 1. Risk Prioritization Matrix

| Severity | Count | Summary of Critical Concerns |
|---|---|---|
| **P0 (Critical)** | 3 | Stale Menu Snapshot Overwriting Attachments; Indiscriminate Tool Deposit in `JobAdapter`; Monolithic God Classes |
| **P1 (Severe)** | 3 | Silent Tool Swap Abort; Concurrent Equipment Mutation; Unloaded Chunk Boundary Pathfinding |
| **P2 (Moderate)** | 3 | Flat Single-Package Architecture (58 classes); Ephemeral Reservation Loss on Restart; High GC Pressure |
| **P3 (Minor)** | 2 | Deprecated API Aliases; Redundant Mod Asset Directories |

---

## 2. P0 Findings: Critical Data Loss & State Corruption

### Finding P0-01: Stale Snapshot Write-Back in `WorkerManagementMenu`
- **Source Evidence:**
  - `WorkerManagementMenu.java:101`: `mob.setData(ExampleMod.WORKER_STORAGE, ws);`
  - `WorkerManagementMenu.java:105-121`:
    ```java
    private final SimpleContainer storage = new SimpleContainer(WorkerStorage.SIZE) {
        @Override public void setChanged() {
            super.setChanged();
            ...
            if (storageWriter != null) {
                List<ItemStack> items = new ArrayList<>();
                for (int i = 0; i < WorkerStorage.SIZE; i++) items.add(getItem(i).copy());
                storageWriter.accept(new WorkerStorage(items));
            }
        }
    };
    ```
- **Hazard Analysis:**
  1. When player opens the GUI, `WorkerManagementMenu` snapshots the 15 slots into `storage`.
  2. While the menu is open, if the worker's execution loop triggers a tool swap (`WorkerExecutionLoop.swapJobTool`), the underlying entity attachment is updated on the entity.
  3. If the player subsequently shifts or clicks an item in the GUI, `storage.setChanged()` executes and writes back its internal copy of all 15 slots to `WORKER_STORAGE`.
  4. The tool swapped in the background, or an item stashed by the worker, is completely overwritten and erased by the stale menu snapshot.
- **Impact:** Confirmed data loss of tools and items during concurrent player inspection and worker execution.

---

### Finding P0-02: Indiscriminate Storage Dump in `JobAdapter.java` (`GenericDepositAdapter`)
- **Source Evidence:**
  - `JobAdapter.java:126-149`:
    ```java
    for (ItemStack stack : ws.items()) {
        if (stack.isEmpty()) {
            remaining.add(ItemStack.EMPTY);
            continue;
        }
        if (itemHandler != null) {
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(itemHandler, stack, false);
            remaining.add(leftover);
            ...
    ```
- **Hazard Analysis:**
  1. `GenericDepositAdapter` iterates over all 15 slots in `WorkerStorage`.
  2. Unlike `MinerJobController` which only deposits pending drops, `JobAdapter` dumps **every non-empty item** into the target chest.
  3. It does not check `JobToolRequirement`, item tags, or reservation flags.
  4. Any backup pickaxes, hoes, weapons, or seeds sitting in WorkerStorage are stripped from the worker and pushed into the chest!
- **Impact:** Total loss of worker equipment into delivery storage containers when executing chained queue jobs.

---

### Finding P0-03: Monolithic God Classes without Reentrant State Locks
- **Source Evidence:**
  - `WorkerExecutionLoop.java` (1,648 lines)
  - `MinerJobController.java` (1,640 lines)
- **Hazard Analysis:**
  - Both classes mix spatial math, block breaking, tool durability policies, pathfinding, capability checking, and attachment serialization in single execution files.
  - No lock synchronization exists between the server tick handler, player menu interactions, and incoming network packets (`MinerConfigSubmitPayload`).
- **Impact:** Race conditions, unhandled concurrency anomalies, and extremely high regression risks during maintenance.

---

## 3. P1 Findings: Severe Behavioral Defects & Desynchronization

### Finding P1-01: Silent Failure & Stalling on Storage Space Exhaustion
- **Source Evidence:**
  - `WorkerExecutionLoop.java:353-355`:
    ```java
    if (!toStash.isEmpty()) {
        return ToolSwapResult.INSUFFICIENT_STORAGE_SPACE;
    }
    ```
- **Hazard Analysis:**
  - When swapping tools, if `MAINHAND` contains an item and all 15 WorkerStorage slots are full or cannot stack, the method fails closed (returning `INSUFFICIENT_STORAGE_SPACE`).
  - However, the caller in `WorkerExecutionLoop.processWorker` does not emit a player alert, drop the item safely, or trigger a return-to-base delivery cycle. The worker remains frozen in place indefinitely.
- **Impact:** Worker stalls permanently with no feedback to the player.

---

### Finding P1-02: Concurrent Equipment Mutation between AI and Menu
- **Source Evidence:**
  - `WorkerManagementMenu.java:144-148`:
    ```java
    @Override public void setItem(int index, ItemStack stack) {
        Mob mob = worker();
        if (mob != null && sessionValid(inventory.player, mob) && candidateCount() == 1 && !inventory.player.level().isClientSide())
            mob.setItemSlot(equipment[index], stack);
    }
    ```
- **Hazard Analysis:**
  - `mob.setItemSlot` is called directly from container menu slots without checking if the mob is currently swinging, mining, or being ticked by AI.
- **Impact:** Visual ghost items, client/server equipment desynchronization, and corrupted weapon attack animations.

---

### Finding P1-03: Unloaded Chunk Boundary Hazards in Long-Range Mining
- **Source Evidence:**
  - `MinerJobController.java:820-850`, `StorageDestinationAdapter.java:55`.
- **Hazard Analysis:**
  - If a player designates a delivery chest in an unloaded chunk while the worksite is loaded, the worker attempts to pathfind towards the unloaded coordinate.
  - Pathfinding aborts or stalls, causing worker to enter `WorkerStatus.STUCK`.
- **Impact:** Pathfinding failure and frequent stuck false-positives.

---

## 4. P2 Findings: Architectural & Maintainability Concerns

### Finding P2-01: Flat Single-Package Anti-Pattern
- **Source Evidence:** All 58 production `.java` files are located in `src/main/java/com/example/examplemod/`.
- **Risk:**
  - Package-private encapsulation cannot be enforced across logical subsystems (e.g. `CropRegistry` is exposed to `WorkerExecutionLoop`).
  - Circular references between adapters, menus, and controllers are obscured.

---

### Finding P2-02: Ephemeral In-Memory Reservation Registry
- **Source Evidence:** `JobReservationRegistry.java`.
- **Risk:**
  - Reservations on chests and blocks are stored in static RAM collections.
  - When the server restarts or reloads, all reservations vanish, potentially allowing multiple workers to lock onto the same chest simultaneously upon reboot.

---

### Finding P2-03: Excessive Garbage Collection Allocations in Tick Loops
- **Source Evidence:** `WorkerExecutionLoop.java:319`:
  `new ArrayList<>(items.stream().map(ItemStack::copy).toList())`
- **Risk:**
  - On a server with 50+ workers running at 20 TPS, copying all 15 item stacks per worker per tick creates significant memory churn and micro-stutters.

---

## 5. P3 Findings: Minor Defects & Deprecations

1. **Deprecated API Aliases:**
   - `StorageDestinationAdapter.java:195-203` retains `@Deprecated deliverWithNeighborFallback` which simply redirects to `deliverToDesignatedStorage`.
2. **Redundant Mod Asset Namespaces:**
   - Resources exist under both `assets/elstarticket` and `assets/examplemod` in `src/main/resources`.
