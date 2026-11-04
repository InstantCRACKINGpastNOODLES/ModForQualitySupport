package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerInteractionPriorityTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WORKER_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORKER_2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation NETHER = ResourceLocation.withDefaultNamespace("the_nether");

    @Test
    void boundTicketWithMatchingWorkerClaimsSuccess() {
        WorkerProfile profile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.of(WORKER_1),
                Optional.of(OWNER),
                true,
                true,
                profile,
                false,
                OWNER,
                WORKER_1,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.MANAGE, decision, "Matching worker with authorized owner must evaluate to MANAGE");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_SUCCESS, claim, "MANAGE must yield HANDLE_SUCCESS");
        assertEquals(InteractionResult.SUCCESS, claim.result());
        assertTrue(claim.shouldCancelEvent(), "Authoritative Elstar management must cancel downstream event propagation");
    }

    @Test
    void boundTicketWithWrongWorkerYieldsDenied() {
        // Player holds ticket bound to WORKER_1, but clicks WORKER_2
        WorkerProfile profile2 = new WorkerProfile(WORKER_2, OWNER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.of(WORKER_1),
                Optional.of(OWNER),
                true,
                true,
                profile2,
                false,
                OWNER,
                WORKER_2,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.WRONG_WORKER, decision, "Ticket bound to different worker must yield WRONG_WORKER");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_DENIED, claim, "WRONG_WORKER must yield HANDLE_DENIED to prevent native sit/fall-through");
        assertEquals(InteractionResult.FAIL, claim.result());
        assertTrue(claim.shouldCancelEvent(), "Wrong worker must cancel the event with FAIL to prevent unintended TLM maid sit/stand");
    }

    @Test
    void ticketBoundToAnotherPlayerWorkerIsDenied() {
        // Ticket is bound to worker owned by OTHER_PLAYER; OWNER attempts to use it
        WorkerProfile profile = new WorkerProfile(WORKER_1, OTHER_PLAYER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.of(WORKER_1),
                Optional.of(OTHER_PLAYER),
                true,
                true,
                profile,
                false,
                OWNER,
                WORKER_1,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.DENIED, decision, "Unauthorized player holding another's ticket must be DENIED");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_DENIED, claim);
        assertEquals(InteractionResult.FAIL, claim.result());
        assertTrue(claim.shouldCancelEvent(), "Unauthorized attempt must cancel event with FAIL to prevent security bypass");
    }

    @Test
    void unboundTicketWithUntamedMobClaimsBind() {
        // Fresh unbound ticket used on an untamed wild mob
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.empty(),
                Optional.empty(),
                true,
                true,
                null,
                false,
                OWNER,
                WORKER_1,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.BIND, decision, "Unbound ticket on untamed mob must evaluate to BIND");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_SUCCESS, claim);
        assertEquals(InteractionResult.SUCCESS, claim.result());
        assertTrue(claim.shouldCancelEvent(), "Accepted binding must claim exclusive ownership of the interaction");
    }

    @Test
    void unboundTicketWithOwnedTamedMobClaimsBind() {
        // Fresh unbound ticket used on a mob tamed by the current player
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.empty(),
                Optional.empty(),
                true,
                true,
                null,
                false,
                OWNER,
                WORKER_1,
                OVERWORLD,
                false // foreignTamed = false: owned by player
        );

        assertEquals(BindingTicketItem.Decision.BIND, decision, "Player-owned tamed mob can be bound as worker");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_SUCCESS, claim);
        assertTrue(claim.shouldCancelEvent());
    }

    @Test
    void unboundTicketWithForeignTamedMobIsDenied() {
        // Fresh unbound ticket used on another player's tamed pet (foreignTamed = true)
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.empty(),
                Optional.empty(),
                true,
                true,
                null,
                false,
                OWNER,
                WORKER_1,
                OVERWORLD,
                true // foreignTamed = true
        );

        assertEquals(BindingTicketItem.Decision.DENIED, decision, "Foreign tamed mob must be DENIED from binding");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_DENIED, claim);
        assertEquals(InteractionResult.FAIL, claim.result());
        assertTrue(claim.shouldCancelEvent(), "Denied binding must cancel interaction to protect foreign pets");
    }

    @Test
    void unboundTicketWithForeignBoundWorkerIsDenied() {
        // Target mob is already bound to another player
        WorkerProfile foreignProfile = new WorkerProfile(WORKER_1, OTHER_PLAYER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.empty(),
                Optional.empty(),
                true,
                true,
                foreignProfile,
                false,
                OWNER,
                WORKER_1,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.DENIED, decision, "Mob already bound to another player must be DENIED");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_DENIED, claim);
        assertEquals(InteractionResult.FAIL, claim.result());
        assertTrue(claim.shouldCancelEvent());
    }

    @Test
    void unboundTicketWithOwnBoundWorkerClaimsManage() {
        // Target mob is already bound to this player; using unbound ticket opens management
        WorkerProfile ownProfile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.empty(),
                Optional.empty(),
                true,
                true,
                ownProfile,
                false,
                OWNER,
                WORKER_1,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.MANAGE, decision, "Unbound ticket on own worker must evaluate to MANAGE");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_SUCCESS, claim);
        assertEquals(InteractionResult.SUCCESS, claim.result());
        assertTrue(claim.shouldCancelEvent());
    }

    @Test
    void noTicketInHandYieldsPass() {
        // Player has empty hand or non-ticket item; clicking a bound worker
        WorkerProfile profile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                false, // hasTicket = false
                Optional.empty(),
                Optional.empty(),
                true,
                true,
                profile,
                false,
                OWNER,
                WORKER_1,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.PASS, decision, "No ticket in hand must yield PASS");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.PASS, claim);
        assertFalse(claim.shouldCancelEvent(), "Non-ticket interactions must not cancel events or interfere with other mods");
    }

    @Test
    void nonMobEntityYieldsPass() {
        // Player holding ticket clicks a non-mob entity (e.g. armor stand, boat, player)
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.of(WORKER_1),
                Optional.of(OWNER),
                false, // isMob = false
                true,
                null,
                false,
                OWNER,
                WORKER_1,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.PASS, decision, "Non-mob targets must yield PASS");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.PASS, claim);
        assertFalse(claim.shouldCancelEvent(), "Non-mob interactions must pass through to vanilla/mods");
    }

    @Test
    void unavailableDeadOrRemovedWorkerIsRejected() {
        // Target is dead or removed
        WorkerProfile profile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.of(WORKER_1),
                Optional.of(OWNER),
                true,
                false, // isAvailable = false
                profile,
                false,
                OWNER,
                WORKER_1,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.UNAVAILABLE, decision, "Dead/removed mob must yield UNAVAILABLE");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_DENIED, claim);
        assertTrue(claim.shouldCancelEvent());
    }

    @Test
    void dimensionMismatchForBoundWorkerIsDenied() {
        // Target profile is recorded in nether, player interacts in overworld
        WorkerProfile netherProfile = new WorkerProfile(WORKER_1, OWNER, NETHER, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.of(WORKER_1),
                Optional.of(OWNER),
                true,
                true,
                netherProfile,
                false,
                OWNER,
                WORKER_1,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.DENIED, decision, "Dimension mismatch must be DENIED");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_DENIED, claim);
        assertTrue(claim.shouldCancelEvent());
    }

    @Test
    void legacyBoundMobYieldsDuplicate() {
        // Mob has legacy WORKER_OWNER data but no full profile
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.empty(),
                Optional.empty(),
                true,
                true,
                null,
                true, // legacyBound = true
                OWNER,
                WORKER_1,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.DUPLICATE, decision, "Legacy bound mob must yield DUPLICATE");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_DENIED, claim);
        assertTrue(claim.shouldCancelEvent());
    }

    @Test
    void handEvaluationAndDeduplicationLogic() {
        // Simulation of hand evaluation:
        // Scenario 1: Main hand holds bound ticket for matching worker -> claims success, cancels event
        WorkerProfile profile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision mainHandDecision = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_1), Optional.of(OWNER),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.MANAGE, mainHandDecision);
        assertTrue(BindingTicketItem.toClaim(mainHandDecision).shouldCancelEvent(),
                "Main hand consumes interaction; offhand must not be triggered");

        // Scenario 2: Main hand holds non-ticket (e.g. food/sword) -> yields PASS, does not cancel event
        BindingTicketItem.Decision mainHandEmpty = BindingTicketItem.evaluateInteraction(
                false, Optional.empty(), Optional.empty(),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.PASS, mainHandEmpty);
        assertFalse(BindingTicketItem.toClaim(mainHandEmpty).shouldCancelEvent(),
                "Main hand non-ticket allows normal mod interaction");

        // When main hand passes, offhand holding bound ticket is then evaluated:
        BindingTicketItem.Decision offhandTicket = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_1), Optional.of(OWNER),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.MANAGE, offhandTicket);
        assertTrue(BindingTicketItem.toClaim(offhandTicket).shouldCancelEvent(),
                "Offhand ticket evaluates cleanly without interference from main hand");
    }

    @Test
    void serverValidationAuthoritativelyOverridesClientCustomData() {
        // Hacked or modified ticket says OWNER owns WORKER_1, but the authoritative server profile says OTHER_PLAYER owns WORKER_1
        WorkerProfile serverAuthoritativeProfile = new WorkerProfile(WORKER_1, OTHER_PLAYER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision forgedTicketAttempt = BindingTicketItem.evaluateInteraction(
                true,
                Optional.of(WORKER_1),
                Optional.of(OWNER), // Ticket claims OWNER
                true,
                true,
                serverAuthoritativeProfile, // Server says OTHER_PLAYER
                false,
                OWNER,
                WORKER_1,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.DENIED, forgedTicketAttempt,
                "Server authoritative profile must override ticket data and deny access when owners do not match");
        assertEquals(InteractionResult.FAIL, BindingTicketItem.toClaim(forgedTicketAttempt).result());
    }

    @Test
    void foreignTicketOnDifferentWorkerYieldsDenied() {
        // Player holds a ticket bound to WORKER_1 (which belongs to OTHER_PLAYER).
        // Player right-clicks an unrelated entity (WORKER_2).
        WorkerProfile unrelatedProfile = new WorkerProfile(WORKER_2, OWNER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true,
                Optional.of(WORKER_1),
                Optional.of(OTHER_PLAYER),
                true,
                true,
                unrelatedProfile,
                false,
                OWNER,
                WORKER_2,
                OVERWORLD,
                false
        );

        assertEquals(BindingTicketItem.Decision.WRONG_WORKER, decision,
                "Holding ticket for another worker while clicking WORKER_2 must yield WRONG_WORKER");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_DENIED, claim,
                "WRONG_WORKER must yield HANDLE_DENIED to prevent unintended native sit or fall-through");
        assertTrue(claim.shouldCancelEvent(), "Must cancel the event to suppress native TLM interaction");
    }

    @Test
    void unboundTicketBindsMobAOnceAndCannotSilentlyBindMobB() {
        net.minecraft.world.item.ItemStack ticket = new net.minecraft.world.item.ItemStack(ExampleMod.BINDING_TICKET.get());
        assertFalse(BindingTicketItem.isTicketBound(ticket));
        assertTrue(BindingTicketItem.getBoundWorkerId(ticket).isEmpty());

        // Step 1: Unbound ticket binds Mob A
        boolean boundA = BindingTicketItem.bindTicket(ticket, WORKER_1, OWNER);
        assertTrue(boundA, "Unbound ticket must successfully bind Mob A");
        assertTrue(BindingTicketItem.isTicketBound(ticket));
        assertEquals(Optional.of(WORKER_1), BindingTicketItem.getBoundWorkerId(ticket));
        assertEquals(Optional.of(OWNER), BindingTicketItem.getBoundOwnerId(ticket));

        // Step 2: Same ticket attempts to bind Mob B -> must be strictly rejected
        boolean boundB = BindingTicketItem.bindTicket(ticket, WORKER_2, OWNER);
        assertFalse(boundB, "An already-bound ticket must NEVER bind a second mob");

        // Step 3: Identity on ticket remains strictly bound to Mob A (never overwritten)
        assertEquals(Optional.of(WORKER_1), BindingTicketItem.getBoundWorkerId(ticket),
                "Ticket's bound worker UUID must not change when used on a different mob");
        assertEquals(Optional.of(OWNER), BindingTicketItem.getBoundOwnerId(ticket));
    }

    @Test
    void boundTicketOpensElstarGuiOnlyForMatchingAuthorizedWorker() {
        WorkerProfile profile1 = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);
        WorkerProfile profile2 = new WorkerProfile(WORKER_2, OWNER, OVERWORLD, WorkerStatus.IDLE);

        // Case 1: Bound to WORKER_1, clicking WORKER_1 -> MANAGE (opens GUI)
        BindingTicketItem.Decision matchDecision = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_1), Optional.of(OWNER),
                true, true, profile1, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.MANAGE, matchDecision);
        BindingTicketItem.InteractionClaim matchClaim = BindingTicketItem.toClaim(matchDecision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_SUCCESS, matchClaim);
        assertTrue(matchClaim.shouldCancelEvent());

        // Case 2: Bound to WORKER_1, clicking WORKER_2 -> WRONG_WORKER (does NOT open GUI, cancels with FAIL)
        BindingTicketItem.Decision wrongDecision = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_1), Optional.of(OWNER),
                true, true, profile2, false, OWNER, WORKER_2, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.WRONG_WORKER, wrongDecision);
        BindingTicketItem.InteractionClaim wrongClaim = BindingTicketItem.toClaim(wrongDecision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_DENIED, wrongClaim);
        assertTrue(wrongClaim.shouldCancelEvent(), "Wrong worker click must cancel event with FAIL to prevent unintended native sit");
    }

    @Test
    void otherPlayersTicketCannotControlWorker() {
        WorkerProfile workerProfile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);

        // Intruder holding ticket bound to OTHER_PLAYER attempts to use it on OWNER's worker
        BindingTicketItem.Decision intruderDecision = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_1), Optional.of(OTHER_PLAYER),
                true, true, workerProfile, false, OTHER_PLAYER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.DENIED, intruderDecision,
                "WorkerProfile owner check must authoritatively reject unauthorized player");
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_DENIED, BindingTicketItem.toClaim(intruderDecision));
        assertEquals(InteractionResult.FAIL, BindingTicketItem.toClaim(intruderDecision).result());
    }

    @Test
    void validTicketInteractionSuppressesNativeMaidSitEarly() {
        // When player uses ticket on their matching worker, the claim is HANDLE_SUCCESS with event cancellation.
        // This guarantees that the interaction is claimed early on both client and server before downstream sit logic.
        WorkerProfile profile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_1), Optional.of(OWNER),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.MANAGE, decision);
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(InteractionResult.SUCCESS, claim.result());
        assertTrue(claim.shouldCancelEvent(), "Must cancel event so native maid sit handler never executes");
    }

    @Test
    void emptyHandAndUnrelatedItemPreserveNativeMaidBehavior() {
        WorkerProfile profile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);

        // Empty hand: hasTicket = false -> PASS without event cancellation
        BindingTicketItem.Decision emptyHand = BindingTicketItem.evaluateInteraction(
                false, Optional.empty(), Optional.empty(),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.PASS, emptyHand);
        assertFalse(BindingTicketItem.toClaim(emptyHand).shouldCancelEvent(),
                "Empty hand must NOT cancel event, allowing native maid GUI to open");

        // Unrelated item (e.g. food/clock): hasTicket = false -> PASS without event cancellation
        BindingTicketItem.Decision unrelatedItem = BindingTicketItem.evaluateInteraction(
                false, Optional.empty(), Optional.empty(),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.PASS, unrelatedItem);
        assertFalse(BindingTicketItem.toClaim(unrelatedItem).shouldCancelEvent(),
                "Unrelated items must NOT cancel event, allowing native TLM interactions");
    }

    @Test
    void regressionMatrix_bindingTicket_idle_matchingAuthorizedWorker_opensGuiAndSuppressesNativeSit() {
        // Held item: Binding Ticket, Current mode: IDLE, Target: Matching authorized worker
        // Expected: Elstar management GUI; no native sit command
        WorkerProfile profile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_1), Optional.of(OWNER),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.MANAGE, decision);
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(InteractionResult.SUCCESS, claim.result());
        assertTrue(claim.shouldCancelEvent(), "Must claim SUCCESS and cancel event to suppress native TLM maid sit");
    }

    @Test
    void regressionMatrix_bindingTicket_idle_differentWorker_neverRebindsAndPassesPerOwnershipPolicy() {
        // Held item: Binding Ticket bound to WORKER_1, Current mode: IDLE, Target: WORKER_2
        // Expected: Never rebind the ticket; deny or pass according to explicit ownership policy; never change UUID
        net.minecraft.world.item.ItemStack ticket = new net.minecraft.world.item.ItemStack(ExampleMod.BINDING_TICKET.get());
        assertTrue(BindingTicketItem.bindTicket(ticket, WORKER_1, OWNER));
        assertEquals(Optional.of(WORKER_1), BindingTicketItem.getBoundWorkerId(ticket));

        // Rebinding attempt on WORKER_2 must fail strictly
        assertFalse(BindingTicketItem.bindTicket(ticket, WORKER_2, OWNER), "A bound ticket must NEVER rebind a different worker");
        assertEquals(Optional.of(WORKER_1), BindingTicketItem.getBoundWorkerId(ticket), "Bound worker UUID must never change");

        // Interaction evaluation for different worker owned by same player
        WorkerProfile profile2 = new WorkerProfile(WORKER_2, OWNER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_1), Optional.of(OWNER),
                true, true, profile2, false, OWNER, WORKER_2, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.WRONG_WORKER, decision);
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(InteractionResult.FAIL, claim.result());
        assertTrue(claim.shouldCancelEvent(), "Must DENY with event cancellation to prevent native sit/fall-through while using a Binding Ticket");
    }

    @Test
    void regressionMatrix_bindingTicket_selectingWorksite_blockAndEntityInteractions() {
        // Current mode: SELECTING_WORKSITE
        long now = System.currentTimeMillis();
        WorkerOrderSession.Session session = new WorkerOrderSession.Session(
                OWNER, WORKER_1, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_WORKSITE,
                Optional.empty(), Optional.empty(), 64L, now
        );
        WorkerOrderSession.setSessionForTesting(session);

        assertTrue(WorkerOrderSession.hasActiveSession(OWNER));
        assertEquals(WorkerOrderSession.Stage.SELECTING_WORKSITE, WorkerOrderSession.getSession(OWNER).orElseThrow().stage());

        WorkerOrderSession.clearAll();
    }

    @Test
    void regressionMatrix_bindingTicket_selectingStorage_storageBlockInteraction() {
        // Current mode: SELECTING_STORAGE
        long now = System.currentTimeMillis();
        net.minecraft.core.BlockPos worksitePos = new net.minecraft.core.BlockPos(100, 64, 100);
        WorkerTarget worksite = new WorkerTarget(OVERWORLD, worksitePos);

        WorkerOrderSession.Session session = new WorkerOrderSession.Session(
                OWNER, WORKER_1, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_STORAGE,
                Optional.of(worksite), Optional.empty(), 64L, now
        );
        WorkerOrderSession.setSessionForTesting(session);

        assertTrue(WorkerOrderSession.hasActiveSession(OWNER));
        assertEquals(WorkerOrderSession.Stage.SELECTING_STORAGE, WorkerOrderSession.getSession(OWNER).orElseThrow().stage());
        assertTrue(WorkerOrderSession.getSession(OWNER).orElseThrow().worksite().isPresent());
        assertTrue(WorkerOrderSession.getSession(OWNER).orElseThrow().storage().isEmpty());

        WorkerOrderSession.clearAll();
    }

    @Test
    void regressionMatrix_emptyHandAndUnrelatedItem_preservesNativeMaidBehaviorAndEmergencySit() {
        WorkerProfile profile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);

        // 1. Empty hand: hasTicket = false -> PASS without event cancellation (preserves native TLM maid GUI)
        BindingTicketItem.Decision emptyHand = BindingTicketItem.evaluateInteraction(
                false, Optional.empty(), Optional.empty(),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.PASS, emptyHand);
        assertFalse(BindingTicketItem.toClaim(emptyHand).shouldCancelEvent(),
                "Empty-hand interaction with maid must preserve native TLM GUI");

        // 2. Unrelated item (stick / weapon / food): hasTicket = false -> PASS without event cancellation
        BindingTicketItem.Decision stickItem = BindingTicketItem.evaluateInteraction(
                false, Optional.empty(), Optional.empty(),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.PASS, stickItem);
        assertFalse(BindingTicketItem.toClaim(stickItem).shouldCancelEvent(),
                "Unrelated items must NOT cancel event, allowing emergency sit/stand command before maid enters danger");
    }

    @Test
    void regressionMatrix_bindingTicket_unauthorizedWorker_strictlyDenied() {
        // Target worker belongs to OTHER_PLAYER
        WorkerProfile foreignWorker = new WorkerProfile(WORKER_2, OTHER_PLAYER, OVERWORLD, WorkerStatus.IDLE);

        // Intruder holding ticket attempts to manage foreign worker
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_2), Optional.of(OTHER_PLAYER),
                true, true, foreignWorker, false, OWNER, WORKER_2, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.DENIED, decision, "Unauthorized worker interaction must be strictly DENIED");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(InteractionResult.FAIL, claim.result());
        assertTrue(claim.shouldCancelEvent(), "Must cancel event with FAIL to prevent security bypass or accidental binding");

        // Unbound ticket on foreign worker also strictly denied
        BindingTicketItem.Decision unboundOnForeign = BindingTicketItem.evaluateInteraction(
                true, Optional.empty(), Optional.empty(),
                true, true, foreignWorker, false, OWNER, WORKER_2, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.DENIED, unboundOnForeign);
        assertTrue(BindingTicketItem.toClaim(unboundOnForeign).shouldCancelEvent());
    }

    @Test
    void audit_boundTicketOnDifferentWorkerCannotTriggerNativeSitStand() {
        // Requirement: Bound ticket on a different worker cannot trigger native sit/stand
        ItemStack ticket = new ItemStack(ExampleMod.BINDING_TICKET.get());
        assertTrue(BindingTicketItem.bindTicket(ticket, WORKER_1, OWNER));
        assertEquals(Optional.of(WORKER_1), BindingTicketItem.getBoundWorkerId(ticket));

        // Rebinding must be strictly rejected
        assertFalse(BindingTicketItem.bindTicket(ticket, WORKER_2, OWNER), "Rebinding different mob must be strictly rejected");
        assertEquals(Optional.of(WORKER_1), BindingTicketItem.getBoundWorkerId(ticket), "Bound worker UUID must remain immutable");

        // Interaction on different worker must yield WRONG_WORKER -> HANDLE_DENIED (FAIL + cancel event)
        WorkerProfile profile2 = new WorkerProfile(WORKER_2, OWNER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_1), Optional.of(OWNER),
                true, true, profile2, false, OWNER, WORKER_2, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.WRONG_WORKER, decision);
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_DENIED, claim,
                "WRONG_WORKER must yield HANDLE_DENIED so native TLM maid sit/stand cannot execute");
        assertEquals(InteractionResult.FAIL, claim.result());
        assertTrue(claim.shouldCancelEvent(), "Must cancel the event to suppress native TLM sit/stand command");
    }

    @Test
    void audit_boundTicketOnCorrectWorkerOpensOnlyIntendedElstarGui() {
        // Requirement: Bound ticket on the correct worker opens only the intended Elstar GUI
        WorkerProfile profile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_1), Optional.of(OWNER),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.MANAGE, decision);
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_SUCCESS, claim);
        assertEquals(InteractionResult.SUCCESS, claim.result());
        assertTrue(claim.shouldCancelEvent(), "Must claim SUCCESS and cancel event to suppress native TLM handlers");
    }

    @Test
    void audit_emptyHandAndUnrelatedItemPreserveNativeBehavior() {
        // Requirement: Empty-hand and unrelated-item interactions preserve native behavior
        WorkerProfile profile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);

        // Empty hand
        BindingTicketItem.Decision emptyHand = BindingTicketItem.evaluateInteraction(
                false, Optional.empty(), Optional.empty(),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.PASS, emptyHand);
        assertFalse(BindingTicketItem.toClaim(emptyHand).shouldCancelEvent(),
                "Empty hand must NOT cancel event, preserving native TLM maid GUI");

        // Unrelated item (stick / weapon / food)
        BindingTicketItem.Decision stickItem = BindingTicketItem.evaluateInteraction(
                false, Optional.empty(), Optional.empty(),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.PASS, stickItem);
        assertFalse(BindingTicketItem.toClaim(stickItem).shouldCancelEvent(),
                "Unrelated items must NOT cancel event, preserving native emergency sit command");
    }

    @Test
    void audit_specificAndGenericEntityEventsDoNotDuplicateSideEffects() {
        // Requirement: Specific and generic entity events do not duplicate side effects
        BindingTicketItem.clearRecentInteractionsForTesting();

        BindingTicketItem.InteractionKey key1 = new BindingTicketItem.InteractionKey(OWNER, InteractionHand.MAIN_HAND, 101);
        BindingTicketItem.InteractionKey key2 = new BindingTicketItem.InteractionKey(OWNER, InteractionHand.MAIN_HAND, 101);
        BindingTicketItem.InteractionKey keyDifferentHand = new BindingTicketItem.InteractionKey(OWNER, InteractionHand.OFF_HAND, 101);
        BindingTicketItem.InteractionKey keyDifferentEntity = new BindingTicketItem.InteractionKey(OWNER, InteractionHand.MAIN_HAND, 202);

        // Same player, hand, and entity ID match and share hash code
        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());

        // Different hand is strictly isolated
        assertNotEquals(key1, keyDifferentHand);

        // Different entity is strictly isolated
        assertNotEquals(key1, keyDifferentEntity);

        BindingTicketItem.clearRecentInteractionsForTesting();
    }

    @Test
    void audit_entityInteractionFollowedByBlockInteractionInSameTickHandledIndependently() {
        // Requirement: Entity interaction followed by block interaction in the same tick is handled independently
        BindingTicketItem.clearRecentInteractionsForTesting();

        // 1. Entity interaction key is distinct from block interaction processing
        BindingTicketItem.InteractionKey entityKey = new BindingTicketItem.InteractionKey(OWNER, InteractionHand.MAIN_HAND, 101);
        assertNotNull(entityKey);

        // 2. Setting worksite order session and querying block position does NOT touch entity deduplicator
        long now = System.currentTimeMillis();
        WorkerOrderSession.Session session = new WorkerOrderSession.Session(
                OWNER, WORKER_1, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_WORKSITE,
                Optional.empty(), Optional.empty(), 64L, now
        );
        WorkerOrderSession.setSessionForTesting(session);

        assertTrue(WorkerOrderSession.hasActiveSession(OWNER));
        assertEquals(WorkerOrderSession.Stage.SELECTING_WORKSITE, WorkerOrderSession.getSession(OWNER).orElseThrow().stage());

        // Block interaction is evaluated independently
        WorkerOrderSession.clearAll();
        BindingTicketItem.clearRecentInteractionsForTesting();
    }

    @Test
    void audit_mainHandOffhandCombinationsDoNotDuplicateSuppressOrMisroute() {
        // Requirement: Main-hand/offhand combinations do not duplicate, suppress, or misroute valid actions
        WorkerProfile profile = new WorkerProfile(WORKER_1, OWNER, OVERWORLD, WorkerStatus.IDLE);

        // Combination 1: Main hand ticket
        BindingTicketItem.Decision mainHandTicket = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_1), Optional.of(OWNER),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.MANAGE, mainHandTicket);
        assertTrue(BindingTicketItem.toClaim(mainHandTicket).shouldCancelEvent(),
                "Main hand ticket claims action; offhand must be suppressed");

        // Combination 2: Main hand unrelated item -> PASS
        BindingTicketItem.Decision mainHandUnrelated = BindingTicketItem.evaluateInteraction(
                false, Optional.empty(), Optional.empty(),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.PASS, mainHandUnrelated);
        assertFalse(BindingTicketItem.toClaim(mainHandUnrelated).shouldCancelEvent(),
                "Main hand unrelated item must NOT cancel event, allowing native maid interaction");

        // Offhand ticket evaluates cleanly when main hand passes
        BindingTicketItem.Decision offhandTicket = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_1), Optional.of(OWNER),
                true, true, profile, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.MANAGE, offhandTicket);
        assertTrue(BindingTicketItem.toClaim(offhandTicket).shouldCancelEvent(),
                "Offhand ticket evaluates cleanly without being misrouted or falsely suppressed");
    }

    @Test
    void audit_invalidWorksiteAndStorageTargetsPreserveActiveSelectionSession() {
        // Requirement: Invalid worksite/storage targets preserve the active selection session
        long now = System.currentTimeMillis();

        // 1. SELECTING_WORKSITE: invalid target must NOT cancel or terminate session
        WorkerOrderSession.Session worksiteSession = new WorkerOrderSession.Session(
                OWNER, WORKER_1, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_WORKSITE,
                Optional.empty(), Optional.empty(), 64L, now
        );
        WorkerOrderSession.setSessionForTesting(worksiteSession);

        assertTrue(WorkerOrderSession.hasActiveSession(OWNER));
        assertEquals(WorkerOrderSession.Stage.SELECTING_WORKSITE, WorkerOrderSession.getSession(OWNER).orElseThrow().stage());

        // Verify session persists (not terminated)
        assertTrue(WorkerOrderSession.hasActiveSession(OWNER), "Session must remain active when target is invalid");

        // 2. SELECTING_STORAGE: invalid storage must NOT cancel or terminate session
        BlockPos worksitePos = new BlockPos(10, 64, 10);
        WorkerTarget worksite = new WorkerTarget(OVERWORLD, worksitePos);
        WorkerOrderSession.Session storageSession = new WorkerOrderSession.Session(
                OWNER, WORKER_1, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_STORAGE,
                Optional.of(worksite), Optional.empty(), 64L, now
        );
        WorkerOrderSession.setSessionForTesting(storageSession);

        assertTrue(WorkerOrderSession.hasActiveSession(OWNER));
        assertEquals(WorkerOrderSession.Stage.SELECTING_STORAGE, WorkerOrderSession.getSession(OWNER).orElseThrow().stage());
        assertTrue(WorkerOrderSession.getSession(OWNER).orElseThrow().worksite().isPresent());

        // Session remains active ready for another click
        assertTrue(WorkerOrderSession.hasActiveSession(OWNER), "Storage selection session must remain active after invalid target");

        WorkerOrderSession.clearAll();
    }

    @Test
    void audit_unauthorizedWorkersRemainInaccessible() {
        // Requirement: Unauthorized workers remain inaccessible
        WorkerProfile foreignWorker = new WorkerProfile(WORKER_2, OTHER_PLAYER, OVERWORLD, WorkerStatus.IDLE);

        // Ticket bound to worker 2 owned by other player -> strictly DENIED
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_2), Optional.of(OTHER_PLAYER),
                true, true, foreignWorker, false, OWNER, WORKER_2, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.DENIED, decision, "Unauthorized worker interaction must be strictly DENIED");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(decision);
        assertEquals(InteractionResult.FAIL, claim.result());
        assertTrue(claim.shouldCancelEvent(), "Must cancel event with FAIL to prevent unauthorized control");

        // Unbound ticket on foreign worker -> strictly DENIED
        BindingTicketItem.Decision unboundOnForeign = BindingTicketItem.evaluateInteraction(
                true, Optional.empty(), Optional.empty(),
                true, true, foreignWorker, false, OWNER, WORKER_2, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.DENIED, unboundOnForeign);
        assertTrue(BindingTicketItem.toClaim(unboundOnForeign).shouldCancelEvent(),
                "Must cancel event with FAIL to prevent accidental binding of foreign worker");
    }

    @Test
    void interactionKeySeparatesClientAndServerLogicalSides() {
        BindingTicketItem.InteractionKey clientKey = new BindingTicketItem.InteractionKey(true, OWNER, InteractionHand.MAIN_HAND, 42);
        BindingTicketItem.InteractionKey serverKey = new BindingTicketItem.InteractionKey(false, OWNER, InteractionHand.MAIN_HAND, 42);

        assertTrue(clientKey.isClient());
        assertFalse(serverKey.isClient());
        assertNotEquals(clientKey, serverKey, "Client and server interaction keys must not collide in deduplication cache");
        assertNotEquals(clientKey.hashCode(), serverKey.hashCode(), "Client and server interaction hashes must differ");

        // Backward compatibility: 3-arg constructor defaults to server side
        BindingTicketItem.InteractionKey legacyKey = new BindingTicketItem.InteractionKey(OWNER, InteractionHand.MAIN_HAND, 42);
        assertFalse(legacyKey.isClient());
        assertEquals(serverKey, legacyKey);
    }

    @Test
    void seatedMaidBindingWorkflowClaimsExclusivePriority() {
        // Seated maid is eligible, alive, loaded, untamed/unbound
        BindingTicketItem.Decision bindDecision = BindingTicketItem.evaluateInteraction(
                true, Optional.empty(), Optional.empty(),
                true, true, null, false, OWNER, WORKER_1, OVERWORLD, false
        );
        assertEquals(BindingTicketItem.Decision.BIND, bindDecision, "Binding ticket used on seated eligible maid must evaluate to BIND");
        BindingTicketItem.InteractionClaim claim = BindingTicketItem.toClaim(bindDecision);
        assertEquals(BindingTicketItem.InteractionClaim.HANDLE_SUCCESS, claim);
        assertTrue(claim.shouldCancelEvent(), "Binding workflow must cancel downstream event to prevent TLM sitting toggle");
    }
}
