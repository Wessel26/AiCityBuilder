package com.example.aicitybuilder;

import com.example.aicitybuilder.village.JobTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.Optional;

/**
 * BuilderGoal:
 * - Werkt ticket-driven: claimt een BUILDER ticket (via ClaimJobGoal)
 * - Loopt naar ticket target
 * - Laat de echte bouw-stap logic in BotEntity.handleBuildLogic() gebeuren
 *
 * Fix:
 * - bot.getActiveTicket() kan raw Optional zijn -> we casten veilig naar JobTicket via instanceof
 */
public class BuilderGoal extends Goal {

    private final BotEntity bot;
    private BlockPos targetPos;
    private int repathCooldown = 0;

    public BuilderGoal(BotEntity bot) {
        this.bot = bot;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (bot.level().isClientSide) return false;
        if (bot.getJob() != BotJobType.BUILDER) return false;
        if (!(bot.level() instanceof ServerLevel)) return false;

        Optional<?> opt = bot.getActiveTicket();
        if (opt.isEmpty()) return false;

        Object o = opt.get();
        if (!(o instanceof JobTicket ticket)) return false;

        if (ticket.getType() != BotJobType.BUILDER) return false;

        targetPos = ticket.getTarget();
        return targetPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathCooldown = 0;
    }

    @Override
    public void stop() {
        targetPos = null;
        bot.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (targetPos == null) return;

        // Loop naar target. De daadwerkelijke build is in BotEntity.handleBuildLogic().
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

        // Dichtbij genoeg: we laten BotEntity.handleBuildLogic() de steps doen.
        // We voltooien het ticket niet hier, want jouw build-project completion wordt elders bepaald.
        // (Als je wél per-ticket completion wil, kunnen we dat later koppelen aan project done.)
    }
}
