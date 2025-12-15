package com.example.aicitybuilder;

import com.example.aicitybuilder.village.JobTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.Optional;

/**
 * MVP Farmer:
 * - ticket-driven (FARMER ticket)
 * - loopt naar target
 * - harvest rijpe crops in de buurt (drops -> bot pickup -> deposit -> ledger)
 */
public class FarmerGoal extends Goal {
    private static final int SEARCH_RADIUS = 6;
    private static final int MAX_HARVESTS_PER_TICKET = 24;

    private final BotEntity bot;
    private BlockPos targetPos;
    private int harvestCount = 0;
    private int repathCooldown = 0;

    public FarmerGoal(BotEntity bot) {
        this.bot = bot;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (bot.level().isClientSide) return false;
        if (bot.getJob() != BotJobType.FARMER) return false;
        if (!(bot.level() instanceof ServerLevel)) return false;

        Optional opt = bot.getActiveTicket();
        if (opt.isEmpty()) return false;
        JobTicket t = (JobTicket) opt.get();

        if (t.getType() != BotJobType.FARMER) return false;
        targetPos = t.getTarget();
        return targetPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        harvestCount = 0;
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

        // stop als inventory (bijna) vol is, of genoeg geoogst
        if (bot.isInventoryAlmostFull() || harvestCount >= MAX_HARVESTS_PER_TICKET) {
            bot.completeActiveTicket();
            return;
        }

        // ga naar target
        double distSq = bot.distanceToSqr(
                targetPos.getX() + 0.5D,
                targetPos.getY() + 0.5D,
                targetPos.getZ() + 0.5D
        );

        if (distSq > 16.0D) {
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

        // dichtbij genoeg: zoek rijpe crop
        BlockPos ripe = findRipeCropNear(level, targetPos, SEARCH_RADIUS);
        if (ripe == null) {
            // niets te oogsten -> ticket klaar
            bot.completeActiveTicket();
            return;
        }

        bot.getLookControl().setLookAt(ripe.getX() + 0.5D, ripe.getY() + 0.5D, ripe.getZ() + 0.5D);

        // harvest block met drops
        level.destroyBlock(ripe, true, bot);
        harvestCount++;
    }

    private static BlockPos findRipeCropNear(ServerLevel level, BlockPos around, int r) {
        for (int dy = 1; dy >= -1; dy--) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = around.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;

                    if (s.getBlock() instanceof CropBlock crop) {
                        if (crop.isMaxAge(s)) {
                            return p.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }
}
