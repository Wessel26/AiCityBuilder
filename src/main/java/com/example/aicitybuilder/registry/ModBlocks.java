package com.example.aicitybuilder.registry;

import com.example.aicitybuilder.blocks.VillageCenterBlock;
import com.example.aicitybuilder.cityaibot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, cityaibot.MODID);

    public static final RegistryObject<Block> VILLAGE_CENTER =
            BLOCKS.register("village_center", () ->
                    new VillageCenterBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(3.0F, 6.0F)
                            .requiresCorrectToolForDrops()
                    )
            );
}
