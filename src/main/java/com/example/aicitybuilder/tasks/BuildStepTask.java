package com.example.aicitybuilder.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public class BuildStepTask extends Task {
    public String structureId;
    public BlockPos origin;
    public int stageIndex;

    public BuildStepTask(UUID id) {
        super(id, TaskType.BUILD_STEP);
    }

    public static BuildStepTask of(String structureId, BlockPos origin, int stageIndex) {
        BuildStepTask t = new BuildStepTask(UUID.randomUUID());
        t.structureId = structureId;
        t.origin = origin;
        t.stageIndex = stageIndex;
        return t;
    }

    @Override
    public String dedupeKey() {
        return "BUILD_STEP:" + structureId + ":" + origin.asLong() + ":" + stageIndex;
    }

    @Override
    public CompoundTag savePayload() {
        CompoundTag p = new CompoundTag();
        p.putString("StructureId", structureId);
        p.putLong("Origin", origin.asLong());
        p.putInt("StageIndex", stageIndex);
        return p;
    }

    public static BuildStepTask load(UUID id, CompoundTag p) {
        BuildStepTask t = new BuildStepTask(id);
        t.structureId = p.getString("StructureId");
        t.origin = BlockPos.of(p.getLong("Origin"));
        t.stageIndex = p.getInt("StageIndex");
        return t;
    }
}
