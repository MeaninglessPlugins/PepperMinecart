package org.eu.pcraft.pepperminecart.holder;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

@Getter
public class MinecartChestHolder implements InventoryHolder {
    @Setter
    private Inventory inventory;
    Minecart minecart;
    public MinecartChestHolder (Minecart m) {
        minecart = m;
    }
}
