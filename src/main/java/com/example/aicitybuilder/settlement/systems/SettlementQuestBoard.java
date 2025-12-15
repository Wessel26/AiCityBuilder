package com.example.aicitybuilder.settlement.systems;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SettlementQuestBoard {
    private final List<SettlementQuest> quests = new ArrayList<>();

    public List<SettlementQuest> all() { return quests; }

    public Optional<SettlementQuest> get(UUID id) {
        return quests.stream().filter(q -> q.id.equals(id)).findFirst();
    }

    public void add(SettlementQuest q) {
        quests.add(q);
        quests.sort(Comparator.comparingLong((SettlementQuest x) -> x.createdGameTime).reversed());
        if (quests.size() > 30) quests.subList(30, quests.size()).clear();
    }

    public void cleanup(long nowGameTime, long maxAge) {
        quests.removeIf(q -> (nowGameTime - q.createdGameTime) > maxAge && q.status != QuestStatus.OPEN);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        for (SettlementQuest q : quests) l.add(q.save());
        t.put("Quests", l);
        return t;
    }

    public static SettlementQuestBoard load(CompoundTag t) {
        SettlementQuestBoard b = new SettlementQuestBoard();
        ListTag l = t.getList("Quests", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) b.quests.add(SettlementQuest.load(l.getCompound(i)));
        return b;
    }
}
