package com.example.aicitybuilder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Centrale "dorps-brain" goal: kiest welke job de bot nu moet uitvoeren
 * op basis van eenvoudige dorpsbehoeften (Millénaire-achtige flow).
 *
 * Eerste versie:
 * - zolang het dorp weinig logs heeft: LUMBERJACK
 * - daarna: BUILDER
 * - als de bot inventory (bijna) vol zit en er is opslag: tijdelijk TRADER (deposit)
 */
public class VillageWorkGoal extends Goal {

    private final BotEntity bot;

    // 1x per seconde beslissen
    private int decisionCooldown = 0;

    // Drempels (tweakbaar)
    private static final int MIN_LOGS_FOR_BUILDING = 128;

    public VillageWorkGoal(BotEntity bot) {
        this.bot = bot;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        // Altijd actief op server-side; wij sturen alleen de job-keuze.
        return !bot.level().isClientSide;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        if (!(bot.level() instanceof ServerLevel)) return;

        if (decisionCooldown > 0) {
            decisionCooldown--;
            return;
        }
        decisionCooldown = 20;

        VillageData village = bot.getVillageData();
        if (village == null || !village.hasCenter()) {
            return;
        }

        // Als inventory (bijna) vol is, forceer deposit gedrag (als storage bestaat).
        if (village.hasStoragePos() && bot.isInventoryAlmostFull()) {
            bot.setJob(BotJobType.TRADER);
            return;
        }

        int totalLogs = village.getTotalLogs();

        if (totalLogs < MIN_LOGS_FOR_BUILDING) {
            bot.setJob(BotJobType.LUMBERJACK);
        } else {
            bot.setJob(BotJobType.BUILDER);
        }
    }
}
