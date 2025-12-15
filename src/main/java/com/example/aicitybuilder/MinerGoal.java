package com.example.aicitybuilder;

import com.example.aicitybuilder.village.JobTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.Optional;

/**
 * MVP Miner:
 * - werkt ticket-driven (MINER ticket)
 * - loopt naar target
 * - breekt stone/cobble blocks in de buurt (drops als cobblestone)
 * - stopt na N blocks of als inventory bijna vol is
 *
 * Drops worden daarna door HAULER/Deposit in stockpile gezet -> ledger stijgt -> builder kan door.
 */
public class MinerGoal extends Goal {

    private static final int MAX_BREAKS_PER_TICKET = 24;
    private static final int SEARCH_RADIUS = 3;

    private final BotEntity bot;

    private BlockPos targetPos;
    private int breakCount = 0;
    private int repathCooldown = 0;

    public MinerGoal(BotEntity bot) {
        this.bot = bot;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (bot.level().isClientSide) return false;
        if (bot.getJob() != BotJobType.MINER) return false;
        if (!(bot.level() instanceof ServerLevel)) return false;

        Optional<JobTicket> opt = bot.getActiveTicket();
        if (opt.isEmpty()) return false;

        JobTicket t = opt.get();
        if (t.getType() != BotJobType.MINER) return false;

        targetPos = t.getTarget();
        return targetPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        breakCount = 0;
        repathCooldown = 0;
    }

    @Override
    public void stop() {
        targetPos = null;
        bot.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(bot.level() instanceof ServerLevel level)) return;
        if (targetPos == null) return;

        // stop als inventory (bijna) vol is, of genoeg gemined
        if (bot.isInventoryAlmostFull() || breakCount >= MAX_BREAKS_PER_TICKET) {
            bot.completeActiveTicket();
            return;
        }

        // ga naar target
        double distSq = bot.distanceToSqr(
                targetPos.getX() + 0.5D,
                targetPos.getY() + 0.5D,
                targetPos.getZ() + 0.5D
        );

        if (distSq > 9.0D) {
            if (repathCooldown-- <= 0) {
                bot.getNavigation().moveTo(
                        targetPos.getX() + 0.5D,
                        targetPos.getY() + 0.5D,
                        targetPos.getZ() + 0.5D,
                        1.0D
                );
                repathCooldown = 20;
            }
            bot.getLookControl().setLookAt(
                    targetPos.getX() + 0.5D,
                    targetPos.getY() + 0.5D,
                    targetPos.getZ() + 0.5D
            );
            return;
        }

        // dichtbij genoeg: breek stone/cobble in de buurt
        BlockPos candidate = findMineableNear(level, targetPos, SEARCH_RADIUS);
        if (candidate == null) {
            // niets meer te minen -> ticket klaar
            bot.completeActiveTicket();
            return;
        }

        bot.getLookControl().setLookAt(candidate.getX() + 0.5D, candidate.getY() + 0.5D, candidate.getZ() + 0.5D);

        // breek block met drops
        level.destroyBlock(candidate, true, bot);
        breakCount++;
    }

    private static BlockPos findMineableNear(ServerLevel level, BlockPos around, int r) {
        // simpele scan (klein blokje) voor stone/cobble/deepslate varianten
        for (int dy = 1; dy >= -2; dy--) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = around.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;

                    // Stone/cobble achtig: we nemen stone/cobblestone en “stone replaceables”
                    if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.STONE_ORE_REPLACEABLES)
                            || s.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)) {
                        return p.immutable();
                    }
                }
            }
        }
        return null;
    }
}
