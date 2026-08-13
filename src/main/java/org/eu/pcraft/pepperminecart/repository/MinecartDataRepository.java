package org.eu.pcraft.pepperminecart.repository;

import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.NbtApiException;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.ItemStack;

/**
 * 负责矿车上自定义方块物品的 NBT 持久化读写
 */
public class MinecartDataRepository {

    private static final String BLOCK_INFO_KEY = "BlockInfo";

    public ItemStack getBlockItem(Minecart minecart) {
        try {
            return NBT.getPersistentData(minecart, nbt -> nbt.getItemStack(BLOCK_INFO_KEY));
        } catch (NbtApiException e) {
            // BlockInfo 键存在但内容损坏：按无方块处理，避免炸掉调用它的热路径事件（如 VehicleMoveEvent）
            return null;
        }
    }

    /** 轻量判断：是否挂有 BlockInfo 键（不反序列化物品，供热路径使用） */
    public boolean hasBlockInfo(Minecart minecart) {
        return NBT.getPersistentData(minecart, nbt -> nbt.hasTag(BLOCK_INFO_KEY));
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
