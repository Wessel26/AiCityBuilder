package com.example.aicitybuilder.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public class HaulTask extends Task {
    public ItemStack what = ItemStack.EMPTY;
    public BlockPos from;
    public BlockPos to;

    public HaulTask(UUID id) {
        super(id, TaskType.HAUL);
    }

    public static HaulTask of(ItemStack what, BlockPos from, BlockPos to) {
        HaulTask t = new HaulTask(UUID.randomUUID());
        t.what = what.copy();
        t.from = from;
        t.to = to;
        return t;
    }

    @Override
    public String dedupeKey() {
        return "HAUL:" + what.getItem().toString() + ":" + what.getCount() + ":" + from.asLong() + ":" + to.asLong();
    }

    @Override
    public CompoundTag savePayload() {
        CompoundTag p = new CompoundTag();
        p.put("What", what.save(new CompoundTag()));
        p.putLong("From", from.asLong());
        p.putLong("To", to.asLong());
        return p;
    }

    public static HaulTask load(UUID id, CompoundTag p) {
        HaulTask t = new HaulTask(id);
        t.what = ItemStack.of(p.getCompound("What"));
        t.from = BlockPos.of(p.getLong("From"));
        t.to = BlockPos.of(p.getLong("To"));
        return t;
    }
}
