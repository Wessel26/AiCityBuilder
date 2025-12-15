package com.example.aicitybuilder.structures;

import com.example.aicitybuilder.settlement.BuildState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;
import java.util.UUID;

public class BuildingInstance {
    public String structureId;
    public BlockPos origin;
    public Rotation rotation = Rotation.NONE;
    public Mirror mirror = Mirror.NONE;
    public int stageIndex = 0;
    public BuildState state = BuildState.PLANNED;
    @Nullable public UUID assignedBuilder;

    public BuildingInstance() {}

    public BuildingInstance(String structureId, BlockPos origin) {
        this.structureId = structureId;
        this.origin = origin;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("StructureId", structureId);
        t.putLong("Origin", origin.asLong());
        t.putString("Rotation", rotation.name());
        t.putString("Mirror", mirror.name());
        t.putInt("StageIndex", stageIndex);
        t.putString("State", state.name());
        if (assignedBuilder != null) t.putUUID("AssignedBuilder", assignedBuilder);
        return t;
    }

    public static BuildingInstance load(CompoundTag t) {
        BuildingInstance b = new BuildingInstance();
        b.structureId = t.getString("StructureId");
        b.origin = BlockPos.of(t.getLong("Origin"));
        try { b.rotation = Rotation.valueOf(t.getString("Rotation")); } catch (Exception ignored) {}
        try { b.mirror = Mirror.valueOf(t.getString("Mirror")); } catch (Exception ignored) {}
        b.stageIndex = t.getInt("StageIndex");
        try { b.state = BuildState.valueOf(t.getString("State")); } catch (Exception ignored) {}
        if (t.hasUUID("AssignedBuilder")) b.assignedBuilder = t.getUUID("AssignedBuilder");
        return b;
    }
}
