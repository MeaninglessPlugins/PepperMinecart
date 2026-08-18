package com.pepperminecart.display;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Minecart;

/**
 * 原版显示方块 API（Minecart#setDisplayBlockData / DisplayState NBT）的封装：
 * 方块作为矿车实体自身属性渲染，随实体自动持久化，矿车死亡随之消失。
 */
public final class CartBlockDisplay {

    private final Minecart cart;

    public CartBlockDisplay(Minecart cart) {
        this.cart = cart;
    }

    public void set(Material material) {
        if (material == null) {
            throw new IllegalArgumentException("显示方块材质不能为 null");
        }
        if (!material.isBlock()) {
            throw new IllegalArgumentException("显示方块必须是方块材质: " + material);
        }
        cart.setDisplayBlockData(material.createBlockData());
    }

    public void set(BlockData data) {
        if (data == null) {
            throw new IllegalArgumentException("显示方块数据不能为 null");
        }
        cart.setDisplayBlockData(data);
    }

    public void clear() {
        cart.setDisplayBlockData(Material.AIR.createBlockData());
    }

    public boolean has() {
        BlockData d = cart.getDisplayBlockData();
        return d != null && !d.getMaterial().isAir();
    }

    public Material material() {
        BlockData d = cart.getDisplayBlockData();
        return d == null ? Material.AIR : d.getMaterial();
    }
}
