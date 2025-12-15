package com.example.aicitybuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Eenvoudige wereldgeneratie voor dorpsstructuren.
 * Voor nu: een klein opslag-huis bij het village-center, met chest, crafting table en furnace.
 */
public class VillageStructures {

    /**
     * Zorgt dat er een simpel opslag-huis bestaat voor het gegeven dorp.
     * Als er al een storagePos is ingesteld, wordt er niets gedaan.
     */
    public static void ensureStorageHouse(ServerLevel level, VillageData data) {
        if (!data.hasCenter()) {
            return;
        }
        if (data.hasStoragePos()) {
            return;
        }

        BlockPos center = data.getCenter();

        // Kies een origin net naast het center zodat we ruimte hebben
        BlockPos origin = center.offset(3, 0, 0);
        int baseY = origin.getY();

        BlockState floor = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState wall = Blocks.OAK_LOG.defaultBlockState();
        BlockState roof = Blocks.OAK_PLANKS.defaultBlockState();

        int size = 5;

        // Vloer (y = baseY)
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                BlockPos p = origin.offset(x, 0, z);
                level.setBlock(p, floor, 3);
            }
        }

        // Muren (rand, hoogte 3, y=baseY+1..baseY+3)
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    boolean edge = x == 0 || x == size - 1 || z == 0 || z == size - 1;
                    if (edge) {
                        BlockPos p = origin.offset(x, y, z);
                        level.setBlock(p, wall, 3);
                    }
                }
            }
        }

        // Dak (vlak, op y=baseY+4)
        int roofY = 4;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                BlockPos p = origin.offset(x, roofY, z);
                level.setBlock(p, roof, 3);
            }
        }

        // Simpele deur-opening aan één kant (vervang log door lucht)
        BlockPos door1 = origin.offset(2, 1, 0);
        BlockPos door2 = origin.offset(2, 2, 0);
        level.removeBlock(door1, false);
        level.removeBlock(door2, false);

        // Binnenin: chest, crafting table en furnace
        BlockPos chestPos = origin.offset(1, 1, 2);
        BlockPos craftPos = origin.offset(2, 1, 2);
        BlockPos furnacePos = origin.offset(3, 1, 2);

        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(craftPos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState(), 3);

        // Sla storagePos (center van opslag: de chest) op in VillageData
        data.setStoragePos(chestPos);
    }
}
