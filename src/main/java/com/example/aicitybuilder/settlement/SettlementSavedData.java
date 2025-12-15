package com.example.aicitybuilder.settlement;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SettlementSavedData extends SavedData {
    public static final String DATA_NAME = "aicitybuilder_settlements";

    public final Map<UUID, SettlementData> settlements = new HashMap<>();

    public static SettlementSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                SettlementSavedData::load,
                SettlementSavedData::new,
                DATA_NAME
        );
    }

    public static SettlementSavedData load(CompoundTag tag) {
        SettlementSavedData d = new SettlementSavedData();
        ListTag list = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            SettlementData s = SettlementData.load(list.getCompound(i));
            d.settlements.put(s.id, s);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (SettlementData s : settlements.values()) {
            list.add(s.save());
        }
        tag.put("Settlements", list);
        return tag;
    }
}
