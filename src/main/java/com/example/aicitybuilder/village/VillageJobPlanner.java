package com.example.aicitybuilder.village;

import com.example.aicitybuilder.BotEntity;
import com.example.aicitybuilder.BotJobType;
import com.example.aicitybuilder.ModEntities;
import com.example.aicitybuilder.VillageData;
import com.example.aicitybuilder.VillageManagerData;
import com.example.aicitybuilder.building.Blueprints;
import com.example.aicitybuilder.settlement.SettlementState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class VillageJobPlanner {

    private static final int PLAN_INTERVAL_TICKS = 100; // 5 sec

    private static final int LOG_SEARCH_RADIUS = 20;
    private static final int ITEM_SEARCH_RADIUS = 24;

    private static final int MIN_LOGS_FOR_BUILDING = 128;
    private static final int MAX_OPEN_LUMBER_TICKETS = 6;

    private static final int MAX_OPEN_HAUL_TICKETS = 10;

    private static final int MIN_COBBLE_FOR_BUILDING = 128;
    private static final int MAX_OPEN_MINER_TICKETS = 4;

    private static final int PROJECT_CHECK_RADIUS = 256;
    private static final int PROJECT_ITEM_BUFFER = 8;
    private static final int PROJECT_ITEM_ENTITY_RADIUS = 32;
    private static final int MAX_OPEN_PROJECT_HAUL = 6;
    private static final int MAX_OPEN_PROJECT_PRODUCE = 6;

    private static final String ITEM_COBBLE = "minecraft:cobblestone";

    // POPULATION
    private static final int POP_SYNC_INTERVAL_TICKS = 200; // 10 sec
    private static final int POP_SYNC_RADIUS = 160;
    private static final int POP_SPAWN_ATTEMPTS = 10;

    // FOOD (MVP)
    private static final String FOOD_BREAD = "minecraft:bread";
    private static final String FOOD_POTATO = "minecraft:potato";
    private static final String FOOD_CARROT = "minecraft:carrot";

    private static final int FOOD_LOW_THRESHOLD = 24; // units
    private static final int FOOD_SPAWN_COST_UNITS = 16; // 16 potato OR 16 carrot OR 8 bread

    private static final int MAX_OPEN_FARMER_TICKETS = 2;

    private int cooldown = 0;
    private int popSyncCooldown = 0;

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        VillageData data = VillageData.get(level);
        if (data == null || !data.hasCenter()) return;

        BlockPos center = data.getCenter();
        long now = level.getGameTime();

        // POPULATION: sync + spawn (food gated)
        handlePopulation(level, data, center, now);

        // planning throttled
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        cooldown = PLAN_INTERVAL_TICKS;

        JobBoard board = data.getJobBoard();
        board.cleanup(now, 20L * 60L * 5L);

        // FOOD need -> FARMER tickets
        tryPostFarmerTickets(level, data, board, center, now);

        // Project-driven supply
        tryPostProjectSupplyTickets(level, data, board, center, now);

        // Legacy lumber
        if (data.getTotalLogs() < MIN_LOGS_FOR_BUILDING) {
            int openLumber = countOpen(board, BotJobType.LUMBERJACK);
            int toCreate = Math.max(0, MAX_OPEN_LUMBER_TICKETS - openLumber);

            for (int i = 0; i < toCreate; i++) {
                BlockPos log = findNaturalLog(level, center, LOG_SEARCH_RADIUS);
                if (log == null) break;
                if (board.hasOpenTicketNear(BotJobType.LUMBERJACK, log, 4)) continue;
                board.post(new JobTicket(UUID.randomUUID(), BotJobType.LUMBERJACK, 10, log, now));
            }
        }

        // General cobble need from ledger
        int cobbleInLedger = getLedgerCount(level, center, ITEM_COBBLE);
        if (cobbleInLedger < MIN_COBBLE_FOR_BUILDING) {
            int openMiner = countOpen(board, BotJobType.MINER);
            int toCreate = Math.max(0, MAX_OPEN_MINER_TICKETS - openMiner);

            for (int i = 0; i < toCreate; i++) {
                BlockPos mineSpot = findMineSpot(level, center, 10);
                if (mineSpot == null) break;
                if (board.hasOpenTicketNear(BotJobType.MINER, mineSpot, 4)) continue;
                board.post(new JobTicket(UUID.randomUUID(), BotJobType.MINER, 9, mineSpot, now));
            }
        }

        // Generic hauling cleanup
        if (data.hasStoragePos()) {
            int openHaul = countOpen(board, BotJobType.HAULER);
            int budget = Math.max(0, MAX_OPEN_HAUL_TICKETS - openHaul);

            if (budget > 0) {
                List<ItemEntity> items = level.getEntitiesOfClass(
                        ItemEntity.class,
                        new AABB(
                                center.getX() - ITEM_SEARCH_RADIUS, center.getY() - 8, center.getZ() - ITEM_SEARCH_RADIUS,
                                center.getX() + ITEM_SEARCH_RADIUS, center.getY() + 8, center.getZ() + ITEM_SEARCH_RADIUS
                        ),
                        e -> e.isAlive() && !e.getItem().isEmpty()
                );

                for (ItemEntity item : items) {
                    if (budget <= 0) break;
                    BlockPos p = item.blockPosition();
                    if (board.hasOpenTicketNear(BotJobType.HAULER, p, 2)) continue;

                    board.post(new JobTicket(UUID.randomUUID(), BotJobType.HAULER, 5, p, now));
                    budget--;
                }
            }
        }
    }

    // ---------------- POPULATION (FOOD GATED) ----------------

    private void handlePopulation(ServerLevel level, VillageData data, BlockPos center, long now) {
        // sync echte bot count soms
        if (popSyncCooldown-- <= 0) {
            popSyncCooldown = POP_SYNC_INTERVAL_TICKS;

            int r = POP_SYNC_RADIUS;
            AABB box = new AABB(
                    center.getX() - r, center.getY() - 64, center.getZ() - r,
                    center.getX() + r, center.getY() + 64, center.getZ() + r
            );

            int count = level.getEntitiesOfClass(BotEntity.class, box, e -> e.isAlive()).size();
            data.setPopulation(count);
        }

        if (!data.canSpawnCitizen(now)) return;

        // Food gating: we moeten food kunnen consumeren uit settlement ledger
        VillageManagerData mgr = VillageManagerData.get(level);
        Optional<VillageManagerData.VillageRecord> nearest = mgr.findNearest(level, center, PROJECT_CHECK_RADIUS);
        if (nearest.isEmpty()) return;

        SettlementState st = mgr.getState(nearest.get().id);

        if (!tryConsumeFoodForSpawn(st)) {
            // niet genoeg food -> geen spawn
            return;
        }

        // Spawn bot bij center (surface)
        for (int tries = 0; tries < POP_SPAWN_ATTEMPTS; tries++) {
            int dx = level.random.nextInt(7) - 3;
            int dz = level.random.nextInt(7) - 3;
            int x = center.getX() + dx;
            int z = center.getZ() + dz;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

            BotEntity bot = ModEntities.PLAYER_BOT.get().create(level);
            if (bot == null) return;

            bot.moveTo(x + 0.5D, y, z + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
            bot.finalizeSpawn(level, level.getCurrentDifficultyAt(bot.blockPosition()), MobSpawnType.NATURAL, null, null);

            level.addFreshEntity(bot);

            data.setPopulation(data.getPopulation() + 1);
            data.markSpawned(now);
            mgr.setDirty();
            return;
        }

        // Als spawn faalt, food was al geconsumeerd.
        // MVP: laten we dat accepteren (rare edge-case). Later: refund.
    }

    private static boolean tryConsumeFoodForSpawn(SettlementState st) {
        // voorkeur: potato -> carrot -> bread (bread is duurder, kost 8)
        if (st.tryConsume(FOOD_POTATO, FOOD_SPAWN_COST_UNITS)) return true;
        if (st.tryConsume(FOOD_CARROT, FOOD_SPAWN_COST_UNITS)) return true;
        if (st.tryConsume(FOOD_BREAD, 8)) return true;
        return false;
    }

    private static int foodUnits(SettlementState st) {
        // MVP units:
        // potato=1, carrot=1, bread=2
        int potato = st.getCount(FOOD_POTATO);
        int carrot = st.getCount(FOOD_CARROT);
        int bread = st.getCount(FOOD_BREAD);
        return potato + carrot + (bread * 2);
    }

    private static void tryPostFarmerTickets(ServerLevel level, VillageData data, JobBoard board, BlockPos center, long now) {
        VillageManagerData mgr = VillageManagerData.get(level);
        Optional<VillageManagerData.VillageRecord> nearest = mgr.findNearest(level, center, PROJECT_CHECK_RADIUS);
        if (nearest.isEmpty()) return;

        SettlementState st = mgr.getState(nearest.get().id);

        int units = foodUnits(st);
        if (units >= FOOD_LOW_THRESHOLD) return;

        int openFarmer = countOpen(board, BotJobType.FARMER);
        int toCreate = Math.max(0, MAX_OPEN_FARMER_TICKETS - openFarmer);

        for (int i = 0; i < toCreate; i++) {
            if (board.hasOpenTicketNear(BotJobType.FARMER, center, 4)) break;
            board.post(new JobTicket(UUID.randomUUID(), BotJobType.FARMER, 12, center, now));
        }
    }

    // ------------- PROJECT SUPPLY -------------

    private static void tryPostProjectSupplyTickets(ServerLevel level, VillageData data, JobBoard board, BlockPos center, long now) {
        if (!data.hasActiveProject()) return;
        if (!data.hasStoragePos()) return;

        List<Blueprints.BuildStep> steps = Blueprints.get(data.getProjectId());
        if (steps == null || steps.isEmpty()) return;

        int idx = data.getProjectStep();
        if (idx < 0 || idx >= steps.size()) return;

        Blueprints.BuildStep step = steps.get(idx);
        BlockState target = step.state();
        if (target == null || target.isAir()) return;

        Item costItem = target.getBlock().asItem();
        if (costItem == null || costItem == Items.AIR) return;

        String itemId = BuiltInRegistries.ITEM.getKey(costItem).toString();

        VillageManagerData mgr = VillageManagerData.get(level);
        Optional<VillageManagerData.VillageRecord> nearest = mgr.findNearest(level, center, PROJECT_CHECK_RADIUS);
        if (nearest.isEmpty()) return;

        UUID vid = nearest.get().id;
        SettlementState st = mgr.getState(vid);

        int have = st.getCount(itemId);
        if (have >= PROJECT_ITEM_BUFFER) return;

        int need = PROJECT_ITEM_BUFFER - have;

        int openProduceBudget = Math.max(0, MAX_OPEN_PROJECT_PRODUCE - countOpenProducer(board));
        if (openProduceBudget > 0) {
            BotJobType producer = mapItemToProducer(itemId);

            if (producer == BotJobType.LUMBERJACK) {
                BlockPos log = findNaturalLog(level, center, LOG_SEARCH_RADIUS);
                if (log != null && !board.hasOpenTicketNear(BotJobType.LUMBERJACK, log, 4)) {
                    board.post(JobTicket.material(BotJobType.LUMBERJACK, 20, log, now, itemId, Math.max(1, need)));
                }
            } else if (producer == BotJobType.MINER) {
                BlockPos mineSpot = findMineSpot(level, center, 10);
                if (mineSpot != null && !board.hasOpenTicketNear(BotJobType.MINER, mineSpot, 4)) {
                    board.post(JobTicket.material(BotJobType.MINER, 18, mineSpot, now, itemId, Math.max(1, need)));
                }
            } else if (producer == BotJobType.CRAFTER) {
                BlockPos storage = data.getStoragePos();
                if (storage != null && !board.hasOpenTicketNear(BotJobType.CRAFTER, storage, 2)) {
                    board.post(JobTicket.material(BotJobType.CRAFTER, 19, storage, now, itemId, Math.max(1, need)));
                }
            }
        }

        int openMaterialHaul = countOpenMaterial(board, BotJobType.HAULER, itemId);
        int haulBudget = Math.max(0, MAX_OPEN_PROJECT_HAUL - openMaterialHaul);
        if (haulBudget <= 0) return;

        List<ItemEntity> items = level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(
                        center.getX() - PROJECT_ITEM_ENTITY_RADIUS, center.getY() - 8, center.getZ() - PROJECT_ITEM_ENTITY_RADIUS,
                        center.getX() + PROJECT_ITEM_ENTITY_RADIUS, center.getY() + 8, center.getZ() + PROJECT_ITEM_ENTITY_RADIUS
                ),
                e -> e.isAlive() && !e.getItem().isEmpty() && e.getItem().getItem() == costItem
        );

        for (ItemEntity ent : items) {
            if (haulBudget <= 0) break;

            BlockPos p = ent.blockPosition();
            if (board.hasOpenTicketNear(BotJobType.HAULER, p, 2)) continue;

            board.post(JobTicket.material(BotJobType.HAULER, 16, p, now, itemId, Math.max(1, need)));
            haulBudget--;

            need -= Math.max(1, ent.getItem().getCount());
            if (need <= 0) break;
        }
    }

    private static BotJobType mapItemToProducer(String itemId) {
        if (itemId == null) return null;
        if (itemId.endsWith("_log")) return BotJobType.LUMBERJACK;
        if (ITEM_COBBLE.equals(itemId)) return BotJobType.MINER;
        if (itemId.endsWith("_planks")) return BotJobType.CRAFTER;
        return null;
    }

    private static int getLedgerCount(ServerLevel level, BlockPos center, String itemId) {
        VillageManagerData mgr = VillageManagerData.get(level);
        Optional<VillageManagerData.VillageRecord> nearest = mgr.findNearest(level, center, PROJECT_CHECK_RADIUS);
        if (nearest.isEmpty()) return 0;
        SettlementState st = mgr.getState(nearest.get().id);
        return st.getCount(itemId);
    }

    private static int countOpen(JobBoard board, BotJobType type) {
        int n = 0;
        for (JobTicket t : board.getAllTickets()) {
            if (t.getType() == type && t.getStatus() == JobStatus.OPEN) n++;
        }
        return n;
    }

    private static int countOpenMaterial(JobBoard board, BotJobType type, String itemId) {
        int n = 0;
        for (JobTicket t : board.getAllTickets()) {
            if (t.getStatus() != JobStatus.OPEN) continue;
            if (t.getType() != type) continue;
            if (!t.hasRequestedItem()) continue;
            if (!itemId.equals(t.getRequestedItemId())) continue;
            n++;
        }
        return n;
    }

    private static int countOpenProducer(JobBoard board) {
        int n = 0;
        for (JobTicket t : board.getAllTickets()) {
            if (t.getStatus() != JobStatus.OPEN) continue;
            if (t.getType() == BotJobType.LUMBERJACK
                    || t.getType() == BotJobType.MINER
                    || t.getType() == BotJobType.CRAFTER) {
                n++;
            }
        }
        return n;
    }

    private static BlockPos findNaturalLog(ServerLevel level, BlockPos center, int radius) {
        for (int tries = 0; tries < 80; tries++) {
            int dx = level.random.nextInt(radius * 2 + 1) - radius;
            int dz = level.random.nextInt(radius * 2 + 1) - radius;

            int x = center.getX() + dx;
            int z = center.getZ() + dz;

            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos base = new BlockPos(x, y, z);

            for (int dy = -6; dy <= 6; dy++) {
                BlockPos p = base.offset(0, dy, 0);
                BlockState s = level.getBlockState(p);
                if (!s.is(BlockTags.LOGS)) continue;
                if (hasLeavesAbove(level, p)) return p.immutable();
            }
        }
        return null;
    }

    private static boolean hasLeavesAbove(ServerLevel level, BlockPos log) {
        for (int dy = 1; dy <= 8; dy++) {
            BlockPos p = log.above(dy);
            if (level.getBlockState(p).is(BlockTags.LEAVES)) return true;
        }
        return false;
    }

    private static BlockPos findMineSpot(ServerLevel level, BlockPos center, int radius) {
        for (int tries = 0; tries < 60; tries++) {
            int dx = level.random.nextInt(radius * 2 + 1) - radius;
            int dz = level.random.nextInt(radius * 2 + 1) - radius;

            int x = center.getX() + dx;
            int z = center.getZ() + dz;

            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int dy = 2; dy <= 12; dy++) {
                BlockPos p = new BlockPos(x, surfaceY - dy, z);
                BlockState s = level.getBlockState(p);
                if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.STONE_ORE_REPLACEABLES)) {
                    return p.immutable();
                }
            }
        }
        return null;
    }
}
