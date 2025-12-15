package com.example.aicitybuilder;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import com.example.aicitybuilder.registry.ModBlocks;
import com.example.aicitybuilder.registry.ModItems;

@Mod(cityaibot.MODID)
public class cityaibot {

    // Dit MOET gelijk zijn aan de modId in mods.toml
    public static final String MODID = "aicitybuilder";

    public cityaibot() {
        // Mod event bus
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Registreer onze entity types
        ModEntities.ENTITIES.register(modEventBus);

        // Registreer blocks/items
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);

        // Forge event bus voor dingen als commands
        MinecraftForge.EVENT_BUS.register(this);

        // Village job planner (tickets)
        MinecraftForge.EVENT_BUS.register(new com.example.aicitybuilder.village.VillageJobPlanner());

        // New settlement + datapack registry hooks
        MinecraftForge.EVENT_BUS.register(new ModEvents());

        // Attributes registreren
        modEventBus.addListener(this::onEntityAttributeCreate);
    }

    private void onEntityAttributeCreate(EntityAttributeCreationEvent event) {
        event.put(ModEntities.PLAYER_BOT.get(), BotEntity.createAttributes().build());
    }

    /**
     * Registreer de /botspawn en /botdespawn commands.
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // /botspawn
        dispatcher.register(
                Commands.literal("botspawn")
                        // Permission level 2 ≈ operator
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> BotCommands.spawnBot(ctx.getSource()))
        );

        // /botdespawn
        dispatcher.register(
                Commands.literal("botdespawn")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> BotCommands.despawnBot(ctx.getSource()))
        );

        // /createvillage [radius]
        CreateVillageCommand.register(dispatcher);
    }
}
