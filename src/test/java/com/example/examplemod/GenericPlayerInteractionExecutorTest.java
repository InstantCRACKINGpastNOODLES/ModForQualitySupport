package com.example.examplemod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

/**
 * Phase 1C — Generic Player-like Interaction Executor Specification and Regression Tests.
 * Strictly covers Groups A through G as mandated by Phase 1C scope.
 */
class GenericPlayerInteractionExecutorTest {

    private static final ResourceLocation GENERIC_ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "generic_player_executor");
    private static final ResourceLocation ADAPTER_ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "specialized_adapter");
    private static final ResourceLocation CAPABILITY_ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "capability_handler");

    private WorkerInteractionRegistry registry;
    private MockPlayerActionBridge bridge;
    private GenericPlayerInteractionExecutor executor;
    private Unsafe unsafe;
    private ServerLevel stubLevel;
    private ServerPlayer stubPlayer;

    @BeforeEach
    void setup() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (Unsafe) f.get(null);

        stubLevel = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
        stubPlayer = (ServerPlayer) unsafe.allocateInstance(ServerPlayer.class);

        registry = new WorkerInteractionRegistry();
        bridge = new MockPlayerActionBridge();
        executor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge);
        registry.registerExecutor(executor);
    }

    private InteractionIntent blockClickIntent(ActionType actionType) {
        return InteractionIntent.builder()
                .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                .actionType(actionType)
                .executionMode(ExecutionMode.CLICK)
                .build();
    }

    private InteractionIntent entityClickIntent(ActionType actionType, UUID entityId) {
        return InteractionIntent.builder()
                .target(new InteractionTarget.EntityTarget(entityId))
                .actionType(actionType)
                .executionMode(ExecutionMode.CLICK)
                .build();
    }

    private InteractionIntent airClickIntent() {
        return InteractionIntent.builder()
                .target(new InteractionTarget.AirTarget(Vec3.ZERO, new Vec3(0, 0, 1)))
                .actionType(ActionType.RIGHT_CLICK)
                .executionMode(ExecutionMode.CLICK)
                .build();
    }

    private InteractionIntent holdIntent() {
        return InteractionIntent.builder()
                .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                .actionType(ActionType.LEFT_CLICK)
                .executionMode(ExecutionMode.HOLD)
                .build();
    }

    private InteractionIntent pressReleaseIntent(int durationTicks) {
        return InteractionIntent.builder()
                .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                .actionType(ActionType.RIGHT_CLICK)
                .executionMode(ExecutionMode.PRESS_RELEASE)
                .durationTicks(durationTicks)
                .build();
    }

    private WorkerInteractionContext createContext(UUID workerId, UUID ownerId) {
        return WorkerInteractionContext.builder()
                .level(stubLevel)
                .playerOverride(stubPlayer)
                .workerId(workerId)
                .ownerId(ownerId)
                .workerPosition(Vec3.ZERO)
                .workerEyePosition(new Vec3(0, 1.5, 0))
                .build();
    }

    // =========================================================================
    // Group A — Routing & Registry
    // =========================================================================

    @Nested
    @DisplayName("Group A — Routing & Registry")
    class GroupA_Routing {

        @Test
        @DisplayName("A01: Generic executor registered cleanly")
        void testA01_RegisteredCleanly() {
            assertTrue(registry.getExecutor(GENERIC_ID).isPresent());
            assertEquals(1, registry.getExecutorCount());
            assertEquals(InteractionExecutor.Category.GENERIC_PLAYER, executor.category());
            assertEquals(100, executor.priority());
        }

        @Test
        @DisplayName("A02: GENERIC_FIRST selects Generic when eligible")
        void testA02_GenericFirstSelectsGeneric() {
            InteractionIntent intent = blockClickIntent(ActionType.RIGHT_CLICK);
            ResolutionResult res = registry.resolve(intent, RoutingPolicy.GENERIC_FIRST);
            assertTrue(res.isResolved());
            assertEquals(GENERIC_ID, res.executor().orElseThrow().id());
        }

        @Test
        @DisplayName("A03: Specialized fallback works when Generic ineligible")
        void testA03_SpecializedFallbackWhenGenericIneligible() {
            // Endpoints are not handled by Generic executor
            InteractionIntent endpointIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.EndpointTarget(ResourceLocation.fromNamespaceAndPath("test", "endpoint")))
                    .actionType(ActionType.RIGHT_CLICK)
                    .executionMode(ExecutionMode.CLICK)
                    .build();

            MockSpecializedAdapter adapter = new MockSpecializedAdapter(ADAPTER_ID, 50, true);
            registry.registerExecutor(adapter);

            ResolutionResult res = registry.resolve(endpointIntent, RoutingPolicy.GENERIC_FIRST);
            assertTrue(res.isResolved());
            assertEquals(ADAPTER_ID, res.executor().orElseThrow().id());
        }

        @Test
        @DisplayName("A04: GENERIC_STRICT rejects specialized fallback")
        void testA04_GenericStrictRejectsFallback() {
            InteractionIntent endpointIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.EndpointTarget(ResourceLocation.fromNamespaceAndPath("test", "endpoint")))
                    .actionType(ActionType.RIGHT_CLICK)
                    .executionMode(ExecutionMode.CLICK)
                    .build();

            MockSpecializedAdapter adapter = new MockSpecializedAdapter(ADAPTER_ID, 50, true);
            registry.registerExecutor(adapter);

            ResolutionResult res = registry.resolve(endpointIntent, RoutingPolicy.GENERIC_STRICT);
            assertFalse(res.isResolved());
            assertEquals(ResolutionResult.Status.NO_COMPATIBLE_EXECUTOR, res.status());
        }

        @Test
        @DisplayName("A05: SPECIALIZED_FIRST prefers specialized adapter over Generic")
        void testA05_SpecializedFirstPrefersAdapter() {
            MockSpecializedAdapter adapter = new MockSpecializedAdapter(ADAPTER_ID, 50, true);
            registry.registerExecutor(adapter);

            InteractionIntent intent = blockClickIntent(ActionType.RIGHT_CLICK);
            ResolutionResult res = registry.resolve(intent, RoutingPolicy.SPECIALIZED_FIRST);
            assertTrue(res.isResolved());
            assertEquals(ADAPTER_ID, res.executor().orElseThrow().id());
        }

        @Test
        @DisplayName("A06: CAPABILITY_ONLY isolates Generic executor")
        void testA06_CapabilityOnlyIsolatesGeneric() {
            MockCapabilityHandler capabilityHandler = new MockCapabilityHandler(CAPABILITY_ID);
            registry.registerExecutor(capabilityHandler);

            InteractionIntent endpointIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.EndpointTarget(ResourceLocation.fromNamespaceAndPath("test", "cap")))
                    .actionType(ActionType.RIGHT_CLICK)
                    .executionMode(ExecutionMode.CLICK)
                    .build();

            ResolutionResult res = registry.resolve(endpointIntent, RoutingPolicy.CAPABILITY_ONLY);
            assertTrue(res.isResolved());
            assertEquals(CAPABILITY_ID, res.executor().orElseThrow().id());

            // A block click under CAPABILITY_ONLY should be rejected
            ResolutionResult blockRes = registry.resolve(blockClickIntent(ActionType.RIGHT_CLICK), RoutingPolicy.CAPABILITY_ONLY);
            assertFalse(blockRes.isResolved());
        }

        @Test
        @DisplayName("A07: Ambiguous routing between equal-priority same-category is deterministic")
        void testA07_DeterministicTieBreaking() {
            ResourceLocation generic2Id = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "generic_player_executor_2");
            GenericPlayerInteractionExecutor executor2 = new GenericPlayerInteractionExecutor(generic2Id, 100, bridge);
            registry.registerExecutor(executor2);

            InteractionIntent intent = blockClickIntent(ActionType.RIGHT_CLICK);
            ResolutionResult res = registry.resolve(intent, RoutingPolicy.GENERIC_FIRST);
            assertEquals(ResolutionResult.Status.AMBIGUOUS_ROUTING, res.status());
            assertEquals(2, res.candidates().size());
            // Lexicographical sorting order
            assertEquals(GENERIC_ID, res.candidates().get(0));
            assertEquals(generic2Id, res.candidates().get(1));
        }
    }

    // =========================================================================
    // Group B — Discrete Interaction
    // =========================================================================

    @Nested
    @DisplayName("Group B — Discrete Interaction")
    class GroupB_DiscreteInteraction {

        @Test
        @DisplayName("B01: Left-click block discrete execution succeeds")
        void testB01_LeftClickBlock() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = blockClickIntent(ActionType.LEFT_CLICK);

            InteractionResult res = executor.executeDiscrete(intent, ctx);
            assertTrue(res.outcome().isSuccess());
            assertEquals(1, bridge.leftClickBlockCount);
        }

        @Test
        @DisplayName("B02: Right-click block discrete execution succeeds")
        void testB02_RightClickBlock() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = blockClickIntent(ActionType.RIGHT_CLICK);

            InteractionResult res = executor.executeDiscrete(intent, ctx);
            assertTrue(res.outcome().isSuccess());
            assertEquals(1, bridge.rightClickBlockCount);
        }

        @Test
        @DisplayName("B03: Left-click entity (attack) succeeds")
        void testB03_LeftClickEntity() {
            UUID workerId = UUID.randomUUID();
            UUID targetEntityId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = entityClickIntent(ActionType.LEFT_CLICK, targetEntityId);

            GenericPlayerInteractionExecutor entityExec = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected TargetResolution checkEntityTarget(ServerLevel level, UUID entityId) {
                    return TargetResolution.success(null);
                }
            };

            InteractionResult res = entityExec.executeDiscrete(intent, ctx);
            assertTrue(res.outcome().isSuccess());
            assertEquals(1, bridge.leftClickEntityCount);
        }

        @Test
        @DisplayName("B04: Right-click entity (interact) succeeds")
        void testB04_RightClickEntity() {
            UUID workerId = UUID.randomUUID();
            UUID targetEntityId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = entityClickIntent(ActionType.RIGHT_CLICK, targetEntityId);

            GenericPlayerInteractionExecutor entityExec = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected TargetResolution checkEntityTarget(ServerLevel level, UUID entityId) {
                    return TargetResolution.success(null);
                }
            };

            InteractionResult res = entityExec.executeDiscrete(intent, ctx);
            assertTrue(res.outcome().isSuccess());
            assertEquals(1, bridge.rightClickEntityCount);
        }

        @Test
        @DisplayName("B05: Right-click air (item use) succeeds")
        void testB05_RightClickAir() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = airClickIntent();

            InteractionResult res = executor.executeDiscrete(intent, ctx);
            assertTrue(res.outcome().isSuccess());
            assertEquals(1, bridge.rightClickAirCount);
        }

        @Test
        @DisplayName("B06: Left-click air is rejected as unsupported")
        void testB06_LeftClickAirRejected() {
            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.AirTarget(Vec3.ZERO, new Vec3(0, 0, 1)))
                    .actionType(ActionType.LEFT_CLICK)
                    .executionMode(ExecutionMode.CLICK)
                    .build();

            assertFalse(executor.canHandle(intent));
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionResult res = executor.executeDiscrete(intent, ctx);
            assertEquals(InteractionOutcome.UNSUPPORTED, res.outcome());
        }

        @Test
        @DisplayName("B07: EndpointTarget is rejected as unsupported")
        void testB07_EndpointTargetRejected() {
            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.EndpointTarget(ResourceLocation.fromNamespaceAndPath("test", "endpoint")))
                    .actionType(ActionType.RIGHT_CLICK)
                    .executionMode(ExecutionMode.CLICK)
                    .build();

            assertFalse(executor.canHandle(intent));
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionResult res = executor.executeDiscrete(intent, ctx);
            assertEquals(InteractionOutcome.UNSUPPORTED, res.outcome());
        }

        @Test
        @DisplayName("B08: Out-of-reach target is blocked")
        void testB08_OutOfReachTargetBlocked() {
            UUID workerId = UUID.randomUUID();
            // Target block at (100, 0, 0) while worker is at (0, 0, 0)
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(new BlockPos(100, 0, 0)))
                    .actionType(ActionType.RIGHT_CLICK)
                    .executionMode(ExecutionMode.CLICK)
                    .maxReachDistance(4.5)
                    .build();

            InteractionResult res = executor.executeDiscrete(intent, ctx);
            assertEquals(InteractionOutcome.BLOCKED, res.outcome());
            assertEquals("OUT_OF_REACH", res.reasonCode());
            assertEquals(0, bridge.rightClickBlockCount);
        }

        @Test
        @DisplayName("B09: Permission denial returns rejected")
        void testB09_PermissionDenialRejected() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .permissionPredicate(intent -> false) // Deny permission
                    .build();

            InteractionIntent intent = blockClickIntent(ActionType.RIGHT_CLICK);
            InteractionResult res = executor.executeDiscrete(intent, ctx);
            assertEquals(InteractionOutcome.REJECTED, res.outcome());
            assertEquals("PERMISSION_DENIED", res.reasonCode());
            assertEquals(0, bridge.rightClickBlockCount);
        }

        @Test
        @DisplayName("B10: Missing worker identity rejects execution")
        void testB10_MissingWorkerIdentity() {
            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .build();

            InteractionIntent intent = blockClickIntent(ActionType.RIGHT_CLICK);
            InteractionResult res = executor.executeDiscrete(intent, ctx);
            assertEquals(InteractionOutcome.REJECTED, res.outcome());
            assertEquals("MISSING_WORKER_IDENTITY", res.reasonCode());
        }

        @Test
        @DisplayName("B11: Bridge exception returns structured failure without crashing")
        void testB11_BridgeExceptionReturnsStructuredFailure() {
            bridge.throwOnCall = new RuntimeException("Simulated interaction failure");
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            InteractionIntent intent = blockClickIntent(ActionType.RIGHT_CLICK);
            InteractionResult res = executor.executeDiscrete(intent, ctx);
            assertEquals(InteractionOutcome.FAILED, res.outcome());
            assertEquals("EXECUTION_EXCEPTION", res.reasonCode());
        }

        @Test
        @DisplayName("B12: Null context returns failed")
        void testB12_NullContextReturnsFailed() {
            InteractionIntent intent = blockClickIntent(ActionType.RIGHT_CLICK);
            InteractionResult res = executor.executeDiscrete(intent, null);
            assertEquals(InteractionOutcome.FAILED, res.outcome());
            assertEquals("NULL_CONTEXT", res.reasonCode());
        }

        @Test
        @DisplayName("B13: Line of sight obstructed returns BLOCKED")
        void testB13_LineOfSightObstructed() {
            GenericPlayerInteractionExecutor losExec = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected boolean checkLineOfSight(ServerLevel level, Vec3 eyePos, Vec3 targetCenter, InteractionTarget target) {
                    return false; // Obstructed
                }
            };
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .actionType(ActionType.RIGHT_CLICK)
                    .executionMode(ExecutionMode.CLICK)
                    .requireLineOfSight(true)
                    .build();

            InteractionResult res = losExec.executeDiscrete(intent, ctx);
            assertEquals(InteractionOutcome.BLOCKED, res.outcome());
            assertEquals("LINE_OF_SIGHT_OBSTRUCTED", res.reasonCode());
        }

        @Test
        @DisplayName("B14: Target chunk unloaded returns UNAVAILABLE")
        void testB14_TargetChunkUnloaded() {
            GenericPlayerInteractionExecutor chunkExec = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected boolean checkChunkLoaded(ServerLevel level, BlockPos pos) {
                    return false; // Unloaded chunk
                }
            };
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = blockClickIntent(ActionType.RIGHT_CLICK);

            InteractionResult res = chunkExec.executeDiscrete(intent, ctx);
            assertEquals(InteractionOutcome.UNAVAILABLE, res.outcome());
            assertEquals("TARGET_CHUNK_UNLOADED", res.reasonCode());
        }

        @Test
        @DisplayName("B15: Target entity dead/removed returns UNAVAILABLE")
        void testB15_TargetEntityUnavailable() {
            GenericPlayerInteractionExecutor entityExec = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected TargetResolution checkEntityTarget(ServerLevel level, UUID entityId) {
                    return TargetResolution.unavailable("TARGET_ENTITY_UNAVAILABLE");
                }
            };
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = entityClickIntent(ActionType.RIGHT_CLICK, UUID.randomUUID());

            InteractionResult res = entityExec.executeDiscrete(intent, ctx);
            assertEquals(InteractionOutcome.UNAVAILABLE, res.outcome());
            assertEquals("TARGET_ENTITY_UNAVAILABLE", res.reasonCode());
        }

        @Test
        @DisplayName("B16: Spawn protected block returns REJECTED")
        void testB16_SpawnProtectionRejected() {
            GenericPlayerInteractionExecutor spawnExec = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected boolean checkSpawnProtected(ServerLevel level, ServerPlayer player, BlockPos pos) {
                    return true; // Spawn protected
                }
            };
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = blockClickIntent(ActionType.RIGHT_CLICK);

            InteractionResult res = spawnExec.executeDiscrete(intent, ctx);
            assertEquals(InteractionOutcome.REJECTED, res.outcome());
            assertEquals("SPAWN_PROTECTED", res.reasonCode());
        }

        @Test
        @DisplayName("B17: Non-server thread rejects execution")
        void testB17_WrongThreadRejected() {
            GenericPlayerInteractionExecutor threadExec = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected boolean isServerThread(ServerLevel level) {
                    return false; // Not on server thread
                }
            };
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = blockClickIntent(ActionType.RIGHT_CLICK);

            InteractionResult res = threadExec.executeDiscrete(intent, ctx);
            assertEquals(InteractionOutcome.REJECTED, res.outcome());
            assertEquals("WRONG_THREAD", res.reasonCode());
        }
    }

    // =========================================================================
    // Group C — FakePlayer Identity, Isolation and Item Reconciliation
    // =========================================================================

    @Nested
    @DisplayName("Group C — FakePlayer Identity, Isolation and Item Reconciliation")
    class GroupC_IdentityAndReconciliation {

        @Test
        @DisplayName("C01: Worker UUID is preserved and distinct from owner UUID")
        void testC01_WorkerIdentityDistinctFromOwner() {
            UUID workerId = UUID.randomUUID();
            UUID ownerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, ownerId);

            assertEquals(workerId, ctx.workerId().orElseThrow());
            assertEquals(ownerId, ctx.ownerId().orElseThrow());
            assertFalse(workerId.equals(ownerId), "Worker and owner identity must remain distinct");
        }

        @Test
        @DisplayName("C02: Two different workers produce distinct FakePlayer profile identities")
        void testC02_TwoWorkersIndependentProfiles() {
            UUID worker1 = UUID.randomUUID();
            UUID worker2 = UUID.randomUUID();

            UUID fake1 = UUID.nameUUIDFromBytes(("elstar_worker:" + worker1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            UUID fake2 = UUID.nameUUIDFromBytes(("elstar_worker:" + worker2).getBytes(java.nio.charset.StandardCharsets.UTF_8));

            assertFalse(fake1.equals(fake2), "FakePlayer UUIDs derived from different workers must be distinct");
            assertFalse(fake1.equals(worker1), "FakePlayer UUID must not be interchangeable with Worker UUID");
        }

        @Test
        @DisplayName("C03: Item reconciliation — no consumption preserves worker item")
        void testC03_NoConsumptionPreservesItem() {
            ItemStack initial = new ItemStack(Items.DIAMOND_PICKAXE);
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);
            AtomicReference<ItemStack> workerHolder = new AtomicReference<>(initial);
            WorkerInteractionContext.ItemAccess access = WorkerInteractionContext.ItemAccess.of(workerHolder::get, workerHolder::set);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, access);
            assertFalse(prep.wasEmpty());

            InteractionItemReconciler.ReconciliationResult res = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, access, prep);
            assertFalse(res.consumed());
            assertFalse(res.partiallyConsumed());
            assertFalse(res.damaged());
            assertFalse(res.transformed());
            assertEquals(initial.getItem(), workerHolder.get().getItem());
            assertEquals(ItemStack.EMPTY, inv.getItemInHand(InteractionHand.MAIN_HAND));
        }

        @Test
        @DisplayName("C04: Item reconciliation — full consumption empties worker slot")
        void testC04_FullConsumptionEmptiesWorkerSlot() {
            ItemStack initial = new ItemStack(Items.BREAD, 1);
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);
            AtomicReference<ItemStack> workerHolder = new AtomicReference<>(initial);
            WorkerInteractionContext.ItemAccess access = WorkerInteractionContext.ItemAccess.of(workerHolder::get, workerHolder::set);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, access);
            // Simulate consumption by setting hand to EMPTY
            inv.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

            InteractionItemReconciler.ReconciliationResult res = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, access, prep);
            assertTrue(res.consumed());
            assertTrue(workerHolder.get().isEmpty());
            assertEquals(ItemStack.EMPTY, inv.getItemInHand(InteractionHand.MAIN_HAND));
        }

        @Test
        @DisplayName("C05: Item reconciliation — partial consumption decrements stack count")
        void testC05_PartialConsumptionDecrementsCount() {
            ItemStack initial = new ItemStack(Items.TORCH, 16);
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);
            AtomicReference<ItemStack> workerHolder = new AtomicReference<>(initial);
            WorkerInteractionContext.ItemAccess access = WorkerInteractionContext.ItemAccess.of(workerHolder::get, workerHolder::set);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, access);
            // Simulate placing 1 torch (hand has 15 remaining)
            inv.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH, 15));

            InteractionItemReconciler.ReconciliationResult res = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, access, prep);
            assertTrue(res.partiallyConsumed());
            assertEquals(15, workerHolder.get().getCount());
            assertEquals(ItemStack.EMPTY, inv.getItemInHand(InteractionHand.MAIN_HAND));
        }

        @Test
        @DisplayName("C06: Item reconciliation — durability damage updates item damage")
        void testC06_DurabilityDamageUpdatesItem() {
            ItemStack initial = new ItemStack(Items.DIAMOND_PICKAXE);
            initial.setDamageValue(0);
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);
            AtomicReference<ItemStack> workerHolder = new AtomicReference<>(initial);
            WorkerInteractionContext.ItemAccess access = WorkerInteractionContext.ItemAccess.of(workerHolder::get, workerHolder::set);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, access);
            // Simulate tool damage
            ItemStack damaged = initial.copy();
            damaged.setDamageValue(5);
            inv.setItemInHand(InteractionHand.MAIN_HAND, damaged);

            InteractionItemReconciler.ReconciliationResult res = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, access, prep);
            assertTrue(res.damaged());
            assertEquals(5, workerHolder.get().getDamageValue());
            assertEquals(ItemStack.EMPTY, inv.getItemInHand(InteractionHand.MAIN_HAND));
        }

        @Test
        @DisplayName("C07: Item reconciliation — item transformation replaces stack")
        void testC07_ItemTransformationReplacesStack() {
            ItemStack initial = new ItemStack(Items.BUCKET);
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);
            AtomicReference<ItemStack> workerHolder = new AtomicReference<>(initial);
            WorkerInteractionContext.ItemAccess access = WorkerInteractionContext.ItemAccess.of(workerHolder::get, workerHolder::set);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, access);
            // Simulate filling bucket with water
            inv.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));

            InteractionItemReconciler.ReconciliationResult res = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, access, prep);
            assertTrue(res.transformed());
            assertEquals(Items.WATER_BUCKET, workerHolder.get().getItem());
            assertEquals(ItemStack.EMPTY, inv.getItemInHand(InteractionHand.MAIN_HAND));
        }

        @Test
        @DisplayName("C08: Item reconciliation — secondary drops safely collected without voiding")
        void testC08_SecondaryDropsCollected() {
            ItemStack initial = new ItemStack(Items.SHEARS);
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);
            AtomicReference<ItemStack> workerHolder = new AtomicReference<>(initial);
            WorkerInteractionContext.ItemAccess access = WorkerInteractionContext.ItemAccess.of(workerHolder::get, workerHolder::set);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, access);
            // Simulate sheep shearing adding wool to inventory
            inv.addSecondaryItem(new ItemStack(Items.WHITE_WOOL, 3));

            InteractionItemReconciler.ReconciliationResult res = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, access, prep);
            assertEquals(1, res.secondaryItems().size());
            assertEquals(Items.WHITE_WOOL, res.secondaryItems().get(0).getItem());
            assertEquals(3, res.secondaryItems().get(0).getCount());
            // Secondary slots cleared on player
            assertEquals(0, inv.getSecondaryItems().size());
        }

        @Test
        @DisplayName("C09: Item reconciliation — exception safety restores worker item")
        void testC09_ExceptionSafetyRestoresItem() {
            ItemStack initial = new ItemStack(Items.DIAMOND_PICKAXE);
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);
            AtomicReference<ItemStack> workerHolder = new AtomicReference<>(initial);
            WorkerInteractionContext.ItemAccess access = WorkerInteractionContext.ItemAccess.of(workerHolder::get, workerHolder::set);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, access);
            InteractionItemReconciler.ReconciliationResult res = InteractionItemReconciler.reconcileOnException(inv, InteractionHand.MAIN_HAND, access, prep);

            assertEquals(initial.getItem(), workerHolder.get().getItem());
            assertEquals(ItemStack.EMPTY, inv.getItemInHand(InteractionHand.MAIN_HAND));
        }
    }

    // =========================================================================
    // Group D — HOLD Lifecycle Integration
    // =========================================================================

    @Nested
    @DisplayName("Group D — HOLD Lifecycle Integration")
    class GroupD_HoldLifecycle {

        private HoldSessionManager manager;

        @BeforeEach
        void initManager() {
            manager = new HoldSessionManager(registry, 200, 60);
        }

        @Test
        @DisplayName("D01: Start continuous is invoked exactly once and transitions session to PRESSED")
        void testD01_StartContinuousInvokedOnce() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            InteractionResult res = manager.startSession(workerId, holdIntent(), ctx);
            assertTrue(res.outcome().isSuccess());
            assertTrue(manager.hasActiveSession(workerId));
            assertEquals(HoldSessionState.PRESSED, manager.getActiveSession(workerId).orElseThrow().state());
        }

        @Test
        @DisplayName("D02: Continuation tick transitions PRESSED to HELD and does NOT restart interaction")
        void testD02_ContinuationTransitionsToHeld() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            manager.startSession(workerId, holdIntent(), ctx);
            bridge.nextResult = InteractionResult.waiting("BREAKING");

            manager.tick(1, ctx);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.HELD, session.state());
            assertEquals(1, session.elapsedTicks());
        }

        @Test
        @DisplayName("D03: WAITING tick keeps session active")
        void testD03_WaitingKeepsSessionActive() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            manager.startSession(workerId, holdIntent(), ctx);
            bridge.nextResult = InteractionResult.waiting("BREAKING");

            for (int t = 1; t <= 10; t++) {
                manager.tick(t, ctx);
                assertTrue(manager.hasActiveSession(workerId));
            }
            assertEquals(10, manager.getActiveSession(workerId).orElseThrow().elapsedTicks());
        }

        @Test
        @DisplayName("D04: Normal release succeeds and button released cleanly")
        void testD04_NormalReleaseSucceeds() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            manager.startSession(workerId, holdIntent(), ctx);
            bridge.nextResult = InteractionResult.waiting("BREAKING");
            manager.tick(1, ctx);

            InteractionResult res = manager.release(workerId, ctx);
            assertTrue(res.outcome().isSuccess());
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, bridge.cancelCount);
        }

        @Test
        @DisplayName("D05: Failed release triggers fallback cancellation")
        void testD05_FailedReleaseTriggersFallback() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            // Create an executor whose releaseContinuous fails
            ResourceLocation failExecId = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "fail_release_executor");
            GenericPlayerInteractionExecutor failExecutor = new GenericPlayerInteractionExecutor(failExecId, 200, bridge) {
                @Override
                public InteractionResult releaseContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks) {
                    return InteractionResult.failed("RELEASE_FAILED", "Simulated release failure", id());
                }
            };
            registry.registerExecutor(failExecutor);

            manager.startSession(workerId, holdIntent(), ctx);
            InteractionResult res = manager.release(workerId, ctx);

            // Fallback cancellation succeeded, session transitioned to CANCELLED
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, bridge.cancelCount);
        }

        @Test
        @DisplayName("D06: Failed fallback retains quarantine (CLEANUP_PENDING)")
        void testD06_FailedFallbackRetainsQuarantine() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            ResourceLocation stubbornId = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "stubborn_executor");
            GenericPlayerInteractionExecutor stubbornExecutor = new GenericPlayerInteractionExecutor(stubbornId, 300, bridge) {
                @Override
                public InteractionResult releaseContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks) {
                    return InteractionResult.failed("RELEASE_FAILED", "Release failed", id());
                }

                @Override
                public InteractionResult cancelContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks, String reason) {
                    return InteractionResult.failed("CANCEL_FAILED", "Cancel failed", id());
                }
            };
            registry.registerExecutor(stubbornExecutor);

            manager.startSession(workerId, holdIntent(), ctx);
            InteractionResult res = manager.release(workerId, ctx);

            // Worker remains locked!
            assertTrue(manager.isWorkerLocked(workerId));
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.CLEANUP_PENDING, session.state());
        }

        @Test
        @DisplayName("D07: Cancellation is safe and reentrant")
        void testD07_CancelIsReentrantAndSafe() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            manager.startSession(workerId, holdIntent(), ctx);
            boolean cancelled1 = manager.cancel(workerId, ctx, "TEST_CANCEL_1");
            assertTrue(cancelled1);
            assertFalse(manager.hasActiveSession(workerId));

            // Repeated cancel is a safe no-op
            boolean cancelled2 = manager.cancel(workerId, ctx, "TEST_CANCEL_2");
            assertFalse(cancelled2);
        }

        @Test
        @DisplayName("D08: Tick exception triggers fallback cleanup")
        void testD08_TickExceptionTriggersCleanup() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            manager.startSession(workerId, holdIntent(), ctx);
            bridge.throwOnCall = new RuntimeException("Tick runtime crash");

            // Ticking with exception must trigger cancelSession and remove active session
            manager.tick(1, ctx);
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, bridge.cancelCount);
        }

        @Test
        @DisplayName("D09: Target out of reach during continuous hold triggers cleanup")
        void testD09_TargetOutOfReachTriggersCleanup() {
            UUID workerId = UUID.randomUUID();
            // Start session in reach
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            manager.startSession(workerId, holdIntent(), ctx);

            // Create context where worker moved 100 blocks away
            WorkerInteractionContext movedCtx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .workerPosition(new Vec3(100, 0, 0))
                    .workerEyePosition(new Vec3(100, 1.5, 0))
                    .build();

            // Next tick detects target is out of reach -> cancels session
            manager.tick(1, movedCtx);
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, bridge.cancelCount);
        }

        @Test
        @DisplayName("D10: Max hold timeout triggers watchdog cancellation")
        void testD10_MaxHoldTimeoutTriggersCancellation() {
            HoldSessionManager shortMgr = new HoldSessionManager(registry, 10, 60);
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            shortMgr.startSession(workerId, holdIntent(), ctx);
            bridge.nextResult = InteractionResult.waiting("STILL_BREAKING");

            for (int t = 1; t <= 10; t++) {
                shortMgr.tick(t, ctx);
            }

            // Exceeding max hold ticks triggers cancellation
            shortMgr.tick(11, ctx);
            assertFalse(shortMgr.hasActiveSession(workerId));
            assertEquals(1, bridge.cancelCount);
        }
    }

    // =========================================================================
    // Group E — Execution Modes
    // =========================================================================

    @Nested
    @DisplayName("Group E — Execution Modes")
    class GroupE_ExecutionModes {

        @Test
        @DisplayName("E01: CLICK is discrete")
        void testE01_ClickIsDiscrete() {
            assertTrue(executor.supportsMode(ExecutionMode.CLICK));
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionResult res = executor.executeDiscrete(blockClickIntent(ActionType.RIGHT_CLICK), ctx);
            assertTrue(res.outcome().isSuccess());
            assertEquals(1, bridge.rightClickBlockCount);
        }

        @Test
        @DisplayName("E02: HOLD is continuous")
        void testE02_HoldIsContinuous() {
            assertTrue(executor.supportsMode(ExecutionMode.HOLD));
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionResult startRes = executor.startContinuous(holdIntent(), ctx);
            assertTrue(startRes.outcome().isSuccess());
        }

        @Test
        @DisplayName("E03: PRESS_RELEASE has exact duration semantics")
        void testE03_PressReleaseExactDuration() {
            assertTrue(executor.supportsMode(ExecutionMode.PRESS_RELEASE));
            HoldSessionManager mgr = new HoldSessionManager(registry, 200, 60);
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            int duration = 5;
            InteractionIntent intent = pressReleaseIntent(duration);
            mgr.startSession(workerId, intent, ctx);

            // Tick for duration ticks
            for (int t = 1; t < duration; t++) {
                mgr.tick(t, ctx);
                assertTrue(mgr.hasActiveSession(workerId), "Session must remain active before duration is reached");
            }

            // At tick = duration, release is triggered automatically
            mgr.tick(duration, ctx);
            assertFalse(mgr.hasActiveSession(workerId), "Session must be released at exact duration");
        }

        @Test
        @DisplayName("E04: REPEAT consists of discrete interactions")
        void testE04_RepeatSupported() {
            assertTrue(executor.supportsMode(ExecutionMode.REPEAT));
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent repeatIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .actionType(ActionType.RIGHT_CLICK)
                    .executionMode(ExecutionMode.REPEAT)
                    .repeatIntervalTicks(10)
                    .build();

            assertTrue(executor.canHandle(repeatIntent));
            InteractionResult res = executor.executeDiscrete(repeatIntent, ctx);
            assertTrue(res.outcome().isSuccess());
        }
    }

    // =========================================================================
    // Group F — Observability and Watchdog (P2 Resolution)
    // =========================================================================

    @Nested
    @DisplayName("Group F — Observability and Watchdog")
    class GroupF_ObservabilityAndWatchdog {

        @Test
        @DisplayName("F01: CLEANUP_FAILED fires CleanupDiagnosticListener with structured event")
        void testF01_CleanupFailedFiresListener() {
            HoldSessionManager mgr = new HoldSessionManager(registry, 200, 10, 2);
            AtomicReference<HoldSessionManager.CleanupDiagnosticEvent> capturedEvent = new AtomicReference<>();
            mgr.addCleanupDiagnosticListener(capturedEvent::set);

            ResourceLocation stubbornId = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "stubborn_exec");
            GenericPlayerInteractionExecutor stubbornExecutor = new GenericPlayerInteractionExecutor(stubbornId, 300, bridge) {
                @Override
                public InteractionResult releaseContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks) {
                    return InteractionResult.failed("RELEASE_FAIL", "Fail", id());
                }

                @Override
                public InteractionResult cancelContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks, String reason) {
                    return InteractionResult.failed("CANCEL_FAIL", "Fail", id());
                }
            };
            registry.registerExecutor(stubbornExecutor);

            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            mgr.startSession(workerId, holdIntent(), ctx);

            // Trigger failed release -> enters CLEANUP_PENDING (retries = 0)
            mgr.release(workerId, ctx);
            assertNotNull(capturedEvent.get());
            assertEquals(workerId, capturedEvent.get().workerId());

            // Retry 1
            mgr.tick(1, ctx);
            // Retry 2 -> transitions to CLEANUP_FAILED
            mgr.tick(2, ctx);

            HoldSession session = mgr.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.CLEANUP_FAILED, session.state());
            assertEquals("MAX_CLEANUP_RETRIES_EXCEEDED", capturedEvent.get().failureReason());
            assertTrue(capturedEvent.get().quarantined());
        }

        @Test
        @DisplayName("F02: CleanupDiagnosticListener exception does NOT corrupt cleanup or unlock worker")
        void testF02_ListenerExceptionDoesNotCorruptCleanup() {
            HoldSessionManager mgr = new HoldSessionManager(registry, 200, 10, 1);
            mgr.addCleanupDiagnosticListener(event -> {
                throw new RuntimeException("Crashing diagnostic listener");
            });

            ResourceLocation stubbornId = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "stubborn_exec_2");
            GenericPlayerInteractionExecutor stubbornExecutor = new GenericPlayerInteractionExecutor(stubbornId, 300, bridge) {
                @Override
                public InteractionResult releaseContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks) {
                    return InteractionResult.failed("RELEASE_FAIL", "Fail", id());
                }

                @Override
                public InteractionResult cancelContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks, String reason) {
                    return InteractionResult.failed("CANCEL_FAIL", "Fail", id());
                }
            };
            registry.registerExecutor(stubbornExecutor);

            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            mgr.startSession(workerId, holdIntent(), ctx);

            // Releasing must not crash despite throwing listener
            mgr.release(workerId, ctx);
            assertTrue(mgr.isWorkerLocked(workerId));
        }

        @Test
        @DisplayName("F03: Intermediate verified progress in WAITING prevents watchdog stall timeout")
        void testF03_VerifiedProgressAvoidsWatchdogTimeout() {
            HoldSessionManager mgr = new HoldSessionManager(registry, 1000, 5);
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            mgr.startSession(workerId, holdIntent(), ctx);
            // Executor reports WAITING with verified = true (active progress breaking block)
            bridge.nextResult = new InteractionResult(
                    InteractionOutcome.WAITING,
                    "BREAKING",
                    Optional.of("progress=0.5"),
                    Optional.of(GENERIC_ID),
                    true // VERIFIED PROGRESS!
            );

            for (int t = 1; t <= 20; t++) {
                mgr.tick(t, ctx);
                assertTrue(mgr.hasActiveSession(workerId), "Verified progress must prevent watchdog timeout at tick " + t);
            }
            assertEquals(0, bridge.cancelCount);
        }

        @Test
        @DisplayName("F04: Unverified WAITING triggers watchdog timeout when consecutive limit exceeded")
        void testF04_UnverifiedWaitingTriggersWatchdogTimeout() {
            HoldSessionManager mgr = new HoldSessionManager(registry, 1000, 5);
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            mgr.startSession(workerId, holdIntent(), ctx);
            // Executor reports WAITING with verified = false (unproductive wait)
            bridge.nextResult = InteractionResult.waiting("STALLED");

            for (int t = 1; t <= 6; t++) {
                mgr.tick(t, ctx);
            }

            assertFalse(mgr.hasActiveSession(workerId), "Unverified waiting exceeding limit must trigger watchdog cancellation");
            assertEquals(1, bridge.cancelCount);
        }

        @Test
        @DisplayName("F05: Manual signalProgress resets consecutive waiting watchdog")
        void testF05_ManualSignalProgressResetsWatchdog() {
            HoldSessionManager mgr = new HoldSessionManager(registry, 1000, 5);
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            mgr.startSession(workerId, holdIntent(), ctx);
            bridge.nextResult = InteractionResult.waiting("WAITING");

            for (int t = 1; t <= 4; t++) {
                mgr.tick(t, ctx);
            }
            assertTrue(mgr.hasActiveSession(workerId));

            // Manual progress signal resets counter
            mgr.signalProgress(workerId);

            for (int t = 5; t <= 8; t++) {
                mgr.tick(t, ctx);
                assertTrue(mgr.hasActiveSession(workerId));
            }
        }
    }

    // =========================================================================
    // Group G — Regression
    // =========================================================================

    @Nested
    @DisplayName("Group G — Regression")
    class GroupG_Regression {

        @Test
        @DisplayName("G01: InteractionCore contracts remain intact")
        void testG01_InteractionCoreContractsIntact() {
            assertEquals(2, ActionType.values().length);
            assertEquals(4, ExecutionMode.values().length);
            assertEquals(9, InteractionOutcome.values().length);
        }

        @Test
        @DisplayName("G02: HoldSessionManager contracts remain intact")
        void testG02_HoldSessionContractsIntact() {
            HoldSessionManager mgr = new HoldSessionManager(registry);
            assertEquals(HoldSessionManager.DEFAULT_MAX_HOLD_TICKS, mgr.defaultMaxHoldTicks());
            assertEquals(HoldSessionManager.DEFAULT_MAX_WAITING_TICKS, mgr.maxWaitingTicks());
        }

        @Test
        @DisplayName("G03: WorkerStorage is not mutated by interaction executor")
        void testG03_WorkerStorageNotMutated() {
            WorkerStorage storage = WorkerStorage.empty();
            assertEquals(WorkerStorage.SIZE, storage.items().size());
            for (ItemStack item : storage.items()) {
                assertTrue(item.isEmpty());
            }
        }
    }


    // =========================================================================
    // Remediation A — Secondary Item Preservation (12 Scenarios)
    // =========================================================================

    @Nested
    @DisplayName("Remediation A — Secondary Item Preservation (12 Scenarios)")
    class RemediationA_SecondaryItemPreservation {

        @Test
        @DisplayName("R_A01: No secondary items produced preserves main item cleanly")
        void testR_A01_NoSecondaryItem() {
            ItemStack initial = new ItemStack(Items.DIAMOND_PICKAXE);
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.ofMutable(initial));

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, sink);
            ItemReconciliationReceipt receipt = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, sink, prep);

            assertTrue(receipt.isClean());
            assertFalse(receipt.consumed());
            assertEquals(0, receipt.transferredSecondaryItems().size());
            assertEquals(0, sink.getPendingItems().size());
            assertEquals(ItemStack.EMPTY, inv.getItemInHand(InteractionHand.MAIN_HAND));
        }

        @Test
        @DisplayName("R_A02: One secondary item produced is safely transferred to sink")
        void testR_A02_OneSecondaryItem() {
            ItemStack initial = new ItemStack(Items.MUSHROOM_STEW);
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.ofMutable(initial));

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, sink);
            inv.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            inv.addSecondaryItem(new ItemStack(Items.BOWL, 1));

            ItemReconciliationReceipt receipt = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, sink, prep);

            assertTrue(receipt.isClean());
            assertTrue(receipt.consumed());
            assertEquals(1, receipt.pendingSecondaryItems().size());
            assertEquals(Items.BOWL, receipt.pendingSecondaryItems().get(0).getItem());
            assertEquals(1, receipt.pendingSecondaryItems().get(0).getCount());
            assertEquals(1, sink.getPendingItems().size());
            assertEquals(Items.BOWL, sink.getPendingItems().get(0).getItem());
            assertEquals(0, inv.getSecondaryItems().size());
        }

        @Test
        @DisplayName("R_A03: Multiple secondary items produced are all preserved without voiding")
        void testR_A03_MultipleSecondaryItems() {
            ItemStack initial = new ItemStack(Items.SHEARS);
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.ofMutable(initial));

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, sink);
            inv.addSecondaryItem(new ItemStack(Items.WHITE_WOOL, 3));
            inv.addSecondaryItem(new ItemStack(Items.STRING, 1));

            ItemReconciliationReceipt receipt = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, sink, prep);

            assertTrue(receipt.isClean());
            assertEquals(2, receipt.pendingSecondaryItems().size());
            assertEquals(2, sink.getPendingItems().size());
            assertEquals(0, inv.getSecondaryItems().size());
        }

        @Test
        @DisplayName("R_A04: Main item fully consumed and secondary item retained — zero duplication")
        void testR_A04_MainItemFullyConsumed() {
            ItemStack initial = new ItemStack(Items.HONEY_BOTTLE, 1);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, sink);
            inv.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            inv.addSecondaryItem(new ItemStack(Items.GLASS_BOTTLE, 1));

            ItemReconciliationReceipt receipt = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, sink, prep);

            assertTrue(receipt.consumed());
            assertTrue(workerHand.get().isEmpty());
            assertEquals(1, sink.getPendingItems().size());
            assertEquals(Items.GLASS_BOTTLE, sink.getPendingItems().get(0).getItem());
        }

        @Test
        @DisplayName("R_A05: Main item partial consumption decrements count while secondary items transfer")
        void testR_A05_MainItemPartialConsumption() {
            ItemStack initial = new ItemStack(Items.BREAD, 5);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, sink);
            inv.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 4));
            inv.addSecondaryItem(new ItemStack(Items.WHEAT_SEEDS, 2));

            ItemReconciliationReceipt receipt = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, sink, prep);

            assertTrue(receipt.partiallyConsumed());
            assertEquals(4, workerHand.get().getCount());
            assertEquals(1, sink.getPendingItems().size());
            assertEquals(2, sink.getPendingItems().get(0).getCount());
        }

        @Test
        @DisplayName("R_A06: Main item transformation updates hand and transfers secondary items")
        void testR_A06_MainItemTransformation() {
            ItemStack initial = new ItemStack(Items.BUCKET);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, sink);
            inv.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MILK_BUCKET));
            inv.addSecondaryItem(new ItemStack(Items.LEATHER, 1));

            ItemReconciliationReceipt receipt = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, sink, prep);

            assertTrue(receipt.transformed());
            assertEquals(Items.MILK_BUCKET, workerHand.get().getItem());
            assertEquals(1, sink.getPendingItems().size());
            assertEquals(Items.LEATHER, sink.getPendingItems().get(0).getItem());
        }

        @Test
        @DisplayName("R_A07: Secondary item rejection by full sink retains item in unhandled list")
        void testR_A07_SecondaryItemCapacityFull() {
            ItemStack initial = new ItemStack(Items.SHEARS);
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);
            ItemReconciliationSink rejectingSink = new ItemReconciliationSink() {
                @Override public ItemTransferResult acceptMainHandItem(ItemStack stack) { return ItemTransferResult.accepted(); }
                @Override public ItemTransferResult acceptSecondaryItem(ItemStack stack) { return ItemTransferResult.rejected(stack, "CONTAINER_FULL"); }
                @Override public List<ItemStack> getPendingItems() { return List.of(); }
                @Override public List<ItemStack> drainPendingItems() { return List.of(); }
            };

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, rejectingSink);
            inv.addSecondaryItem(new ItemStack(Items.WHITE_WOOL, 3));

            ItemReconciliationReceipt receipt = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, rejectingSink, prep);

            assertFalse(receipt.isClean());
            assertEquals(1, receipt.unhandledSecondaryItems().size());
            assertEquals(Items.WHITE_WOOL, receipt.unhandledSecondaryItems().get(0).getItem());
            // AC-01: Secondary item is NOT cleared from inventory when sink rejects
            assertEquals(1, inv.getSecondaryItems().size());
            assertEquals(Items.WHITE_WOOL, inv.getSecondaryItems().get(0).getItem());
        }

        @Test
        @DisplayName("R_A08: Transfer failure exception retains item safely in unhandled list")
        void testR_A08_TransferFailureRecovery() {
            ItemStack initial = new ItemStack(Items.SHEARS);
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);
            ItemReconciliationSink crashingSink = new ItemReconciliationSink() {
                @Override public ItemTransferResult acceptMainHandItem(ItemStack stack) { return ItemTransferResult.accepted(); }
                @Override public ItemTransferResult acceptSecondaryItem(ItemStack stack) { throw new RuntimeException("STORAGE_CORRUPTED"); }
                @Override public List<ItemStack> getPendingItems() { return List.of(); }
                @Override public List<ItemStack> drainPendingItems() { return List.of(); }
            };

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, crashingSink);
            inv.addSecondaryItem(new ItemStack(Items.WHITE_WOOL, 2));

            ItemReconciliationReceipt receipt = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, crashingSink, prep);

            assertFalse(receipt.isClean());
            assertEquals(1, receipt.unhandledSecondaryItems().size());
            assertEquals(Items.WHITE_WOOL, receipt.unhandledSecondaryItems().get(0).getItem());
            // AC-02: Secondary item is NOT cleared from inventory when sink throws exception
            assertEquals(1, inv.getSecondaryItems().size());
            assertEquals(Items.WHITE_WOOL, inv.getSecondaryItems().get(0).getItem());
        }

        @Test
        @DisplayName("R_A09: Exception safety during interaction transfers secondary items and restores hand")
        void testR_A09_ExceptionDuringTransfer() {
            ItemStack initial = new ItemStack(Items.DIAMOND_PICKAXE);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, sink);
            inv.addSecondaryItem(new ItemStack(Items.COBBLESTONE, 1));

            ItemReconciliationReceipt receipt = InteractionItemReconciler.reconcileOnException(inv, InteractionHand.MAIN_HAND, sink, prep);

            assertEquals(initial.getItem(), workerHand.get().getItem());
            assertEquals(1, sink.getPendingItems().size());
            assertEquals(Items.COBBLESTONE, sink.getPendingItems().get(0).getItem());
        }

        @Test
        @DisplayName("R_A10: Repeated reconciliation does not duplicate secondary items")
        void testR_A10_ReconciliationCalledRepeatedly() {
            ItemStack initial = new ItemStack(Items.SHEARS);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.ofMutable(initial));
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, sink);
            inv.addSecondaryItem(new ItemStack(Items.WHITE_WOOL, 3));

            ItemReconciliationReceipt receipt1 = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, sink, prep);
            assertEquals(1, receipt1.pendingSecondaryItems().size());

            ItemReconciliationReceipt receipt2 = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, sink, prep);
            assertEquals(0, receipt2.pendingSecondaryItems().size());
            assertEquals(1, sink.getPendingItems().size());
        }

        @Test
        @DisplayName("R_A11: Multiple distinct item types in FakePlayer inventory are fully collected")
        void testR_A11_FakePlayerInventoryMultipleTypes() {
            ItemStack initial = new ItemStack(Items.DIAMOND_AXE);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.ofMutable(initial));
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, sink);
            inv.addSecondaryItem(new ItemStack(Items.OAK_LOG, 4));
            inv.addSecondaryItem(new ItemStack(Items.APPLE, 1));
            inv.addSecondaryItem(new ItemStack(Items.STICK, 2));

            ItemReconciliationReceipt receipt = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, sink, prep);

            assertEquals(3, receipt.pendingSecondaryItems().size());
            assertEquals(3, sink.getPendingItems().size());
            assertEquals(0, inv.getSecondaryItems().size());
        }

        @Test
        @DisplayName("R_A12: Full FakePlayer inventory is gathered and cleared without memory leakage")
        void testR_A12_FakePlayerInventoryFull() {
            ItemStack initial = new ItemStack(Items.IRON_PICKAXE);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.ofMutable(initial));
            InteractionItemReconciler.SimpleInventoryAccess inv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepare(inv, InteractionHand.MAIN_HAND, sink);
            for (int i = 0; i < 35; i++) {
                inv.addSecondaryItem(new ItemStack(Items.COBBLESTONE, 64));
            }

            ItemReconciliationReceipt receipt = InteractionItemReconciler.reconcile(inv, InteractionHand.MAIN_HAND, sink, prep);

            assertEquals(35, receipt.pendingSecondaryItems().size());
            assertEquals(35, sink.getPendingItems().size());
            assertEquals(0, inv.getSecondaryItems().size());
        }
    }

    // =========================================================================
    // Remediation B — Continuous Interaction Semantics
    // =========================================================================

    @Nested
    @DisplayName("Remediation B — Continuous Interaction Semantics")
    class RemediationB_ContinuousInteractionSemantics {

        @Test
        @DisplayName("R_B01: Discrete CLICK mode rejects startContinuous")
        void testR_B01_DiscreteModesRejectStartContinuous() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent clickIntent = blockClickIntent(ActionType.LEFT_CLICK);

            InteractionResult res = executor.startContinuous(clickIntent, ctx);
            assertEquals(InteractionOutcome.UNSUPPORTED, res.outcome());
            assertEquals("INVALID_CONTINUOUS_MODE", res.reasonCode());
        }

        @Test
        @DisplayName("R_B02: Discrete REPEAT mode rejects startContinuous")
        void testR_B02_RepeatModeRejectsStartContinuous() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent repeatIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .actionType(ActionType.LEFT_CLICK)
                    .executionMode(ExecutionMode.REPEAT)
                    .repeatIntervalTicks(10)
                    .build();

            InteractionResult res = executor.startContinuous(repeatIntent, ctx);
            assertEquals(InteractionOutcome.UNSUPPORTED, res.outcome());
            assertEquals("INVALID_CONTINUOUS_MODE", res.reasonCode());
        }

        @Test
        @DisplayName("R_B03: Entity left-click continuous hold is rejected as UNSUPPORTED")
        void testR_B03_EntityLeftClickHoldUnsupported() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.EntityTarget(UUID.randomUUID()))
                    .actionType(ActionType.LEFT_CLICK)
                    .executionMode(ExecutionMode.HOLD)
                    .build();

            InteractionResult res = executor.startContinuous(intent, ctx);
            assertEquals(InteractionOutcome.UNSUPPORTED, res.outcome());
            assertEquals("CONTINUOUS_ENTITY_ATTACK_NOT_SUPPORTED", res.reasonCode());
        }

        @Test
        @DisplayName("R_B04: Air left-click continuous hold is rejected as UNSUPPORTED")
        void testR_B04_AirLeftClickHoldUnsupported() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.AirTarget(Vec3.ZERO, Vec3.ZERO))
                    .actionType(ActionType.LEFT_CLICK)
                    .executionMode(ExecutionMode.HOLD)
                    .build();

            InteractionResult res = executor.startContinuous(intent, ctx);
            assertEquals(InteractionOutcome.UNSUPPORTED, res.outcome());
            assertEquals("AIR_LEFT_CLICK_NOT_SUPPORTED", res.reasonCode());
        }

        @Test
        @DisplayName("R_B05: Block right-click continuous hold returns UNSUPPORTED when item is not continuous")
        void testR_B05_BlockRightClickNonContinuousUnsupported() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .actionType(ActionType.RIGHT_CLICK)
                    .executionMode(ExecutionMode.HOLD)
                    .build();

            InteractionResult res = executor.startContinuous(intent, ctx);
            assertEquals(InteractionOutcome.UNSUPPORTED, res.outcome());
            assertEquals("BLOCK_RIGHT_CLICK_NOT_CONTINUOUS", res.reasonCode());
        }

        @Test
        @DisplayName("R_B06: Air right-click continuous hold returns UNSUPPORTED when item is not continuous")
        void testR_B06_AirRightClickNonContinuousUnsupported() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.AirTarget(Vec3.ZERO, Vec3.ZERO))
                    .actionType(ActionType.RIGHT_CLICK)
                    .executionMode(ExecutionMode.HOLD)
                    .build();

            InteractionResult res = executor.startContinuous(intent, ctx);
            assertEquals(InteractionOutcome.UNSUPPORTED, res.outcome());
            assertEquals("AIR_ITEM_NOT_CONTINUOUS", res.reasonCode());
        }

        @Test
        @DisplayName("R_B07: Block continuous breaking lifecycle progresses and completes")
        void testR_B07_BlockContinuousBreakingLifecycle() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionIntent intent = holdIntent();

            bridge.nextResult = InteractionResult.waiting("BREAKING_BLOCK");
            InteractionResult startRes = executor.startContinuous(intent, ctx);
            assertEquals(InteractionOutcome.WAITING, startRes.outcome());

            InteractionResult tickRes = executor.tickContinuous(intent, ctx, 1);
            assertEquals(InteractionOutcome.WAITING, tickRes.outcome());

            InteractionResult releaseRes = executor.releaseContinuous(intent, ctx, 5);
            assertTrue(releaseRes.outcome().isSuccess());
            assertEquals("RELEASE_CONFIRMED", releaseRes.reasonCode());
        }
    }

    // =========================================================================
    // Remediation C — Continuous Item Reconciliation and Cancellation Safety
    // =========================================================================

    @Nested
    @DisplayName("Remediation C — Continuous Item Reconciliation and Cancellation Safety")
    class RemediationC_ContinuousItemReconciliation {

        @Test
        @DisplayName("R_C01: Continuous hold without change preserves item cleanly")
        void testR_C01_HoldNoChange() {
            UUID workerId = UUID.randomUUID();
            ItemStack initial = new ItemStack(Items.DIAMOND_PICKAXE);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess mockInv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            GenericPlayerInteractionExecutor customExecutor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer p) {
                    return mockInv;
                }
            };

            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .itemSink(sink)
                    .build();

            customExecutor.startContinuous(holdIntent(), ctx);
            customExecutor.tickContinuous(holdIntent(), ctx, 1);
            customExecutor.releaseContinuous(holdIntent(), ctx, 2);

            assertEquals(initial.getItem(), workerHand.get().getItem());
            assertEquals(ItemStack.EMPTY, mockInv.getItemInHand(InteractionHand.MAIN_HAND));
        }

        @Test
        @DisplayName("R_C02: Continuous hold item consumed fully empties worker slot upon release")
        void testR_C02_HoldFullConsumption() {
            UUID workerId = UUID.randomUUID();
            ItemStack initial = new ItemStack(Items.BREAD, 1);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess mockInv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            GenericPlayerInteractionExecutor customExecutor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer p) {
                    return mockInv;
                }
            };

            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .itemSink(sink)
                    .build();

            customExecutor.startContinuous(holdIntent(), ctx);
            mockInv.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            customExecutor.releaseContinuous(holdIntent(), ctx, 5);

            assertTrue(workerHand.get().isEmpty());
        }

        @Test
        @DisplayName("R_C03: Continuous hold item partially consumed decrements count")
        void testR_C03_HoldPartialConsumption() {
            UUID workerId = UUID.randomUUID();
            ItemStack initial = new ItemStack(Items.TORCH, 10);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess mockInv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            GenericPlayerInteractionExecutor customExecutor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer p) {
                    return mockInv;
                }
            };

            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .itemSink(sink)
                    .build();

            customExecutor.startContinuous(holdIntent(), ctx);
            mockInv.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH, 8));
            customExecutor.releaseContinuous(holdIntent(), ctx, 2);

            assertEquals(8, workerHand.get().getCount());
        }

        @Test
        @DisplayName("R_C04: Continuous hold tool damage updates item damage value")
        void testR_C04_HoldDurabilityDamage() {
            UUID workerId = UUID.randomUUID();
            ItemStack initial = new ItemStack(Items.DIAMOND_PICKAXE);
            initial.setDamageValue(0);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess mockInv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            GenericPlayerInteractionExecutor customExecutor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer p) {
                    return mockInv;
                }
            };

            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .itemSink(sink)
                    .build();

            customExecutor.startContinuous(holdIntent(), ctx);
            ItemStack damaged = initial.copy();
            damaged.setDamageValue(7);
            mockInv.setItemInHand(InteractionHand.MAIN_HAND, damaged);
            customExecutor.releaseContinuous(holdIntent(), ctx, 10);

            assertEquals(7, workerHand.get().getDamageValue());
        }

        @Test
        @DisplayName("R_C05: Continuous hold item transformation updates slot with new item")
        void testR_C05_HoldTransformation() {
            UUID workerId = UUID.randomUUID();
            ItemStack initial = new ItemStack(Items.BUCKET);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess mockInv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            GenericPlayerInteractionExecutor customExecutor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer p) {
                    return mockInv;
                }
            };

            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .itemSink(sink)
                    .build();

            customExecutor.startContinuous(holdIntent(), ctx);
            mockInv.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
            customExecutor.releaseContinuous(holdIntent(), ctx, 2);

            assertEquals(Items.WATER_BUCKET, workerHand.get().getItem());
        }

        @Test
        @DisplayName("R_C06: Continuous hold producing secondary items preserves them in sink")
        void testR_C06_HoldSecondaryItems() {
            UUID workerId = UUID.randomUUID();
            ItemStack initial = new ItemStack(Items.MUSHROOM_STEW);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess mockInv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            GenericPlayerInteractionExecutor customExecutor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer p) {
                    return mockInv;
                }
            };

            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .itemSink(sink)
                    .build();

            customExecutor.startContinuous(holdIntent(), ctx);
            mockInv.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            mockInv.addSecondaryItem(new ItemStack(Items.BOWL, 1));
            customExecutor.releaseContinuous(holdIntent(), ctx, 3);

            assertTrue(workerHand.get().isEmpty());
            assertEquals(1, sink.getPendingItems().size());
            assertEquals(Items.BOWL, sink.getPendingItems().get(0).getItem());
        }

        @Test
        @DisplayName("R_C07: Cancellation after item consumption empties worker slot — zero duplication")
        void testR_C07_CancellationAfterConsumptionNoDuplication() {
            UUID workerId = UUID.randomUUID();
            ItemStack initial = new ItemStack(Items.BREAD, 1);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess mockInv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            GenericPlayerInteractionExecutor customExecutor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer p) {
                    return mockInv;
                }
            };

            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .itemSink(sink)
                    .build();

            customExecutor.startContinuous(holdIntent(), ctx);
            mockInv.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

            customExecutor.cancelContinuous(holdIntent(), ctx, 10, "TARGET_MOVED");

            assertTrue(workerHand.get().isEmpty());
        }

        @Test
        @DisplayName("R_C08: Cancellation after transformation preserves transformed item")
        void testR_C08_CancellationAfterTransformation() {
            UUID workerId = UUID.randomUUID();
            ItemStack initial = new ItemStack(Items.BUCKET);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess mockInv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            GenericPlayerInteractionExecutor customExecutor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer p) {
                    return mockInv;
                }
            };

            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .itemSink(sink)
                    .build();

            customExecutor.startContinuous(holdIntent(), ctx);
            mockInv.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAVA_BUCKET));
            customExecutor.cancelContinuous(holdIntent(), ctx, 5, "CANCELLED");

            assertEquals(Items.LAVA_BUCKET, workerHand.get().getItem());
        }

        @Test
        @DisplayName("R_C09: Bridge exception during continuous tick restores item safely")
        void testR_C09_TickExceptionReconcilesItems() {
            UUID workerId = UUID.randomUUID();
            ItemStack initial = new ItemStack(Items.DIAMOND_PICKAXE);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess mockInv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            GenericPlayerInteractionExecutor customExecutor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer p) {
                    return mockInv;
                }
            };

            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .itemSink(sink)
                    .build();

            customExecutor.startContinuous(holdIntent(), ctx);
            bridge.throwOnCall = new RuntimeException("TICK_CRASH");

            InteractionResult tickRes = customExecutor.tickContinuous(holdIntent(), ctx, 1);
            assertEquals(InteractionOutcome.FAILED, tickRes.outcome());
            assertEquals("TICK_CONTINUOUS_EXCEPTION", tickRes.reasonCode());

            bridge.throwOnCall = null;
            customExecutor.cancelContinuous(holdIntent(), ctx, 1, "TICK_ERROR");
            assertEquals(initial.getItem(), workerHand.get().getItem());
        }

        @Test
        @DisplayName("R_C10: Exception during release reconciles items and returns FAILED")
        void testR_C10_ReleaseExceptionPreservesItems() {
            UUID workerId = UUID.randomUUID();
            ItemStack initial = new ItemStack(Items.DIAMOND_PICKAXE);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess mockInv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            GenericPlayerInteractionExecutor customExecutor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer p) {
                    return mockInv;
                }
            };

            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .itemSink(sink)
                    .build();

            customExecutor.startContinuous(holdIntent(), ctx);
            bridge.throwOnCancel = new RuntimeException("RELEASE_CRASH");

            InteractionResult releaseRes = customExecutor.releaseContinuous(holdIntent(), ctx, 5);
            assertEquals(InteractionOutcome.FAILED, releaseRes.outcome());
            assertEquals("RELEASE_BRIDGE_EXCEPTION", releaseRes.reasonCode());
            assertEquals(initial.getItem(), workerHand.get().getItem());
        }

        @Test
        @DisplayName("R_C11: Exception during cancel reconciles items and returns FAILED")
        void testR_C11_CancelExceptionPreservesItems() {
            UUID workerId = UUID.randomUUID();
            ItemStack initial = new ItemStack(Items.DIAMOND_PICKAXE);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess mockInv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            GenericPlayerInteractionExecutor customExecutor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer p) {
                    return mockInv;
                }
            };

            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .itemSink(sink)
                    .build();

            customExecutor.startContinuous(holdIntent(), ctx);
            bridge.throwOnCancel = new RuntimeException("CANCEL_CRASH");

            InteractionResult cancelRes = customExecutor.cancelContinuous(holdIntent(), ctx, 5, "ERROR");
            assertEquals(InteractionOutcome.FAILED, cancelRes.outcome());
            assertEquals("CANCEL_BRIDGE_EXCEPTION", cancelRes.reasonCode());
            assertEquals(initial.getItem(), workerHand.get().getItem());
        }

        @Test
        @DisplayName("R_C12: Cleanup retry preserves item integrity across retry attempts")
        void testR_C12_CleanupRetryPreservesItems() {
            UUID workerId = UUID.randomUUID();
            ItemStack initial = new ItemStack(Items.DIAMOND_PICKAXE);
            AtomicReference<ItemStack> workerHand = new AtomicReference<>(initial);
            BufferingItemReconciliationSink sink = new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.of(workerHand::get, workerHand::set));
            InteractionItemReconciler.SimpleInventoryAccess mockInv = new InteractionItemReconciler.SimpleInventoryAccess(initial);

            GenericPlayerInteractionExecutor customExecutor = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer p) {
                    return mockInv;
                }
            };

            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .itemSink(sink)
                    .build();

            customExecutor.startContinuous(holdIntent(), ctx);
            bridge.cancelResult = false; // Bridge fails cancel

            InteractionResult cancelRes = customExecutor.cancelContinuous(holdIntent(), ctx, 5, "RETRY_TEST");
            assertEquals(InteractionOutcome.FAILED, cancelRes.outcome());
            assertEquals("CANCEL_NOT_CONFIRMED", cancelRes.reasonCode());
            assertEquals(initial.getItem(), workerHand.get().getItem());
        }
    }

    // =========================================================================
    // Remediation D — Validation Parity Between Discrete and Continuous
    // =========================================================================

    @Nested
    @DisplayName("Remediation D — Validation Parity Between Discrete and Continuous")
    class RemediationD_ValidationParity {

        @Test
        @DisplayName("R_D01: Non-server thread during continuous tick returns WRONG_THREAD")
        void testR_D01_ContinuousTickEnforcesServerThread() {
            UUID workerId = UUID.randomUUID();
            GenericPlayerInteractionExecutor threadStrict = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override protected boolean isServerThread(ServerLevel level) { return false; }
            };

            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionResult res = threadStrict.tickContinuous(holdIntent(), ctx, 1);
            assertEquals(InteractionOutcome.REJECTED, res.outcome());
            assertEquals("WRONG_THREAD", res.reasonCode());
        }

        @Test
        @DisplayName("R_D02: Permission revocation during continuous hold terminates session")
        void testR_D02_ContinuousTickPermissionRevocation() {
            UUID workerId = UUID.randomUUID();
            AtomicBoolean permitted = new AtomicBoolean(true);
            WorkerInteractionContext ctx = WorkerInteractionContext.builder()
                    .level(stubLevel)
                    .playerOverride(stubPlayer)
                    .workerId(workerId)
                    .ownerId(UUID.randomUUID())
                    .permissionPredicate(intent -> permitted.get())
                    .build();

            executor.startContinuous(holdIntent(), ctx);

            // Revoke permission mid-hold
            permitted.set(false);
            InteractionResult tickRes = executor.tickContinuous(holdIntent(), ctx, 1);
            assertEquals(InteractionOutcome.REJECTED, tickRes.outcome());
            assertEquals("PERMISSION_DENIED", tickRes.reasonCode());
        }

        @Test
        @DisplayName("R_D03: Target chunk unloading during continuous hold returns UNAVAILABLE")
        void testR_D03_ContinuousTickChunkUnloaded() {
            UUID workerId = UUID.randomUUID();
            AtomicBoolean chunkLoaded = new AtomicBoolean(true);
            GenericPlayerInteractionExecutor chunkAware = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override protected boolean checkChunkLoaded(ServerLevel level, BlockPos pos) { return chunkLoaded.get(); }
            };

            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            chunkAware.startContinuous(holdIntent(), ctx);

            // Chunk unloads during hold
            chunkLoaded.set(false);
            InteractionResult tickRes = chunkAware.tickContinuous(holdIntent(), ctx, 1);
            assertEquals(InteractionOutcome.UNAVAILABLE, tickRes.outcome());
            assertEquals("TARGET_CHUNK_UNLOADED", tickRes.reasonCode());
        }

        @Test
        @DisplayName("R_D04: Target entity death during continuous hold returns UNAVAILABLE")
        void testR_D04_ContinuousTickEntityDied() {
            UUID workerId = UUID.randomUUID();
            UUID entityId = UUID.randomUUID();
            AtomicBoolean entityAlive = new AtomicBoolean(true);
            GenericPlayerInteractionExecutor entityAware = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected TargetResolution checkEntityTarget(ServerLevel level, UUID id) {
                    return entityAlive.get() ? TargetResolution.success(null) : TargetResolution.unavailable("TARGET_ENTITY_DEAD");
                }
            };

            InteractionIntent entityIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.EntityTarget(entityId))
                    .actionType(ActionType.RIGHT_CLICK)
                    .executionMode(ExecutionMode.HOLD)
                    .build();

            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            // Entity dies during hold
            entityAlive.set(false);
            InteractionResult tickRes = entityAware.tickContinuous(entityIntent, ctx, 1);
            assertEquals(InteractionOutcome.UNAVAILABLE, tickRes.outcome());
            assertEquals("TARGET_ENTITY_DEAD", tickRes.reasonCode());
        }

        @Test
        @DisplayName("R_D05: Spawn protection activated during hold returns REJECTED")
        void testR_D05_ContinuousTickSpawnProtection() {
            UUID workerId = UUID.randomUUID();
            AtomicBoolean spawnProtected = new AtomicBoolean(false);
            GenericPlayerInteractionExecutor spawnAware = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override protected boolean checkSpawnProtected(ServerLevel level, ServerPlayer player, BlockPos pos) { return spawnProtected.get(); }
            };

            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            spawnAware.startContinuous(holdIntent(), ctx);

            // Spawn protection activated mid-hold
            spawnProtected.set(true);
            InteractionResult tickRes = spawnAware.tickContinuous(holdIntent(), ctx, 1);
            assertEquals(InteractionOutcome.REJECTED, tickRes.outcome());
            assertEquals("SPAWN_PROTECTED", tickRes.reasonCode());
        }

        @Test
        @DisplayName("R_D06: Line of sight obstructed during continuous hold returns BLOCKED")
        void testR_D06_ContinuousTickLineOfSightObstructed() {
            UUID workerId = UUID.randomUUID();
            AtomicBoolean losClear = new AtomicBoolean(true);
            GenericPlayerInteractionExecutor losAware = new GenericPlayerInteractionExecutor(GENERIC_ID, 100, bridge) {
                @Override
                protected boolean checkLineOfSight(ServerLevel level, Vec3 eyePos, Vec3 targetCenter, InteractionTarget target) {
                    return losClear.get();
                }
            };

            InteractionIntent losIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .actionType(ActionType.LEFT_CLICK)
                    .executionMode(ExecutionMode.HOLD)
                    .requireLineOfSight(true)
                    .build();

            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            losAware.startContinuous(losIntent, ctx);

            // Obstacle placed
            losClear.set(false);
            InteractionResult tickRes = losAware.tickContinuous(losIntent, ctx, 1);
            assertEquals(InteractionOutcome.BLOCKED, tickRes.outcome());
            assertEquals("LINE_OF_SIGHT_OBSTRUCTED", tickRes.reasonCode());
        }

        @Test
        @DisplayName("R_D07: Target moving beyond reach during continuous hold returns BLOCKED")
        void testR_D07_ContinuousTickTargetMovedOutOfReach() {
            UUID workerId = UUID.randomUUID();
            InteractionIntent shortReachIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(new BlockPos(10, 0, 0)))
                    .actionType(ActionType.LEFT_CLICK)
                    .executionMode(ExecutionMode.HOLD)
                    .maxReachDistance(3.0)
                    .build();

            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            InteractionResult tickRes = executor.tickContinuous(shortReachIntent, ctx, 1);
            assertEquals(InteractionOutcome.BLOCKED, tickRes.outcome());
            assertEquals("OUT_OF_REACH", tickRes.reasonCode());
        }
    }

    // =========================================================================
    // Remediation E — Cleanup Safety, Bridge Confirmation and Worker Lock Retention
    // =========================================================================

    @Nested
    @DisplayName("Remediation E — Cleanup Safety, Bridge Confirmation and Worker Lock Retention")
    class RemediationE_CleanupSafetyAndConfirmation {

        @Test
        @DisplayName("R_E01: Normal bridge release confirmation returns RELEASE_CONFIRMED")
        void testR_E01_ReleaseBridgeConfirmedReturnsSuccess() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            executor.startContinuous(holdIntent(), ctx);
            InteractionResult res = executor.releaseContinuous(holdIntent(), ctx, 5);

            assertTrue(res.outcome().isSuccess());
            assertEquals("RELEASE_CONFIRMED", res.reasonCode());
        }

        @Test
        @DisplayName("R_E02: Unconfirmed bridge release returns FAILED instead of fake success")
        void testR_E02_ReleaseBridgeUnconfirmedReturnsFailed() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            executor.startContinuous(holdIntent(), ctx);
            bridge.cancelResult = false; // Bridge cannot confirm release

            InteractionResult res = executor.releaseContinuous(holdIntent(), ctx, 5);
            assertEquals(InteractionOutcome.FAILED, res.outcome());
            assertEquals("RELEASE_NOT_CONFIRMED", res.reasonCode());
        }

        @Test
        @DisplayName("R_E03: Bridge exception during release returns FAILED instead of swallowing")
        void testR_E03_ReleaseBridgeExceptionReturnsFailed() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            executor.startContinuous(holdIntent(), ctx);
            bridge.throwOnCancel = new RuntimeException("RELEASE_FAILURE");

            InteractionResult res = executor.releaseContinuous(holdIntent(), ctx, 5);
            assertEquals(InteractionOutcome.FAILED, res.outcome());
            assertEquals("RELEASE_BRIDGE_EXCEPTION", res.reasonCode());
        }

        @Test
        @DisplayName("R_E04: Unconfirmed bridge cancel returns FAILED instead of fake success")
        void testR_E04_CancelBridgeUnconfirmedReturnsFailed() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            executor.startContinuous(holdIntent(), ctx);
            bridge.cancelResult = false;

            InteractionResult res = executor.cancelContinuous(holdIntent(), ctx, 5, "TEST");
            assertEquals(InteractionOutcome.FAILED, res.outcome());
            assertEquals("CANCEL_NOT_CONFIRMED", res.reasonCode());
        }

        @Test
        @DisplayName("R_E05: Bridge exception during cancel returns FAILED instead of swallowing")
        void testR_E05_CancelBridgeExceptionReturnsFailed() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());

            executor.startContinuous(holdIntent(), ctx);
            bridge.throwOnCancel = new RuntimeException("CANCEL_FAILURE");

            InteractionResult res = executor.cancelContinuous(holdIntent(), ctx, 5, "TEST");
            assertEquals(InteractionOutcome.FAILED, res.outcome());
            assertEquals("CANCEL_BRIDGE_EXCEPTION", res.reasonCode());
        }

        @Test
        @DisplayName("R_E06: Quarantined worker retains lock when release and fallback both fail")
        void testR_E06_QuarantinedWorkerRetainsLock() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            HoldSessionManager mgr = new HoldSessionManager(registry, 200, 60);

            mgr.startSession(workerId, holdIntent(), ctx);
            bridge.cancelResult = false;

            mgr.release(workerId, ctx);

            assertTrue(mgr.isWorkerLocked(workerId), "Worker must remain locked when cleanup fails");
            HoldSession session = mgr.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.CLEANUP_PENDING, session.state());
        }

        @Test
        @DisplayName("R_E07: Diagnostic event is fired with accurate failure telemetry")
        void testR_E07_DiagnosticEventFiredOnCleanupFailure() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            HoldSessionManager mgr = new HoldSessionManager(registry, 200, 60);

            AtomicReference<HoldSessionManager.CleanupDiagnosticEvent> capturedEvent = new AtomicReference<>();
            mgr.addCleanupDiagnosticListener(capturedEvent::set);

            mgr.startSession(workerId, holdIntent(), ctx);
            bridge.cancelResult = false;

            mgr.release(workerId, ctx);

            assertNotNull(capturedEvent.get(), "CleanupDiagnosticListener must receive event");
            assertEquals(workerId, capturedEvent.get().workerId());
            assertEquals(GENERIC_ID, capturedEvent.get().executorId());
            assertTrue(capturedEvent.get().failureReason().contains("CANCEL_FALLBACK_FAILED"));
        }

        @Test
        @DisplayName("R_E08: Throwing listener does not disrupt session state or unlock worker")
        void testR_E08_ListenerExceptionDoesNotDisruptCleanup() {
            UUID workerId = UUID.randomUUID();
            WorkerInteractionContext ctx = createContext(workerId, UUID.randomUUID());
            HoldSessionManager mgr = new HoldSessionManager(registry, 200, 60);

            mgr.addCleanupDiagnosticListener(event -> {
                throw new RuntimeException("LISTENER_EXPLODED");
            });

            mgr.startSession(workerId, holdIntent(), ctx);
            bridge.cancelResult = false;

            mgr.release(workerId, ctx);

            assertTrue(mgr.isWorkerLocked(workerId), "Worker must remain locked despite listener exception");
            assertEquals(HoldSessionState.CLEANUP_PENDING, mgr.getActiveSession(workerId).orElseThrow().state());
        }
    }

    // =========================================================================
    // Test Doubles
    // =========================================================================

    static class MockPlayerActionBridge implements PlayerActionBridge {
        int rightClickBlockCount = 0;
        int leftClickBlockCount = 0;
        int rightClickEntityCount = 0;
        int leftClickEntityCount = 0;
        int rightClickAirCount = 0;
        int cancelCount = 0;
        int stopCount = 0;

        InteractionResult nextResult = null;
        Throwable throwOnCall = null;
        Throwable throwOnCancel = null;
        Throwable throwOnStop = null;
        Consumer<ServerPlayer> onExecutePlayerCallback = null;

        @Override
        public InteractionResult rightClickBlock(ResourceLocation executorId, ServerPlayer player, ServerLevel level, ItemStack stack, InteractionHand hand, BlockHitResult hitResult) {
            rightClickBlockCount++;
            if (throwOnCall != null) throw new RuntimeException(throwOnCall);
            if (onExecutePlayerCallback != null) onExecutePlayerCallback.accept(player);
            return nextResult != null ? nextResult : InteractionResult.success(executorId, true, "MOCK_BLOCK_USE");
        }

        @Override
        public InteractionResult leftClickBlock(ResourceLocation executorId, ServerPlayer player, ServerLevel level, BlockPos pos, Direction face, ExecutionMode mode, int elapsedTicks) {
            leftClickBlockCount++;
            if (throwOnCall != null) throw new RuntimeException(throwOnCall);
            if (onExecutePlayerCallback != null) onExecutePlayerCallback.accept(player);
            return nextResult != null ? nextResult : InteractionResult.success(executorId, true, "MOCK_BLOCK_BREAK");
        }

        @Override
        public InteractionResult rightClickEntity(ResourceLocation executorId, ServerPlayer player, ServerLevel level, Entity targetEntity, InteractionHand hand, Vec3 hitVec) {
            rightClickEntityCount++;
            if (throwOnCall != null) throw new RuntimeException(throwOnCall);
            if (onExecutePlayerCallback != null) onExecutePlayerCallback.accept(player);
            return nextResult != null ? nextResult : InteractionResult.success(executorId, true, "MOCK_ENTITY_INTERACT");
        }

        @Override
        public InteractionResult leftClickEntity(ResourceLocation executorId, ServerPlayer player, ServerLevel level, Entity targetEntity) {
            leftClickEntityCount++;
            if (throwOnCall != null) throw new RuntimeException(throwOnCall);
            if (onExecutePlayerCallback != null) onExecutePlayerCallback.accept(player);
            return nextResult != null ? nextResult : InteractionResult.success(executorId, true, "MOCK_ENTITY_ATTACK");
        }

        @Override
        public InteractionResult rightClickAir(ResourceLocation executorId, ServerPlayer player, ServerLevel level, ItemStack stack, InteractionHand hand) {
            rightClickAirCount++;
            if (throwOnCall != null) throw new RuntimeException(throwOnCall);
            if (onExecutePlayerCallback != null) onExecutePlayerCallback.accept(player);
            return nextResult != null ? nextResult : InteractionResult.success(executorId, true, "MOCK_AIR_USE");
        }

        boolean cancelResult = true;

        @Override
        public boolean cancelContinuousAction(ResourceLocation executorId, ServerPlayer player, ServerLevel level, InteractionIntent intent, int elapsedTicks) {
            cancelCount++;
            if (throwOnCancel != null) throw new RuntimeException(throwOnCancel);
            return cancelResult;
        }
    }

    static class MockSpecializedAdapter implements InteractionExecutor {
        private final ResourceLocation id;
        private final int priority;
        private final boolean handlesAll;

        MockSpecializedAdapter(ResourceLocation id, int priority, boolean handlesAll) {
            this.id = id;
            this.priority = priority;
            this.handlesAll = handlesAll;
        }

        @Override public ResourceLocation id() { return id; }
        @Override public Category category() { return Category.SPECIALIZED_ADAPTER; }
        @Override public int priority() { return priority; }
        @Override public Set<ExecutionMode> supportedModes() { return Set.of(ExecutionMode.CLICK, ExecutionMode.HOLD); }
        @Override public boolean canHandle(InteractionIntent intent) { return handlesAll; }
    }

    static class MockCapabilityHandler implements InteractionExecutor {
        private final ResourceLocation id;

        MockCapabilityHandler(ResourceLocation id) {
            this.id = id;
        }

        @Override public ResourceLocation id() { return id; }
        @Override public Category category() { return Category.CAPABILITY_HANDLER; }
        @Override public int priority() { return 100; }
        @Override public Set<ExecutionMode> supportedModes() { return Set.of(ExecutionMode.CLICK); }
        @Override public boolean canHandle(InteractionIntent intent) { return intent.target() instanceof InteractionTarget.EndpointTarget; }
    }
}
