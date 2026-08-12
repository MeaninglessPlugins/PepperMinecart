package org.eu.pcraft.pepperminecart.feature;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.ItemStack;
import org.eu.pcraft.pepperminecart.repository.MinecartDataRepository;

/**
 * 矿车方块物品的 NBT 持久化与矿车形态操作
 */
final class CartPersistence {

    private final MinecartDataRepository repository = new MinecartDataRepository();

    ItemStack getBlockItem(Minecart minecart) {
        return repository.getBlockItem(minecart);
    }

    void setBlockItem(Minecart minecart, ItemStack item) {
        repository.setBlockItem(minecart, item);
    }

    void removeBlockItem(Minecart minecart) {
        repository.removeBlockItem(minecart);
    }

    ItemStack getFurnaceMinecartFuel(Minecart minecart) {
        return repository.getFurnaceMinecartFuel(minecart);
    }

    void setFurnaceMinecartFuel(Minecart minecart, ItemStack fuel) {
        repository.setFurnaceMinecartFuel(minecart, fuel);
    }

    /**
     * 用指定实体类型替换矿车实体（原版特殊矿车转换/还原共用），返回新实体
     */
    Entity replaceMinecart(Minecart oldCart, EntityType newType) {
        Entity newEntity = oldCart.getWorld().spawnEntity(oldCart.getLocation(), newType);
        newEntity.setVelocity(oldCart.getVelocity());
        newEntity.setRotation(oldCart.getLocation().getYaw(), oldCart.getLocation().getPitch());
        oldCart.remove();
        return newEntity;
    }

    /**
     * 以自定义矿车形态存放方块：写 BlockInfo + 显示方块
     */
    void storeCustomBlock(Minecart minecart, ItemStack item) {
        setBlockItem(minecart, item);
        minecart.setDisplayBlockData(item.getType().createBlockData());
    }

    /**
     * 清空自定义矿车上的方块
     */
    void clearCustomBlock(Minecart minecart) {
        removeBlockItem(minecart);
        minecart.setDisplayBlockData(Material.AIR.createBlockData());
    }
}
