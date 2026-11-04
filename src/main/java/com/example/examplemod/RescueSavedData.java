package com.example.examplemod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Durable World-Saved Data for Active Worker Rescues.
 *
 * Guarantees:
 * 1. World & Server Restart Durability: All active rescues are serialized to NBT
 *    and saved in `data/elstarticket_rescues.dat` via DimensionDataStorage.
 * 2. Sequence Persistence: Monotonic sequence counter is saved and restored across restarts.
 * 3. Chunk Unload Immunity: Active rescue state remains safe in world storage even if
 *    the worker chunk is unloaded.
 * 4. Dirty Marking: Automatically marks dirty on additions, updates, and removals, ensuring
 *    flushes to disk on standard world saves.
 */
public class RescueSavedData extends SavedData {

    public static final String FILE_ID = "elstarticket_rescues";

    private final Map<UUID, RescueServerManager.ActiveRescue> activeRescues = new ConcurrentHashMap<>();
    private final AtomicLong sequenceGenerator = new AtomicLong(1);

    public RescueSavedData() {
    }

    public static SavedData.Factory<RescueSavedData> factory() {
        return new SavedData.Factory<>(RescueSavedData::new, RescueSavedData::load);
    }

    public static RescueSavedData get(ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return FallbackHolder.INSTANCE;
        }
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    private static class FallbackHolder {
        private static final RescueSavedData INSTANCE = new RescueSavedData();
    }

    public long nextSequence() {
        long seq = sequenceGenerator.incrementAndGet();
        setDirty();
        return seq;
    }

    public void putRescue(RescueServerManager.ActiveRescue rescue) {
        if (rescue != null && rescue.workerId() != null) {
            activeRescues.put(rescue.workerId(), rescue);
            setDirty();
        }
    }

    public Optional<RescueServerManager.ActiveRescue> getRescue(UUID workerId) {
        if (workerId == null) return Optional.empty();
        return Optional.ofNullable(activeRescues.get(workerId));
    }

    public Optional<RescueServerManager.ActiveRescue> removeRescue(UUID workerId) {
        if (workerId == null) return Optional.empty();
        RescueServerManager.ActiveRescue removed = activeRescues.remove(workerId);
        if (removed != null) {
            setDirty();
        }
        return Optional.ofNullable(removed);
    }

    public Map<UUID, RescueServerManager.ActiveRescue> getAllRescues() {
        return Collections.unmodifiableMap(activeRescues);
    }

    public List<RescueServerManager.ActiveRescue> getRescuesForOwner(UUID ownerUUID) {
        if (ownerUUID == null) return List.of();
        return activeRescues.values().stream()
                .filter(r -> ownerUUID.equals(r.ownerUUID()))
                .toList();
    }

    public List<RescueServerManager.ActiveRescue> getRescuesForOwnerName(String ownerName) {
        if (ownerName == null || ownerName.isEmpty()) return List.of();
        return activeRescues.values().stream()
                .filter(r -> ownerName.equals(r.ownerName()))
                .toList();
    }

    public void clearAll() {
        activeRescues.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("Sequence", sequenceGenerator.get());

        ListTag listTag = new ListTag();
        for (RescueServerManager.ActiveRescue rescue : activeRescues.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("WorkerId", rescue.workerId());
            entry.putString("WorkerName", rescue.workerName() != null ? rescue.workerName() : "Worker");
            if (rescue.ownerUUID() != null) {
                entry.putUUID("OwnerUUID", rescue.ownerUUID());
            }
            entry.putString("OwnerName", rescue.ownerName() != null ? rescue.ownerName() : "");
            if (rescue.eventId() != null) {
                entry.putUUID("EventId", rescue.eventId());
            }
            entry.putString("Dimension", rescue.dimension().toString());
            entry.putInt("PosX", rescue.workerPos().getX());
            entry.putInt("PosY", rescue.workerPos().getY());
            entry.putInt("PosZ", rescue.workerPos().getZ());
            entry.putString("Trigger", rescue.trigger() != null ? rescue.trigger().name() : EquipmentCapabilityPolicy.RescueTrigger.NONE.name());
            entry.putString("Reason", rescue.reason() != null ? rescue.reason() : "");
            entry.putLong("Sequence", rescue.sequence());
            listTag.add(entry);
        }
        tag.put("Rescues", listTag);
        return tag;
    }

    public static RescueSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RescueSavedData data = new RescueSavedData();
        if (tag.contains("Sequence", Tag.TAG_LONG)) {
            data.sequenceGenerator.set(tag.getLong("Sequence"));
        }

        if (tag.contains("Rescues", Tag.TAG_LIST)) {
            ListTag listTag = tag.getList("Rescues", Tag.TAG_COMPOUND);
            for (int i = 0; i < listTag.size(); i++) {
                CompoundTag entry = listTag.getCompound(i);
                if (entry.hasUUID("WorkerId")) {
                    UUID workerId = entry.getUUID("WorkerId");
                    String workerName = entry.getString("WorkerName");
                    UUID ownerUUID = entry.hasUUID("OwnerUUID") ? entry.getUUID("OwnerUUID") : null;
                    String ownerName = entry.getString("OwnerName");
                    UUID eventId = entry.hasUUID("EventId") ? entry.getUUID("EventId") : UUID.randomUUID();
                    ResourceLocation dimension = ResourceLocation.parse(entry.getString("Dimension"));
                    BlockPos pos = new BlockPos(entry.getInt("PosX"), entry.getInt("PosY"), entry.getInt("PosZ"));
                    String triggerStr = entry.getString("Trigger");
                    EquipmentCapabilityPolicy.RescueTrigger trigger;
                    try {
                        trigger = EquipmentCapabilityPolicy.RescueTrigger.valueOf(triggerStr);
                    } catch (Throwable t) {
                        trigger = EquipmentCapabilityPolicy.RescueTrigger.MANUAL_OPERATOR_REQUEST;
                    }
                    String reason = entry.getString("Reason");
                    long seq = entry.getLong("Sequence");

                    RescueServerManager.ActiveRescue rescue = new RescueServerManager.ActiveRescue(
                            workerId, workerName, ownerUUID, ownerName, eventId, dimension, pos, trigger, reason, seq
                    );
                    data.activeRescues.put(workerId, rescue);
                }
            }
        }
        return data;
    }
}
