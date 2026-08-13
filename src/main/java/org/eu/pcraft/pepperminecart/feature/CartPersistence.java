package org.eu.pcraft.pepperminecart.feature;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.ItemStack;
import org.eu.pcraft.pepperminecart.repository.MinecartDataRepository;

import java.util.ArrayList;

/**
 * 矿车方块物品的 NBT 持久化与矿车形态操作
 */
final class CartPersistence {

    private final MinecartDataRepository repository = new MinecartDataRepository();

    ItemStack getBlockItem(Minecart minecart) {
        return repository.getBlockItem(minecart);
    }

    /** 轻量判断：是否挂有 BlockInfo 键（不反序列化物品，供热路径使用） */
    boolean hasBlockInfo(Minecart minecart) {
        return repository.hasBlockInfo(minecart);
    }

    void setBlockItem(Minecart minecart, ItemStack item) {
        repository.setBlockItem(minecart, item);
    }

    void removeBlockItem(Minecart minecart) {
        repository.removeBlockItem(minecart);
    }

    /**
     * 用指定实体类型替换矿车实体（原版特殊矿车转换/还原共用），返回新实体。
     * 同步迁移自定义名与乘客，避免转换后丢失命名/乘客。
     */
    Entity replaceMinecart(Minecart oldCart, EntityType newType) {
        Location loc = oldCart.getLocation();
        Entity newEntity = oldCart.getWorld().spawnEntity(loc, newType);
        newEntity.setVelocity(oldCart.getVelocity());
        newEntity.setRotation(loc.getYaw(), loc.getPitch());
        if (oldCart.customName() != null) {
            newEntity.customName(oldCart.customName());
            newEntity.setCustomNameVisible(oldCart.isCustomNameVisible());
        }
        for (Entity passenger : new ArrayList<>(oldCart.getPassengers())) {
            oldCart.removePassenger(passenger);
            newEntity.addPassenger(passenger);
        }
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
