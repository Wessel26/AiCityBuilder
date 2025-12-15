package com.example.aicitybuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.inventory.ContainerData;

import java.util.EnumSet;

/**
 * AI-doel voor bots om hun inventory te legen in het dorps-opslag-huis.
 * Werkt samen met VillageData.getStoragePos().
 */
public class DepositToStorageGoal extends Goal {

    private final BotEntity bot;
    private BlockPos storagePos;
    private boolean finished;

    public DepositToStorageGoal(BotEntity bot) {
        this.bot = bot;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (bot.level().isClientSide) return false;
        VillageData village = bot.getVillageData();
        if (village == null || !village.hasStoragePos()) return false;

        // Check of bot überhaupt iets in z'n inventory heeft
        boolean hasItems = false;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            if (!bot.getInventory().getItem(i).isEmpty()) {
                hasItems = true;
                break;
            }
        }
        if (!hasItems) return false;

        this.storagePos = village.getStoragePos();
        this.finished = false;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (finished) return false;
        if (bot.level().isClientSide) return false;
        // Stoppen als inventory leeg is
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            if (!bot.getInventory().getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void tick() {
        if (storagePos == null) {
            finished = true;
            return;
        }

        double distSq = bot.distanceToSqr(
                storagePos.getX() + 0.5D,
                storagePos.getY() + 1.0D,
                storagePos.getZ() + 0.5D
        );

        // Nog niet dichtbij genoeg → lopen
        if (distSq > 4.0D) {
            bot.getNavigation().moveTo(
                    storagePos.getX() + 0.5D,
                    storagePos.getY() + 1.0D,
                    storagePos.getZ() + 0.5D,
                    1.0D
            );
            return;
        }

        // Dichtbij genoeg: probeer te dumpen in de chest, anders droppen we gewoon items op de grond
        if (!(bot.level() instanceof ServerLevel serverLevel)) {
            finished = true;
            return;
        }

        BlockEntity be = serverLevel.getBlockEntity(storagePos);
        if (be instanceof ChestBlockEntity chest) {
            // Stop alle items in de chest
            for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
                ItemStack stack = bot.getInventory().getItem(i);
                if (stack.isEmpty()) continue;

                ItemStack remaining = stack;

                for (int slot = 0; slot < chest.getContainerSize() && !remaining.isEmpty(); slot++) {
                    ItemStack chestStack = chest.getItem(slot);
                    if (chestStack.isEmpty()) {
                        chest.setItem(slot, remaining);
                        remaining = ItemStack.EMPTY;
                        break;
                    } else if (ItemStack.isSameItemSameTags(chestStack, remaining) &&
                               chestStack.getCount() < chestStack.getMaxStackSize()) {
                        int space = chestStack.getMaxStackSize() - chestStack.getCount();
                        int toMove = Math.min(space, remaining.getCount());
                        chestStack.grow(toMove);
                        remaining.shrink(toMove);
                    }
                }

                bot.getInventory().setItem(i, remaining);
            }

            chest.setChanged();
        } else {
            // Geen geldige chest? Drop dan alles op de grond
            for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
                ItemStack stack = bot.getInventory().getItem(i);
                if (!stack.isEmpty()) {
                    bot.spawnAtLocation(stack);
                    bot.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
        }

        finished = true;
    }
}
