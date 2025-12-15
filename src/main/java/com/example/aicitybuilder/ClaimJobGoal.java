package com.example.aicitybuilder;

import com.example.aicitybuilder.village.JobBoard;
import com.example.aicitybuilder.village.JobTicket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Laat een bot een job-ticket claimen (1 tegelijk).
 *
 * Dit is de "dispatcher" die Millénaire-achtige orde geeft.
 */
public class ClaimJobGoal extends Goal {

    private final BotEntity bot;
    private int cooldown = 0;

    public ClaimJobGoal(BotEntity bot) {
        this.bot = bot;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        return !bot.level().isClientSide;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        if (!(bot.level() instanceof ServerLevel)) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        cooldown = 20; // 1x per seconde

        // 's nachts claimen we geen nieuwe tickets
        long time = bot.level().getDayTime() % 24000L;
        boolean isNight = time >= 13000L && time <= 23000L;
        if (isNight) return;

        VillageData village = bot.getVillageData();
        if (village == null || !village.hasCenter()) return;

        // Als bot al een actief ticket heeft, niets doen
        if (bot.getActiveTicketId() != null) {
            // check of ticket nog bestaat
            Optional<JobTicket> t = village.getJobBoard().getTicket(bot.getActiveTicketId());
            if (t.isEmpty()) {
                bot.clearActiveTicket();
            }
            return;
        }

        BotJobType preferred = bot.getJob();
        // TRADER is geen "werk-ticket". Als inventory vol -> deposit goal pakt dat op.
        if (preferred == BotJobType.TRADER) return;

        JobBoard board = village.getJobBoard();
        Optional<JobTicket> claimed = board.claimBest(bot.getUUID(), preferred, bot.blockPosition());
        claimed.ifPresent(ticket -> bot.setActiveTicketId(ticket.getId()));
    }
}
