# ELSTAR TICKET — INTERACTION RISK REGISTER
**Mod ID:** `elstarticket` | **Package:** `com.example.examplemod`  
**Environment:** Minecraft 1.21.1 | NeoForge 21.1.264 | Java 21 | TLM 1.5.3  
**Audit Date:** 2026-11-05 | **Mode:** READ-ONLY Risk Analysis  
**Status Evidence Legend:** `[SOURCE_VERIFIED]`, `[API_CONFIRMED]`, `[PROPOSED]`, `[UNKNOWN]`, `[NOT_IMPLEMENTED]`, `[NOT_RUNTIME_VERIFIED]`

---

## 1. Prioritized Interaction Risk Matrix

| Severity | Count | Summary of Risks |
|---|---|---|
| **P0 (Critical)** | 4 | Permission Bypass in Claims; Item Loss During Live Swapping; Unintended Block Stripping/Destruction; Action Duplication |
| **P1 (Severe)** | 4 | Stale Held-Item State; 20-TPS Repeated Click Loops; Target Raycast Occlusion (Grass/Water); Client Ghost Blocks |
| **P2 (Moderate)** | 3 | Conflicting Multiple Adapters; Mod API Version Drift; Tick Lag from Heavy FakePlayer Allocations |

---

## 2. In-Depth Risk Analyses & Mitigations

### Risk 01: Permission Bypass in Claimed Chunks (P0)
- **Cause:** `WorkerFakePlayer` performing interactions using an arbitrary fake UUID without referencing the worker owner's identity.
- **Impact:** Workers could steal from private containers or grief protected bases on multiplayer servers running FTB Chunks, OpenPartiesAndClaims, or WorldGuard.
- **Detection Strategy:** Preflight check testing `NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(...))` and `PlayerInteractEvent.RightClickBlock`.
- **Mitigation:**
  - `WorkerFakePlayer` must strictly mirror the owner's `UUID` and `GameProfile`.
  - Check `owner.mayBuild()` and query claim APIs before dispatching any `InteractionIntent`.

---

### Risk 02: Item Loss or Duplication During Live Interaction (P0)
- **Cause:** An interaction intent is dispatched while `WorkerExecutionLoop.swapJobTool` or `WorkerManagementMenu` mutates `WorkerStorage` or `MAINHAND` on the same tick.
- **Impact:** Items duplicated in hand and storage, or active tools consumed and overwritten by stale container syncs.
- **Detection Strategy:** Unit tests verifying tool identity before and after simulated block break and menu open.
- **Mitigation:**
  - `InteractionIntent` carries an immutable `ItemStack heldItemSnapshot`.
  - Pre-commit lock: Worker inventory mutations are locked while an intent is actively resolving.
  - Fail-closed rollback if inventory state diverged during execution.

---

### Risk 03: Unintended World Modification (Axe Stripping / Trampling) (P0)
- **Cause:** Generic `RIGHT_CLICK` on a log while holding an axe strips the bark (`ToolActions.AXE_STRIP`) instead of interacting with an adjacent block entity; jumping onto farmland destroys tilled soil.
- **Impact:** Unintended destruction of player builds and farmlands.
- **Detection Strategy:** Blacklist filtering for destructive secondary item actions in `JobSafetyPolicy`.
- **Mitigation:**
  - For `RIGHT_CLICK` intended for machine activation, enforce empty-hand interaction unless a specific item requirement (e.g. bucket, wrench) is demanded by the job step.
  - Prevent workers from executing `RIGHT_CLICK` with axes, hoes, or shovels on generic blocks unless explicitly commanded.

---

### Risk 04: Duplicate Interactions (Rapid Double-Clicking) (P0)
- **Cause:** Executing an intent every tick causes toggleable blocks (doors, levers, fence gates) to immediately invert back and forth 20 times per second.
- **Impact:** Redstone loop spam, door jamming, audio glitching, and corrupted machine recipe cycles.
- **Detection Strategy:** Tick monitor logging frequency of intents per target coordinate.
- **Mitigation:**
  - Mandatory 4-tick debounce cooldown per discrete target coordinate.
  - Require postcondition confirmation before clearing the cooldown.

---

### Risk 05: Stale Held-Item State (P1)
- **Cause:** Tool breaks during continuous mining, but the worker continues issuing `LEFT_CLICK` with the broken or vacated slot.
- **Impact:** Worker attempts to mine high-hardness ore with bare fists, destroying drops without yielding resources.
- **Detection Strategy:** Verify `JobToolRequirement.isSatisfiedBy(worker.getMainHandItem())` immediately before every tick's intent resolution.
- **Mitigation:**
  - If held item breaks or changes, abort intent with `FAILED_TOOL_DEPLETED` and yield to `tryEnsureMiningToolEquipped`.

---

### Risk 06: 20-TPS Repeated Click Loops on Incompatible Targets (P1)
- **Cause:** Job controller targets an unpowered machine or empty container; interaction returns `FAILED`, and the controller immediately re-attempts it every tick.
- **Impact:** Severe server CPU consumption and spamming error logs.
- **Detection Strategy:** Failure count tracker on target `BlockPos`.
- **Mitigation:**
  - Exponential backoff: after 3 consecutive failures, suppress interactions with that target for 60 ticks (3.0 seconds).

---

### Risk 07: Target Raycast Occlusion by Ambient Blocks (P1)
- **Cause:** Worker attempts to click a crop or machine, but a tall grass blade, flower, or water source block intersects the raycast line between worker eyes and target.
- **Impact:** Worker punches grass repeatedly instead of harvesting the crop behind it.
- **Detection Strategy:** Raycast collision mask filtering passable, replaceable blocks (`BlockState.canBeReplaced()`).
- **Mitigation:**
  - Interaction raytracer ignores passable non-solid blocks when resolving `BlockTarget`.

---

### Risk 08: Client/Server Desynchronization (Ghost Blocks) (P1)
- **Cause:** Server breaks block via `level.destroyBlock` without notifying client renderer with particle/sound packets.
- **Impact:** Ghost blocks appearing client-side where worker broke a block, confusing players.
- **Detection Strategy:** In-game visual inspection of block breaking progress.
- **Mitigation:**
  - Broadcast `level.destroyBlockProgress(worker.getId(), pos, stage)` and `level.levelEvent(2001, pos, Block.getId(state))`.

---

### Risk 09: Conflicting Multiple Adapters (P2)
- **Cause:** Both a generic capability adapter and a mod-specific adapter return `supports = true` for the same block.
- **Impact:** Ambiguous execution, duplicate actions, or unexpected side-effects.
- **Detection Strategy:** Registry check detecting overlapping adapter priorities (`WorkerInteractionRegistryTest.java:77-84`).
- **Mitigation:**
  - Strict priority ordering: Explicit mod adapters (Priority 100) override Generic Capabilities (Priority 50), which override Vanilla Simulation (Priority 10).

---

### Risk 10: Mod API Version Drift (P2)
- **Cause:** External mods (e.g. Create, Waystones) updating method signatures across minor versions.
- **Impact:** `NoSuchMethodError` crashing the dedicated server on interaction.
- **Detection Strategy:** Automated reflection sanity checks on mod integration classes during mod startup.
- **Mitigation:**
  - Use soft reflection wrapped in `try-catch` blocks; fallback to `UNSUPPORTED` if external method calls throw.

---

### Risk 11: Performance & Tick Overhead from FakePlayer Churn (P2)
- **Cause:** Allocating new `WorkerFakePlayer` instances every tick for dozens of active workers.
- **Impact:** High garbage collection pressure and micro-stutters.
- **Detection Strategy:** JVM heap profiling measuring allocation rate of player objects.
- **Mitigation:**
  - Thread-local or level-scoped pooled `WorkerFakePlayer` reuse, re-binding coordinates and held item per interaction.
