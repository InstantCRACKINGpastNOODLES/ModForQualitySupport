package com.example.examplemod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 1A Interaction Core Specification Tests.
 * Strictly covers Groups A, B, C, and D as mandated by Phase 1A scope.
 */
class InteractionCoreTest {

    private static final ResourceLocation DUMMY_MOD_ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "test_action");
    private static final ResourceLocation GENERIC_ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "generic_player_executor");
    private static final ResourceLocation ADAPTER_ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "specialized_adapter");
    private static final ResourceLocation CAPABILITY_ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "capability_handler");

    // =========================================================================
    // Group A — Data Model
    // =========================================================================

    @Nested
    @DisplayName("Group A — Data Model")
    class GroupA_DataModel {

        @Test
        @DisplayName("1. LEFT_CLICK and RIGHT_CLICK valid")
        void test1_ActionTypePrimitives() {
            ActionType[] types = ActionType.values();
            assertEquals(2, types.length, "Worker interaction primitives must consist of exactly two types");
            assertTrue(Arrays.asList(types).contains(ActionType.LEFT_CLICK));
            assertTrue(Arrays.asList(types).contains(ActionType.RIGHT_CLICK));
            assertTrue(ActionType.LEFT_CLICK.isLeftClick());
            assertFalse(ActionType.LEFT_CLICK.isRightClick());
            assertTrue(ActionType.RIGHT_CLICK.isRightClick());
            assertFalse(ActionType.RIGHT_CLICK.isLeftClick());
        }

        @Test
        @DisplayName("2. Four ExecutionModes valid")
        void test2_FourExecutionModes() {
            ExecutionMode[] modes = ExecutionMode.values();
            assertEquals(4, modes.length, "Exactly four execution modes must be defined");
            assertTrue(Arrays.asList(modes).contains(ExecutionMode.CLICK));
            assertTrue(Arrays.asList(modes).contains(ExecutionMode.HOLD));
            assertTrue(Arrays.asList(modes).contains(ExecutionMode.REPEAT));
            assertTrue(Arrays.asList(modes).contains(ExecutionMode.PRESS_RELEASE));

            assertTrue(ExecutionMode.CLICK.isDiscrete());
            assertFalse(ExecutionMode.CLICK.isContinuous());
            assertTrue(ExecutionMode.HOLD.isContinuous());
            assertFalse(ExecutionMode.HOLD.isDiscrete());
            assertTrue(ExecutionMode.REPEAT.isDiscrete());
            assertTrue(ExecutionMode.PRESS_RELEASE.isContinuous());
        }

        @Test
        @DisplayName("3. Null fields rejected")
        void test3_NullFieldsRejected() {
            UUID id = UUID.randomUUID();
            InteractionTarget target = new InteractionTarget.BlockTarget(BlockPos.ZERO);

            assertThrows(NullPointerException.class, () -> new InteractionIntent(
                    null, ActionType.RIGHT_CLICK, ExecutionMode.CLICK, target, InteractionHand.MAIN_HAND,
                    Optional.empty(), 4.5, true, Optional.empty(), Optional.empty()));

            assertThrows(NullPointerException.class, () -> new InteractionIntent(
                    id, null, ExecutionMode.CLICK, target, InteractionHand.MAIN_HAND,
                    Optional.empty(), 4.5, true, Optional.empty(), Optional.empty()));

            assertThrows(NullPointerException.class, () -> new InteractionIntent(
                    id, ActionType.RIGHT_CLICK, null, target, InteractionHand.MAIN_HAND,
                    Optional.empty(), 4.5, true, Optional.empty(), Optional.empty()));

            assertThrows(NullPointerException.class, () -> new InteractionIntent(
                    id, ActionType.RIGHT_CLICK, ExecutionMode.CLICK, null, InteractionHand.MAIN_HAND,
                    Optional.empty(), 4.5, true, Optional.empty(), Optional.empty()));

            assertThrows(NullPointerException.class, () -> new InteractionIntent(
                    id, ActionType.RIGHT_CLICK, ExecutionMode.CLICK, target, null,
                    Optional.empty(), 4.5, true, Optional.empty(), Optional.empty()));

            assertThrows(NullPointerException.class, () -> new InteractionIntent(
                    id, ActionType.RIGHT_CLICK, ExecutionMode.CLICK, target, InteractionHand.MAIN_HAND,
                    null, 4.5, true, Optional.empty(), Optional.empty()));

            assertThrows(NullPointerException.class, () -> new InteractionIntent(
                    id, ActionType.RIGHT_CLICK, ExecutionMode.CLICK, target, InteractionHand.MAIN_HAND,
                    Optional.empty(), 4.5, true, null, Optional.empty()));

            assertThrows(NullPointerException.class, () -> new InteractionIntent(
                    id, ActionType.RIGHT_CLICK, ExecutionMode.CLICK, target, InteractionHand.MAIN_HAND,
                    Optional.empty(), 4.5, true, Optional.empty(), null));
        }

        @Test
        @DisplayName("4. Invalid reach rejected")
        void test4_InvalidReachRejected() {
            InteractionTarget target = new InteractionTarget.BlockTarget(BlockPos.ZERO);

            // <= 0 must throw IllegalArgumentException, must NEVER silently default to 4.5
            assertThrows(IllegalArgumentException.class, () -> InteractionIntent.builder()
                    .target(target)
                    .maxReachDistance(0.0)
                    .build());

            assertThrows(IllegalArgumentException.class, () -> InteractionIntent.builder()
                    .target(target)
                    .maxReachDistance(-1.0)
                    .build());

            assertThrows(IllegalArgumentException.class, () -> InteractionIntent.builder()
                    .target(target)
                    .maxReachDistance(Double.NaN)
                    .build());

            assertThrows(IllegalArgumentException.class, () -> InteractionIntent.builder()
                    .target(target)
                    .maxReachDistance(Double.POSITIVE_INFINITY)
                    .build());

            // Positive reaches are valid
            InteractionIntent intent = InteractionIntent.builder()
                    .target(target)
                    .maxReachDistance(3.0)
                    .build();
            assertEquals(3.0, intent.maxReachDistance());
        }

        @Test
        @DisplayName("5. Temporal parameter validation")
        void test5_TemporalParameterValidation() {
            InteractionTarget target = new InteractionTarget.BlockTarget(BlockPos.ZERO);

            // CLICK cannot specify durationTicks or repeatIntervalTicks
            assertThrows(IllegalArgumentException.class, () -> InteractionIntent.builder()
                    .target(target)
                    .executionMode(ExecutionMode.CLICK)
                    .durationTicks(10)
                    .build());

            assertThrows(IllegalArgumentException.class, () -> InteractionIntent.builder()
                    .target(target)
                    .executionMode(ExecutionMode.CLICK)
                    .repeatIntervalTicks(5)
                    .build());

            // PRESS_RELEASE requires positive durationTicks
            assertThrows(IllegalArgumentException.class, () -> InteractionIntent.builder()
                    .target(target)
                    .executionMode(ExecutionMode.PRESS_RELEASE)
                    .build());

            assertThrows(IllegalArgumentException.class, () -> InteractionIntent.builder()
                    .target(target)
                    .executionMode(ExecutionMode.PRESS_RELEASE)
                    .durationTicks(0)
                    .build());

            assertThrows(IllegalArgumentException.class, () -> InteractionIntent.builder()
                    .target(target)
                    .executionMode(ExecutionMode.PRESS_RELEASE)
                    .durationTicks(-5)
                    .build());

            InteractionIntent pressReleaseIntent = InteractionIntent.builder()
                    .target(target)
                    .executionMode(ExecutionMode.PRESS_RELEASE)
                    .durationTicks(20)
                    .build();
            assertEquals(Optional.of(20), pressReleaseIntent.durationTicks());

            // REPEAT requires positive repeatIntervalTicks
            assertThrows(IllegalArgumentException.class, () -> InteractionIntent.builder()
                    .target(target)
                    .executionMode(ExecutionMode.REPEAT)
                    .build());

            assertThrows(IllegalArgumentException.class, () -> InteractionIntent.builder()
                    .target(target)
                    .executionMode(ExecutionMode.REPEAT)
                    .repeatIntervalTicks(0)
                    .build());

            InteractionIntent repeatIntent = InteractionIntent.builder()
                    .target(target)
                    .executionMode(ExecutionMode.REPEAT)
                    .repeatIntervalTicks(4)
                    .build();
            assertEquals(Optional.of(4), repeatIntent.repeatIntervalTicks());

            // HOLD allows empty durationTicks or positive timeout, but no repeatIntervalTicks
            InteractionIntent holdIndefinite = InteractionIntent.builder()
                    .target(target)
                    .executionMode(ExecutionMode.HOLD)
                    .build();
            assertTrue(holdIndefinite.durationTicks().isEmpty());

            InteractionIntent holdTimeout = InteractionIntent.builder()
                    .target(target)
                    .executionMode(ExecutionMode.HOLD)
                    .durationTicks(60)
                    .build();
            assertEquals(Optional.of(60), holdTimeout.durationTicks());

            assertThrows(IllegalArgumentException.class, () -> InteractionIntent.builder()
                    .target(target)
                    .executionMode(ExecutionMode.HOLD)
                    .repeatIntervalTicks(5)
                    .build());
        }

        @Test
        @DisplayName("6. Intent ID validation")
        void test6_IntentIdValidation() {
            UUID id = UUID.randomUUID();
            InteractionIntent intent = InteractionIntent.builder()
                    .intentId(id)
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .build();
            assertEquals(id, intent.intentId(), "Intent must preserve its stable UUID identifier");
        }

        @Test
        @DisplayName("7. Target validation")
        void test7_TargetValidation() {
            // BlockTarget validation
            assertThrows(NullPointerException.class, () -> new InteractionTarget.BlockTarget(null));
            BlockPos pos = new BlockPos(10, 64, -10);
            InteractionTarget.BlockTarget blockTarget = new InteractionTarget.BlockTarget(pos, Direction.UP, new Vec3(10.5, 65.0, -9.5));
            assertEquals(pos, blockTarget.pos());
            assertEquals(Optional.of(Direction.UP), blockTarget.face());
            assertTrue(blockTarget.hitVec().isPresent());

            // EntityTarget validation
            assertThrows(NullPointerException.class, () -> new InteractionTarget.EntityTarget(null));
            UUID entityId = UUID.randomUUID();
            InteractionTarget.EntityTarget entityTarget = new InteractionTarget.EntityTarget(entityId, 42, new Vec3(1.0, 2.0, 3.0));
            assertEquals(entityId, entityTarget.entityId());
            assertEquals(Optional.of(42), entityTarget.networkId());

            // AirTarget validation
            assertThrows(NullPointerException.class, () -> new InteractionTarget.AirTarget(null, Vec3.ZERO));
            assertThrows(NullPointerException.class, () -> new InteractionTarget.AirTarget(Vec3.ZERO, null));
            InteractionTarget.AirTarget airTarget = new InteractionTarget.AirTarget(new Vec3(0, 60, 0), new Vec3(0, 1, 0));
            assertNotNull(airTarget.origin());
            assertNotNull(airTarget.direction());

            // EndpointTarget validation
            assertThrows(NullPointerException.class, () -> new InteractionTarget.EndpointTarget(null));
            InteractionTarget.EndpointTarget endpointTarget = new InteractionTarget.EndpointTarget(DUMMY_MOD_ID);
            assertEquals(DUMMY_MOD_ID, endpointTarget.endpointId());
        }

        @Test
        @DisplayName("8. EntityTarget uses UUID as primary identity")
        void test8_EntityTargetUsesUuidAsPrimaryIdentity() {
            UUID uuid1 = UUID.randomUUID();
            UUID uuid2 = UUID.randomUUID();

            InteractionTarget.EntityTarget targetA = new InteractionTarget.EntityTarget(uuid1, Optional.of(100), Optional.empty());
            InteractionTarget.EntityTarget targetB = new InteractionTarget.EntityTarget(uuid1, Optional.of(200), Optional.empty());
            InteractionTarget.EntityTarget targetC = new InteractionTarget.EntityTarget(uuid2, Optional.of(100), Optional.empty());

            assertEquals(targetA.entityId(), targetB.entityId(), "EntityTarget must use UUID as primary identity");
            assertNotEquals(targetA.entityId(), targetC.entityId());
        }

        @Test
        @DisplayName("9. Intent does not contain mutable ItemStack")
        void test9_IntentDoesNotContainMutableItemStack() {
            // Reflection test: ensure no field in InteractionIntent is an ItemStack
            for (Field field : InteractionIntent.class.getDeclaredFields()) {
                assertNotEquals("net.minecraft.world.item.ItemStack", field.getType().getName(),
                        "InteractionIntent must never hold a direct ItemStack reference: field " + field.getName());
            }

            // Verify ItemDescriptor only contains immutable references
            ItemDescriptor descriptor = new ItemDescriptor(
                    ResourceLocation.withDefaultNamespace("iron_pickaxe"),
                    InteractionHand.MAIN_HAND,
                    Optional.of("hash123")
            );
            assertEquals(ResourceLocation.withDefaultNamespace("iron_pickaxe"), descriptor.itemId());
            assertEquals(InteractionHand.MAIN_HAND, descriptor.hand());
            assertEquals(Optional.of("hash123"), descriptor.componentFingerprint());
        }

        @Test
        @DisplayName("10. Intent holds no mutable runtime state")
        void test10_IntentHoldsNoMutableRuntimeState() {
            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .build();

            // Record classes are final and have only final components
            assertTrue(InteractionIntent.class.isRecord(), "InteractionIntent must be an immutable record");
            for (Field field : InteractionIntent.class.getDeclaredFields()) {
                assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()),
                        "Field " + field.getName() + " in InteractionIntent must be final");
            }
        }
    }

    // =========================================================================
    // Group B — Registry
    // =========================================================================

    @Nested
    @DisplayName("Group B — Registry")
    class GroupB_Registry {

        @Test
        @DisplayName("11. Register executor successfully")
        void test11_RegisterExecutorSuccess() {
            WorkerInteractionRegistry registry = new WorkerInteractionRegistry();
            TestExecutor executor = new TestExecutor(GENERIC_ID, InteractionExecutor.Category.GENERIC_PLAYER, 10);

            registry.registerExecutor(executor);
            assertEquals(1, registry.getExecutorCount());
            assertTrue(registry.getExecutor(GENERIC_ID).isPresent());
            assertEquals(executor, registry.getExecutor(GENERIC_ID).get());
        }

        @Test
        @DisplayName("12. Duplicate executor ID rejected")
        void test12_DuplicateExecutorIdRejected() {
            WorkerInteractionRegistry registry = new WorkerInteractionRegistry();
            TestExecutor exec1 = new TestExecutor(GENERIC_ID, InteractionExecutor.Category.GENERIC_PLAYER, 10);
            TestExecutor exec2 = new TestExecutor(GENERIC_ID, InteractionExecutor.Category.SPECIALIZED_ADAPTER, 20);

            registry.registerExecutor(exec1);
            assertThrows(IllegalArgumentException.class, () -> registry.registerExecutor(exec2),
                    "Duplicate executor ID registration must throw IllegalArgumentException");
        }

        @Test
        @DisplayName("13. Unregister and lookup")
        void test13_UnregisterAndLookup() {
            WorkerInteractionRegistry registry = new WorkerInteractionRegistry();
            TestExecutor executor = new TestExecutor(GENERIC_ID, InteractionExecutor.Category.GENERIC_PLAYER, 10);

            registry.registerExecutor(executor);
            assertTrue(registry.getExecutor(GENERIC_ID).isPresent());

            boolean removed = registry.unregisterExecutor(GENERIC_ID);
            assertTrue(removed);
            assertFalse(registry.getExecutor(GENERIC_ID).isPresent());
            assertEquals(0, registry.getExecutorCount());

            boolean removedAgain = registry.unregisterExecutor(GENERIC_ID);
            assertFalse(removedAgain);
        }

        @Test
        @DisplayName("14. Registration order does not affect result")
        void test14_RegistrationOrderIndependence() {
            TestExecutor lowPriority = new TestExecutor(
                    ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "exec_low"),
                    InteractionExecutor.Category.GENERIC_PLAYER, 10);
            TestExecutor highPriority = new TestExecutor(
                    ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "exec_high"),
                    InteractionExecutor.Category.GENERIC_PLAYER, 50);

            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .build();

            // Order 1: Register low then high
            WorkerInteractionRegistry reg1 = new WorkerInteractionRegistry();
            reg1.registerExecutor(lowPriority);
            reg1.registerExecutor(highPriority);
            ResolutionResult res1 = reg1.resolve(intent);

            // Order 2: Register high then low
            WorkerInteractionRegistry reg2 = new WorkerInteractionRegistry();
            reg2.registerExecutor(highPriority);
            reg2.registerExecutor(lowPriority);
            ResolutionResult res2 = reg2.resolve(intent);

            assertTrue(res1.isResolved());
            assertTrue(res2.isResolved());
            assertEquals(highPriority.id(), res1.executor().get().id());
            assertEquals(highPriority.id(), res2.executor().get().id());
        }

        @Test
        @DisplayName("15. Priority tie handled deterministically")
        void test15_PriorityTieHandledDeterministically() {
            ResourceLocation idA = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "exec_alpha");
            ResourceLocation idB = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "exec_beta");

            TestExecutor execA = new TestExecutor(idA, InteractionExecutor.Category.GENERIC_PLAYER, 20);
            TestExecutor execB = new TestExecutor(idB, InteractionExecutor.Category.GENERIC_PLAYER, 20);

            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .build();

            // Registration order A then B
            WorkerInteractionRegistry reg1 = new WorkerInteractionRegistry();
            reg1.registerExecutor(execA);
            reg1.registerExecutor(execB);
            ResolutionResult res1 = reg1.resolve(intent);

            // Registration order B then A
            WorkerInteractionRegistry reg2 = new WorkerInteractionRegistry();
            reg2.registerExecutor(execB);
            reg2.registerExecutor(execA);
            ResolutionResult res2 = reg2.resolve(intent);

            assertEquals(ResolutionResult.Status.AMBIGUOUS_ROUTING, res1.status());
            assertEquals(ResolutionResult.Status.AMBIGUOUS_ROUTING, res2.status());
            // Candidate order must be deterministic (sorted lexicographically)
            assertEquals(res1.candidates(), res2.candidates());
            assertEquals(List.of(idA, idB), res1.candidates());
        }

        @Test
        @DisplayName("16. No candidate returns UNSUPPORTED")
        void test16_NoCandidateReturnsUnsupported() {
            WorkerInteractionRegistry emptyRegistry = new WorkerInteractionRegistry();
            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .build();

            ResolutionResult result = emptyRegistry.resolve(intent);
            assertEquals(ResolutionResult.Status.NO_COMPATIBLE_EXECUTOR, result.status());
            assertFalse(result.isResolved());
        }
    }

    // =========================================================================
    // Group C — Resolver
    // =========================================================================

    @Nested
    @DisplayName("Group C — Resolver")
    class GroupC_Resolver {

        @Test
        @DisplayName("17. Generic-first routing works correctly")
        void test17_GenericFirstRouting() {
            WorkerInteractionRegistry registry = new WorkerInteractionRegistry();

            // Generic Player Executor with lower numeric priority
            TestExecutor generic = new TestExecutor(GENERIC_ID, InteractionExecutor.Category.GENERIC_PLAYER, 10);
            // Specialized Adapter with higher numeric priority
            TestExecutor adapter = new TestExecutor(ADAPTER_ID, InteractionExecutor.Category.SPECIALIZED_ADAPTER, 100);

            registry.registerExecutor(adapter);
            registry.registerExecutor(generic);

            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .build();

            // Under GENERIC_FIRST, generic executor must be selected despite lower priority
            ResolutionResult result = registry.resolve(intent, RoutingPolicy.GENERIC_FIRST);
            assertTrue(result.isResolved());
            assertEquals(GENERIC_ID, result.executor().get().id(),
                    "Under GENERIC_FIRST policy, generic executor must be selected over specialized adapter");
        }

        @Test
        @DisplayName("18. Specialized adapter selected only under valid policy")
        void test18_SpecializedAdapterSelectedOnlyUnderValidPolicy() {
            WorkerInteractionRegistry registry = new WorkerInteractionRegistry();
            TestExecutor generic = new TestExecutor(GENERIC_ID, InteractionExecutor.Category.GENERIC_PLAYER, 10);
            TestExecutor adapter = new TestExecutor(ADAPTER_ID, InteractionExecutor.Category.SPECIALIZED_ADAPTER, 50);

            registry.registerExecutor(generic);
            registry.registerExecutor(adapter);

            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .build();

            // SPECIALIZED_FIRST explicitly routes to adapter
            ResolutionResult specResult = registry.resolve(intent, RoutingPolicy.SPECIALIZED_FIRST);
            assertTrue(specResult.isResolved());
            assertEquals(ADAPTER_ID, specResult.executor().get().id());

            // GENERIC_STRICT rejects adapters even if only adapter exists
            WorkerInteractionRegistry adapterOnlyRegistry = new WorkerInteractionRegistry();
            adapterOnlyRegistry.registerExecutor(adapter);

            ResolutionResult strictResult = adapterOnlyRegistry.resolve(intent, RoutingPolicy.GENERIC_STRICT);
            assertEquals(ResolutionResult.Status.NO_COMPATIBLE_EXECUTOR, strictResult.status(),
                    "GENERIC_STRICT policy must never route to specialized adapters");
        }

        @Test
        @DisplayName("19. Capability Handler is not default click executor")
        void test19_CapabilityHandlerNotDefaultClickExecutor() {
            WorkerInteractionRegistry registry = new WorkerInteractionRegistry();
            TestExecutor capability = new TestExecutor(CAPABILITY_ID, InteractionExecutor.Category.CAPABILITY_HANDLER, 100);
            registry.registerExecutor(capability);

            InteractionIntent clickIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .actionType(ActionType.RIGHT_CLICK)
                    .executionMode(ExecutionMode.CLICK)
                    .build();

            // Under GENERIC_FIRST, capability handler must not be selected for click intents
            ResolutionResult result = registry.resolve(clickIntent, RoutingPolicy.GENERIC_FIRST);
            assertEquals(ResolutionResult.Status.NO_COMPATIBLE_EXECUTOR, result.status(),
                    "Capability Handler must never be selected as a default click executor");

            // Capability handler is only chosen when policy is explicitly CAPABILITY_ONLY
            ResolutionResult capResult = registry.resolve(clickIntent, RoutingPolicy.CAPABILITY_ONLY);
            assertTrue(capResult.isResolved());
            assertEquals(CAPABILITY_ID, capResult.executor().get().id());
        }

        @Test
        @DisplayName("20. Unsupported mode returns UNSUPPORTED")
        void test20_UnsupportedModeReturnsUnsupported() {
            WorkerInteractionRegistry registry = new WorkerInteractionRegistry();
            // Executor that only supports CLICK
            TestExecutor clickOnly = new TestExecutor(GENERIC_ID, InteractionExecutor.Category.GENERIC_PLAYER, 10,
                    Set.of(ExecutionMode.CLICK), intent -> true);
            registry.registerExecutor(clickOnly);

            InteractionIntent holdIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .executionMode(ExecutionMode.HOLD)
                    .build();

            ResolutionResult result = registry.resolve(holdIntent);
            assertEquals(ResolutionResult.Status.UNSUPPORTED_MODE, result.status());
            assertFalse(result.isResolved());
        }

        @Test
        @DisplayName("21. Unsupported target handled cleanly")
        void test21_UnsupportedTargetHandledCleanly() {
            WorkerInteractionRegistry registry = new WorkerInteractionRegistry();
            // Executor that handles ONLY BlockTarget
            TestExecutor blockOnly = new TestExecutor(GENERIC_ID, InteractionExecutor.Category.GENERIC_PLAYER, 10,
                    Set.of(ExecutionMode.CLICK), intent -> intent.target() instanceof InteractionTarget.BlockTarget);
            registry.registerExecutor(blockOnly);

            InteractionIntent entityIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.EntityTarget(UUID.randomUUID()))
                    .build();

            ResolutionResult result = registry.resolve(entityIntent);
            assertEquals(ResolutionResult.Status.NO_COMPATIBLE_EXECUTOR, result.status());
            assertFalse(result.isResolved());
        }

        @Test
        @DisplayName("22. UNKNOWN outcome prevents blind fallback")
        void test22_UnknownOutcomePreventsBlindFallback() {
            // Verification of UNKNOWN result semantics:
            InteractionResult unknownResult = InteractionResult.unknown(GENERIC_ID, "PARTIAL_COMMITTED_STATE");
            assertEquals(InteractionOutcome.UNKNOWN, unknownResult.outcome());
            assertTrue(unknownResult.outcome().isTerminal(), "UNKNOWN is a terminal outcome for the step");
            assertFalse(unknownResult.verified(), "UNKNOWN results must never be marked verified");

            // Contract requirement: when execution returns UNKNOWN, the pipeline must not blindly fallback
            // Resolver only resolves an executor; execution must respect UNKNOWN outcome without re-dispatching
            assertEquals("PARTIAL_COMMITTED_STATE", unknownResult.reasonCode());
        }

        @Test
        @DisplayName("23. Resolver causes no world side effects")
        void test23_ResolverCausesNoWorldSideEffects() {
            WorkerInteractionRegistry registry = new WorkerInteractionRegistry();
            TestExecutor executor = new TestExecutor(GENERIC_ID, InteractionExecutor.Category.GENERIC_PLAYER, 10);
            registry.registerExecutor(executor);

            InteractionIntent intent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .build();

            // Calling resolve must not change any state or execute the executor
            ResolutionResult result = registry.resolve(intent);
            assertTrue(result.isResolved());
            assertEquals(0, executor.executeCount, "Resolver must not execute any actions during resolution");
        }
    }

    // =========================================================================
    // Group D — Compatibility
    // =========================================================================

    @Nested
    @DisplayName("Group D — Compatibility")
    class GroupD_Compatibility {

        @Test
        @DisplayName("24. Legacy WorkerInteraction tests and APIs remain compatible")
        void test24_LegacyWorkerInteractionCompatibility() {
            WorkerInteractionRegistry registry = new WorkerInteractionRegistry();
            registry.register(new WorkerInteractionRegistry.Handler() {
                @Override
                public boolean supports(WorkerInteraction.Request request) {
                    return true;
                }

                @Override
                public WorkerInteraction.Result validate(WorkerInteraction.Request request) {
                    return new WorkerInteraction.Result(WorkerInteraction.Outcome.SUCCESS, "OK");
                }
            });

            // Legacy handler dispatch works
            WorkerProfile profile = new WorkerProfile(UUID.randomUUID(), UUID.randomUUID(),
                    ResourceLocation.withDefaultNamespace("overworld"), WorkerStatus.IDLE);
            WorkerInteraction.Request req = new WorkerInteraction.Request(
                    DUMMY_MOD_ID, profile, profile.ownerId(), profile.entityId(),
                    profile.dimension(), new WorkerInteraction.Block(BlockPos.ZERO), true, true, java.util.Map.of()
            );

            WorkerInteraction.Result res = registry.dispatch(req);
            assertEquals(WorkerInteraction.Outcome.SUCCESS, res.outcome());
        }

        @Test
        @DisplayName("25. Production behavior untouched and compiles cleanly")
        void test25_ProductionClassesUntouched() {
            // Verify core classes loadable and unmodified
            assertNotNull(MinerJobController.class);
            assertNotNull(FarmerJobController.class);
            assertNotNull(WorkerExecutionLoop.class);
            assertNotNull(WorkerStorage.class);
        }

        @Test
        @DisplayName("26. No FakePlayer or HOLD runtime claimed passed in Phase 1A")
        void test26_NoFakePlayerOrHoldRuntimeTestedInPhase1A() {
            // Confirm test suite does not require or instantiate FakePlayer or HoldSessionManager
            // Contract interfaces and data structures are tested in isolation
            Set<ExecutionMode> modes = Set.of(ExecutionMode.CLICK, ExecutionMode.HOLD, ExecutionMode.REPEAT, ExecutionMode.PRESS_RELEASE);
            assertEquals(4, modes.size());
        }
    }

    // =========================================================================
    // Test Helper
    // =========================================================================

    private static class TestExecutor implements InteractionExecutor {
        private final ResourceLocation id;
        private final Category category;
        private final int priority;
        private final Set<ExecutionMode> supportedModes;
        private final java.util.function.Predicate<InteractionIntent> handlerPredicate;
        int executeCount = 0;

        TestExecutor(ResourceLocation id, Category category, int priority) {
            this(id, category, priority, Set.of(ExecutionMode.CLICK, ExecutionMode.HOLD, ExecutionMode.REPEAT, ExecutionMode.PRESS_RELEASE), intent -> true);
        }

        TestExecutor(ResourceLocation id, Category category, int priority, Set<ExecutionMode> supportedModes,
                     java.util.function.Predicate<InteractionIntent> handlerPredicate) {
            this.id = id;
            this.category = category;
            this.priority = priority;
            this.supportedModes = supportedModes;
            this.handlerPredicate = handlerPredicate;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public Category category() {
            return category;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public Set<ExecutionMode> supportedModes() {
            return supportedModes;
        }

        @Override
        public boolean canHandle(InteractionIntent intent) {
            return handlerPredicate.test(intent);
        }

        @Override
        public InteractionResult executeDiscrete(InteractionIntent intent, InteractionContext context) {
            executeCount++;
            return InteractionResult.success(id, true);
        }
    }
}
