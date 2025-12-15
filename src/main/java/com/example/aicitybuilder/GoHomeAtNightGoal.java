package com.example.aicitybuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Simpele dag/nacht routine: 's nachts terug naar homePos.
 */
public class GoHomeAtNightGoal extends Goal {

    private final BotEntity bot;

    public GoHomeAtNightGoal(BotEntity bot) {
        this.bot = bot;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (bot.level().isClientSide) return false;
        BlockPos home = bot.getHomePos();
        if (home == null) return false;

        long time = bot.level().getDayTime() % 24000L;
        boolean isNight = time >= 13000L && time <= 23000L;
        if (!isNight) return false;

        return bot.distanceToSqr(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D) > 4.0D;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        BlockPos home = bot.getHomePos();
        if (home == null) return;

        bot.getNavigation().moveTo(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D, 1.0D);
        bot.getLookControl().setLookAt(home.getX() + 0.5D, home.getY() + 0.5D, home.getZ() + 0.5D);
    }
}
