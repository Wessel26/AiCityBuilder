package com.example.aicitybuilder.structures;

import com.example.aicitybuilder.cityaibot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class StructurePlacer {
    private static final Logger LOGGER = LogManager.getLogger();

    private StructurePlacer() {}

    public static boolean placeStage(ServerLevel level, ResourceLocation templateId, BlockPos origin, Rotation rot, Mirror mir) {
        try {
            StructureTemplateManager mgr = level.getStructureManager();
            StructureTemplate tpl = mgr.getOrCreate(templateId);
            if (tpl == null || tpl.getSize().equals(BlockPos.ZERO)) {
                // fallback: place a marker block so you can see something happened
                level.setBlock(origin, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                LOGGER.warn("[{}] Missing/empty template {}, placed marker instead.", cityaibot.MODID, templateId);
                return false;
            }

            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(rot)
                    .setMirror(mir)
                    .setIgnoreEntities(false);

            tpl.placeInWorld(level, origin, origin, settings, level.random, 3);
            return true;
        } catch (Exception e) {
            level.setBlock(origin, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            LOGGER.error("[{}] Failed placing template {} at {}", cityaibot.MODID, templateId, origin, e);
            return false;
        }
    }
}
