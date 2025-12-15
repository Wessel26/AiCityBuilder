package com.example.aicitybuilder.settlement.systems;

import net.minecraft.nbt.CompoundTag;

public class SettlementDefense {
    public int threatLevel = 0;
    public long lastRaidGameTime = 0L;
    public boolean alert = false;

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("Threat", threatLevel);
        t.putLong("LastRaid", lastRaidGameTime);
        t.putBoolean("Alert", alert);
        return t;
    }

    public static SettlementDefense load(CompoundTag t) {
        SettlementDefense d = new SettlementDefense();
        d.threatLevel = t.getInt("Threat");
        d.lastRaidGameTime = t.getLong("LastRaid");
        d.alert = t.getBoolean("Alert");
        return d;
    }
}
