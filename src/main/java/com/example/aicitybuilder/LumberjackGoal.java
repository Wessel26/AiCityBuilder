package com.example.aicitybuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * AI Goal voor de houthakker-job.
 *
 * Deze goal:
 * - zoekt naar een boomstam (BlockTags.LOGS) in de buurt
 * - loopt naar de basis van de stam
 * - hakt de stam van onder naar boven af
 *
 * Het echte bijhouden van verzamelde logs in VillageData gebeurt via BotEntity#pickUpItem,
 * zodat alle hout dat de bot oppakt automatisch naar het dorp geboekt kan worden.
 */
public class LumberjackGoal extends Goal {
    private static final int MAX_TREE_HEIGHT = 16;

    private final BotEntity bot;
    private BlockPos targetLogPos;
    private BlockPos currentChopPos;
    private boolean chopping;

    public LumberjackGoal(BotEntity bot) {
        this.bot = bot;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (bot.level().isClientSide) return false;
        if (bot.getJob() != BotJobType.LUMBERJACK) return false;
        if (bot.getVillageData() == null) return false;

        // Ticket-driven: alleen werken als we een actief LUMBERJACK ticket hebben
        var ticketOpt = bot.getActiveTicket();
        if (ticketOpt.isEmpty() || ticketOpt.get().getType() != BotJobType.LUMBERJACK) return false;

        this.targetLogPos = ticketOpt.get().getTarget();
        return this.targetLogPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return bot.getJob() == BotJobType.LUMBERJACK && targetLogPos != null;
    }

    @Override
    public void start() {
        this.chopping = false;
        if (this.targetLogPos != null) {
            // Bepaal eerst de basis van de stam (laagste log-blok)
            this.targetLogPos = findTreeBase(bot.level(), this.targetLogPos);
            moveTowardsTree();
        }
    }

    @Override
    public void stop() {
        this.chopping = false;
        this.targetLogPos = null;
        this.currentChopPos = null;
        bot.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (targetLogPos == null) {
            return;
        }

        double distSq = bot.distanceToSqr(
                targetLogPos.getX() + 0.5D,
                targetLogPos.getY(),
                targetLogPos.getZ() + 0.5D
        );

        // Nog niet dichtbij genoeg: blijf lopen
        if (distSq > 4.0D) {
            moveTowardsTree();
            return;
        }

        // Dichtbij genoeg: begin (of ga door met) hakken
        if (!chopping) {
            this.chopping = true;
            this.currentChopPos = targetLogPos.immutable();
        }

        if (currentChopPos == null) {
            // Klaar met hakken
            bot.completeActiveTicket();
            this.stop();
            return;
        }

        Level level = bot.level();
        BlockState state = level.getBlockState(currentChopPos);
        if (state.is(BlockTags.LOGS)) {
            // Simpele "insta-mine" van log-blokken, drops worden als items gespawned
            level.destroyBlock(currentChopPos, true, bot);

            // Ga één blok omhoog in de stam
            BlockPos above = currentChopPos.above();
            if (above.getY() - targetLogPos.getY() > MAX_TREE_HEIGHT) {
                // Te hoog -> stoppen
                currentChopPos = null;
            } else if (level.getBlockState(above).is(BlockTags.LOGS)) {
                currentChopPos = above;
            } else {
                // Geen log meer boven -> klaar
                currentChopPos = null;
            }
        } else {
            // Geen log meer op huidige positie -> klaar
            currentChopPos = null;
        }

        // Kijk naar de boom terwijl hij hakt
        if (currentChopPos != null) {
            bot.getLookControl().setLookAt(
                    currentChopPos.getX() + 0.5D,
                    currentChopPos.getY() + 0.5D,
                    currentChopPos.getZ() + 0.5D
            );
        }
    }

    private void moveTowardsTree() {
        if (targetLogPos == null) return;
        bot.getNavigation().moveTo(
                targetLogPos.getX() + 0.5D,
                targetLogPos.getY(),
                targetLogPos.getZ() + 0.5D,
                1.0D
        );
    }

    /**
     * Vind de dichtstbijzijnde log in een kubus rondom de bot.
     */
    // findNearestTree(...) is vervangen door ticket-planning (VillageJobPlanner)


    /**
     * Zoek de basis (laagste log) van een gevonden stam.
     */
    private BlockPos findTreeBase(Level level, BlockPos start) {
        BlockPos pos = start;
        while (pos.getY() > level.getMinBuildHeight()) {
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (!belowState.is(BlockTags.LOGS)) {
                break;
            }
            pos = below;
        }
        return pos;
    }
}
