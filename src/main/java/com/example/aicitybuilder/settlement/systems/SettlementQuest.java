package com.example.aicitybuilder.settlement.systems;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

public class SettlementQuest {
    public UUID id = UUID.randomUUID();
    public QuestType type = QuestType.FETCH;
    public QuestStatus status = QuestStatus.OPEN;

    // For FETCH quests
    public Item item = null;
    public int amount = 0;
    public int progress = 0;

    // Rewards
    public Item rewardItem = null;
    public int rewardCount = 0;
    public int reputationReward = 0;

    public long createdGameTime = 0L;

    public static SettlementQuest fetch(Item item, int amount, Item reward, int rewardCount, int rep, long gameTime) {
        SettlementQuest q = new SettlementQuest();
        q.type = QuestType.FETCH;
        q.item = item;
        q.amount = Math.max(1, amount);
        q.rewardItem = reward;
        q.rewardCount = Math.max(0, rewardCount);
        q.reputationReward = rep;
        q.createdGameTime = gameTime;
        return q;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        t.putString("Type", type.name());
        t.putString("Status", status.name());
        t.putLong("Created", createdGameTime);

        if (item != null && ForgeRegistries.ITEMS.getKey(item) != null) t.putString("Item", ForgeRegistries.ITEMS.getKey(item).toString());
        t.putInt("Amount", amount);
        t.putInt("Progress", progress);

        if (rewardItem != null && ForgeRegistries.ITEMS.getKey(rewardItem) != null) t.putString("RewardItem", ForgeRegistries.ITEMS.getKey(rewardItem).toString());
        t.putInt("RewardCount", rewardCount);
        t.putInt("RepReward", reputationReward);
        return t;
    }

    public static SettlementQuest load(CompoundTag t) {
        SettlementQuest q = new SettlementQuest();
        q.id = t.hasUUID("Id") ? t.getUUID("Id") : UUID.randomUUID();
        try { q.type = QuestType.valueOf(t.getString("Type")); } catch (Exception ignored) {}
        try { q.status = QuestStatus.valueOf(t.getString("Status")); } catch (Exception ignored) {}
        q.createdGameTime = t.getLong("Created");

        if (t.contains("Item")) {
            ResourceLocation rl = new ResourceLocation(t.getString("Item"));
            q.item = ForgeRegistries.ITEMS.getValue(rl);
        }
        q.amount = t.getInt("Amount");
        q.progress = t.getInt("Progress");

        if (t.contains("RewardItem")) {
            ResourceLocation rl = new ResourceLocation(t.getString("RewardItem"));
            q.rewardItem = ForgeRegistries.ITEMS.getValue(rl);
        }
        q.rewardCount = t.getInt("RewardCount");
        q.reputationReward = t.getInt("RepReward");
        return q;
    }
}
