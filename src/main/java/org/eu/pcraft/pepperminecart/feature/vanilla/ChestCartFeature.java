package org.eu.pcraft.pepperminecart.feature.vanilla;

import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.eu.pcraft.pepperminecart.feature.FeatureContext;

/**
 * 箱子矿车：放置转移箱子库存，取下还原箱子物品内容
 */
public class ChestCartFeature extends VanillaCartFeature {

    public ChestCartFeature(Material material, EntityType entityType) {
        super(material, entityType);
    }

    @Override
    protected void transferContents(Entity newEntity, ItemStack placedItem, FeatureContext ctx) {
        if (!(newEntity instanceof StorageMinecart storage)) return;
        if (!(placedItem.getItemMeta() instanceof BlockStateMeta meta)) return;
        if (!(meta.getBlockState() instanceof Chest chest)) return;
        storage.getInventory().setContents(chest.getInventory().getContents());
    }

    @Override
    protected void fillContainer(Minecart minecart, Container container, FeatureContext ctx) {
        if (minecart instanceof StorageMinecart holder) {
            container.getInventory().setContents(holder.getInventory().getContents());
        }
    }
}
