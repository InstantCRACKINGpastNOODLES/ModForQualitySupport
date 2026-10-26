package com.example.examplemod;

import java.util.List;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerManagementMenuTest {
    @Test
    void selectionFiltersZeroOneAndMultipleOwnedWorkers() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
        WorkerProfile first = new WorkerProfile(UUID.randomUUID(), owner, dimension, WorkerStatus.IDLE);
        WorkerProfile second = new WorkerProfile(UUID.randomUUID(), owner, dimension, WorkerStatus.IDLE);
        WorkerProfile foreign = new WorkerProfile(UUID.randomUUID(), other, dimension, WorkerStatus.IDLE);
        assertEquals(List.of(), WorkerManagementMenu.eligible(List.of(foreign), profile ->
                WorkerManagementMenu.authorizedProfile(profile, owner, profile.entityId(), dimension)));
        assertEquals(List.of(first), WorkerManagementMenu.eligible(List.of(foreign, first), profile ->
                WorkerManagementMenu.authorizedProfile(profile, owner, profile.entityId(), dimension)));
        assertEquals(List.of(first, second), WorkerManagementMenu.eligible(List.of(foreign, first, second), profile ->
                WorkerManagementMenu.authorizedProfile(profile, owner, profile.entityId(), dimension)));
    }

    @Test
    void staleAndUnauthorizedProfileIdentitiesAreRejected() {
        UUID owner = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
        WorkerProfile profile = new WorkerProfile(entity, owner, dimension, WorkerStatus.IDLE);
        assertTrue(WorkerManagementMenu.authorizedProfile(profile, owner, entity, dimension));
        assertFalse(WorkerManagementMenu.authorizedProfile(null, owner, entity, dimension));
        assertFalse(WorkerManagementMenu.authorizedProfile(profile, UUID.randomUUID(), entity, dimension));
        assertFalse(WorkerManagementMenu.authorizedProfile(profile, owner, UUID.randomUUID(), dimension));
        assertFalse(WorkerManagementMenu.authorizedProfile(profile, owner, entity,
                ResourceLocation.withDefaultNamespace("the_nether")));
        assertTrue(WorkerManagementMenu.candidateMatches(entity, entity));
        assertFalse(WorkerManagementMenu.candidateMatches(entity, UUID.randomUUID()));
    }
}