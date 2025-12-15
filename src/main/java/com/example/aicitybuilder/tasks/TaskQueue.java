package com.example.aicitybuilder.tasks;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;

public class TaskQueue {
    private final Map<UUID, Task> tasks = new LinkedHashMap<>();
    private final Set<String> dedupeKeys = new HashSet<>();

    public Collection<Task> all() { return tasks.values(); }

    public boolean offer(Task task) {
        String key = task.dedupeKey();
        if (dedupeKeys.contains(key)) return false;
        tasks.put(task.id, task);
        dedupeKeys.add(key);
        return true;
    }

    public Optional<Task> claimNext(UUID botId, Set<TaskType> allowed) {
        return tasks.values().stream()
                .filter(t -> t.status == TaskStatus.OPEN)
                .filter(t -> t.claimedBy == null)
                .filter(t -> allowed == null || allowed.contains(t.type))
                .sorted(Comparator.comparingInt((Task t) -> -t.priority).thenComparingLong(t -> t.createdAt))
                .findFirst()
                .map(t -> {
                    t.claimedBy = botId;
                    t.status = TaskStatus.IN_PROGRESS;
                    t.lastProgressAt = System.currentTimeMillis();
                    return t;
                });
    }

    public void complete(UUID taskId) {
        Task t = tasks.get(taskId);
        if (t == null) return;
        t.status = TaskStatus.DONE;
        t.lastProgressAt = System.currentTimeMillis();
    }

    public void markProgress(UUID taskId) {
        Task t = tasks.get(taskId);
        if (t == null) return;
        t.lastProgressAt = System.currentTimeMillis();
    }

    public void timeoutUnstick(long nowMs, long timeoutMs) {
        for (Task t : tasks.values()) {
            if (t.status == TaskStatus.IN_PROGRESS && t.claimedBy != null) {
                long age = nowMs - t.lastProgressAt;
                if (age > timeoutMs) {
                    t.status = TaskStatus.OPEN;
                    t.claimedBy = null;
                }
            }
        }
    }

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        for (Task t : tasks.values()) {
            list.add(t.save());
        }
        root.put("Tasks", list);
        return root;
    }

    public static TaskQueue load(CompoundTag root) {
        TaskQueue q = new TaskQueue();
        ListTag list = root.getList("Tasks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Task t = Task.load(list.getCompound(i));
            q.tasks.put(t.id, t);
            q.dedupeKeys.add(t.dedupeKey());
        }
        return q;
    }
}
