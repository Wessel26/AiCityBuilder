package com.example.aicitybuilder.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

public class ContainerLink {
    public BlockPos pos;
    public String type = "generic";

    public ContainerLink() {}

    public ContainerLink(BlockPos pos, String type) {
        this.pos = pos;
        this.type = type;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("Pos", pos.asLong());
        t.putString("Type", type);
        return t;
    }

    public static ContainerLink load(CompoundTag t) {
        ContainerLink l = new ContainerLink();
        l.pos = BlockPos.of(t.getLong("Pos"));
        l.type = t.getString("Type");
        return l;
    }
}
