package com.example.aicitybuilder;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

public class CreateVillageCommand {

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("createvillage")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> createAtPlayer(ctx.getSource(), null))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 512))
                                .executes(ctx -> createAtPlayer(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"))))
        );
    }

    private static int createAtPlayer(CommandSourceStack source, Integer radiusOpt) {
        if (!(source.getEntity() instanceof Player)) {
            source.sendFailure(Component.literal("Dit command moet door een speler gebruikt worden."));
            return 0;
        }
        Player player = (Player) source.getEntity();
        ServerLevel level = source.getLevel();
        BlockPos center = player.blockPosition();

        VillageData data = VillageData.get(level);

        data.setCenter(center);

        // Als je later radius toevoegt, kun je dat hier opslaan.
        // (nu doen we er nog niets mee, zodat het compile-safe blijft)
        // if (radiusOpt != null) data.setRadius(radiusOpt);

        VillageStructures.ensureStorageHouse(level, data);

        BlockPos storage = data.getStoragePos();
        source.sendSuccess(() -> Component.literal(
                        "Village aangemaakt! Center: " + center.toShortString()
                                + (storage != null ? (" | Storage: " + storage.toShortString()) : "")
                ),
                true
        );

        return 1;
    }
}
