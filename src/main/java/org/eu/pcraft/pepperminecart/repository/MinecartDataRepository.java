package org.eu.pcraft.pepperminecart.repository;

import de.tr7zw.changeme.nbtapi.NBT;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.ItemStack;

/**
 * 负责矿车上自定义方块物品的 NBT 持久化读写
 */
public class MinecartDataRepository {

    private static final String BLOCK_INFO_KEY = "BlockInfo";

    public ItemStack getBlockItem(Minecart minecart) {
        return NBT.getPersistentData(minecart, nbt -> nbt.getItemStack(BLOCK_INFO_KEY));
    }

    public void setBlockItem(Minecart minecart, ItemStack item) {
        NBT.modifyPersistentData(minecart, nbt -> {
            nbt.setItemStack(BLOCK_INFO_KEY, item);
        });
    }

    public void removeBlockItem(Minecart minecart) {
        NBT.modifyPersistentData(minecart, nbt -> {
            nbt.removeKey(BLOCK_INFO_KEY);
        });
    }
}
