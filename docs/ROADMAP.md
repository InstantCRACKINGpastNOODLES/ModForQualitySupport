# Elstar Ticket roadmap

Environment: Minecraft 1.21.1, NeoForge 21.1.264, ModDevGradle 2.0.148, Java 21. The mod ID is now `elstarticket` and the display name is Elstar Ticket; Java packages/class names and some template content retain their original names.

## Work Control Architecture Redesign: Dedicated Work Order UI, Binding Ticket HUD Overlay, S2C Session Synchronization & Worker Execution Reliability (build and unit-tested; live gameplay unverified)
- **Root-Cause Analysis of Prior Behavior:**
  - **Why only Move was operational:**
    1. *Direct Shortcut vs Multi-Stage Session:* Shift-Right-Clicking on the ground with a Binding Ticket directly invoked `profile.assignMove()`, bypassing the session system and requiring only a single block click with no tools and no storage. In contrast, Mine and Farm required multi-stage configuration (worksite + storage container) and verified tools.
    2. *Missing HUD & Invisible Storage Stage:* Without a persistent HUD overlay or client-side session state, players received only transient action-bar messages (which faded in 3 seconds). Players were unaware they were in `SELECTING_STORAGE` mode; subsequent clicks on ground or blocks were rejected as `INVALID_STORAGE`, and sessions silently timed out after 60s.
    3. *Pathfinding Throttling Inversion in `WorkerExecutionLoop`:* In Farm and Mine loops, pathfinding checked `(!pathfinder.getNavigation().isDone() || tick % 20 == 0)`. When navigating (`!isDone() == true`), this condition evaluated to `true` **every single tick (20 Hz)**. Continuously calling `pathfinder.moveTo()` resets the entity's pathfinder every tick, causing the worker to stutter or freeze in place instead of walking to worksites, crops, or storage.
    4. *Sitting Pose Stalling:* If a maid was in a seated pose (`TamableAnimal.isOrderedToSit()` / `isInSittingPose()` / TLM `isMaidSitting()`), vanilla pathfinding was suppressed.
- **Three Distinct Architecture Responsibilities (Goal A):**
  1. **Worker Management UI ([`WorkerManagementScreen.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerManagementScreen.java), [`WorkerManagementMenu.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerManagementMenu.java)):**
     - Retained and streamlined for worker vitals (hearts/armor), entity preview, equipment slots, worker storage, player inventory, behavior buttons (Follow, Stand, Set Home), and current running job status/progress/failure reasons.
     - Preserves native Touhou Little Maid UI without overrides or conflicts.
     - Clicking task buttons opens the dedicated Work Order UI rather than closing the screen with hardcoded values.
  2. **Dedicated Work Order UI ([`WorkOrderScreen.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkOrderScreen.java)):**
     - Full screen for configuring work orders: job type selection (`Move`, `Mine`, `Farm`), quota parameter buttons (`16`, `32`, `64`), and real-time equipment verification (displaying whether the worker is holding an authorized pickaxe or hoe).
     - Provides clear workflow previews (Move: 1 step; Mine/Farm: Step 1 Worksite $\to$ Step 2 Storage).
     - Clicking "Start Selection" sends the parameterized order packet (button IDs 105, 201..203, 211..213) to the server, closes the screen, and transitions smoothly into in-world target selection mode.
  3. **Binding Ticket HUD Overlay ([`BindingTicketHUD.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/BindingTicketHUD.java), [`ExampleModClient.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/ExampleModClient.java)):**
     - Registered on NeoForge's `RegisterGuiLayersEvent` above `VanillaGuiLayers.CROSSHAIR`.
     - Displays real-time status banners whenever a ticket is held or an order session is active:
       - *Unbound Ticket:* Prompts the player to aim and right-click a worker; raycasts crosshair to identify valid target workers vs ineligible entities.
       - *Bound Ticket:* Displays bound worker identity, right-click to manage, and shift-click quick move prompt.
       - *Stage: `SELECTING_WORKSITE`:* Shows Job title, requested quota, prompt "Step 1: Right-click a block to select worksite", error hints on invalid blocks, and sneak-cancel prompt.
       - *Stage: `SELECTING_STORAGE`:* Shows worksite coordinates, prompt "Step 2: Right-click storage container (Chest/Barrel) within 16 blocks", and error hints.
       - *Feedback Banner:* Transient confirmation banner (Order Activated / Order Cancelled) lasting 4 seconds upon state changes.
- **Client/Server Synchronization ([`OrderSessionSyncPayload.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/OrderSessionSyncPayload.java), [`WorkerOrderSession.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerOrderSession.java), [`ExampleMod.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/ExampleMod.java)):**
  - Registered custom packet payload `elstarticket:order_session_sync` via `RegisterPayloadHandlersEvent`.
  - Server authoritatively transmits session stage (`SELECTING_WORKSITE`, `SELECTING_STORAGE`, `ORDER_COMPLETED`, `ORDER_CANCELLED`), worker metadata, quota, and validation error messages via `PacketDistributor.sendToPlayer()`.
- **Worker Execution Loop & Standing State Enforcement ([`WorkerExecutionLoop.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerExecutionLoop.java)):**
  - Corrected navigation throttling condition from `(!isDone || tick % 20 == 0)` to `(isDone || tick % 20 == 0)` across all Farm and Mine movement dispatchers, allowing uninterrupted pathfinding.
  - Added `ensureStanding(worker)` to automatically clear sitting poses when active Move, Mine, or Farm jobs are assigned or running.
- **Verification & Test Status:**
  - Added 6 regression tests in [`WorkControlRedesignTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/WorkControlRedesignTest.java):
    - `orderSessionSyncPayloadEncodesAndDecodesCorrectly`
    - `orderSessionSyncPayloadHandlesNullWorksitePos`
    - `bindingTicketHudTracksSessionStateLifecycle`
    - `newOrderSessionDoesNotInheritStaleOrderTargetsOrDestinations`
    - `workerOrderSessionDirectionNormalizedAndPreserved`
    - `workerCannotReceiveNewJobIfAlreadyAssignedOrRunning`
  - Total automated test suite: **193 unit tests passed, 0 failures, 0 skipped**.
  - Clean build verified: `build/libs/elstarticket-1.0.0.jar` (241,179 bytes).

## UI Layout, Slot Drift, Add Task Flow & Interaction Priority Fix (build and unit-tested; live gameplay unverified)
- **Bug A — Items Tab UI Overlap Fixed ([`WorkerManagementScreen.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerManagementScreen.java)):**
  - **Root Cause:** In the Items tab (`activeTab == 1`), `layoutPlan.previewW() == 0`, causing `renderLabels` to render compact vitals at hardcoded `X = 140, Y = 12`. The string `"20/20 HP  Armor: 0"` spanned $X = 140..235$, directly colliding with the tab buttons ("All", "Worker", "Items") placed at $X = 178..310, Y = 8$.
  - **Correction:** Re-anchored the header layout in Tab 1: worker name is constrained to 65px at $X = 8, Y = 6$, and compact vitals are drawn at $X = 76, Y = 6$ (spanning $X = 76..161$), leaving 17px of clear margin before the "All" button at $X = 178$. Tab buttons are shifted to $Y = 6$ to align cleanly with header text. Section labels ("Storage" and "Inventory") are placed at $Y = 25$, completely separated from the header area.
- **Bug B — Inventory Slots Drift Outside Window Fixed ([`WorkerManagementMenu.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerManagementMenu.java), [`WorkerManagementScreen.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerManagementScreen.java)):**
  - **Root Cause:** In `WorkerManagementMenu`, slot positions were hardcoded in the constructor at `STORAGE_Y = 126`, `PLAYER_INV_Y = 126`, and `HOTBAR_Y = 184`. When switching to the Items tab, `WorkerManagementScreen` shrunk `tabHeight` to 160 and drew background panels at $Y = 30$, but slot instances were never repositioned. As a result, the slots at $Y = 126..202$ hung 42px below the bottom of the window into the void, while the background boxes at $Y = 30$ remained empty.
  - **Correction:** Added dynamic slot repositioning `updateSlotPositions()` in `WorkerManagementMenu`. When `activeTab == 1` (Items tab), storage slots are repositioned to $Y = 36..72$, player inventory slots to $Y = 36..72$, and hotbar slots to $Y = 94$. In Tab -1 (All) and Tab 0 (Worker), slots remain at $Y = 126..202$. `updateSlotPositions()` is invoked during menu initialization and tab switching. Synchronized tab changes across client and server via container button IDs 110, 111, and 112. Panel height in Tab 1 is set to 130px, fitting all slots, backgrounds, and labels with comfortable margins and zero void drift.
- **Bug C — Add Task Buttons Ergonomics & Worksite Flow Fixed ([`WorkerManagementScreen.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerManagementScreen.java), [`WorkerManagementMenu.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerManagementMenu.java), [`BindingTicketItem.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/BindingTicketItem.java)):**
  - **Root Cause:**
    1. In `WorkerManagementScreen`, task buttons (Mine, Farm, Move) were squeezed into $W = 70$ (`taskBtnW = (70 - 4) / 3 = 22` px), severely truncating labels. In addition, `addFarmBtn.visible` was never updated in `render()`.
    2. In `BindingTicketItem.onRightClickBlock`, when selecting a worksite or storage, client-side execution cancelled the event with `event.setCanceled(true)`, causing `MultiPlayerGameMode.useItemOn` to abort locally without transmitting `ServerboundUseItemOnPacket` to the server. The server never received the worksite block-click, leaving the order session stuck in `SELECTING_WORKSITE`.
    3. In `WorkerManagementMenu.clickMenuButton`, clicking Mine or Farm checked only `mob.getMainHandItem()`. If a player equipped a pickaxe or farming tool into the worker's internal storage rather than the equipment hand slot, the request was rejected.
  - **Correction:**
    1. Expanded task button width to 56px each across $W = 174$ ($X = 128..302, Y = 50..66$), allowing clear, un-truncated rendering. Synchronized `addFarmBtn.visible` with tab state in `render()`.
    2. In `onRightClickBlock`, bypassed cancellation on the logical client (`if (world.isClientSide()) return;`), allowing Minecraft to transmit `ServerboundUseItemOnPacket` to the server where `selectWorksite` and `selectStorage` execute authoritatively.
    3. Added automatic storage-to-hand equipping in `clickMenuButton`: if the worker's main hand does not hold an authorized tool, the menu checks `WorkerStorage` and swaps an authorized tool into the main hand.
- **Bug D — Interaction Priority Over Maid Sitting Pose Fixed ([`BindingTicketItem.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/BindingTicketItem.java)):**
  - **Root Cause:** `RECENT_ENTITY_INTERACTIONS` used `InteractionKey(playerId, hand, entityId)` without distinguishing logical sides. In singleplayer / integrated server, the client thread recorded an interaction result into the static map. When the server thread received the interact packet within the same game tick, it matched the client's entry and aborted early without invoking `processEntityInteraction`. The server never bound the maid, opened the GUI, or started the order session. In addition, cancelling `EntityInteractSpecific` on the client prevented the interact packet from being sent. Consequently, Touhou Little Maid's native `mobInteract` executed, toggling the seated maid between sitting and standing.
  - **Correction:**
    1. Added `boolean isClient` to `InteractionKey`, completely partitioning client and server deduplication caches.
    2. On the logical client, allowed `EntityInteractSpecific` to pass through without cancellation, ensuring `MultiPlayerGameMode.interactAt` transmits `ServerboundInteractPacket` to the server. `EntityInteract` is cancelled on the client to suppress native client-side sitting toggle.
    3. On the logical server, `onEntityInteractSpecific` authoritatively executes `processEntityInteraction` (binding the maid, starting an order, or opening the GUI) and cancels the event with `InteractionResult.SUCCESS`, preventing TLM's server `mobInteract` from ever toggling the maid's sitting pose.
- **Verification & Test Status:**
  - Added 4 regression tests across [`WorkerManagementScreenTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/WorkerManagementScreenTest.java), [`WorkerManagementMenuTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/WorkerManagementMenuTest.java), and [`WorkerInteractionPriorityTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/WorkerInteractionPriorityTest.java):
    - `itemsTabLayoutHasZeroOverlapBetweenVitalsAndTabs`
    - `slotRepositioningKeepsAllSlotsInsidePanelAcrossTabs`
    - `interactionKeySeparatesClientAndServerLogicalSides`
    - `seatedMaidBindingWorkflowClaimsExclusivePriority`
  - Total test suite: **187 unit tests** pass cleanly with 0 failures, 0 errors, 0 skipped.
  - Clean build produces `build/libs/elstarticket-1.0.0.jar` (224,605 bytes).

## Final Logic Audit Before Live Gameplay: Miner Quota, Multi-Container Authorization, and Crop Selection (build and unit-tested; live gameplay unverified)
- **Miner `ITEM_COUNT` Quota, Output Conservation, and Completion Semantics ([`MinerJobData.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobData.java), [`WorkerJob.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerJob.java), [`MinerJobController.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java)):**
  - **Decoupled Objective Tracking:** Separated requested item quota (`requestedBlocks`), actual blocks broken (`blocksMined`), output generated/staged (`pendingStacks`), items pending delivery (`itemsPendingDelivery`), items delivered (`itemsDelivered`), and job completion (`isFullyCompleted()`).
  - **No Clamping of Blocks Mined:** In `ITEM_COUNT` mode, `blocksMined` is strictly decoupled from `requestedBlocks` and increments with each broken block without clamping or throwing exceptions when `blocksMined > requestedBlocks`.
  - **Accurate Objective Progress:** In `WorkerJob.withMinerData`, progress in `ITEM_COUNT` mode calculates based on gathered produce `(itemsDelivered + itemsPendingDelivery) * 100 / requestedBlocks` rather than blocks mined.
  - **Output Conservation & Zero/Surplus Drops:** Zero-drop blocks still advance `blocksMined` by 1. Blocks yielding surplus drops (e.g. Fortune III on the final block) preserve all extra items in `pendingStacks`; all surplus is deposited into storage and credited to `itemsDelivered` without truncation or voiding.
  - **Completion Guard:** An order cannot transition to `JobStatus.COMPLETED` while items remain in `pendingStacks` or `itemsPendingDelivery > 0`. Quota satisfaction transitions to `DELIVERING`; once storage insertion is verified and pending count reaches 0, the job transitions to `COMPLETED` at 100% progress.
- **Storage Destination Authorization & Safe Multi-Container Delivery ([`StorageDestinationAdapter.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/StorageDestinationAdapter.java)):**
  - **Double Chest Native Compound Resolution:** `StorageDestinationAdapter.resolveContainer` utilizes `ChestBlock.getContainer(...)` to resolve the native 54-slot `CompoundContainer` for double chests in one logical inventory step, preventing duplicate insertion or artificial split-inventory errors.
  - **Eliminated Arbitrary Neighbor Scanning:** Removed automatic 6-direction adjacent container scanning that risked depositing outputs into private chests, hoppers, or furnaces. Storage delivery now routes to designated primary storage, falling back to secondary storage only if explicitly authorized by the player.
  - **BlockEntity Liveness Validation:** Checks `!blockEntity.isRemoved()` before accessing containers.
  - **Safe Storage Pause:** If authorized storage destinations are full, the worker transitions to `WAITING_FOR_STORAGE` while safely preserving all uninserted ItemStacks in `pendingStacks` without item loss.
- **Farmer Worksite Anchor vs. Permitted Crop Selection ([`FarmerJobData.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/FarmerJobData.java), [`WorksiteAdapter.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorksiteAdapter.java), [`WorkerOrderSession.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerOrderSession.java), [`FarmerJobController.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/FarmerJobController.java)):**
  - **Safe Default-Deny Allowlist:** `FarmerJobData.isCropPermitted` strictly requires a non-empty `permittedCrops` allowlist containing the crop's block ID; an empty allowlist returns `false`. Legacy saves with missing allowlists safely default-deny.
  - **Anchor vs. Crop Independence:** `FarmWorksiteAdapter.isValidWorksite` permits directly targeted crops or farmland blocks with a supported crop immediately above them. Clicking bare farmland, dirt, stone, or air without a crop is rejected (`INVALID_WORKSITE`) unless the player's session has pre-configured permitted crops.
  - **Live Block State Revalidation:** `FarmerJobController.executeHarvestTransaction` and `MinerJobController.executeMiningTransaction` revalidate the live block state against the `ServerLevel` immediately prior to mutation, aborting with `REJECTED_PREFLIGHT` without progress or tool damage if the block was removed or replaced.
- **Verification & Test Status:**
  - Added 5 regression tests across [`MinerJobExactQuantityDeliveryTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/MinerJobExactQuantityDeliveryTest.java) and [`FarmerJobTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/FarmerJobTest.java):
    - `minerItemCountModeQuotaSatisfiedWithPendingStacksCannotCompleteUntilDelivered`
    - `minerItemCountModeProgressReflectsItemsGatheredNotBlocksMined`
    - `farmerEmptyAllowlistRejectsHarvestingAtExecutionGate`
    - `farmerCropDeliveryToContainerPreservesUninsertedStacksOnCapacityExhaustion`
    - `farmWorksiteAdapterRejectsBareFarmlandWithoutPreconfiguredAllowlist`
  - Total test suite: **183 unit tests** pass cleanly with 0 failures, 0 errors, 0 skipped.
  - Clean build produces `build/libs/elstarticket-1.0.0.jar` (220,777 bytes).

## Focused Post-Completion Audit: Quota, Boundaries, Crop Selection, and Storage (build and unit-tested; live gameplay unverified)
- **Strict Horizontal Tunnel Enforcing & Bound Invariance ([`MinerJobData.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobData.java), [`MinerJobController.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java), [`WorkerExecutionLoop.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerExecutionLoop.java)):**
  - **Horizontal Normalization:** Vertical directions (`UP`, `DOWN`) passed or deserialized are strictly normalized to horizontal `Direction.NORTH` in `MinerJobData` constructor, `withDirection`, and `findNextTunnelTarget`. Unintended vertical shafts cannot be created by looking up or down.
  - **Stable Persisted Bound:** Distance bounding ($\le 32$ horizontal radius, $\le 8$ vertical delta) is calculated from the persisted worksite `anchor`, never from the worker's moving position.
  - **Safe Stop & Status Feedback:** Obstruction (bedrock, fluid, barrier, blacklisted blocks) or boundary completion halts the worker immediately and sends an action-bar message to the owner. Transition to `PAUSED` passes `null` failure reason, resolving a defect where `WorkerJob.validate` previously rejected non-null failure strings on `PAUSED` jobs.
- **`ITEM_COUNT` vs `BLOCK_COUNT` Quota & Surplus Loot Conservation ([`MinerJobData.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobData.java)):**
  - **`blocksMined` Decoupling:** In `ITEM_COUNT` mode, `blocksMined` is no longer clamped to `requestedBlocks` (which represents the item target). `blocksMined` tracks actual blocks broken without throwing `IllegalArgumentException` when `blocksMined > requestedBlocks`.
  - **Surplus Loot Conservation:** Surplus items produced by the final block (e.g. Fortune III yields 4 diamonds on 1 remaining quota) are completely retained in `pendingStacks`, delivered to the chest, and credited to `itemsDelivered`. No items are discarded.
  - **No Uncollected Item Credit:** Quota progress checks `(itemsDelivered + itemsPendingDelivery) >= requestedBlocks`, and items become `itemsDelivered` only upon verified container insertion.
- **Farmer Crop Selection & Farmland Rejection ([`WorksiteAdapter.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorksiteAdapter.java), [`WorkerOrderSession.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerOrderSession.java)):**
  - **Farmland/Air Rejection:** `FarmWorksiteAdapter.isValidWorksite` now strictly requires `CropRegistry.isSupportedCrop(state.getBlock())`. Clicking farmland, air, dirt, or stone is rejected as `INVALID_WORKSITE`, preventing the accidental creation of an unrestricted allowlist.
  - **Dynamic Crop Discovery:** Newly planted or matured matching crops within the 16-block radius are discovered on tick scans. Non-permitted crops and immature crops are ignored.
- **Storage Multi-Block Deduplication & Identity Check ([`StorageDestinationAdapter.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/StorageDestinationAdapter.java)):**
  - **Visited BlockEntity Tracking:** `deliverWithNeighborFallback` tracks visited block entities (`Set<BlockEntity> visited`) and checks `!be.isRemoved()`, preventing double-counting or double-insertion when compound containers or double chests are encountered across adjacent scans.
- **Verification & Test Status:**
  - Added 5 regression tests in [`MinerJobExactQuantityDeliveryTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/MinerJobExactQuantityDeliveryTest.java) and [`FarmerJobTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/FarmerJobTest.java):
    - `verticalDirectionNormalizedToHorizontalInMinerJobData`
    - `itemCountModeAllowsBlocksMinedToExceedRequestedCountWithoutClampingOrError`
    - `surplusLootInItemCountModeRetainedAndDeliveredWithoutVoiding`
    - `farmWorksiteAdapterRejectsFarmlandAirAndNonCropBlocks`
    - `pausedJobTransitionAllowedWithNullFailureReason`
  - Total test suite: **178 unit tests** across 15 test suites pass cleanly with 0 failures, 0 errors, 0 skipped.
  - Clean build produces `build/libs/elstarticket-1.0.0.jar` (219,421 bytes).

## Final Ticket-Priority Consistency Audit (build and unit-tested; live client unverified)
- **`WRONG_WORKER` Controlled DENIED Correction ([`BindingTicketItem.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/BindingTicketItem.java)):**
  - **Identified Defect:** A bound ticket clicked on a different worker owned by the same player previously evaluated to `Decision.WRONG_WORKER` mapping to `InteractionClaim.PASS`. This allowed downstream vanilla and TLM maid interaction logic to continue, inadvertently triggering maid sneak sit/stand commands or native maid inventory while the player was explicitly using a Binding Ticket.
  - **Correction:** `toClaim(Decision.WRONG_WORKER)` now maps to `InteractionClaim.HANDLE_DENIED` (`result = InteractionResult.FAIL`, `shouldCancelEvent() = true`).
  - **Invariants Preserved:**
    1. Rebinding is strictly forbidden (`bindTicket` returns false; ticket UUID remains immutable).
    2. Wrong worker's Elstar GUI is never opened.
    3. Active job and order selection session remain untouched.
    4. Clear action-bar feedback (`message.elstarticket.wrong_worker`) is displayed.
    5. Empty-hand and unrelated-item native controls are 100% preserved.
- **Event Routing & Multi-Dimensional Deduplication Identity:**
  - **Composite Deduplication Key ([`BindingTicketItem.InteractionKey`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/BindingTicketItem.java)):** Replaced flat player-tick mapping with `InteractionKey(playerId, hand, entityId)`.
  - **No Cross-Talk Between Hands:** Main-hand interactions do not suppress or share cache with offhand interactions.
  - **No Cross-Talk Between Targets:** Interacting with entity A does not suppress interaction with entity B in the same tick.
  - **Independent Block Interactions:** `RightClickBlock` operates on a completely separate code path from entity deduplication. A prior entity click in the same tick can never suppress a subsequent block interaction.
  - **Order-Preserving Block Cancellation:** In `onRightClickBlock`, cancellation occurs strictly *after* `WorkerOrderSession.selectWorksite` or `selectStorage` has evaluated the block. Invalid targets return `INVALID_WORKSITE` / `INVALID_STORAGE`, display feedback, and cancel block GUI activation (`FAIL`) while strictly preserving the active selection session.
- **Verification & Test Status:**
  - Added 8 dedicated regression audit tests in [`WorkerInteractionPriorityTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/WorkerInteractionPriorityTest.java):
    - `audit_boundTicketOnDifferentWorkerCannotTriggerNativeSitStand`
    - `audit_boundTicketOnCorrectWorkerOpensOnlyIntendedElstarGui`
    - `audit_emptyHandAndUnrelatedItemPreserveNativeBehavior`
    - `audit_specificAndGenericEntityEventsDoNotDuplicateSideEffects`
    - `audit_entityInteractionFollowedByBlockInteractionInSameTickHandledIndependently`
    - `audit_mainHandOffhandCombinationsDoNotDuplicateSuppressOrMisroute`
    - `audit_invalidWorksiteAndStorageTargetsPreserveActiveSelectionSession`
    - `audit_unauthorizedWorkersRemainInaccessible`
  - Total test suite: **141 unit tests** across 15 test suites pass cleanly with 0 failures, 0 errors, 0 skipped.
  - Clean build produces `build/libs/elstarticket-1.0.0.jar` (169,170 bytes).

## Critical Interaction and Worker Order Regression Fix (build and unit-tested; live client unverified)
- **Bug 1: Exclusive Binding Identity & State Desynchronization ([`BindingTicketItem.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/BindingTicketItem.java)):**
  - **Root Cause:** `bindTicket` unconditionally stamped `TAG_WORKER_ID` without checking if `isTicketBound(stack)` was true, allowing one ticket to overwrite its target mob when clicked on a second mob. Additionally, server-side item stack modifications during interaction were not explicitly synchronized to player inventory container slots (`player.setItemInHand`, `containerMenu.broadcastChanges`).
  - **Fix:** `bindTicket(ItemStack, UUID, UUID)` returns boolean and strictly aborts if `isTicketBound(stack)` is already true (`ticket_already_bound`). Server-side binding explicitly calls `player.setItemInHand(hand, stack)` and `serverPlayer.containerMenu.broadcastChanges()`.
  - **Automated Tests:** Verified via `unboundTicketBindsMobAOnceAndCannotSilentlyBindMobB` and `otherPlayersTicketCannotControlWorker` in [`WorkerInteractionPriorityTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/WorkerInteractionPriorityTest.java).
- **Bug 2: Worker Hand Tool Evaluation & Add Task Action Flow ([`WorkerOrderSession.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerOrderSession.java), [`WorkerManagementMenu.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerManagementMenu.java), [`WorkerManagementScreen.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerManagementScreen.java)):**
  - **Root Cause:** In `WorkerOrderSession.startOrder`, line 124 inspected `player.getMainHandItem()` (which was the Binding Ticket itself!) instead of `worker.getMainHandItem()` (the worker mob's equipped tool). In `WorkerManagementMenu`, button 104 was a stub printing `add_task_info` without starting an order session.
  - **Fix:** `WorkerOrderSession.startOrder` now inspects `worker.getMainHandItem()`. `WorkerManagementMenu` implements button 104 (`[Mine]`) and button 105 (`[Move]`); button 104 verifies `WorkerJobCapability.Registry.canPerformMining(mob, heldTool)`, starts `WorkerJobs.MINE`, closes the screen (`player.closeContainer()`), and delivers user feedback to select worksite.
  - **Automated Tests:** Verified via `addTaskMineRequiresVerifiedMiningToolOnWorker`, `orderSelectionSequencePreservesStateAndRejectsPrematureActivation`, and `sessionCannotBeHijackedByAnotherPlayer` in [`WorkerOrderSystemTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/WorkerOrderSystemTest.java).
- **Bug 3: Client/Server Attachment Asymmetry & Suppressing Maid Native Sit ([`BindingTicketItem.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/BindingTicketItem.java)):**
  - **Root Cause:** NeoForge `WORKER_PROFILE` attachment is registered server-only. On the logical client, `mob.hasData(WORKER_PROFILE)` was false, causing client-side `evaluateInteraction` to return `UNAVAILABLE` -> `FAIL`/`PASS`. The client interaction event was neither consumed nor cancelled, allowing Touhou Little Maid's client interaction handler to execute maid sneak-click sit/stand toggling.
  - **Fix:** Added client-side early claim in `processEntityInteraction`: on the client, checks ticket `CustomData` components (`TAG_WORKER_ID`, `TAG_OWNER_ID`); if targeting the matching worker, immediately claims `InteractionResult.SUCCESS` and cancels the event (`event.setCanceled(true)`). Server validates authoritative profile.
  - **Automated Tests:** Verified via `validTicketInteractionSuppressesNativeMaidSitEarly` and `emptyHandAndUnrelatedItemPreserveNativeMaidBehavior` in [`WorkerInteractionPriorityTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/WorkerInteractionPriorityTest.java).
- **Bug 4: Entity Interaction Hijacking During Order Session & Fallback GUI Prevention ([`BindingTicketItem.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/BindingTicketItem.java)):**
  - **Root Cause:** `onEntityInteract` lacked a check for active `WorkerOrderSession`. Clicking an unrelated entity (e.g. sheep) evaluated `WRONG_WORKER` -> `PASS`. Minecraft's interaction pipeline fell back to `Item.use()`, which scanned for nearby workers and opened the multi-worker management GUI.
  - **Fix:** `onEntityInteract` intercepts entity clicks when `WorkerOrderSession.hasActiveSession(player.getUUID())` is true (in `SELECTING_WORKSITE` or `SELECTING_STORAGE`), claims `InteractionResult.SUCCESS`, cancels the event, displays feedback that a block must be targeted, and keeps the active session open. Also modified `Item.use()` so a bound ticket only opens the menu if its specific bound worker is nearby, never opening the multi-worker GUI.
  - **Diagnostic Logging:** Added configurable `logDiagnostic` (`Config.DEBUG_LOGGING`, default true) tracing event type, hand, bound worker, target entity, session state, and interaction decision.
- **Verification & Test Status:**
  - Added 8 dedicated regression tests across [`WorkerInteractionPriorityTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/WorkerInteractionPriorityTest.java) and [`WorkerOrderSystemTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/WorkerOrderSystemTest.java).
  - Total test suite: **127 unit tests** across 15 test suites pass cleanly with 0 failures, 0 errors, 0 skipped.
  - Clean build produces `build/libs/elstarticket-1.0.0.jar` (164,748 bytes).

## Final Miner Objective and Delivery Semantics Audit (build and unit-tested; gameplay unverified)
- **Gameplay Contract & Objective Accounting Invariants:**
  - **Objective tracks blocks broken, not output item type/count:** A mining order specifies target block type/predicate, exact target block count, and designated storage. It does not dictate output item type or quantity. (E.g. Mining 10,000 Stone blocks with a non-Silk Touch pickaxe credits Stone blocks broken and delivers Cobblestone drops without requiring Stone items).
  - **`blocksMined` mutation coupling:** `blocksMined` increments strictly by 1 upon committed destruction of the target block, completely independent of drop counts.
  - **Zero & multiple drops accounting:** Blocks yielding 0 drops still advance `blocksMined` by 1 (and complete immediately if no drops are pending). Blocks yielding multiple drops (e.g. Redstone Ore dropping 5 dust) increment `blocksMined` by 1 while queuing all 5 items into `pendingStacks`.
  - **Durable component preservation in pending storage:** Item counts, DataComponents (names, enchantments), and item identities are preserved in `pendingStacks` across NBT/JSON serialization.
  - **Storage pause on full/unavailable container:** If storage is full or missing, delivery pauses and the worker transitions to `WAITING_FOR_STORAGE`. While in this state, `canExecuteMiningStep` strictly returns `REJECTED_INVALID_STATE`, preventing the worker from mining any additional blocks.
  - **Partial insertion precision:** `deliverToStorage` only shrinks pending stacks by the exact amount accepted by the destination container; uninserted remainder stays in `pendingStacks`.
  - **Mining quota boundary:** Mining stops as soon as `blocksMined >= requestedBlocks`, transitioning to `DELIVERING` if items remain pending, and only to `COMPLETED` when `pendingStacks` is completely empty.
  - **Transient retry idempotency:** Transient failures before mutation unstage cleanly with 0 progress and 0 drops; subsequent retries succeed without duplicate blocks or items.
  - **Recovery air limitation:** Documented that a recovered `air` state after a crash cannot distinguish whether the worker destroyed the block before crashing or an external actor destroyed the block while offline, due to Minecraft Anvil chunk format lacking actor provenance/timestamps.
- **Verification & Test Status:**
  - Added 6 dedicated regression tests in [`MinerJobExactQuantityDeliveryTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/MinerJobExactQuantityDeliveryTest.java):
    - `stoneToCobblestoneObjectiveTracksBlocksNotOutputItemType`
    - `zeroDropsFromMinedBlockIncrementsBlocksMinedAndCompletesIfNoPending`
    - `multipleDropsFromSingleBlockIncrementsBlockCountByOneOnly`
    - `partialDeliveryRemovesOnlyAcceptedAmountAndRetainsRemainderAcrossRestart`
    - `fullStoragePausesDeliveryAndBlocksAnyFurtherMining`
    - `retryAfterTransientFailureDoesNotDuplicateProgressOrDrops`
  - Total test suite: **119 unit tests** across 15 test suites pass cleanly with 0 failures, 0 errors, 0 skipped.
  - Clean build produces `build/libs/elstarticket-1.0.0.jar` (160,910 bytes).

## Mutation Ownership and Loot Fidelity Audit (build and unit-tested; gameplay unverified)
- **Mutation Ownership Limitation Analysis ([`MinerJobController`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java)):**
  - **Runtime Ownership Invariant:** In live execution, if an external actor breaks the block to air before `blockDestroyer.destroy()` runs, `level.destroyBlock(targetPos, false)` returns `false` (cannot destroy air). `executeMiningTransaction` catches this and immediately cancels the transaction (`MUTATION_FAILED`), crediting 0 blocks and 0 drops.
  - **Crash-Recovery Storage Engine Limitation:** If the server process crashes ungracefully *between* intent staging and destruction, and another actor breaks the target block to air while offline or before the worker resumes, `evaluateReconciliation` inspecting `currentState.isAir()` commits the transaction. Minecraft's Anvil chunk format (`.mca`) records only block states and possesses zero actor attribution, timestamps, or mutation ownership records. This limitation is physically unavoidable in Minecraft without external non-vanilla world logs.
  - **Smallest Safe Policy Enforced:**
    1. During runtime, pre-destruction external removal aborts with `MUTATION_FAILED`.
    2. During recovery, foreign block replacements fail closed (`UNRESOLVED_AMBIGUOUS`).
    3. During recovery, unloaded chunks fail closed (`UNRESOLVED_CHUNK_UNLOADED`).
    4. Intact blocks cancel cleanly (`CANCELLED`), eliminating phantom drops.
    5. Terminal jobs fail closed (`UNRESOLVED_TERMINATED_JOB`).
- **Loot Fidelity & Prohibited Block Rejection:**
  - Added [`JobSafetyPolicy.isBlockBlacklisted`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/JobSafetyPolicy.java) checking hard blacklists (`BEDROCK`, `BARRIER`, `SPAWNER`, command blocks, portals).
  - Updated [`MinerJobController.canExecuteMiningStep`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java) to evaluate `state != null` and immediately reject `state.isAir()`, `state.hasBlockEntity()`, `isBlockBlacklisted(state.getBlock())`, or indestructible blocks with `REJECTED_SAFETY_POLICY`.
  - Updated [`MinerJobController.calculateExpectedDrops`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java) to strictly throw on unverified tools, block entities, blacklisted blocks, or indestructible blocks.
  - Loot context in `Block.getDrops` provides `LootContextParams.TOOL` (including Silk Touch, Fortune), `LootContextParams.THIS_ENTITY` (worker `Mob`), `LootContextParams.ORIGIN` (`targetPos`), and `LootContextParams.BLOCK_STATE`.
- **Verification & Test Status:**
  - Added 3 dedicated regression tests in [`MinerJobExactQuantityDeliveryTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/MinerJobExactQuantityDeliveryTest.java):
    - `runtimeExternalActorRemovalBeforeDestroyAbortsWithoutProgressOrDrops`
    - `canExecuteMiningStepRejectsAirAndBlacklistedAndBlockEntities`
    - `calculateExpectedDropsRejectsBlacklistedBlocks`
  - Total test suite: **113 unit tests** across 15 test suites pass with 0 failures, 0 errors, 0 skipped.
  - Clean build produces `build/libs/elstarticket-1.0.0.jar` (160,910 bytes).

## Final Recovery Edge-Case Audit & Expected Drops Correctness (build and unit-tested; gameplay unverified)
- **Reconciliation Edge-Case Decision Table ([`MinerJobController.evaluateReconciliation`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java)):**
  - **Identified Defect:** Reconciliation previously relied exclusively on `!inFlight.blockId().equals(currentBlockId)`. Unloaded chunks (returning `void_air`) or foreign block placements (e.g. `dirt` placed at `stone` location) were falsely treated as successful mining destructions, crediting drops and progress incorrectly.
  - **Targeted Fix:** Implemented `evaluateReconciliation(WorkerProfile, Level)` and pure `evaluateReconciliation(WorkerProfile, boolean chunkLoaded, BlockState currentState)`:
    1. **Edge Case 1 (Target replaced with original block):** `currentBlockId.equals(inFlight.blockId())`. Proven intact in chunk. Outcome is `CANCELLED`: in-flight intent is unstaged without crediting drops or advancing `blocksMined`. The intact block remains in the world ready to be mined legitimately.
    2. **Edge Case 2 (Target replaced by foreign block):** `!currentState.isAir() && !currentBlockId.equals(inFlight.blockId())`. Outcome is `UNRESOLVED_AMBIGUOUS`: fails closed! Does not commit drops, does not cancel in-flight mutation, and preserves the unresolved transaction on `WorkerProfile` for safe recovery or manual intervention.
    3. **Edge Case 3 (Target chunk unloaded or unavailable):** `!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)`. Outcome is `UNRESOLVED_CHUNK_UNLOADED`: fails closed! Preserves `InFlightMutation` without touching job progress until the chunk is actually loaded.
    4. **Edge Case 4 (Job cancelled, replaced, or already terminal):** If `job.status()` is `COMPLETED` or `FAILED`, outcome is `UNRESOLVED_TERMINATED_JOB`: fails closed and refuses to alter terminal jobs. If job was cancelled, returns `NO_OP`.
    5. **Edge Case 5 (Server restarts after staging but before destruction):** Target block is still original block in loaded chunk. Outcome is `CANCELLED`: unstages without crediting drops, leaving the intact block to be mined on the next tick.
- **Authoritative Expected Drops & Zero-World-Drop Guarantee:**
  - Added [`MinerJobController.calculateExpectedDrops`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java):
    - Uses vanilla `Block.getDrops(state, level, pos, null, worker, tool)` to evaluate server loot tables against the worker's identity and the tool's exact enchantments (Fortune, Silk Touch) and DataComponents.
    - Strictly rejects unverified tools (`JobSafetyPolicy.isMiningToolVerified(tool)`).
    - Strictly rejects blocks with block entities (`state.hasBlockEntity()`).
    - Skips indestructible blocks (`state.getDestroySpeed(level, pos) < 0`).
  - Added [`MinerJobController.executeLiveMiningStep`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java):
    - Executes `level.destroyBlock(targetPos, false, worker)` strictly with `dropInWorld = false`.
    - Guarantees drops are never both spawned as world entities and added to `pendingStacks`.
- **Verification & Test Status:**
  - Added 7 dedicated regression tests in [`MinerJobExactQuantityDeliveryTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/MinerJobExactQuantityDeliveryTest.java):
    - `reconciliationFailsClosedWhenChunkIsUnloadedOrUnavailable`
    - `reconciliationFailsClosedWhenTargetReplacedByForeignBlock`
    - `reconciliationCancelsWhenTargetStillContainsOriginalBlock`
    - `reconciliationCommitsWhenTargetIsAir`
    - `reconciliationFailsClosedOnTerminatedJob`
    - `calculateExpectedDropsRejectsUnverifiedTool`
    - `calculateExpectedDropsRejectsBlockEntities`
  - Total test suite: **110 unit tests** across 15 test suites pass with 0 failures, 0 errors, 0 skipped.
  - Clean build produces `build/libs/elstarticket-1.0.0.jar` (155,816 bytes).

## Post-Mutation Failure Consistency Audit & Idempotent Transaction Protocol (build and unit-tested; gameplay unverified)
- **Order of Operations Trace:**
  - Evaluated the complete execution sequence:
    1. Pre-execution reconciliation: `MinerJobController.reconcileInFlightMutation` inspects world state to commit or cancel any unfinalized mutation from a previous crashed tick/session.
    2. Safety preflight: `MinerJobController.canExecuteMiningStep` and `JobSafetyPolicy.validateBlockMutation` verify tool safety, remaining quota, distance, and permissions before touching the world.
    3. Pre-mutation drop generation: Drops are calculated server-side in memory via `dropSupplier` (simulating `Block.getDrops(state, level, targetPos, null, worker, tool)`).
    4. Durable intent staging: `MinerJobData.withInFlightMutation(targetPos, blockId, expectedDrops)` stages intent on `WorkerProfile` and commits to worker attachment *before* mutation occurs.
    5. World mutation (irreversible boundary): `level.destroyBlock(targetPos, false)` (or setting block to air without spawning world items) mutates the chunk.
    6. Post-mutation tool damage: `toolDamager.applyDamage()` applies durability cost. Exceptions thrown here are caught and non-fatal so world changes and drops are never discarded.
    7. Final progress commit (durable output boundary): `commitInFlightMutation()` clears in-flight intent, increments `blocksMined` (+1), and appends `expectedDrops` to `pendingStacks`.
- **The Two Boundaries:**
  - **Irreversible World Mutation Boundary:** The instant chunk block data is modified (`destroyBlock(targetPos, false)`). At this microsecond, the block is air on the server thread.
  - **Durable Output Boundary:** The instant `worker.setData(WORKER_PROFILE, committedProfile)` updates the worker entity's attachment with incremented `blocksMined` and collected `pendingStacks`.
- **Exception Boundary Coverage & Idempotent Recovery:**
  - **Boundary 1 (immediately after successful block destruction):** If an exception occurs immediately after the block is destroyed, the staged profile retains `InFlightMutation`. Upon server restart or next tick reconciliation, inspecting `level.getBlockState(pos)` finds the block is no longer `blockId` (it is air). The reconciliation commits `expectedDrops` into `pendingStacks` and credits `blocksMined` (+1). Zero lost drops, zero uncredited blocks.
  - **Boundary 2 (after drop generation but before mutation):** If an exception occurs or block destruction returns `false` (e.g. block was protected), reconciliation finds the block at `targetPos` is still `blockId`. The staged mutation is cancelled cleanly without crediting `blocksMined` or adding drops. Zero phantom progress, zero duplicate drops.
  - **Boundary 3 (during tool-damage processing):** If tool damage calculation fails post-mutation, the transaction catches the exception, logs it, and safely proceeds to commit the mined block and drops.
  - **Boundary 4 (during pending-stack/profile update):** If server crashes during final profile persistence, `InFlightMutation` was already written during pre-mutation staging. It survives NBT/JSON serialization via `InFlightMutation.CODEC`. On restart, the world state reconciles the destroyed block and commits drops safely.
  - **Boundary 5 (during delivery and container transition):** Partial container acceptance updates only accepted items; remaining items are strictly retained in `pendingStacks`, and status transitions to `WAITING_FOR_STORAGE`. Conservation of items (`itemsDelivered + itemsPendingDelivery`) is 100% exact.
  - **Boundary 6 (requested quantity cap):** Reconciling or committing an in-flight mutation is strictly capped at `requestedBlocks` (e.g. 5/5 remains 5/5).
- **Drop Custody Policy:**
  - Drops are gathered directly into `pendingStacks` with `dropInWorld = false`. Mined items never exist as vulnerable loose `ItemEntity` instances in the world chunk.
  - Full item identity, count, and `DataComponents` are preserved across restarts until deposited into designated storage slots.
- **Storage Engine Realities:**
  - Minecraft chunk files (`.mca`) and entity files are separate storage structures on disk. While operations on the server thread are serialized synchronously, ungraceful process kills during OS disk flushing can desynchronize region files. The `InFlightMutation` protocol provides the narrowest possible window of vulnerability and deterministic world-authoritative recovery upon world reload.
- **Verification & Test Status:**
  - Added 6 dedicated regression tests in [`MinerJobExactQuantityDeliveryTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/MinerJobExactQuantityDeliveryTest.java):
    - `boundary1_exceptionAfterSuccessfulBlockDestruction_reconcilesCommittedDropsAndProgress`
    - `boundary2_exceptionAfterDropGenerationBeforeMutation_cancelsWithoutPhantomProgress`
    - `boundary3_toolDamageExceptionDoesNotAbortProgressOrDropPersistence`
    - `boundary4_exceptionDuringProfilePersistenceSurvivesRestartAndReconciles`
    - `boundary5_deliveryFailureOrPartialContainerConservesItemsWithoutLossOrDuplication`
    - `recoveryCannotMineBeyondRequestedQuantity`
  - Clean build produces `build/libs/elstarticket-1.0.0.jar` (155,816 bytes).
  - All 103 unit tests across 15 test suites pass with 0 failures, 0 errors, 0 skipped.

## Pre-Gameplay Verification: Mutation Accounting, Durable Item Stacks & Durability (build and unit-tested; gameplay unverified)
- **Invariant 1: Actual World-Mutation Accounting:**
  - Evaluated the entire path: [`MinerResourcePreflight`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerResourcePreflight.java) -> [`WorkerOrderSession`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerOrderSession.java) -> [`MinerJobController.canExecuteMiningStep`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java) -> [`JobSafetyPolicy.validateBlockMutation`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/JobSafetyPolicy.java) -> [`WorkerExecutionLoop.executeBlockAction`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerExecutionLoop.java).
  - Every block changed must be known and bounded before mutation occurs. Tools with unknown/unbounded effects (`maxBlocksPerAction <= 0`) or collateral exceeding rate limits (`collateral > maxBlocksPerTick`) are rejected before execution.
  - Strict default-deny in [`MiningToolCapability.VanillaMiningToolCapability`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MiningToolCapability.java) permits only verified vanilla single-block tools (`SAFE_VANILLA_TOOLS`). Unverified modded tools (e.g. Avaritia AOE tools) are rejected before execution.
- **Invariant 2: Durable Storage of Actual Pending Item Stacks (Defect Corrected):**
  - Identified defect: [`MinerJobData`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobData.java) previously stored only an aggregate numeric counter (`long itemsPendingDelivery`), preventing item stack reconstruction on restart or partial delivery.
  - Minimal safe fix implemented: Added `List<ItemStack> pendingStacks` serialized via `ItemStack.OPTIONAL_CODEC.listOf()` inside [`MinerJobData`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobData.java).
  - Added [`MinerJobController.deliverToStorage`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java#L168-L238) and [`deliverToContainer`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java#L243-L257) ensuring partial chest acceptance preserves uninserted ItemStacks in `pendingStacks`, transitions to `WAITING_FOR_STORAGE`, and survives save/restart without loss or duplication.
  - Allowed `WAITING_FOR_STORAGE -> COMPLETED` transition in [`WorkerJob.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerJob.java#L72) when all pending items are deposited into newly-freed chest space.
- **Invariant 3: Tool Durability Before Mutation (Defect Corrected):**
  - Identified defect: Checking only `remaining >= MIN_TOOL_DURABILITY` allowed an operation costing 1 or 2 durability to drop remaining durability to 1 or break the tool (0 durability).
  - Minimal safe fix implemented: Added [`JobSafetyPolicy.hasSufficientDurability(ItemStack, int durabilityCost)`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/JobSafetyPolicy.java#L105-L121) enforcing `remaining > durabilityCost && (remaining - durabilityCost) >= MIN_TOOL_DURABILITY`.
  - Rejects damageable tools before mutation if the durability cost is unknown (`cost <= 0`) or if the next action could break the tool. Non-damageable tools have no fabricated durability costs.
- **Verification & Test Status:**
  - Added targeted regression tests in [`MinerJobExactQuantityDeliveryTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/MinerJobExactQuantityDeliveryTest.java):
    - `partialDeliveryPersistsCompleteItemStacksSurvivesRestartAndResumesWithoutLossOrDuplication`
    - `toolDurabilityCheckBeforeMutationPreventsToolBreaking`
  - Total test suite: 97 unit tests across 15 test suites pass cleanly with 0 failures, 0 errors.
  - Clean build produces `build/libs/elstarticket-1.0.0.jar` (145,310 bytes).

## Miner Architecture Consistency Audit & Reconciliation (build and unit-tested; gameplay unverified)
- **Removal of Contradictory Total-Job Quotas:**
  - Removed obsolete hardcoded quotas (`DEFAULT_MAX_BLOCK_BUDGET = 128` and `DEFAULT_MAX_WORKER_LIFETIME_BLOCK_QUOTA = 1024`) and methods `isWorkerQuotaAvailable` and single-argument `isBudgetAvailable` from [`JobSafetyPolicy`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/JobSafetyPolicy.java).
  - The player's requested mining quantity (e.g. 100,000,000 blocks) is authoritative and never arbitrarily capped.
  - All progress and pre-mutation methods now use 64-bit `long` arithmetic (`JobSafetyPolicy.isProgressWithinTarget(long, int, long)`, `JobSafetyPolicy.validateBlockMutation(...)`, `WorkerExecutionLoop.executeBlockAction(...)`, `MinerResourcePreflight.evaluate(...)`, and `MinerJobData`).
- **Pre-Mutation Validation (No Math.min After-the-Fact Fallback):**
  - Discrete actions determine affected blocks before action and compare with exact remaining requested quantity (`requestedBlocks - blocksMined`).
  - If a tool would affect more blocks than remain in the player's request (`plannedBlocksInTick > remaining`), the action is rejected before any world change occurs (`REJECTED_COLLATERAL_EXCEEDS_REMAINING` in [`MinerJobController`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobController.java) and `REJECTED_TARGET_QUANTITY_REACHED` in [`JobSafetyPolicy`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/JobSafetyPolicy.java)).
  - Configured per-tick world-change limits (`DEFAULT_MAX_BLOCKS_PER_TICK = 1`) are enforced independently of the total requested quantity.
- **Preserved Durability Semantics & Non-Damageable Tool Handling:**
  - Damageable tools check durability before each action, halt at `< MIN_TOOL_DURABILITY` (2), deliver already-collected products when possible, and transition to `WAITING_FOR_TOOL`.
  - Non-damageable tools (e.g. Avaritia items) do not fabricate durability costs or require spare tools, but must pass explicit capability verification and collateral bounding before mutation.
- **Exact Delivery and Persistence Invariants:**
  - `JobStatus.COMPLETED` requires `blocksMined >= requestedBlocks` AND `itemsPendingDelivery == 0`, with all products deposited into designated storage.
  - Full or unavailable storage stops mining and transitions to `WAITING_FOR_STORAGE`, retaining all collected items in worker inventory.
  - Save/reload via `WorkerJob.CODEC` preserves `long`-valued requested quantity, blocks mined, pending delivery, delivered items, and storage target.
- **Verification & Test Status:**
  - All 15 test suites and 95+ unit tests pass with 0 failures and 0 errors.

## Miner job: exact quantity, guaranteed delivery, and durability independence (build and unit-tested; gameplay unverified)
- **Exact Player-Controlled Requested Quantity:**
  - The Miner job (`WorkerJobs.MINE`) tracks `requestedBlocks` (`long`), `blocksMined` (`long`), `itemsPendingDelivery` (`long`), `itemsDelivered` (`long`), and designated `storage` (`WorkerTarget`) inside [`MinerJobData`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/MinerJobData.java).
  - Uses 64-bit signed arithmetic to support large jobs (e.g. 100,000,000 blocks) without numeric overflow.
  - Mining stops strictly when `blocksMined >= requestedBlocks`. Workers never credit more blocks than requested.
  - Collateral area tools exceeding remaining blocks (`collateral > requestedBlocks - blocksMined`) are rejected before execution (`REJECTED_COLLATERAL_EXCEEDS_REMAINING`).
- **Mandatory Designated Chest & Zero-Item Loss Guarantee:**
  - A valid chest/storage destination is mandatory and assigned during order creation.
  - Drops from mined blocks are gathered into worker inventory / `itemsPendingDelivery`. Items are never dropped or scattered on the ground.
  - If the designated chest is unavailable or becomes full during delivery:
    - Retains all undelivered items in `itemsPendingDelivery`.
    - Enters `JobStatus.WAITING_FOR_STORAGE`, pausing mining until storage space is freed or reauthorized.
  - Delivery credits exact amounts accepted by the container API (`withItemsDelivered`).
- **Strict Completion Conditions:**
  - A Miner job enters `COMPLETED` if and only if `blocksMined >= requestedBlocks` AND `itemsPendingDelivery == 0`.
  - Reaching the block quota while items remain in transit transitions the job to `JobStatus.DELIVERING`, never `COMPLETED`.
  - `WorkerJob.CODEC` strictly rejects deserializing a `COMPLETED` Miner job if `itemsPendingDelivery > 0`.
- **Independent Durability Stop Condition:**
  - Damageable tools (e.g. wooden pickaxe) check remaining durability before every action; reaching `< MIN_TOOL_DURABILITY` (2) stops mining, delivers pending items if possible, and enters `JobStatus.WAITING_FOR_TOOL`.
  - Tools with no durability consumption (including verified Avaritia items) do not wait for durability loss, do not fabricate wear, and continue until `requestedBlocks` is reached.
- **Resumability Across Save & Reload:**
  - Encoded and decoded via `WorkerJob.CODEC` and `MinerJobData.CODEC`. All progress, quotas, pending items, delivered items, and storage targets survive restarts without duplicate mining or lost items.
- **Verification & Test Status:**
  - Dedicated regression test suite [`MinerJobExactQuantityDeliveryTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/MinerJobExactQuantityDeliveryTest.java) covers all 10 required regression scenarios with 100% pass rate.
  - All 95+ unit tests across 15 test suites pass cleanly with 0 failures, 0 errors, 0 skips.
  - Clean build produces `build/libs/elstarticket-1.0.0.jar` (141,446 bytes).

## Catastrophic tool behavior & world-destruction prevention (build and unit-tested; gameplay unverified)
- **Avaritia Equipment & Infinite-Durability Handling (`MinerResourcePreflight`):**
  - **No Durability-Based Stopping Condition:** Tools like Avaritia's Infinity Pickaxe have no durability consumption and never break. Never assume tools lose durability. Durability is NOT a universal measure of mining cost or the primary stopping condition.
  - **Wear & Replacement Independence:** For tools with no durability, replacement tools are never required, and stopping points are never estimated from tool wear. The requested mining quantity is calculated independently of durability.
  - **Player-Controlled Total Quantity:** The requested total quantity remains player-controlled (e.g. 500, 10,000 blocks). Arbitrary global total-block caps are not used as substitutes for per-tick rate limits.
  - **Per-Tick Rate Limit Enforcement:** Every additional block affected by a tool's special mining behavior (including collateral effects) counts against the configured per-tick limit (`DEFAULT_MAX_BLOCKS_PER_TICK = 1`).
  - **Safety Preflight Verification:** Infinite durability does NOT imply safe autonomous execution. If an adapter cannot measure or constrain collateral effects within the player's requested work area and per-tick execution policy (`maxBlocksPerAction <= 0`), the tool is rejected for autonomous mining.
- **Default-Deny Security Model for Unverified Tools:**
  - Unknown tools are strictly rejected for autonomous block-breaking jobs. Presence in an equipment slot or matching vanilla tags/harvest levels/display names does NOT grant authorization.
  - Built-in `MiningToolCapability.VanillaMiningToolCapability` enumerates only verified vanilla tools (`WOODEN_PICKAXE`..`NETHERITE_PICKAXE`, shovels, axes, hoes, shears).
  - Powerful mod tools (e.g. Avaritia World Breaker, Draconic Evolution tools, AOE hammers, explosive diggers) return `isVerifiedSafe == false` by default and are completely blocked from autonomous world mutation.
  - No mod-specific blacklists or dependencies on Avaritia are added to core; protection is achieved generically via default-deny.
- **Explicit Capability Contract (`MiningToolCapability`):**
  - Requires capability providers to explicitly specify `isVerifiedSafe(ItemStack)` and `maxBlocksPerAction(ItemStack)`.
  - Tools with unbounded, unknown, or non-positive action effects (`maxBlocksPerAction <= 0`) are rejected immediately.
- **Multiple Independent Hard Limits:**
  1. **Per-Tick World Change Limit:** `DEFAULT_MAX_BLOCKS_PER_TICK = 1` blocks per discrete tick/action, strictly enforced against all affected blocks.
  2. **Player-Controlled Target Progress:** `isProgressWithinTarget` ensures progress advances across ticks up to the player's requested quantity.
  3. **Work Region Bounds:** Maximum horizontal radius of 32m (`DEFAULT_MAX_WORK_RADIUS = 32`) and vertical delta of +/- 8m (`DEFAULT_MAX_VERTICAL_DELTA = 8`).
  4. **Bounded Scanning:** Maximum scan radius of 16m (`DEFAULT_MAX_SCAN_RADIUS = 16`).
  5. **Tool Preservation (Damageable Tools Only):** Minimum durability threshold of 2 (`MIN_TOOL_DURABILITY = 2`) prevents breaking damageable tools; non-damageable tools bypass this check safely.
  6. **Hard Blacklist & Tile Entity Protection:** Bedrock, Barrier, Command Blocks, Portals, Spawners, and any block possessing a block entity (`state.hasBlockEntity()`) are strictly protected from block-breaking mutations.
- **Tool Replacement & Consistency Guard:**
  - `JobSafetyPolicy.isToolConsistent` verifies that the currently held tool matches the tool validated at job start. Swapping to an unverified tool or a damaged tool (< 2 durability) immediately stops execution with `REJECTED_TOOL_REPLACED`.
- **Pre-Mutation Validation (No Unsafe Fallbacks):**
  - `JobSafetyPolicy.validateBlockMutation` evaluates all independent guardrails *before* any block is touched. Never relies on post-action checks and never falls back to unchecked player-use item simulations.
- **Verification & Test Status:**
  - Unit tests in `MinerResourcePreflightTest` and `JobSafetyPolicyTest` verify preflight with infinite-durability tools, player-controlled requested quantities, per-tick collateral rate limiting, default-deny rejection of unverified tools, and immunity of central safety policies to external adapter bypass.
  - All unit tests pass cleanly with 0 failures, 0 errors.
  - **No false safety claims:** Autonomous mining with Avaritia or other uncontrolled AOE tools is intentionally disabled and unverified in live gameplay.

## Worker order system & job safety contract (build and unit-tested; gameplay unverified)
- **Architecture & Multi-Stage State Machine:**
  - `WorkerOrderSession`: Server-authoritative in-memory state machine for multi-stage job configuration (`SELECTING_WORKSITE` -> `SELECTING_STORAGE` -> `VALIDATING` -> `COMPLETED` / `CANCELLED`).
  - Auto-inactivity timeout of 60 seconds (`SESSION_TIMEOUT_MS = 60_000L`) prevents stale memory retention or leaked sessions.
  - Clean entry point: Sneak-right-clicking an owned bound worker with its matching ticket initiates order configuration mode without opening the management GUI or conflicting with normal interaction.
  - Worksites and storage destinations are selected via subsequent in-world block clicks using the Binding Ticket (`useOn`).
  - Jobs requiring only a worksite (e.g. `WorkerJobs.MOVE`) immediately validate and activate the job, whereas multi-stage production jobs transition to storage selection.
  - Direct sneak-block Move job assignment remains fully functional when no order configuration session is active.
- **Job Safety Contract (`JobSafetyPolicy`):**
  - Central, non-bypassable safety guardrail for all worker operations and order assignments.
  - Horizontal work radius bounded to 32 blocks (`DEFAULT_MAX_WORK_RADIUS = 32`); vertical delta bounded to +/- 8 blocks (`DEFAULT_MAX_VERTICAL_DELTA = 8`).
  - Scan radius bounded to 16 blocks (`DEFAULT_MAX_SCAN_RADIUS = 16`), preventing world-scale lag from unbounded scanning.
  - Player-controlled target quantity is authoritative: no arbitrary 128 or 1024 total quotas. Runaway world destruction is prevented via per-tick rate limits (`DEFAULT_MAX_BLOCKS_PER_TICK = 1`), worksite bounds, and pre-mutation collateral checks.
  - Delivery storage destination distance bounded to 16 blocks from worksite (`DEFAULT_MAX_STORAGE_DISTANCE = 16`).
  - Tool preservation: `isToolSafeToUse` strictly enforces minimum remaining durability of 2 (`MIN_TOOL_DURABILITY = 2`), preventing tool breakage on damageable items.
  - Hard block blacklist: `BEDROCK`, `BARRIER`, `COMMAND_BLOCK`s, `STRUCTURE_BLOCK`, `JIGSAW`, `SPAWNER`, `END_PORTAL`, `END_PORTAL_FRAME`, `END_GATEWAY`, and `NETHER_PORTAL`.
  - Tile entity / Block entity protection: `state.hasBlockEntity()` rejected from block-breaking mutations by default, protecting player chests, machines, and modded functional blocks.
  - Security & permission enforcement: verifies server thread, player world bounds, chunk presence, and player `mayBuild()` before permitting work.
- **Extensible Adapter Contracts:**
  - `WorksiteAdapter`: Validates worksite validity per job type without hardcoding mod blocks into core. Includes `MoveWorksiteAdapter` for `WorkerJobs.MOVE`.
  - `StorageDestinationAdapter`: Validates storage containers (`VanillaContainerStorageAdapter` for vanilla chests, barrels, etc.).
  - `WorkerJobCapability`: Validates worker entity eligibility and tool requirements prior to order initiation.
- **Verification & Test Status:**
  - Unit tests: Added `JobSafetyPolicyTest` (testing bounds, tool durability thresholds, storage distances, block budgets) and `WorkerOrderSystemTest` (testing session lifecycle, timeouts, worksite/storage registry dispatch, capability evaluation, and top-face move targeting).
  - All unit tests pass cleanly with 0 failures, 0 errors.
  - **Gameplay unverified:** In-game assignment flow and chat feedback require in-game manual verification in a live Minecraft client.

## Authoritative item-first interaction priority (build, unit, and event-tested; gameplay unverified)
- **Architecture & Design:**
  - The Elstar Binding Ticket (`elstarticket:binding_ticket`) serves as the unique, authoritative signal that the player intends to perform an Elstar worker action.
  - Generic mod-compatibility: rather than hardcoding per-mod hooks for Touhou Little Maid (TLM), Ice and Fire (IAF), or vanilla mobs, Elstar intercepts player interactions at the canonical NeoForge hook (`PlayerInteractEvent.EntityInteract`) registered on `NeoForge.EVENT_BUS` at `EventPriority.HIGHEST`. Relying exclusively on `EntityInteract` (rather than dual-subscribing with `EntityInteractSpecific`) avoids double-dispatch from sub-hitbox `interactAt` fallthrough when results are `FAIL`.
  - Calling `event.setCanceled(true)` with `event.setCancellationResult(InteractionResult.SUCCESS)` (or `InteractionResult.sidedSuccess(...)`) halts all downstream NeoForge event bus listeners and bypasses vanilla `mobInteract`, preventing external mod menus (TLM maid inventory, IAF dragon riding/GUI) or vanilla actions (shearing, milking, riding) from opening or conflicting with the Elstar management GUI.
- **Authoritative Decision Engine Rules:**
  1. **Bound ticket + matching worker + authorized owner:** Elstar takes full priority. Opens exactly one `WorkerManagementMenu` on the server, cancels downstream event propagation with `SUCCESS`. Does not require sneaking. Avoids damage, duplication, or duplicate menu opening.
  2. **Bound ticket + wrong worker:** Does not open Elstar GUI and does not manage another worker. Returns `PASS` and leaves the event uncancelled (`isCanceled == false`), preserving the normal external mod or vanilla interaction for that target entity.
  3. **Ticket bound to another player's worker:** Unauthorized management is denied. Does not disclose private worker data. Cancels event with `FAIL` to prevent exploitation.
  4. **Unbound ticket:** Preserves the binding workflow. Evaluates untamed mobs or player-owned tamed mobs, binds the worker, records the binding on the ticket stack via `DataComponents.CUSTOM_DATA`, marks mob persistence, and claims exclusive interaction with `SUCCESS`. Rejects foreign tamed mobs (owned by another player) or already-bound workers with `FAIL`.
  5. **No Elstar Ticket in relevant hand:** Event listener returns immediately without modifying event state, leaving all third-party mod and vanilla entity interactions completely unhindered.
- **Hand Interaction & Deduplication Policy:**
  - Evaluates `InteractionHand.MAIN_HAND` first.
  - If the main hand holds a ticket and consumes the action (SUCCESS or FAIL), Minecraft halts interaction, preventing the offhand from being processed or triggering a duplicate action.
  - If the main hand holds an empty hand or a non-ticket item, the main hand event passes through unhindered; if it does not consume the action, the offhand is evaluated cleanly without false suppression.
  - Dual-wielding tickets evaluates the main hand first and suppresses duplicate offhand opening.
- **Verification & Test Status:**
  - Automated tests: `gradlew.bat clean build` and `gradlew.bat test --rerun-tasks` executed 64 JUnit tests across 11 test suites with 0 failures and 0 errors.
  - Includes 16 dedicated unit tests in `WorkerInteractionPriorityTest` covering all decision rules, matching/wrong worker branches, untamed/tamed/foreign mob handling, unauthorized player gating, hand priority deduplication, server profile authoritative validation over ticket custom data, and event cancellation expectations.
  - **Gameplay unverified:** In-game visual confirmation of TLM and IAF mob interaction suppression in live Minecraft client/server remains to be performed manually.

## Worker inventory menu & UI redesign (build, codec, and layout tested; gameplay unverified)
- **GUI Redesign:**
  - **Left panel (compact worker information):** Reduced mob preview area (46×48), displaying the 3D entity model and worker name. Vitals display real health using Minecraft hearts (up to 10 hearts / 20 HP with numerical current/max display) and real armor value with vanilla shield/armor icon beside health. Four armor slots (Helmet, Chestplate, Leggings, Boots) arranged in a 2×2 grid (x={10, 30}, y={72, 92}) underneath the preview; two hand slots (Main-hand at x=66, y=82 and Offhand at x=88, y=82) positioned underneath vitals. Empty slots render vanilla item-atlas textures (`item/empty_armor_slot_helmet`, `chestplate`, `leggings`, `boots`, `item/empty_slot_sword`, `item/empty_armor_slot_shield`) with hover tooltips.
  - **Right panel (task list and behaviors):** Dedicated task list panel (182×54) displaying active job type, target/zone summary, and real status badge (`[Running]`, `[Paused]`, `[Assigned]`, `[Idle]`). Row includes an inline `[Cancel]` button (ID 100) and an `[+ Add Task]` button (ID 104) providing assignment instructions without inventing unsupported tasks.
  - **Behavior controls:** Three persistent behavior controls (`[Follow]`, `[Stand]`, `[Set Home]`) below the task list (y=74..108), with active state indicators (e.g. `● Follow`, `● Stand`) and home area summary text (`Home: 3x3 chunks` / `Home: Not set`). Unrelated fake buttons (`Wander`, `Select Target`, `Lock`) have been removed.
  - **Lower panel (storage and player inventory):** Exactly 15 worker storage slots arranged 5×3 (x=14, y=126..180) and player inventory + hotbar arranged 9×4 (x=126, y=126..202). Section labels (`Worker Storage` at x=14, y=114; `Player Inventory` at x=126, y=114) are placed cleanly above slots with zero overlap.
  - **Adaptive viewports and tabs:** 312×212 unified layout supported on normal viewports; compact tabs (`[All]`, `[Worker]`, `[Items]`) adapt seamlessly to small screens down to 320×180 without clipping interactive controls or slots.
- **Menu Validity Fix (Distant Worker Access):**
  - Identified root cause of menu auto-closing when workers move: `stillValid` was delegating to `authorized(player, mob)`, which enforced an 8-block tether (`player.distanceToSqr(mob) > 64`).
  - Separated initial menu opening check (`authorized`) from open-session validity (`sessionValid`). `sessionValid` verifies the worker is alive, not removed, in the same dimension/level, and matches the owner UUID and entity UUID. Distance does not close an active menu session, allowing owners to manage workers while they move.
  - Regression unit tests verify `sessionValid` permits distant workers (e.g. 20+ blocks away) while strictly rejecting unauthorized players, mismatched dimensions, dead mobs, or removed entities.

## Worker behaviors and priority policy (build, codec, and unit-tested; gameplay unverified)
- **Supported Modes:**
  1. **Follow:** Default behavior immediately upon binding. Server-side throttled navigation follows owner when distance is between 3m and 32m (throttled to every 20 ticks). Suppressed when an active job or Stand takes precedence.
  2. **Set Home:** Player arms Set Home mode via menu button (ID 103) or ticket; right-clicking a block with the Binding Ticket validates coordinates, world bounds, and build permissions, persisting a 3×3 chunk zone centered on the selected block's chunk (`Math.abs(chunkX - homeChunkX) <= 1 && Math.abs(chunkZ - homeChunkZ) <= 1`). No chunk force-loading is performed.
  3. **Stand:** Halts autonomous navigation and movement. Preserves jobs; active `MOVE` jobs transition to `JobStatus.PAUSED` rather than being deleted, and resume to `JobStatus.RUNNING` when toggled back to Follow.
- **Enforced Priority Order:**
  1. Owner & entity validity and build permissions.
  2. Stand mode stops autonomous movement and pauses active `MOVE` jobs.
  3. Authorized active jobs override Follow movement.
  4. Follow operates only when no higher-priority behavior or job is active.
  5. Home defines an allowed roaming boundary, not a job itself.
- **Verification & Test Status:**
  - Automated tests: `gradlew.bat clean build` passed with 48 unit tests (0 failures, 0 ignored).
  - Codec tests verify backward-compatible serialization with fallback defaults (`WorkerBehavior.FOLLOW`, empty home).
  - Pure helper tests verify throttled navigation, Stand pauses, 3×3 chunk geometry, and menu slot geometry.
  - **Gameplay unverified:** In-game mob pathfinding, live Set Home ticket interaction in multiplayer, visual appearance of UI widgets, and shift-click container interactions require in-game manual verification.

## Persistent navigation target foundation (unit-tested; gameplay unverified)
- In-game verification path (not yet performed): bind a pathfinding mob, then with an empty job slot sneak-use the ticket on the top face of a solid block with air above, within 32 blocks of the worker. The server tick loop requests paths at most every 40 ticks. Open that mob's menu to cancel the job; observe whether its path stops. Save/quit and reopen to check the target and job survive. Mob AI can interrupt paths. No movement or restart persistence has been verified.
- The ticket block-use requires empty space above a loaded block, world bounds, build permission, matching identity/dimension, and a worker within 32 blocks of target. It does not prove claim permissions. Wait assignment by sneak-use on a mob is no longer exposed; existing wait jobs can be cancelled from the menu.
- `WorkerTarget` stores dimension and block coordinates in the existing entity-attached job codec; older `wait` jobs still omit the optional target. The new `move` job requires a target when decoded; missing/malformed target data is rejected rather than becoming a runnable move job. `WorkerProfile.assignMove` accepts only the owner of a live server-side `PathfinderMob` in the same dimension without an existing job. Ordinary assignment cannot create a targetless `move` job.
- For a ticking bound pathfinding mob with a persisted move job, the server loop checks live identity, online owner in the same level, build permission, matching dimensions, world bounds, a loaded target chunk and a 32-block distance limit. It requests a path at most once every 40 ticks when navigation is idle; failed path requests leave job state unchanged. Paths started by this loop are stopped when the job is cancelled, the target changes, the owner is unavailable, or the target chunk unloads. It does not load chunks, modify blocks, advance progress, or complete jobs. A path may still be replaced by a mob's normal AI; this is not reliable worker locomotion until verified with real mobs.
- Sneak-use on a bound worker now opens its menu rather than toggling wait; use the menu to cancel an existing job. Sneak-use on a block assigns `move` only when exactly one owned pathfinding worker is nearby and idle. No real navigation or save/restart verification has occurred.

## Worker tick foundation (unit-tested; no executable job)
- A NeoForge post-entity-tick listener inspects attached profiles only for live server-side mobs on the server thread. Each ticking mob receives at most one synchronous eligibility check per tick; it checks worker UUID, owner UUID, dimension, owner online in the same level, build permission, and active assignment. Missing/removed workers do not enter the loop; unloaded mobs do not tick. The existing `wait` job has no target or action, so the loop skips it without changing job status or progress. Cancellation removes the job before the next check. No chunk loading or saved scheduler state is added.
- Three original unit tests check one-step bounds, cancelled/invalid/unauthorized gating, unavailable owner and error isolation; they do not simulate real ticks or permissions. Navigation target support is limited to `move`; there is still no persistent crop target, target-specific claim authorization, drop/seed accounting, action executor, or completion path. Future work must provide a trusted target/action implementation, recheck permissions and loaded state immediately before mutation, and verify results before transitioning jobs. No normal gameplay verification of this listener has occurred.

## Vanilla crop job feasibility (read-only prerequisite; harvesting blocked)
- The ticket no longer assigns `elstarticket:wait` through mob sneak-use; previously assigned wait jobs can be cancelled in the menu. `WorkerJob` can persist a target for `move`, but **no crop target or area** is assignable. The interaction executor has no production caller; navigation is not a crop action and does not advance jobs. No target protection policy, seed accounting, or replant transaction is implemented. A generic pathfinding mob is not a player. Claiming a harvest or completing a job under these conditions would risk unauthorized edits, lost/duplicated drops, and false completion.
- Added `CropHarvestTarget`, a read-only check for a mature `CropBlock` within 8 blocks of a live, owned worker on the server in a loaded chunk. Its live overload checks owner build permission, but this is **not** target-specific protection/claim authorization and must never be used alone to authorize mutation. Two unit tests exercise mature/immature crops, non-crops, unloaded/denied/distant/null targets; no world mutation, crop job registration, job progress, or gameplay invocation exists. The existing ticket workflow is unchanged; **there is no crop job to assign or test in-game yet**.
- Next prerequisite: design a persistent bounded target and trusted target-specific protection permission policy, choose supported worker navigation and drop/seed custody, and test server-side save/restart and harvesting/replanting with actual block operations before exposing a crop assignment. Cancellation must stop scheduling before mutations; progression requires verified harvest and correct drop handling. No chunk forcing.

## Mod identity migration (build-verified, gameplay unverified)
- `mod_id` drives the Gradle artifact name, generated NeoForge metadata/dependency tables, test mod binding and run configurations. Java's `ExampleMod.MODID` uses `elstarticket`; ticket messages, creative tab localization, and the ticket model also have assets in the new namespace. Old namespace asset files are retained but do not provide assets for the new registry IDs.
- **Breaking save-data change:** existing `examplemod:*` items, jobs, and worker attachments are not remapped to `elstarticket:*`. Back up existing worlds; use a new disposable world for verification until a deliberate migration is implemented. Do not install both the old and new JAR simultaneously. No normal game session has validated loading or save migration.

## First in-game verification (prepared; not yet performed)
- Unit-tested: `gradlew.bat test` passes with 48 tests in seven test classes. These do not prove live ticket use, navigation, or attachment persistence.
- Build-verified: `gradlew.bat build` produces `build/libs/elstarticket-1.0.0.jar`. Packaged metadata identifies `elstarticket`, display name Elstar Ticket, version 1.0.0, required Minecraft `[1.21.1]` and NeoForge `[21.1.264,)`. The JAR contains the entry point and `assets/elstarticket` ticket model and language file; no optional mod dependency is declared. This checks packaging and declared compatibility, not successful loading in PolyMC.
- Ready to attempt in a disposable normal world: in PolyMC, confirm the instance runs Minecraft 1.21.1 with NeoForge **at least 21.1.264** and Java 21 (the presence of Create 6.0.1.10 and Waystones 21.1.46 does not establish its NeoForge version). Back up the world; copy only `build/libs/elstarticket-1.0.0.jar` to that instance's `mods` directory, removing any older examplemod JAR. Launch and confirm the mods screen lists **Elstar Ticket** (`elstarticket`); if loading fails, check the instance log and installed loader/mod versions before proceeding. No installation or launch has been performed here.
- In a creative test world, obtain `/give @s elstarticket:binding_ticket` or find the ticket in the Elstar Ticket creative tab. With the ticket in hand, use it on a live, loaded pathfinding mob (for example a cow). Check the bound feedback; use again to check duplicate-binding rejection. Sneak-use on that same mob to assign `elstarticket:wait`, then sneak-use again to cancel; a second player should be denied management. The ticket is not consumed and the wait job does not execute. For save/restart, bind a uniquely identifiable mob and optionally assign the job, quit and restart the world, then check duplicate rejection and the assignment/cancellation toggle on that *same* mob. A duplicate rejection alone is insufficient to prove the saved job state; the first sneak-use after restart should cancel an existing job (or assign when none was saved). Record screenshots/logs and the exact loader version and outcome. Avoid treating a newly spawned replacement mob as the persisted worker.
- Actually verified in normal gameplay: **none**. There is no diagnostic hook needed for this manual ticket path. Interaction preflight/execution contracts have no production caller; they cannot be exercised with the ticket. Live attachment save/reload, owner checks, job toggle, compatibility with the installed optional mods, and loading the packaged JAR remain unverified. If the PolyMC loader is older than 21.1.264, first use an appropriate NeoForge 1.21.1 instance; do not weaken the declared version requirement without evidence.

## Completed
- First core milestone (superseded by the profile milestone below): a creative-tab Binding Ticket initially stored an owner-only attachment; existing legacy bindings are still recognized.
- Core profile milestone: new bindings store entity UUID, owner UUID, dimension, and idle status in a serialized NeoForge entity attachment. Duplicate bindings (including legacy owner-only bindings) are rejected, and bound mobs are marked persistent.
- Job foundation: profiles optionally serialize a job identifier, status, progress, and failure reason. Owner-only sneak-use of the ticket assigns or cancels a non-executing `elstarticket:wait` demonstration job on pathfinding mobs. Assignments reject mismatched entity identities, dimensions, unknown jobs, and incompatible mobs; lifecycle transitions are bounded and explicit. The tick listener inspects eligibility only; no world changes are introduced.
- Eight JUnit tests using ModDevGradle's unit-test environment cover codec round trips with and without a job, malformed required/job data, persisted job lifecycle/progress/failure, owner-only cancellation, duplicate assignment rejection, and pure binding/management decisions. These do not exercise Minecraft entities, attachment saves, or item-use events.
- Interaction preflight foundation: `WorkerInteraction` describes action, worker/owner identity, dimension, typed block/entity/endpoint target and parameters; `WorkerInteractionRegistry` routes to instance-registered handlers. Dispatch checks trusted server context, profile identity and target availability before selecting a handler, rejects ambiguous or unsupported requests, and does not execute world changes or update jobs. Unit tests cover routing, rejection, ambiguity, unavailable targets, job integrity, and registry isolation.

## Interaction API audit (preflight only)
- Representation is sufficient for describing the proposed categories without a core API change: `Block(BlockPos)` identifies an ordinary block, inventory/menu block, or machine at a position; the action ID and parameters distinguish the requested operation. `Entity(UUID)` identifies an entity. `Endpoint(ResourceLocation)` names a logical target such as an inventory endpoint, multiblock service, or teleportation destination/action; action ID and parameters can carry adapter-specific destination or structure identifiers. These are descriptions, **not** validated live references, inventory/menu handles, or a multiblock/teleportation implementation. An adapter must define and validate the meaning of its parameters (including instance identity and destination dimension where needed); no generic target codec or cross-dimension target semantics exist yet.
- The registry is instance-scoped, accepts independently registered handlers, and rejects zero matches and multiple matches rather than choosing by registration order. It checks requester UUID against profile owner and entity UUID/dimension against the profile, but `server` and `available` are caller-supplied booleans: they do not prove logical-server access, chunk availability, live attachment identity, permissions on target blocks, or a worker's presence/ticking. No production caller currently dispatches requests. Tests exercise only this pure preflight path.
- `Result(SUCCESS, ...)` means validation accepted the request, not execution or job completion. Dispatch neither modifies `WorkerProfile` nor performs world changes; there is no job-to-request link, assignment identifier/idempotency key, or execution scheduler. Before connecting to jobs, a trusted server caller must resolve and authorize worker and target, reject unavailable targets without loading chunks, revalidate at execution time, prevent duplicate operations, and explicitly transition the correct job only after confirmed execution. Handler exceptions and mutable registration during dispatch are not isolated; handlers must currently be trusted and registered before dispatch.

## Create adapter discovery (no adapter installed)
- Candidate first interaction: inspect a Create gearbox's axis/rotation state without mutation, using a loaded block position. The upstream `GearboxBlock` source on `mc1.21.1/dev` exposes `getRotationAxis(BlockState)` and `hasShaftTowards(LevelReader, BlockPos, BlockState, Direction)` ([source](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/kinetics/gearbox/GearboxBlock.java)). This is a **candidate**, not a verified adapter: that moving branch currently identifies itself as 6.0.11, not the reported installed 6.0.1.10. No exact-version binary/source or compatible compile classpath has been verified in this project.
- At discovery time, execution was additionally blocked by the preflight-only registry. The generic execution contract below now addresses the result boundary but does not supply a production caller, Create API verification, target-specific authorization, or replay protection. Do not register a Create handler until these gaps are resolved. No Create dependency or gameplay support was added.

## Execution contract (unit-tested; no production caller)
- Existing `dispatch` remains preflight-only. `ExecutableHandler` adds synchronous `execute` and `verify`; `execute` on the registry checks server thread, live worker attachment/profile identity, owner player UUID and level, dimension, loaded block chunk (without force-loading), caller-provided availability and target permission predicate before selecting a unique executable handler. Its validation must accept before the attempt runs. An attempt returning success is reported as success **only** if the handler independently verifies its documented postcondition; failed/rejected/unavailable attempts are returned directly and unverified attempts return unavailable. Neither path changes a job.
- Four unit tests cover preflight gating, failed/unavailable attempts, postcondition verification and rejection of missing/unauthorized live context. These use no real world: permission checks, entity/target availability and server threading require integration testing. The permission predicate is supplied by trusted server code and must verify actual target-specific permissions; passing an unconditional predicate is unsafe. No production execution caller or adapter exists yet. Repeated requests can repeat mutations: no durable operation ID or deduplication exists, and a verification failure after mutation may leave a partial effect. Handlers must be trusted, synchronous, and avoid unsafe partial mutations; do not retry blindly or treat this as exactly-once execution. No job completion bridge exists.

## Optional adapter contract
- On the server, create a registry instance and register a handler implementing `supports(request)` and `validate(request)`. For example, an adapter for an `elstarticket:inspect` action can support a `WorkerInteraction.Block` target, check parameters in `validate`, and return `Result(REJECTED, "invalid parameters")` or `Result(SUCCESS, "ready")`. SUCCESS means **preflight accepted**, not that any block was used.
- Only trusted server code may construct requests: resolve the attached profile and worker entity, check live identity, ownership and permissions, ensure the target is loaded and available without loading chunks, and pass `server=true` and `available=true` only after these checks. Never accept those booleans or profile snapshots from a client. The execution contract has no production caller or game integration yet; future adapters must recheck live permissions and targets before any mutation.

## Core audit (source and build only)
- `elstarticket:binding_ticket` is registered once, displayed in the template creative tab, localized in English, and modeled with the vanilla name-tag texture. There is no `elstar_ticket` registry ID or custom ticket texture. The item is not consumed and has no survival acquisition path.
- Server-side interaction accepts live, non-removed `Mob` entities in the player's level; other living entities receive PASS. New profiles contain the mob UUID, player UUID, dimension ID, idle worker status, and optional job. NeoForge serialized entity attachments hold profiles; bound mobs are marked persistent. This is entity-local storage, not a world-level registry; unloaded or missing entities cannot be looked up as workers. Duplicate attachments are rejected, including legacy owner-only data, but legacy bindings cannot manage jobs.
- Sneak-use by the matching owner/entity/dimension assigns or cancels the sole `elstarticket:wait` job on `PathfinderMob` instances. Unknown or incompatible job types cannot be assigned through the definition map. Job codec stores status, 0–100 progress, and optional failure reason; transition methods bound progress and legal transitions, but no gameplay code calls them. `WorkerStatus` has only IDLE. There is a guarded eligibility tick but no job action executor, target-specific permission policy, or population limit.
- Saved job data is decoded with codecs; unknown types, idle jobs, inconsistent assigned/completed progress, and inconsistent failure reasons now fail decoding rather than becoming valid assignments. A job type identifies the sole assignment per worker; there is no separate per-assignment UUID. Duplicate assignment leaves the existing job unchanged; assignment requires a live mob on a server level. Other malformed attachment inputs require coverage. No reconciliation exists for changed entity UUIDs/dimensions, dead/missing workers, or legacy owner-only data. Server save/restart and chunk-unload behavior have not been tested in-game.
- Template example items, block, configuration, and server-start logging remain; these are MDK sample code, not worker implementations. No duplicate ticket registration, live entity reference in a profile, job executor, or worker-related unimplemented method was found in the audited source.
- Earlier audits ran six and eight JUnit tests respectively. Identity change verification: `.\gradlew.bat test --rerun-tasks` executed 19 tests (XML: 19 passed, 0 failed, 0 skipped) and `.\gradlew.bat build` succeeded. The unit-test runtime uses the `elstarticket` mod binding; the Java entry point retains its `ExampleMod` class name. No normal game client or dedicated server was launched, so gameplay and restart persistence remain unverified.

## Pending
- Interaction preflight verification: `gradlew.bat test --rerun-tasks` and `gradlew.bat build` succeeded; generated XML reports record 15 passed, 0 failed, 0 skipped (8 profile tests and 7 interaction registry tests). This does not verify live entity resolution, save/restart, or gameplay.
- Next development task: add an integration test of entity attachment save/reload and ticket item-use on a game server, including legacy data, then verify a real game save/restart before adding execution.
- Add obtainability through survival gameplay; validate binding, chunk unloading, and restart persistence in a running dedicated server and add automated gameplay coverage.
- Migrate legacy owner-only bindings into full profiles after validating their owner UUIDs; currently these remain bound but have no profile.
- Define eligible mobs, permission rules, ownership transfer/unbinding, population limits, and client data synchronization before exposing worker controls.
- Add a real job executor with persisted target, explicit capability/permission checks and verified transitions; the demonstration job remains assigned until manually cancelled, and transition methods are not invoked by gameplay yet.
- Add a trusted server-side interaction entry point with live target resolution, permission policy, duplicate-operation protection and actual execution; current preflight cannot guarantee these and does not run an adapter in gameplay. Later add independently verified optional adapters for general machines, Create, Waystones, AE2, Refined Storage, rituals and Draconic crafting.
- Add integration coverage for assignment, cancellation, malformed attachment data, and save/restart persistence. Unknown job identifiers in saved data are now rejected by the profile codec; determine a recovery path for affected saved entities before release.
- Implement a bounded, server-authoritative worker task with persistent state, inventory accounting, and safe block permissions.
- Develop optional integrations only against verified installed mod APIs; separate chunk loading, entity ticking, block entity ticking, and random ticking policies.
- Plan migration for pre-rename `examplemod:*` save data before shipping; replace remaining template classes, assets, and sample registrations separately.

## Farmer Job System: End-to-End Harvest, Collection, Replanting & Delivery (build and unit-tested; gameplay unverified)
- **Architecture & Extensibility:**
  - Implemented [`CropAdapter`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/CropAdapter.java) and [`CropRegistry`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/CropRegistry.java) central default-deny registry.
  - Built-in vanilla support for:
    - Wheat (`Blocks.WHEAT`, seed: `Items.WHEAT_SEEDS`, farmland support)
    - Carrots (`Blocks.CARROTS`, planting item: `Items.CARROT`, farmland support)
    - Potatoes (`Blocks.POTATOES`, planting item: `Items.POTATO`, farmland support)
    - Beetroots (`Blocks.BEETROOTS`, seed: `Items.BEETROOT_SEEDS`, farmland support)
    - Cocoa (`Blocks.COCOA`, planting item: `Items.COCOA_BEANS`, jungle log support, directional facing preserved)
  - Extensible registration point for future mods (Farmer's Delight, Mystical Agriculture) without compile-time hard dependencies.
- **Farmer Order Semantics & Accounting:**
  - Quota tracks target crop blocks harvested, strictly independent of item drop counts.
  - Replanting does not increment harvest count.
  - Progress advances 1 per successfully broken mature crop block up to requested quota.
- **Replanting Rules & Failure Safeguards:**
  - Evaluates matching planting item and support conditions before mutation.
  - Deducts exactly 1 seed/planting item from `WorkerStorage` or freshly dropped items.
  - If no seed available: leaves unplanted safely, credits harvest, queues output for delivery.
  - If replant placement fails: refunds seed without item loss.
  - Replanted crop is placed at age 0, preventing immediate re-harvesting.
- **Storage Delivery & Crash Reconciliation:**
  - Harvested produce deposited into designated storage without dropping on ground.
  - Full container triggers `WAITING_FOR_STORAGE`.
  - Staged `InFlightHarvest` on `WorkerProfile` reconciles idempotently on server crash/restart (air -> committed, age 0 -> committed, intact mature -> cancelled, unloaded chunk -> fail closed).
- **Adversarial Audit & Edge-Case Hardening:**
  - **Seed Voiding on Replant Failure Fixed ([`FarmerJobController.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/FarmerJobController.java)):** Previously, when replanting failed or `blockDestroyer.destroy()` failed, `refundToWorkerStorage` silently dropped seeds if `WorkerStorage` was full or if the seed had been deducted from fresh drops. Added `refundToStorage` with preferred slot, merge, and empty-slot search. If `WorkerStorage` is full or the seed was deducted from drops, the seed is deposited back into `mutableDrops` and queued into `pendingStacks`, guaranteeing zero seeds are ever voided.
  - **Pre-Execution In-Flight Harvest Reconciliation ([`FarmerJobController.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/FarmerJobController.java), [`WorkerExecutionLoop.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/WorkerExecutionLoop.java)):** `executeHarvestTransaction` now checks and reconciles pre-existing `inFlightHarvest` before staging new intent, rejecting with `REJECTED_PREFLIGHT` if unresolved to prevent overwriting uncommitted progress. Added `reconcileInFlightHarvest(Mob worker)` to tick loop for automatic recovery.
  - **Cocoa Replanted State Recognition ([`CropAdapter.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/CropAdapter.java), [`CropRegistry.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/CropRegistry.java)):** `evaluateReconciliation` previously checked `currentState.getBlock() instanceof CropBlock` for age 0. `CocoaBlock` does not extend `CropBlock`, causing replanted cocoa to be misclassified as `UNRESOLVED_AMBIGUOUS`. Added `CropAdapter.isReplantedState` and `CropRegistry.isReplanted`, recognizing age-0 Cocoa and committing properly.
  - **Loot Table & Fortune Bonuses ([`FarmerJobController.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/main/java/com/example/examplemod/FarmerJobController.java)):** Added `calculateCropDrops` generating drops via official Minecraft `Block.getDrops` using worker entity and tool context (respecting Fortune enchantments). `destroyBlock(cropPos, false, worker)` prevents duplicate item entities from scattering in the world.
- **Automated Test Results:**
  - Added 25 dedicated tests across [`CropAdapterTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/CropAdapterTest.java) (7 tests) and [`FarmerJobTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/FarmerJobTest.java) (18 tests).
  - Total test suite: **166 tests** across 17 test suites passing cleanly with 0 failures, 0 errors, 0 skipped.
  - Clean build produces `build/libs/elstarticket-1.0.0.jar` (209,207 bytes).

## Blocked
- Create, Botania, AE2, Refined Storage, Draconic Evolution, Ice and Fire, and Lycanites integrations await version-compatible dependencies and API verification.
- Waystones, magic altar/multiblock and other optional integrations likewise require verified version-compatible APIs; none is a dependency of the preflight foundation.

## Farmer Continuous Crop Re-scan & Wake-up Lifecycle Audit (Phase 8.3)
- **Root-Cause Analysis:**
  - Audited the Farmer Job lifecycle across both Touhou Little Maid and vanilla cow workers (`hasActiveFarmJob` in `WorkerExecutionLoop.java`).
  - Identified that discovered crop targets were never retained in state. Upon discovering a mature crop from `WAITING_FOR_CROPS`, status transitioned to `RUNNING` and initiated navigation, but on tick $T+1$, background scanning started at `foundIndex + 1`. Because the single crop was only 1 position in a 5,445-position volume, within 11 ticks (0.55s) the scan completed and triggered `cycleCompleted()`, falsely kicking the worker back into `WAITING_FOR_CROPS` before it could reach the crop.
  - Upon arriving at the crop block while throttled in `WAITING_FOR_CROPS`, `scanResult.targetPos()` was empty on the 1 tick out of 40 that ran, leaving the worker frozen adjacent to the mature crop.
  - In addition, `scanCursor` was not reset to 0 on cycle completion, wasting retry intervals scanning empty stone/air layers before checking the crop coordinates.
- **Architectural Corrections:**
  - **Active Target Retention:** Added `Optional<BlockPos> activeTarget` to `FarmerJobData`. When a crop is discovered, `activeTarget` locks onto the position. While en route, background scans are skipped, and `activeTarget` is validated every tick. Upon arrival, the harvest transaction executes and clears `activeTarget`.
  - **Server-Authoritative Retry Timer:** Added `int retryTimer` to `FarmerJobData` (40 ticks), safely decremented per tick during `WAITING_FOR_CROPS`.
  - **Center-Outward Re-scan Reset:** When a scan cycle finishes with zero mature crops, `scanCursor` and `emptyChecks` reset to 0, ensuring retry sweeps always start at the farm center and crop layers.
  - **Backwards Compatibility:** All Codecs and constructors retain backwards compatibility; all 7 vanilla crop adapters and selection modes (`ALL_SUPPORTED` and `SELECTED_ONLY`) remain fully preserved.
- **Verification & Test Status:**
  - Added 8 comprehensive regression tests in [`FarmerLifecycleRescanTest.java`](file:///D:/PeakMinecraftMod/MDK-1.21.1-ModDevGradle-main/src/test/java/com/example/examplemod/FarmerLifecycleRescanTest.java).
  - Total automated test suite: **527 tests passed, 0 failures, 0 skipped**.
  - Clean build verified: `build/libs/elstarticket-1.0.0.jar` (435,218 bytes, SHA-256: `435160E2044D6BF9E38D9A00FE85EC986C95642EA04CFCD512E3406CBCDDFFF9`).
  - Deployed to PolyMC instance with 100% identical SHA-256 checksum match.
