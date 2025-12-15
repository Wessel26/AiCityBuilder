package com.example.aicitybuilder.village;

import com.example.aicitybuilder.BotJobType;
import com.example.aicitybuilder.VillageData;
import com.example.aicitybuilder.VillageManagerData;
import com.example.aicitybuilder.building.Blueprints;
import com.example.aicitybuilder.settlement.SettlementState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Post periodiek tickets op basis van dorpsbehoeften.
 *
 * v1 (bestaand):
 * - als logs laag zijn: post LUMBERJACK tickets op bomen rond het village center
 * - als er items op de grond liggen en er is opslag: post HAULER tickets
 *
 * v2 (Stap 5):
 * - als er een actief project is: post "material haul" tickets voor het volgende benodigde item
 *   (haulers kunnen alleen bestaande items ophalen, dus we posten alleen als we de item entities vinden).
 */
public class VillageJobPlanner {

    // Tweakables
    private static final int PLAN_INTERVAL_TICKS = 100; // 5 sec
    private static final int LOG_SEARCH_RADIUS = 20;

    private static final int ITEM_SEARCH_RADIUS = 24;
    private static final int PROJECT_MATERIAL_SEARCH_RADIUS = 32;

    private static final int MIN_LOGS_FOR_BUILDING = 128;
    private static final int MAX_OPEN_LUMBER_TICKETS = 6;
    private static final int MAX_OPEN_HAUL_TICKETS = 10;

    // Project materials
    private static final int MIN_NEXT_ITEM_BUFFER = 8;      // als ledger < 8 -> probeer haul tickets te maken
    private static final int MAX_OPEN_MATERIAL_HAUL = 6;    // anti-spam budget per planning tick

    private int cooldown = 0;

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        cooldown = PLAN_INTERVAL_TICKS;

        VillageData data = VillageData.get(level);
        if (data == null || !data.hasCenter()) return;

        JobBoard board = data.getJobBoard();
        long now = level.getGameTime();

        // Oude tickets opruimen
        board.cleanup(now, 20L * 60L * 5L); // 5 minuten

        BlockPos center = data.getCenter();

        // ---------------------------------------------------------------------
        // 0) NEW: Project material haul tickets (voor het volgende block)
        // ---------------------------------------------------------------------
        tryPostProjectMaterialHaulTickets(level, data, board, center, now);

        // ---------------------------------------------------------------------
        // 1) Lumberjack tickets
        // ---------------------------------------------------------------------
        if (data.getTotalLogs() < MIN_LOGS_FOR_BUILDING) {
            int openLumber = countOpen(board, BotJobType.LUMBERJACK);
            int toCreate = Math.max(0, MAX_OPEN_LUMBER_TICKETS - openLumber);

            for (int i = 0; i < toCreate; i++) {
                BlockPos log = findNaturalLog(level, center, LOG_SEARCH_RADIUS);
                if (log == null) break;

                // voorkom spam op bijna dezelfde plek
                if (board.hasOpenTicketNear(BotJobType.LUMBERJACK, log, 4)) continue;

                board.post(new JobTicket(UUID.randomUUID(), BotJobType.LUMBERJACK, 10, log, now));
            }
        }

        // ---------------------------------------------------------------------
        // 2) Hauler tickets (items naar opslag)
        // ---------------------------------------------------------------------
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

    /**
     * Stap 5: Post "material haul" tickets voor de eerstvolgende build-step,
     * maar alleen als:
     * - er een actief project is
     * - we een village/settlement kunnen bepalen bij dit center
     * - ledger heeft te weinig van het item
     * - er liggen daadwerkelijk item entities van dit item in de buurt (die haulers kunnen ophalen)
     */
    private static void tryPostProjectMaterialHaulTickets(ServerLevel level, VillageData data, JobBoard board, BlockPos center, long now) {
        if (!data.hasActiveProject()) return;
        if (!data.hasStoragePos()) return;

        // Welke step is de eerstvolgende?
        List<Blueprints.BuildStep> steps = Blueprints.get(data.getProjectId());
        if (steps == null || steps.isEmpty()) return;

        int stepIndex = data.getProjectStep();
        if (stepIndex < 0 || stepIndex >= steps.size()) return;

        Blueprints.BuildStep step = steps.get(stepIndex);
        BlockState target = step.state();
        if (target == null || target.isAir()) return;

        Item costItem = target.getBlock().asItem();
        if (costItem == null || costItem == Items.AIR) return;

        String itemId = BuiltInRegistries.ITEM.getKey(costItem).toString();

        // Bepaal settlement/state bij dit center (legacy bridge)
        VillageManagerData mgr = VillageManagerData.get(level);
        Optional<VillageManagerData.VillageRecord> nearest = mgr.findNearest(level, center, 256);
        if (nearest.isEmpty()) return;

        UUID vid = nearest.get().id;
        SettlementState st = mgr.getState(vid);

        int have = st.getCount(itemId);
        if (have >= MIN_NEXT_ITEM_BUFFER) return;

        // We zoeken item entities van dit item in de buurt en posten haul tickets daarop
        int openMaterialHaul = countOpenMaterial(board, BotJobType.HAULER, itemId);
        int budget = Math.max(0, MAX_OPEN_MATERIAL_HAUL - openMaterialHaul);
        if (budget <= 0) return;

        int needed = MIN_NEXT_ITEM_BUFFER - have;

        List<ItemEntity> items = level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(
                        center.getX() - PROJECT_MATERIAL_SEARCH_RADIUS, center.getY() - 8, center.getZ() - PROJECT_MATERIAL_SEARCH_RADIUS,
                        center.getX() + PROJECT_MATERIAL_SEARCH_RADIUS, center.getY() + 8, center.getZ() + PROJECT_MATERIAL_SEARCH_RADIUS
                ),
                e -> e.isAlive()
                        && !e.getItem().isEmpty()
                        && e.getItem().getItem() == costItem
        );

        for (ItemEntity ent : items) {
            if (budget <= 0) break;

            BlockPos p = ent.blockPosition();

            // anti-spam nabij dezelfde locatie
            if (board.hasOpenTicketNear(BotJobType.HAULER, p, 2)) continue;

            // "material" ticket: HAULER met payload (itemId + count hint)
            JobTicket t = JobTicket.material(
                    BotJobType.HAULER,
                    12,            // hoger dan normale haul (5), zodat materials voorrang krijgen
                    p,
                    now,
                    itemId,
                    Math.max(1, needed)
            );

            board.post(t);
            budget--;

            // we hoeven niet eindeloos te posten; dit is alleen een hint/budget
            needed -= Math.max(1, ent.getItem().getCount());
            if (needed <= 0) break;
        }
    }

    private static int countOpen(JobBoard board, BotJobType type) {
        int n = 0;
        for (JobTicket t : board.getAllTickets()) {
            if (t.getType() == type && t.getStatus() == JobStatus.OPEN) n++;
        }
        return n;
    }

    /**
     * Telt open tickets van een type met een specifiek requestedItemId.
     * Handig om "material haul" ticket spam te voorkomen.
     */
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

    /**
     * Zoekt een log-blok dat waarschijnlijk bij een natuurlijke boom hoort.
     * Simpele heuristiek: LOG + bladeren in de buurt boven.
     */
    private static BlockPos findNaturalLog(ServerLevel level, BlockPos center, int radius) {
        // Beperkte random sampling om performance oké te houden.
        for (int tries = 0; tries < 80; tries++) {
            int dx = level.random.nextInt(radius * 2 + 1) - radius;
            int dz = level.random.nextInt(radius * 2 + 1) - radius;

            int x = center.getX() + dx;
            int z = center.getZ() + dz;

            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos base = new BlockPos(x, y, z);

            // scan klein kolommetje
            for (int dy = -6; dy <= 6; dy++) {
                BlockPos p = base.offset(0, dy, 0);
                BlockState s = level.getBlockState(p);
                if (!s.is(BlockTags.LOGS)) continue;

                if (hasLeavesAbove(level, p)) {
                    return p.immutable();
                }
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
}
