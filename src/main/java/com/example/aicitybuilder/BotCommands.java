package com.example.aicitybuilder;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Commands voor de AI City Builder bots.
 *
 * /botspawn   - spawnt ALTIJD een nieuwe bot bij de speler (geen limiet per speler)
 * /botdespawn - despawnt alle bots die gekoppeld zijn aan de speler
 *
 * cityaibot.java registreert de commands en roept hier de static methods aan.
 */
public class BotCommands {

    // Houd per speler bij welke bots bij hem/haar horen
    private static final Map<UUID, List<UUID>> PLAYER_BOTS = new HashMap<>();

    /**
     * Wordt aangeroepen vanuit cityaibot.registerCommands voor /botspawn.
     */
    public static int spawnBot(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();

        Vec3 pos = source.getPosition();

        BotEntity bot = ModEntities.PLAYER_BOT.get().create(level);
        if (bot == null) {
            source.sendFailure(
                    Component.literal("Kon geen bot entity maken.").withStyle(ChatFormatting.RED)
            );
            return 0;
        }

        bot.setOwner(player);
        bot.moveTo(pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());

        level.addFreshEntity(bot);

        // Registreer bot bij deze speler
        PLAYER_BOTS
                .computeIfAbsent(player.getUUID(), id -> new ArrayList<>())
                .add(bot.getUUID());

        source.sendSuccess(
                () -> Component.literal("Bot gespawned.").withStyle(ChatFormatting.GREEN),
                true
        );

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Wordt aangeroepen vanuit cityaibot.registerCommands voor /botdespawn.
     * Despawnt ALLE bots die eerder via /botspawn aan deze speler gekoppeld zijn.
     */
    public static int despawnBot(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();

        UUID playerId = player.getUUID();
        List<UUID> bots = PLAYER_BOTS.getOrDefault(playerId, Collections.emptyList());

        int removed = 0;

        for (UUID botId : bots) {
            Entity bot = level.getEntity(botId);
            if (bot != null) {
                bot.discard();
                removed++;
            }
        }

        PLAYER_BOTS.remove(playerId);

        final int finalRemoved = removed;

        if (finalRemoved == 0) {
            source.sendSuccess(
                    () -> Component.literal("Er waren geen bots gekoppeld aan jou om te despawnen.")
                                   .withStyle(ChatFormatting.YELLOW),
                    false
            );
        } else {
            source.sendSuccess(
                    () -> Component.literal("Gedespawned: " + finalRemoved + " bot(s).")
                                   .withStyle(ChatFormatting.GREEN),
                    true
            );
        }

        return Command.SINGLE_SUCCESS;
    }
}
