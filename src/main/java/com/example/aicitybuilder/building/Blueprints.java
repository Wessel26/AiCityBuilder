package com.example.aicitybuilder.building;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class Blueprints {

    public record BuildStep(BlockPos offset, BlockState state) {}

    public static List<BuildStep> get(String id) {
        if ("tiny_house".equals(id)) {
            return tinyHouse();
        }
        return List.of();
    }

    private static List<BuildStep> tinyHouse() {
        List<BuildStep> steps = new ArrayList<>();

        // 5x5 vloer
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                steps.add(new BuildStep(new BlockPos(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState()));
            }
        }

        // muren (hoogte 3)
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < 5; x++) {
                for (int z = 0; z < 5; z++) {
                    boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                    if (edge) {
                        steps.add(new BuildStep(new BlockPos(x, y, z), Blocks.OAK_LOG.defaultBlockState()));
                    }
                }
            }
        }

        // dak
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                steps.add(new BuildStep(new BlockPos(x, 4, z), Blocks.OAK_PLANKS.defaultBlockState()));
            }
        }

        // deur opening (lucht)
        steps.add(new BuildStep(new BlockPos(2, 1, 0), Blocks.AIR.defaultBlockState()));
        steps.add(new BuildStep(new BlockPos(2, 2, 0), Blocks.AIR.defaultBlockState()));

        return steps;
    }
}
