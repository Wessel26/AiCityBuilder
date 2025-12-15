package com.example.aicitybuilder;

import com.example.aicitybuilder.settlement.SettlementState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.EnumSet;
import java.util.UUID;

/**
 * AI-doel voor bots om hun inventory te legen in het dorps-opslag-huis.
 * Werkt samen met VillageData.getStoragePos().
 *
 * Stap 3:
 * - Als items daadwerkelijk in de chest verdwijnen, update de SettlementState ledger
 *   voor het settlement waar de bot bij hoort (bot.villageId).
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

        // Dichtbij genoeg: probeer te dumpen in de chest, anders droppen we items op de grond
        if (!(bot.level() instanceof ServerLevel serverLevel)) {
            finished = true;
            return;
        }

        // Settlement / ledger context (kan null zijn als bot nog niet gekoppeld is)
        UUID vid = bot.getVillageId();
        VillageManagerData manager = VillageManagerData.get(serverLevel);
        SettlementState state = (vid != null) ? manager.getState(vid) : null;

        BlockEntity be = serverLevel.getBlockEntity(storagePos);
        if (be instanceof ChestBlockEntity chest) {
            // Stop alle items in de chest
            for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
                ItemStack stack = bot.getInventory().getItem(i);
                if (stack.isEmpty()) continue;

                // We rekenen moved op basis van count-delta
                int before = stack.getCount();
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

                ItemStack remaining = stack;

                for (int slot = 0; slot < chest.getContainerSize() && !remaining.isEmpty(); slot++) {
                    ItemStack chestStack = chest.getItem(slot);

                    if (chestStack.isEmpty()) {
                        // alles past in lege slot
                        chest.setItem(slot, remaining);
                        remaining = ItemStack.EMPTY;
                        break;
                    }

                    if (ItemStack.isSameItemSameTags(chestStack, remaining)
                            && chestStack.getCount() < chestStack.getMaxStackSize()) {

                        int space = chestStack.getMaxStackSize() - chestStack.getCount();
                        int toMove = Math.min(space, remaining.getCount());

                        if (toMove > 0) {
                            chestStack.grow(toMove);
                            remaining.shrink(toMove);
                        }
                    }
                }

                // Update bot inventory
                bot.getInventory().setItem(i, remaining);

                // Ledger update: alleen wat echt uit bot-inventory is verdwenen
                int after = remaining.isEmpty() ? 0 : remaining.getCount();
                int moved = before - after;

                if (state != null && moved > 0) {
                    state.add(itemId, moved);
                    manager.setDirty();
                }
            }

            chest.setChanged();
        } else {
            // Geen geldige chest? Drop dan alles op de grond (geen ledger update)
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
