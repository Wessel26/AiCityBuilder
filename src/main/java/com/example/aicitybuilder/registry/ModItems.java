package com.example.aicitybuilder.registry;

import com.example.aicitybuilder.cityaibot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, cityaibot.MODID);

    public static final RegistryObject<Item> VILLAGE_CENTER =
            ITEMS.register("village_center", () ->
                    new BlockItem(ModBlocks.VILLAGE_CENTER.get(), new Item.Properties())
            );
}
