package com.example.aicitybuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, cityaibot.MODID);

    public static final RegistryObject<EntityType<BotEntity>> PLAYER_BOT =
            ENTITIES.register("player_bot", () ->
                    EntityType.Builder
                            .<BotEntity>of(BotEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F) // zelfde hitbox als speler
                            .build(new ResourceLocation(cityaibot.MODID, "player_bot").toString())
            );
}
