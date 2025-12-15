package com.example.aicitybuilder.village;

import com.example.aicitybuilder.BotJobType;
import com.example.aicitybuilder.VillageData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.UUID;

/**
 * Post periodiek tickets op basis van dorpsbehoeften.
 *
 * v1:
 * - als logs laag zijn: post LUMBERJACK tickets op bomen rond het village center
 * - als er items op de grond liggen en er is opslag: post HAULER tickets
 */
public class VillageJobPlanner {

    // Tweakables
    private static final int PLAN_INTERVAL_TICKS = 100; // 5 sec
    private static final int LOG_SEARCH_RADIUS = 20;
    private static final int ITEM_SEARCH_RADIUS = 24;
    private static final int MIN_LOGS_FOR_BUILDING = 128;
    private static final int MAX_OPEN_LUMBER_TICKETS = 6;
    private static final int MAX_OPEN_HAUL_TICKETS = 10;

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
        board.cleanup(now, 20 * 60 * 5); // 5 minuten

        BlockPos center = data.getCenter();

        // 1) Lumberjack tickets
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

        // 2) Hauler tickets (items naar opslag)
        if (data.hasStoragePos()) {
            int openHaul = countOpen(board, BotJobType.HAULER);
            int budget = Math.max(0, MAX_OPEN_HAUL_TICKETS - openHaul);
            if (budget > 0) {
                List<ItemEntity> items = level.getEntitiesOfClass(
                        ItemEntity.class,
                        new net.minecraft.world.phys.AABB(
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

    private static int countOpen(JobBoard board, BotJobType type) {
        int n = 0;
        for (JobTicket t : board.getAllTickets()) {
            if (t.getType() == type && t.getStatus() == JobStatus.OPEN) n++;
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
