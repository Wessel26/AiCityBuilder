package com.example.aicitybuilder;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * Simpele menu-class voor de bot-inventory (4 rijen van 9).
 * Gebruikt het standaard GENERIC_9x4 MenuType, zodat de vanilla chest-GUI wordt gebruikt.
 */
public class BotInventoryMenu extends ChestMenu {

    public BotInventoryMenu(int id, Inventory playerInventory, SimpleContainer botInventory) {
        super(MenuType.GENERIC_9x4, id, playerInventory, botInventory, 4);
    }
}
