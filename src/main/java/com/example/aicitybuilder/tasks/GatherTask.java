package com.example.aicitybuilder.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public class GatherTask extends Task {
    public BlockPos target;

    public GatherTask(UUID id) { super(id, TaskType.GATHER); }

    public static GatherTask of(BlockPos target) {
        GatherTask t = new GatherTask(UUID.randomUUID());
        t.target = target;
        return t;
    }

    @Override
    public String dedupeKey() {
        return "GATHER:" + target.asLong();
    }

    @Override
    public CompoundTag savePayload() {
        CompoundTag p = new CompoundTag();
        p.putLong("Target", target.asLong());
        return p;
    }

    public static GatherTask load(UUID id, CompoundTag p) {
        GatherTask t = new GatherTask(id);
        t.target = BlockPos.of(p.getLong("Target"));
        return t;
    }
}
