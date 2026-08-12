package org.eu.pcraft.pepperminecart.feature.vanilla;

import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.eu.pcraft.pepperminecart.feature.FeatureContext;

/**
 * 熔炉矿车：取下时把燃料槽映射到方块物品（矿车第 0 格 -> 物品燃料槽第 1 格）
 */
public class FurnaceCartFeature extends VanillaCartFeature {

    public FurnaceCartFeature(Material material, EntityType entityType) {
        super(material, entityType);
    }

    @Override
    protected void transferContents(Entity newEntity, ItemStack placedItem, FeatureContext ctx) {
        if (!(newEntity instanceof Minecart minecart)) return;
        if (!(placedItem.getItemMeta() instanceof BlockStateMeta meta)) return;
        if (!(meta.getBlockState() instanceof Container container)) return;
        ItemStack fuel = container.getInventory().getItem(1);
        if (fuel != null && !fuel.getType().isAir()) {
            ctx.setFurnaceMinecartFuel(minecart, fuel);
        }
    }

    @Override
    protected void fillContainer(Minecart minecart, Container container, FeatureContext ctx) {
        ItemStack fuel = readFurnaceFuel(minecart, ctx);
        if (fuel != null) {
            container.getInventory().setItem(1, fuel);
        }
    }

    /**
     * 读取熔炉矿车燃料：优先走 InventoryHolder 第 0 格，失败/为空时回退实体 NBT
     */
    private ItemStack readFurnaceFuel(Minecart minecart, FeatureContext ctx) {
        if (minecart instanceof InventoryHolder holder) {
            ItemStack fuel = holder.getInventory().getItem(0);
            if (fuel != null && !fuel.getType().isAir()) return fuel;
        }
        return ctx.getFurnaceMinecartFuel(minecart);
    }
}
