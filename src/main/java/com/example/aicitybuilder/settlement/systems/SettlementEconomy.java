package com.example.aicitybuilder.settlement.systems;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * Eenvoudige economie:
 * - supply komt uit warehouse (niet hier opgeslagen)
 * - demand wordt bijgehouden en via SettlementData.tick ingevuld
 * - price = basePrice * (demand + 1) / (supply + 1)
 */
public class SettlementEconomy {
    private final Map<Item, Integer> recentDemand = new HashMap<>();
    private final Map<Item, Integer> basePrice = new HashMap<>();

    public void setDemand(Item item, int demand) {
        recentDemand.put(item, Math.max(0, demand));
    }

    public int getDemand(Item item) { return recentDemand.getOrDefault(item, 0); }

    public void setBasePrice(Item item, int emeralds) {
        basePrice.put(item, Math.max(0, emeralds));
    }

    public int getBasePrice(Item item) {
        return basePrice.getOrDefault(item, 1);
    }

    public int priceEmeralds(Item item, int supply) {
        int base = getBasePrice(item);
        int dem = getDemand(item);
        double price = base * ((dem + 1.0) / (supply + 1.0));
        return (int)Math.max(0, Math.min(64, Math.round(price)));
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag d = new ListTag();
        for (Map.Entry<Item, Integer> e : recentDemand.entrySet()) {
            if (ForgeRegistries.ITEMS.getKey(e.getKey()) == null) continue;
            CompoundTag x = new CompoundTag();
            x.putString("I", ForgeRegistries.ITEMS.getKey(e.getKey()).toString());
            x.putInt("D", e.getValue());
            d.add(x);
        }
        t.put("Demand", d);

        ListTag bp = new ListTag();
        for (Map.Entry<Item, Integer> e : basePrice.entrySet()) {
            if (ForgeRegistries.ITEMS.getKey(e.getKey()) == null) continue;
            CompoundTag x = new CompoundTag();
            x.putString("I", ForgeRegistries.ITEMS.getKey(e.getKey()).toString());
            x.putInt("P", e.getValue());
            bp.add(x);
        }
        t.put("BasePrice", bp);
        return t;
    }

    public static SettlementEconomy load(CompoundTag t) {
        SettlementEconomy e = new SettlementEconomy();
        ListTag d = t.getList("Demand", Tag.TAG_COMPOUND);
        for (int i = 0; i < d.size(); i++) {
            CompoundTag x = d.getCompound(i);
            Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(x.getString("I")));
            if (it != null) e.recentDemand.put(it, x.getInt("D"));
        }
        ListTag bp = t.getList("BasePrice", Tag.TAG_COMPOUND);
        for (int i = 0; i < bp.size(); i++) {
            CompoundTag x = bp.getCompound(i);
            Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(x.getString("I")));
            if (it != null) e.basePrice.put(it, x.getInt("P"));
        }
        return e;
    }
}
