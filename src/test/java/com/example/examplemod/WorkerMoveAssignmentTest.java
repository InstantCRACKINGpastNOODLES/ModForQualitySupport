package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerMoveAssignmentTest {
    @Test
    void ownerIdentityAndExistingJobGuardTargetAssignment() {
        UUID worker = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        var dimension = ResourceLocation.withDefaultNamespace("overworld");
        var profile = new WorkerProfile(worker, owner, dimension, WorkerStatus.IDLE);
        BlockPos pos = new BlockPos(4, 64, 4);
        assertTrue(BindingTicketItem.validMoveTarget(profile, owner, worker, dimension, pos, true, true, true, true));
        assertFalse(BindingTicketItem.validMoveTarget(profile, UUID.randomUUID(), worker, dimension, pos, true, true, true, true));
        assertFalse(BindingTicketItem.validMoveTarget(profile, owner, UUID.randomUUID(), dimension, pos, true, true, true, true));
        assertFalse(BindingTicketItem.validMoveTarget(new WorkerProfile(worker, owner, dimension, WorkerStatus.IDLE,
                Optional.of(WorkerJob.assigned(WorkerJobs.WAIT))), owner, worker, dimension, pos, true, true, true, true));
    }

    @Test
    void unloadedOutOfBoundsDistantAndWrongDimensionTargetsAreRejected() {
        UUID worker = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        var dimension = ResourceLocation.withDefaultNamespace("overworld");
        var profile = new WorkerProfile(worker, owner, dimension, WorkerStatus.IDLE);
        BlockPos pos = new BlockPos(4, 64, 4);
        assertFalse(BindingTicketItem.validMoveTarget(profile, owner, worker, dimension, null, true, true, true, true));
        assertFalse(BindingTicketItem.validMoveTarget(profile, owner, worker, dimension, pos, false, true, true, true));
        assertFalse(BindingTicketItem.validMoveTarget(profile, owner, worker, dimension, pos, true, false, true, true));
        assertFalse(BindingTicketItem.validMoveTarget(profile, owner, worker, dimension, pos, true, true, false, true));
        assertFalse(BindingTicketItem.validMoveTarget(profile, owner, worker, dimension, pos, true, true, true, false));
        assertFalse(BindingTicketItem.validMoveTarget(profile, owner, worker,
                ResourceLocation.withDefaultNamespace("the_nether"), pos, true, true, true, true));
    }
}