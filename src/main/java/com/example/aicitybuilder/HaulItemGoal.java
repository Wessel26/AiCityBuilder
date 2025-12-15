package com.example.aicitybuilder;

import com.example.aicitybuilder.village.JobTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Ticket-driven haul goal: ga naar een ItemEntity in de buurt van ticket.target en laat pickUpItem() z'n werk doen.
 */
public class HaulItemGoal extends Goal {

    private final BotEntity bot;

    private BlockPos targetPos;
    private int stuckCooldown = 0;

    public HaulItemGoal(BotEntity bot) {
        this.bot = bot;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (bot.level().isClientSide) return false;
        if (bot.getJob() != BotJobType.HAULER) return false;

        Optional<JobTicket> ticket = bot.getActiveTicket();
        if (ticket.isEmpty() || ticket.get().getType() != BotJobType.HAULER) return false;

        this.targetPos = ticket.get().getTarget();
        return this.targetPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        stuckCooldown = 0;
    }

    @Override
    public void tick() {
        if (!(bot.level() instanceof ServerLevel level)) return;
        if (targetPos == null) return;

        // Zoek item entity dicht bij target. Als weg -> ticket complete.
        List<ItemEntity> items = level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(targetPos).inflate(2.0D),
                e -> e.isAlive() && !e.getItem().isEmpty()
        );

        if (items.isEmpty()) {
            bot.completeActiveTicket();
            return;
        }

        ItemEntity item = items.get(0);
        double distSq = bot.distanceToSqr(item);
        if (distSq > 2.0D) {
            if (stuckCooldown-- <= 0) {
                bot.getNavigation().moveTo(item, 1.1D);
                stuckCooldown = 20;
            }
            bot.getLookControl().setLookAt(item.getX(), item.getY() + 0.2D, item.getZ());
        } else {
            // pickUpItem() gebeurt via collision; we wachten tot item weg is.
            bot.getLookControl().setLookAt(item.getX(), item.getY() + 0.2D, item.getZ());
        }
    }
}
