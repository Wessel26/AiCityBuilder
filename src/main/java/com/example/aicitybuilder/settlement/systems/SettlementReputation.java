package com.example.aicitybuilder.settlement.systems;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SettlementReputation {
    private final Map<UUID, Integer> rep = new HashMap<>();

    public int get(UUID player) {
        return rep.getOrDefault(player, 0);
    }

    public void add(UUID player, int delta) {
        int v = Math.max(-100, Math.min(100, get(player) + delta));
        rep.put(player, v);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        for (Map.Entry<UUID, Integer> e : rep.entrySet()) {
            CompoundTag r = new CompoundTag();
            r.putUUID("P", e.getKey());
            r.putInt("V", e.getValue());
            l.add(r);
        }
        t.put("Rep", l);
        return t;
    }

    public static SettlementReputation load(CompoundTag t) {
        SettlementReputation r = new SettlementReputation();
        ListTag l = t.getList("Rep", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag x = l.getCompound(i);
            if (x.hasUUID("P")) r.rep.put(x.getUUID("P"), x.getInt("V"));
        }
        return r;
    }
}
