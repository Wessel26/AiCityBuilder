package com.example.aicitybuilder.tasks;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

public abstract class Task {
    public final UUID id;
    public final TaskType type;
    public int priority = 0;
    @Nullable public UUID claimedBy;
    public TaskStatus status = TaskStatus.OPEN;
    public long createdAt;
    public long lastProgressAt;

    protected Task(UUID id, TaskType type) {
        this.id = id;
        this.type = type;
    }

    public abstract String dedupeKey();
    public abstract CompoundTag savePayload();

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        t.putString("Type", type.name());
        t.putInt("Priority", priority);
        if (claimedBy != null) t.putUUID("ClaimedBy", claimedBy);
        t.putString("Status", status.name());
        t.putLong("CreatedAt", createdAt);
        t.putLong("LastProgressAt", lastProgressAt);
        t.put("Payload", savePayload());
        return t;
    }

    public static Task load(CompoundTag t) {
        TaskType type = TaskType.valueOf(t.getString("Type"));
        UUID id = t.getUUID("Id");
        CompoundTag p = t.getCompound("Payload");
        Task out;
        switch (type) {
            case BUILD_STEP -> out = BuildStepTask.load(id, p);
            case HAUL -> out = HaulTask.load(id, p);
            case GATHER -> out = GatherTask.load(id, p);
            case CRAFT -> out = CraftTask.load(id, p);
            default -> throw new IllegalStateException("Unknown TaskType " + type);
        }
        out.priority = t.getInt("Priority");
        if (t.hasUUID("ClaimedBy")) out.claimedBy = t.getUUID("ClaimedBy");
        try { out.status = TaskStatus.valueOf(t.getString("Status")); } catch (Exception ignored) {}
        out.createdAt = t.getLong("CreatedAt");
        out.lastProgressAt = t.getLong("LastProgressAt");
        return out;
    }
}
