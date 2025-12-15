package com.example.aicitybuilder;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public class VillageProjectCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("village")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("project")
                    .then(Commands.literal("start")
                        .then(Commands.literal("tiny_house")
                            .executes(ctx -> startTinyHouse(ctx.getSource()))
                        )
                    )
                    .then(Commands.literal("stop")
                        .executes(ctx -> stop(ctx.getSource()))
                    )
                )
        );
    }

    private static int startTinyHouse(CommandSourceStack source) {
        if (!(source.getEntity() instanceof Player)) {
            source.sendFailure(Component.literal("Gebruik dit als speler."));
            return 0;
        }

        // In 1.20.1 is dit al ServerLevel
        var level = source.getLevel();

        VillageData data = VillageData.get(level);
        if (!data.hasCenter()) {
            source.sendFailure(Component.literal("Geen village center gezet. Plaats eerst Village Center block of /createvillage."));
            return 0;
        }

        // Build origin: 6 blocks naast center
        BlockPos origin = data.getCenter().offset(6, 0, 0);

        data.startProject("tiny_house", origin);

        source.sendSuccess(() -> Component.literal("Project gestart: tiny_house @ " + origin.toShortString()), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int stop(CommandSourceStack source) {
        var level = source.getLevel();
        VillageData data = VillageData.get(level);

        data.clearProject();
        source.sendSuccess(() -> Component.literal("Project gestopt."), true);
        return Command.SINGLE_SUCCESS;
    }
}
