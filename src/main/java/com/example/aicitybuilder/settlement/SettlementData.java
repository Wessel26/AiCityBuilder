package com.example.aicitybuilder.settlement;

import com.example.aicitybuilder.storage.WarehouseIndex;
import com.example.aicitybuilder.structures.BuildingInstance;
import com.example.aicitybuilder.structures.StructureDef;
import com.example.aicitybuilder.structures.TemplateRegistry;
import com.example.aicitybuilder.tasks.BuildStepTask;
import com.example.aicitybuilder.tasks.TaskQueue;
import com.example.aicitybuilder.settlement.systems.*;
import com.example.aicitybuilder.ModEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

public class SettlementData {
    public UUID id;
    public BlockPos center;
    public int level = 1;
    public int radius = 32;

    public final List<BuildingInstance> buildings = new ArrayList<>();
    public final Set<UUID> residents = new HashSet<>();

    public TaskQueue taskQueue = new TaskQueue();
    public WarehouseIndex warehouse = new WarehouseIndex();

    public SettlementReputation reputation = new SettlementReputation();
    public SettlementEconomy economy = new SettlementEconomy();
    public SettlementQuestBoard quests = new SettlementQuestBoard();
    public SettlementDefense defense = new SettlementDefense();

    // internal tick trackers
    private long lastPopulationUpdate = 0L;
    private long lastEconomyUpdate = 0L;
    private long lastQuestUpdate = 0L;

    public SettlementData(UUID id, BlockPos center) {
        this.id = id;
        this.center = center;
    }

    public void tick(ServerLevel level) {
        warehouse.tick(level);
        taskQueue.timeoutUnstick(System.currentTimeMillis(), 60_000);

        // State machine for each building
        for (BuildingInstance b : buildings) {
            StructureDef def = TemplateRegistry.get().get(b.structureId);
            if (def == null) continue;

            switch (b.state) {
                case PLANNED -> {
                    if (canStart(def)) {
                        b.state = BuildState.WAITING_RESOURCES;
                    }
                }
                case WAITING_RESOURCES -> {
                    UUID resKey = reservationKey(b);
                    if (warehouse.reserve(def.cost(), resKey)) {
                        b.state = BuildState.BUILDING;
                    }
                }
                case BUILDING -> {
                    ensureBuildTask(b);
                    // completion is driven by bots; for MVP we mark complete if the build task is done
                    // (a real integration would check done per stage).
                    boolean anyOpen = taskQueue.all().stream().anyMatch(t ->
                            t instanceof BuildStepTask bst &&
                                    bst.structureId.equals(b.structureId) &&
                                    bst.origin.equals(b.origin) &&
                                    bst.stageIndex == b.stageIndex &&
                                    t.status != com.example.aicitybuilder.tasks.TaskStatus.DONE
                    );
                    if (!anyOpen) {
                        b.state = BuildState.COMPLETE;
                        warehouse.consumeReserved(level, reservationKey(b));
                        onComplete(b, def);
                    }
                }
                case COMPLETE -> {}
            }
        }
    }

    private void ensureBuildTask(BuildingInstance b) {
        boolean exists = taskQueue.all().stream().anyMatch(t ->
                t instanceof BuildStepTask bst &&
                        bst.structureId.equals(b.structureId) &&
                        bst.origin.equals(b.origin) &&
                        bst.stageIndex == b.stageIndex &&
                        (t.status == com.example.aicitybuilder.tasks.TaskStatus.OPEN ||
                         t.status == com.example.aicitybuilder.tasks.TaskStatus.IN_PROGRESS)
        );
        if (!exists) {
            BuildStepTask t = BuildStepTask.of(b.structureId, b.origin, b.stageIndex);
            t.priority = 100;
            t.createdAt = System.currentTimeMillis();
            t.lastProgressAt = t.createdAt;
            taskQueue.offer(t);
        }
    }

    private boolean canStart(StructureDef def) {
        if (level < def.minLevel()) return false;
        for (String p : def.prerequisites()) {
            boolean ok = buildings.stream().anyMatch(b -> b.structureId.equals(p) && b.state == BuildState.COMPLETE);
            if (!ok) return false;
        }
        return true;
    }

    private void onComplete(BuildingInstance b, StructureDef def) {
        // very simple progression: if town hall, increase level
        if (b.structureId.startsWith("town_hall")) {
            level = Math.max(level, level + 1);
        }
    }

    private static UUID reservationKey(BuildingInstance b) {
        // stable per building instance
        return UUID.nameUUIDFromBytes((b.structureId + ":" + b.origin.asLong()).getBytes());
    }


    private void tickPopulation(ServerLevel serverLevel) {
        long now = serverLevel.getGameTime();
        // Update roughly every 10 seconds
        if (now - lastPopulationUpdate < 200) return;
        lastPopulationUpdate = now;

        int housing = 0;
        int foodProd = 0;
        int defenseValue = 0;

        for (BuildingInstance b : buildings) {
            if (b.state != BuildState.COMPLETE) continue;
            StructureDef def = TemplateRegistry.get().get(b.structureId);
            if (def == null) continue;
            housing += def.housing();
            foodProd += def.food();
            defenseValue += def.defense();
        }

        // naive food: bread is 1 "unit"
        int bread = warehouse.available(Items.BREAD);
        int potatoes = warehouse.available(Items.POTATO);
        int carrots = warehouse.available(Items.CARROT);
        int foodAvailable = bread + (potatoes / 2) + (carrots / 2);

        // Consume 1 food per resident per "day" (20 minutes = 24000 ticks)
        if (now % 24000L == 0L && !residents.isEmpty()) {
            int need = residents.size();
            // best effort: just reduce cached by releasing reservation-like logic not supported; so only model shortage via available()
            if (foodAvailable < need) {
                // starvation -> reduce housing satisfaction by lowering level over time (soft penalty)
                this.level = Math.max(1, this.level - 1);
            }
        }

        // Spawn bots up to housing if enough food buffer
        int desired = Math.min(housing, 50); // safety cap
        if (residents.size() < desired && foodAvailable >= residents.size() + 2) {
            int toSpawn = Math.min(desired - residents.size(), 1); // spawn 1 per update
            for (int i = 0; i < toSpawn; i++) {
                var ent = ModEntities.PLAYER_BOT.get().create(serverLevel);
                if (ent != null) {
                    ent.moveTo(center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5, serverLevel.random.nextFloat() * 360f, 0f);
                    ent.setHomePos(center);
                    serverLevel.addFreshEntity(ent);
                    residents.add(ent.getUUID());
                }
            }
        }

        // Clean up dead residents
        residents.removeIf(uuid -> serverLevel.getEntity(uuid) == null);
        // store aggregate defense into defense.threatLevel dampening
        defense.threatLevel = Math.max(0, defense.threatLevel - Math.max(0, defenseValue / 10));
    }

    private void tickEconomy(ServerLevel level) {
        long now = level.getGameTime();
        if (now - lastEconomyUpdate < 200) return;
        lastEconomyUpdate = now;

        // Demand is driven by: open building costs + open FETCH quests
        java.util.Map<Item, Integer> demand = new java.util.HashMap<>();

        for (BuildingInstance b : buildings) {
            if (b.state == BuildState.COMPLETE) continue;
            StructureDef def = TemplateRegistry.get().get(b.structureId);
            if (def == null) continue;
            for (var e : def.cost().entrySet()) {
                demand.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }

        for (SettlementQuest q : quests.all()) {
            if (q.status != QuestStatus.OPEN) continue;
            if (q.type == QuestType.FETCH && q.item != null) {
                demand.merge(q.item, Math.max(0, q.amount - q.progress), Integer::sum);
            }
        }

        // push into economy model
        for (var e : demand.entrySet()) {
            economy.setDemand(e.getKey(), e.getValue());
        }

        // set some sensible base prices
        economy.setBasePrice(Items.OAK_PLANKS, 1);
        economy.setBasePrice(Items.COBBLESTONE, 1);
        economy.setBasePrice(Items.IRON_INGOT, 4);
        economy.setBasePrice(Items.DIAMOND, 16);
        economy.setBasePrice(Items.BREAD, 1);
    }

    private void tickQuests(ServerLevel level) {
        long now = level.getGameTime();
        if (now - lastQuestUpdate < 200) return;
        lastQuestUpdate = now;

        // Keep 1-3 open quests around (MVP)
        long open = quests.all().stream().filter(q -> q.status == QuestStatus.OPEN).count();
        if (open < 2) {
            // create a quest based on current demand: if planks are demanded, ask for planks, else bread
            Item target = economy.getDemand(Items.OAK_PLANKS) > 0 ? Items.OAK_PLANKS : Items.BREAD;
            int amt = target == Items.OAK_PLANKS ? 32 : 8;
            SettlementQuest q = SettlementQuest.fetch(target, amt, Items.EMERALD, Math.max(1, amt / 16), 5, now);
            quests.add(q);
        }

        // cleanup completed/failed quests after 2 days
        quests.cleanup(now, 24000L * 2);
    }

    private void tickDefense(ServerLevel level) {
        // scan for hostiles near center
        int range = Math.max(16, radius);
        var aabb = new net.minecraft.world.phys.AABB(center).inflate(range, 8, range);
        int hostiles = 0;
        for (Entity e : level.getEntities(null, aabb)) {
            if (e instanceof Monster) hostiles++;
        }

        defense.alert = hostiles > 0;
        if (hostiles > 0) {
            defense.threatLevel = Math.min(100, defense.threatLevel + hostiles);
            // ensure a DEFEND quest exists
            boolean hasDefend = quests.all().stream().anyMatch(q -> q.status == QuestStatus.OPEN && q.type == QuestType.DEFEND);
            if (!hasDefend) {
                SettlementQuest q = new SettlementQuest();
                q.type = QuestType.DEFEND;
                q.createdGameTime = level.getGameTime();
                q.rewardItem = Items.EMERALD;
                q.rewardCount = 2;
                q.reputationReward = 10;
                quests.add(q);
            }
        } else {
            defense.threatLevel = Math.max(0, defense.threatLevel - 1);
        }
    }

    /**
     * Try to submit items for an open FETCH quest.
     * This is an API hook you can call from a future UI / block / command.
     */
    public boolean contributeToQuest(java.util.UUID questId, Player player, Item item, int amount) {
        var qOpt = quests.get(questId);
        if (qOpt.isEmpty()) return false;
        SettlementQuest q = qOpt.get();
        if (q.status != QuestStatus.OPEN) return false;
        if (q.type != QuestType.FETCH) return false;
        if (q.item == null || q.item != item) return false;

        q.progress += Math.max(0, amount);
        if (q.progress >= q.amount) {
            q.status = QuestStatus.COMPLETED;
            if (player != null) reputation.add(player.getUUID(), q.reputationReward);
            // reward payout is left to UI/command integration; this marks completion only.
        }
        return true;
    }

public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        t.putLong("Center", center.asLong());
        t.putInt("Level", level);
        t.putInt("Radius", radius);

        ListTag bl = new ListTag();
        for (BuildingInstance b : buildings) bl.add(b.save());
        t.put("Buildings", bl);

        ListTag rs = new ListTag();
        for (UUID u : residents) {
            CompoundTag ru = new CompoundTag();
            ru.putUUID("U", u);
            rs.add(ru);
        }
        t.put("Residents", rs);

        t.put("TaskQueue", taskQueue.save());
        t.put("Warehouse", warehouse.save());
        t.put("Reputation", reputation.save());
        t.put("Economy", economy.save());
        t.put("Quests", quests.save());
        t.put("Defense", defense.save());
        return t;
    }

    public static SettlementData load(CompoundTag t) {
        UUID id = t.getUUID("Id");
        BlockPos center = BlockPos.of(t.getLong("Center"));
        SettlementData s = new SettlementData(id, center);
        s.level = t.getInt("Level");
        s.radius = t.getInt("Radius");

        ListTag bl = t.getList("Buildings", Tag.TAG_COMPOUND);
        for (int i = 0; i < bl.size(); i++) s.buildings.add(BuildingInstance.load(bl.getCompound(i)));

        ListTag rs = t.getList("Residents", Tag.TAG_COMPOUND);
        for (int i = 0; i < rs.size(); i++) s.residents.add(rs.getCompound(i).getUUID("U"));

        if (t.contains("TaskQueue")) s.taskQueue = TaskQueue.load(t.getCompound("TaskQueue"));
        if (t.contains("Warehouse")) s.warehouse = WarehouseIndex.load(t.getCompound("Warehouse"));
        if (t.contains("Reputation")) s.reputation = SettlementReputation.load(t.getCompound("Reputation"));
        if (t.contains("Economy")) s.economy = SettlementEconomy.load(t.getCompound("Economy"));
        if (t.contains("Quests")) s.quests = SettlementQuestBoard.load(t.getCompound("Quests"));
        if (t.contains("Defense")) s.defense = SettlementDefense.load(t.getCompound("Defense"));
        return s;
    }
}