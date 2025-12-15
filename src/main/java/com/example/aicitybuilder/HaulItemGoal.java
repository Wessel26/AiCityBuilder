package com.example.aicitybuilder;

import com.example.aicitybuilder.village.JobTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Ticket-driven haul goal:
 * - ga naar een ItemEntity in de buurt van ticket.target
 * - laat pickUpItem() z'n werk doen via collision
 *
 * Stap 6: requestedItemId awareness
 * - Als het ticket een requestedItemId heeft, haalen we alleen dat item-type op.
 */
public class HaulItemGoal extends Goal {
    private final BotEntity bot;

    private BlockPos targetPos;
    private int stuckCooldown = 0;

    // ticket context
    private String requestedItemId = null;

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
        this.requestedItemId = ticket.get().hasRequestedItem() ? ticket.get().getRequestedItemId() : null;

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

        // Zoek item entity dicht bij target.
        // Als weg -> ticket complete.
        Item desired = resolveRequestedItem();
        List<ItemEntity> items = level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(targetPos).inflate(2.0D),
                e -> e.isAlive()
                        && !e.getItem().isEmpty()
                        && (desired == null || e.getItem().getItem() == desired)
        );

        if (items.isEmpty()) {
            bot.completeActiveTicket();
            return;
        }

        // Pak de dichtstbijzijnde matching entity (minder "random")
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity e : items) {
            double d = bot.distanceToSqr(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        if (best == null) {
            bot.completeActiveTicket();
            return;
        }

        double distSq = bot.distanceToSqr(best);

        if (distSq > 2.0D) {
            if (stuckCooldown-- <= 0) {
                bot.getNavigation().moveTo(best, 1.1D);
                stuckCooldown = 20;
            }
            bot.getLookControl().setLookAt(best.getX(), best.getY() + 0.2D, best.getZ());
        } else {
            // pickUpItem() gebeurt via collision; we wachten tot item weg is.
            bot.getLookControl().setLookAt(best.getX(), best.getY() + 0.2D, best.getZ());
        }
    }

    private Item resolveRequestedItem() {
        if (requestedItemId == null || requestedItemId.isEmpty()) return null;

        try {
            ResourceLocation rl = ResourceLocation.tryParse(requestedItemId);
            if (rl == null) return null;
            return BuiltInRegistries.ITEM.get(rl);
        } catch (Exception ignored) {
            return null;
        }
    }
}
