# ELSTAR TICKET — INTERACTION SYSTEM AUDIT
**Mod ID:** `elstarticket` | **Package:** `com.example.examplemod`  
**Environment:** Minecraft 1.21.1 | NeoForge 21.1.264 | Java 21 | TLM 1.5.3  
**Audit Date:** 2026-11-05 | **Mode:** READ-ONLY Source Audit  
**Status Evidence Legend:** `[SOURCE_VERIFIED]`, `[API_CONFIRMED]`, `[PROPOSED]`, `[UNKNOWN]`, `[NOT_IMPLEMENTED]`, `[NOT_RUNTIME_VERIFIED]`

---

## 1. Executive Summary

This audit investigates how worker entities in the current Elstar Ticket codebase interact with blocks, entities, inventories, and external world targets. 

### Key Findings
1. **No Unified Interaction Pipeline `[SOURCE_VERIFIED]`:** Autonomous interactions are heavily fragmented across dedicated job controllers (`MinerJobController`, `FarmerJobController`, `JobAdapter`, `WorkerExecutionLoop`). Each controller implements its own ad-hoc block destruction, drop gathering, and inventory deposit routines.
2. **Prototype Interaction Registry is Unintegrated `[SOURCE_VERIFIED]`:** An interaction subsystem exists in [`WorkerInteraction.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerInteraction.java) and [`WorkerInteractionRegistry.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerInteractionRegistry.java), complete with request preflight, validation, and execution interfaces. However, **not a single production job or execution loop calls this registry**. It is tested in isolation (`WorkerInteractionRegistryTest`, `WorkerInteractionExecutionTest`) but completely disconnected from the active mod runtime.
3. **Absence of Simulated Players `[SOURCE_VERIFIED: NOT_IMPLEMENTED]`:** There is zero `FakePlayer` infrastructure in the codebase. All world mutations bypass player-centric interaction hooks (`BlockState.useItemOn`, `BlockState.attack`, `ItemStack.useOn`) and instead directly invoke server world methods (`level.destroyBlock`, `level.setBlock`, `Container.setItem`).
4. **EMIS (External Mod Interaction System) Status `[SOURCE_VERIFIED: NOT_IMPLEMENTED]`:** No class, interface, package, or token named `EMIS` exists in the repository. Capability discovery is currently split across disparate registries (`WorkerJobCapability`, `MiningToolCapability`, `EquipmentCapabilityPolicy`, `CropRegistry`, `StorageDestinationAdapter`).

---

## 2. Inventory of Existing Interaction Classes & Paths

| Class / Path | Primary Interaction Responsibility | Integration Status |
|---|---|---|
| [`WorkerInteraction.java:11-55`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerInteraction.java#L11-L55) | Defines `Request`, `Target` (`Block`, `Entity`, `Endpoint`), `Result`, `Outcome` (`SUCCESS`, `REJECTED`, `FAILED`, `UNAVAILABLE`) | `[SOURCE_VERIFIED]` Standalone prototype. Unused by jobs. |
| [`WorkerInteractionRegistry.java:13-108`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerInteractionRegistry.java#L13-L108) | Instance-scoped adapter registry supporting `Handler` (validate) and `ExecutableHandler` (execute + verify) | `[SOURCE_VERIFIED]` Standalone prototype. Zero calls from `src/main`. |
| [`WorkerExecutionLoop.java:857-900`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerExecutionLoop.java#L857-L900) | Farming dispatch: checks distance, looks at target, swings arm, executes `FarmerJobController.executeHarvestTransaction` | `[SOURCE_VERIFIED]` Hardcoded loop path. |
| [`WorkerExecutionLoop.java:1250-1265`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerExecutionLoop.java#L1250-L1265) | Mining dispatch: checks distance, looks at target, swings arm, executes `MinerJobController.executeLiveMiningStep` | `[SOURCE_VERIFIED]` Hardcoded loop path. |
| [`MinerJobController.java:1605-1638`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java#L1605-L1638) | Executes discrete block breaking via `level.destroyBlock(targetPos, false, worker)` and manually queries `Block.getDrops` | `[SOURCE_VERIFIED]` Direct server bypass. |
| [`FarmerJobController.java:608-665`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/FarmerJobController.java#L608-L665) | Executes crop harvesting via lambda `() -> level.destroyBlock(targetCrop, false, worker)` and replanting via `CropRegistry.replant` | `[SOURCE_VERIFIED]` Direct server bypass. |
| [`JobAdapter.java:270-304`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/JobAdapter.java#L270-L304) | `GenericMachineInteractAdapter`: checks `Capabilities.ItemHandler.BLOCK` on target pos and inserts requirement stack | `[SOURCE_VERIFIED]` Ad-hoc capability adapter. |
| [`StorageDestinationAdapter.java:73-124`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/StorageDestinationAdapter.java#L73-L124) | Resolves live `Container` (including vanilla double chests) and calls controller delivery routines | `[SOURCE_VERIFIED]` Container wrapper. |

---

## 3. Current Interaction Call Chains

### 3.1 Block Mining Interaction Chain
```
WorkerExecutionLoop.onServerTick (Post)
  -> WorkerExecutionLoop.processWorker
    -> distance check: eyeDistSq <= 16.0 || feetDistSq <= 12.25
    -> worker.getLookControl().setLookAt(activePos)
    -> worker.swing(MAIN_HAND)
    -> MinerJobController.executeLiveMiningStep(profile, worker, owner, heldTool, activePos)
      -> Block.getDrops(state, level, targetPos, null, worker, heldTool)
      -> level.destroyBlock(targetPos, false, worker)  <-- Direct World Mutation!
      -> heldTool.hurtAndBreak(1, worker, MAINHAND)
      -> worker.setData(ExampleMod.WORKER_PROFILE, updated)
```
- **Analysis:** Completely bypasses standard left-click block breaking physics, `BlockState.attack()`, break speeds, hardness calculations, and player block break events. If a modded block requires progressive breaking ticks or relies on `PlayerInteractEvent.LeftClickBlock`, it will not function properly.

### 3.2 Crop Harvesting & Replanting Interaction Chain
```
WorkerExecutionLoop.onServerTick
  -> WorkerExecutionLoop.processWorker
    -> distance check: eyeDistSq <= 16.0 || distSq <= 12.25
    -> worker.getLookControl().setLookAt(targetCrop)
    -> worker.swing(MAIN_HAND)
    -> FarmerJobController.executeHarvestTransaction(...)
      -> FarmerJobController.calculateCropDrops(...)
      -> level.destroyBlock(targetCrop, false, worker)  <-- Direct World Mutation!
      -> CropRegistry.replant(level, targetCrop, targetState)
        -> level.setBlock(targetCrop, replantState, 3)  <-- Direct Block Placement!
      -> worker.setData(ExampleMod.WORKER_PROFILE, updated)
```
- **Analysis:** Bypass of right-click crop harvesting (such as right-click harvest mods or vanilla sweet berry bush harvesting). If a modded crop uses `BlockState.useWithoutItem()` or `BlockState.useItemOn()` for harvesting, `destroyBlock` forcibly destroys the plant rather than collecting its fruit.

### 3.3 Generic Machine Interaction Chain (`JobAdapter`)
```
JobQueue.tick
  -> JobAdapter.execute
    -> GenericMachineInteractAdapter.execute
      -> level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face)
      -> ItemHandlerHelper.insertItemStacked(handler, req, false)
```
- **Analysis:** Hardcodes item insertion into an item handler capability. Does not simulate a right-click interaction on the machine block. If a machine requires a player right-click with a wrench, battery, bucket, or empty hand to trigger a recipe or toggle mode, this adapter cannot perform it.

---

## 4. Current Simulated-Player Mechanisms

`[SOURCE_VERIFIED: NOT_IMPLEMENTED]`

An exhaustive search across the entire codebase (`findstr /s /i "FakePlayer" *.java`) confirms that **no simulated player (e.g. `net.neoforged.neoforge.common.util.FakePlayer`) exists**.
- All world queries pass the `Mob` worker entity directly to Minecraft APIs (e.g. `level.destroyBlock(targetPos, false, worker)`).
- When Minecraft methods require a `Player` instance (e.g. `BlockState.useItemOn`, `BlockState.useWithoutItem`, `BlockState.attack`), the mod currently cannot call them.
- In [`JobSafetyPolicy.java:31`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/JobSafetyPolicy.java#L31), `ServerPlayer owner` is passed into validation methods, but the owner may be far away, in another dimension, or offline, meaning the owner cannot safely be passed as the direct interaction actor.

---

## 5. Architectural Problems & Code Duplication

1. **Semantic Divergence Between Job Purpose and Physical Action:**
   - The codebase conflates *what the job wants to achieve* (e.g. `MINE`, `FARM`, `DEPOSIT`) with *how the entity physically interacts with the world* (breaking a block, right-clicking a block, clicking an entity).
   - This leads to duplicating reach checks, look-at targeting, arm swinging, and error handling in every job controller.
2. **Brittle Mod Compatibility:**
   - Because `MinerJobController` and `FarmerJobController` call `level.destroyBlock` directly, modded blocks with custom right-click harvesting (e.g. Pam's HarvestCraft, Farmer's Delight crops, Mystical Agriculture) cannot be harvested without breaking the entire plant.
   - Machines from Create, AE2, or Thermal that require right-click activation cannot be triggered.
3. **Dead Code / Orphaned Interaction Registry:**
   - [`WorkerInteraction.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerInteraction.java) and [`WorkerInteractionRegistry.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerInteractionRegistry.java) represent a clean design pattern (preflight validate -> execute -> verify postcondition), but they are not wired into `WorkerExecutionLoop`. They define actions as arbitrary strings (`ResourceLocation action`) rather than the core player-like primitives (`LEFT_CLICK` / `RIGHT_CLICK`).
4. **Client/Server Desynchronization Hazards:**
   - Physical world changes happen purely on the server thread without dispatching client-side interaction feedback beyond a cosmetic `worker.swing(MAIN_HAND)`.
   - No block break progress particles or sound effects are sent to nearby players during extended mining actions.
