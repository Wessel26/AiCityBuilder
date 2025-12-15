package com.example.aicitybuilder.tasks;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CraftTask extends Task {
    public ItemStack output = ItemStack.EMPTY;
    public List<ItemStack> ingredients = new ArrayList<>();

    public CraftTask(UUID id) { super(id, TaskType.CRAFT); }

    public static CraftTask of(ItemStack output, List<ItemStack> ingredients) {
        CraftTask t = new CraftTask(UUID.randomUUID());
        t.output = output.copy();
        t.ingredients = new ArrayList<>();
        for (ItemStack s : ingredients) t.ingredients.add(s.copy());
        return t;
    }

    @Override
    public String dedupeKey() {
        return "CRAFT:" + output.getItem().toString() + ":" + output.getCount();
    }

    @Override
    public CompoundTag savePayload() {
        CompoundTag p = new CompoundTag();
        p.put("Output", output.save(new CompoundTag()));
        // keep v1 simple: don't serialize ingredients in detail
        return p;
    }

    public static CraftTask load(UUID id, CompoundTag p) {
        CraftTask t = new CraftTask(id);
        t.output = ItemStack.of(p.getCompound("Output"));
        return t;
    }
}
