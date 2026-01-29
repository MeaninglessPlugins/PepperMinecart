package org.eu.pcraft.pepperminecart.manager;

import de.tr7zw.changeme.nbtapi.NBT;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.*;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.eu.pcraft.pepperminecart.PepperMinecart;
import org.eu.pcraft.pepperminecart.holder.MinecartChestHolder;

import java.util.function.Consumer;

public class MinecartManager {

    private final PepperMinecart plugin;

    public MinecartManager(PepperMinecart plugin) {
        this.plugin = plugin;
    }


    // --- 数据获取 ---

    /**
     * 获取矿车上存储的自定义方块物品
     */
    public ItemStack getItemOnMinecart(Minecart minecart) {
        return NBT.getPersistentData(minecart, nbt -> nbt.getItemStack("BlockInfo"));
    }

    // --- 核心业务操作 ---

    /**
     * 在矿车上放置方块（或转换为特殊矿车）
     */
    public void placeBlock(Minecart minecart, ItemStack placedItem) {
        Material material = placedItem.getType();

        // 1. 检查是否需要转换为原版特殊矿车
        EntityType newType = MinecartRegistry.getTransformation(material);
        if (newType != null) {
            replaceMinecart(minecart, newType, placedItem);
            return;
        }

        // 2. 普通方块：保存数据到 NBT 并设置显示
        NBT.modifyPersistentData(minecart, nbt -> {
            nbt.setItemStack("BlockInfo", placedItem);
        });
        // 设置原版显示方块
        minecart.setDisplayBlockData(material.createBlockData());
        //minecart.setDisplayBlockOffset(6);
    }

    /**
     * 移除矿车上的方块
     */
    public void removeBlock(Minecart minecart) {
        NBT.modifyPersistentData(minecart, nbt -> {
            nbt.removeKey("BlockInfo");
        });
        minecart.setDisplayBlockData(Material.AIR.createBlockData());
    }

    /**
     * 替换矿车实体
     */
    private void replaceMinecart(Minecart oldCart, EntityType newType, ItemStack placedItem) {
        Entity newEntity = oldCart.getWorld().spawnEntity(oldCart.getLocation(), newType);
        newEntity.setVelocity(oldCart.getVelocity());
        newEntity.setRotation(oldCart.getLocation().getYaw(), oldCart.getLocation().getPitch());
        if (placedItem.getType() == Material.CHEST) {
            BlockStateMeta meta = (BlockStateMeta) placedItem.getItemMeta();
            Chest chest = (Chest) meta.getBlockState();
            ((StorageMinecart) newEntity).getInventory().setContents(chest.getInventory().getContents());
        }
        oldCart.remove();
    }

    /**
     * 保存 ShulkerBox 数据并清理 Holder
     */
    public void saveAndCloseShulkerBox(MinecartChestHolder holder) {
        Minecart minecart = holder.getMinecart();
        ItemStack boxItem = getItemOnMinecart(minecart);

        if (boxItem != null && boxItem.getItemMeta() instanceof BlockStateMeta meta) {
            if (meta.getBlockState() instanceof ShulkerBox shulkerBox) {
                // 同步库存数据
                shulkerBox.getInventory().setContents(holder.getInventory().getContents());
                meta.setBlockState(shulkerBox);
                boxItem.setItemMeta(meta);

                // 写入 NBT
                NBT.modifyPersistentData(minecart, nbt -> {
                    nbt.setItemStack("BlockInfo", boxItem);
                });
            }
        }
        plugin.getHolderMap().remove(minecart);
    }

    /**
     * 处理普通方块/工作台交互
     */
    public boolean handleWorkstationInteract(Player player, ItemStack stack) {
        Consumer<Player> consumer = MinecartRegistry.getInteraction(stack.getType());
        if (consumer != null) {
            consumer.accept(player);
            return true;
        }
        return false;
    }

    /**
     * 处理 ShulkerBox 打开逻辑
     */
    public boolean handleShulkerBoxInteract(Player player, Minecart minecart, ItemStack stack) {
        if (!stack.getType().name().endsWith("SHULKER_BOX")) {
            return false;
        }

        MinecartChestHolder holder = plugin.getHolderMap().computeIfAbsent(minecart, k -> {
            MinecartChestHolder newHolder = new MinecartChestHolder(minecart);
            Inventory inv = Bukkit.createInventory(newHolder, 27);

            if (stack.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox box) {
                inv.setContents(box.getInventory().getContents());
            }
            newHolder.setInventory(inv);
            return newHolder;
        });

        player.openInventory(holder.getInventory());
        return true;
    }

    /**
     * 销毁矿车时的清理工作
     */
    public void handleCartDestruction(Minecart minecart) {
        MinecartChestHolder holder = plugin.getHolderMap().get(minecart);
        if (holder != null) {
            new java.util.ArrayList<>(holder.getInventory().getViewers()).forEach(HumanEntity::closeInventory);
            // 触发 InventoryCloseEvent 调用 saveAndCloseShulkerBox()
        }
        ItemStack item = getItemOnMinecart(minecart);
        if (item != null) {
            minecart.getWorld().dropItem(minecart.getLocation(), item);
        }
    }
    /**
     * 获取原版矿车上的方块
     */
    public static ItemStack getBlockOnVanillaMinecart(Minecart minecart) {
        // 1. 直接查表获取对应的方块材质
        Material mat = MinecartRegistry.getTransformation(minecart.getType());

        // 如果表里没有(比如普通矿车)，null
        if (mat == null) return null;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        // 2. 复制名称
        if (minecart.getCustomName() != null) {
            meta.setDisplayName(minecart.getCustomName());
        }

        // 3. 复制容器内容 (利用 instanceof 模式匹配简化代码)
        if (minecart instanceof InventoryHolder cartHolder && meta instanceof BlockStateMeta bsm) {
            BlockState state = bsm.getBlockState();
            if (state instanceof Container itemContainer) {
                // 复制库存
                itemContainer.getInventory().setContents(cartHolder.getInventory().getContents());
                bsm.setBlockState(state);
            }
        }

        item.setItemMeta(meta);
        return item;
    }
}
