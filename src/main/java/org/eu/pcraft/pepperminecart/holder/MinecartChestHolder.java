package org.eu.pcraft.pepperminecart.holder;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

@Getter
@AllArgsConstructor
public class MinecartChestHolder implements InventoryHolder {
    private Inventory inventory;
    Minecart minecart;
}
