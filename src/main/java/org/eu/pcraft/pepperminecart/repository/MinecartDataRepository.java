package org.eu.pcraft.pepperminecart.repository;

import de.tr7zw.changeme.nbtapi.NBT;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 负责矿车上自定义方块物品的 NBT 持久化读写
 */
public class MinecartDataRepository {

    private static final String BLOCK_INFO_KEY = "BlockInfo";
    private static final String ITEMS_KEY = "Items";

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

    /**
     * 读取熔炉矿车燃料槽（实体 NBT "Items" 第 0 格），失败或为空时返回 null
     */
    @Nullable
    public ItemStack getFurnaceMinecartFuel(Minecart minecart) {
        try {
            ItemStack[] items = NBT.get(minecart, nbt -> {
                return nbt.getItemStackArray(ITEMS_KEY);
            });
            if (items == null || items.length == 0) return null;
            ItemStack fuel = items[0];
            if (fuel == null || fuel.getType().isAir()) return null;
            return fuel;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 写入熔炉矿车燃料槽（实体 NBT "Items" 第 0 格）
     */
    public void setFurnaceMinecartFuel(Minecart minecart, ItemStack fuel) {
        NBT.modify(minecart, nbt -> {
            nbt.setItemStackArray(ITEMS_KEY, new ItemStack[]{fuel});
            return null;
        });
    }
}
