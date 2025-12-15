package com.example.aicitybuilder.settlement;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable state per settlement/village.
 * In stap 1 houden we alleen een resource-ledger bij (Millénaire stockpile teller).
 * Later komen hier build queue / reputation / etc. bij.
 */
public final class SettlementState {
    public final UUID id;

    // itemId -> count (bijv. "minecraft:oak_planks" -> 64)
    private final Map<String, Integer> ledger = new HashMap<>();

    public SettlementState(UUID id) {
        this.id = id;
    }

    public Map<String, Integer> ledgerView() {
        return java.util.Collections.unmodifiableMap(ledger);
    }

    public int getCount(String itemId) {
        return ledger.getOrDefault(itemId, 0);
    }

    public void add(String itemId, int amount) {
        if (amount <= 0) return;
        ledger.put(itemId, getCount(itemId) + amount);
    }

    public boolean tryConsume(String itemId, int amount) {
        if (amount <= 0) return true;
        int have = getCount(itemId);
        if (have < amount) return false;
        int left = have - amount;
        if (left == 0) ledger.remove(itemId);
        else ledger.put(itemId, left);
        return true;
    }

    public CompoundTag toNbt() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);

        ListTag items = new ListTag();
        for (var e : ledger.entrySet()) {
            CompoundTag it = new CompoundTag();
            it.putString("Item", e.getKey());
            it.putInt("Count", e.getValue());
            items.add(it);
        }
        t.put("Ledger", items);
        return t;
    }

    public static SettlementState fromNbt(CompoundTag t) {
        if (!t.hasUUID("Id")) return null;
        UUID id = t.getUUID("Id");
        SettlementState s = new SettlementState(id);

        if (t.contains("Ledger", Tag.TAG_LIST)) {
            ListTag items = t.getList("Ledger", Tag.TAG_COMPOUND);
            for (int i = 0; i < items.size(); i++) {
                CompoundTag it = items.getCompound(i);
                if (!it.contains("Item")) continue;
                String item = it.getString("Item");
                int count = it.getInt("Count");
                if (count > 0) s.ledger.put(item, count);
            }
        }
        return s;
    }
}

