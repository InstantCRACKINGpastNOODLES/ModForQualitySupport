# ELSTAR TICKET — INTERACTION MIGRATION PLAN
**Mod ID:** `elstarticket` | **Package:** `com.example.examplemod`  
**Environment:** Minecraft 1.21.1 | NeoForge 21.1.264 | Java 21 | TLM 1.5.3  
**Audit Date:** 2026-11-05 | **Mode:** READ-ONLY Architecture Plan  
**Status Evidence Legend:** `[SOURCE_VERIFIED]`, `[API_CONFIRMED]`, `[PROPOSED]`, `[UNKNOWN]`, `[NOT_IMPLEMENTED]`, `[NOT_RUNTIME_VERIFIED]`

---

## 1. Migration Strategy: Non-Destructive Phased Transition

The migration from ad-hoc world mutations to the **Two-Intent Interaction Model (`LEFT_CLICK` / `RIGHT_CLICK`)** must be executed incrementally. The core planning, pathfinding, and safety policies are preserved, while execution leaves are rerouted through the unified `InteractionResolver`.

```
Existing Subsystems                    Proposed Architecture
┌─────────────────────────┐            ┌───────────────────────────┐
│ MinerJobController      │            │ MinerJobController        │
│ - 3D Box Scanning       │──Keep─────▶│ - 3D Box Scanning         │
│ - Preflight Checks      │──Keep─────▶│ - Preflight Checks        │
│ - destroyBlock Direct   │──Replace──▶│ - emits LEFT_CLICK Intent │
└─────────────────────────┘            └─────────────┬─────────────┘
                                                     │
┌─────────────────────────┐                          ▼
│ FarmerJobController     │            ┌───────────────────────────┐
│ - Farmland Scan         │──Keep─────▶│ FarmerJobController       │
│ - Maturity Checks       │──Keep─────▶│ - Farmland Scan           │
│ - destroyBlock & setBlk │──Replace──▶│ - emits L/R CLICK Intent  │
└─────────────────────────┘            └─────────────┬─────────────┘
                                                     │
                                                     ▼
                                       ┌───────────────────────────┐
                                       │ InteractionResolver       │
                                       │ (Tier 1 -> Tier 2 -> 3)   │
                                       └───────────────────────────┘
```

---

## 2. Subsystem-by-Subsystem Migration Roadmap

### 2.1 Mining Subsystem Migration
- **Preserved As-Is:**
  - `MinerJobData` 3D bounding box boundaries, scanning cursors, and objective modes (`BLOCK_COUNT`, `CLEAR_AREA`).
  - `MinerResourcePreflight` evaluating tool tier against block hardness.
- **Rerouted Execution (`WorkerExecutionLoop.java:1250-1265`):**
  - Replace direct call to `MinerJobController.executeLiveMiningStep`:
    ```java
    // Instead of direct level.destroyBlock:
    InteractionIntent intent = new InteractionIntent(
        UUID.randomUUID(),
        ActionType.LEFT_CLICK,
        new InteractionTarget.BlockTarget(targetPos, Direction.UP, Vec3.atCenterOf(targetPos)),
        InteractionHand.MAIN_HAND,
        heldTool.copy(),
        currentHoldTicks,
        4.5,
        true
    );
    InteractionResult result = InteractionResolver.resolveAndExecute(intent, worker, level);
    ```
  - When `result.isWaiting()`: worker continues arm swinging and emits break particles.
  - When `result.isSuccess()`: block drops are collected into `MinerJobData.pendingStacks()`.

### 2.2 Farming Subsystem Migration
- **Preserved As-Is:**
  - `CropRegistry` and `CropAdapter` maturity inspection predicates.
  - Farmland coordinate scanning in `FarmerJobController`.
- **Rerouted Execution (`FarmerJobController.java:608-665`):**
  - **Harvest Action:**
    - If crop requires breaking (e.g. wheat, carrots): worker issues `LEFT_CLICK` intent on crop block.
    - If crop supports right-click fruit picking (e.g. sweet berry bush, modded crops): worker issues `RIGHT_CLICK` intent on crop block.
  - **Replanting Action:**
    - Worker issues `RIGHT_CLICK` intent on farmland block face `Direction.UP` with the seed item in hand.
    - Generic item use (`ItemStack.useOn`) naturally handles seed placement, farmland hydration checks, and block placement events.

### 2.3 Delivery & Storage Migration
- **Preserved As-Is:**
  - `StorageDestinationAdapter.isValidStorage` chunk loading and double-chest detection.
  - `JobReservationRegistry` locks preventing concurrent worker chest collisions.
- **Rerouted Execution:**
  - Worker approaches storage chest -> issues `RIGHT_CLICK` intent on chest block or executes atomic insertion via Tier 2 `Capabilities.ItemHandler.BLOCK`.
  - Guarantees reserve tools are excluded before intent dispatch.

### 2.4 Entity Interaction & Combat Migration
- **Interaction with Passive Mobs:**
  - Milking cow, shearing sheep, feeding animal: issues `RIGHT_CLICK` intent on `EntityTarget`.
- **Combat / Self-Defense:**
  - Striking aggressive hostile mob: issues `LEFT_CLICK` intent on `EntityTarget`.
  - Resolver applies weapon attack damage, attack cooldown strength scale, and weapon durability loss.

---

## 3. Component Disposition Matrix

| Existing Class | Recommendation | Rationale |
|---|---|---|
| [`WorkerInteraction.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerInteraction.java) | **REFACTOR** | Expand sealed `Target` and adopt `ActionType` (`LEFT_CLICK`, `RIGHT_CLICK`). |
| [`WorkerInteractionRegistry.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerInteractionRegistry.java) | **REFACTOR** | Integrate into `InteractionResolver` as the Tier 3 adapter registry. |
| [`WorkerExecutionLoop.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerExecutionLoop.java) | **REFACTOR** | Remove hardcoded `destroyBlock` calls; dispatch intents to resolver. |
| [`MinerJobController.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java) | **WRAP** | Retain 3D math and scanning; delegate execution step to intent resolver. |
| [`FarmerJobController.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/FarmerJobController.java) | **WRAP** | Retain crop discovery; delegate harvest and replant steps to intent resolver. |
| [`JobAdapter.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/JobAdapter.java) | **REFACTOR** | Replace `GenericMachineInteractAdapter` with standard `RIGHT_CLICK` machine intent. |
| [`EquipmentCapabilityPolicy.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/EquipmentCapabilityPolicy.java) | **REUSE AS-IS** | Durability, safety, and energy profiles remain authoritative preflight checks. |
| [`MiningToolCapability.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MiningToolCapability.java) | **REUSE AS-IS** | Single-block tool verification remains active before intent creation. |
| [`CropRegistry.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/CropRegistry.java) | **REUSE AS-IS** | Maturity predicates remain authoritative for target selection. |

---

## 4. Implementation Phasing Strategy

```
Phase 1: Foundation (Architecture & Resolver Engine)
  ├── 1.1 Implement InteractionIntent, ActionType, and InteractionResult
  ├── 1.2 Implement WorkerFakePlayer bridge with owner permissions
  └── 1.3 Implement GenericExecutor (Tier 1 BlockState.use / attack)

Phase 2: Controller Rerouting (Mining & Farming)
  ├── 2.1 Reroute MinerJobController block breaking through LEFT_CLICK
  ├── 2.2 Reroute FarmerJobController harvest/replant through L/R CLICK
  └── 2.3 Verify drop collection and seed refund invariants

Phase 3: Capability Discovery & Adapters (Tiers 2 & 3)
  ├── 3.1 Unify capability lookups (IItemHandler, IEnergyStorage)
  ├── 3.2 Implement Create & Waystones optional adapters
  └── 3.3 Add anti-looping debounce and progressive breaking HUD feedback

Phase 4: Multi-Mod Integration Verification
  ├── 4.1 In-game validation with Touhou Little Maid 1.5.3
  └── 4.2 Compatibility test suites with target mod ecosystems
```
