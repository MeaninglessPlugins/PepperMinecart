package org.eu.pcraft.pepperminecart.feature.vanilla;

import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.eu.pcraft.pepperminecart.config.MainConfigModule;
import org.eu.pcraft.pepperminecart.feature.CartFeature;
import org.eu.pcraft.pepperminecart.feature.FeatureContext;

import java.util.Map;

/**
 * 原版特殊矿车（实体形态）：放置时转为对应实体，取下时还原为普通矿车。
 * TNT/命令方块等无库存类型直接用本类；有库存/燃料的用子类覆写 fillContainer。
 */
public class VanillaCartFeature implements CartFeature {

    protected final Material material;
    protected final EntityType entityType;

    public VanillaCartFeature(Material material, EntityType entityType) {
        this.material = material;
        this.entityType = entityType;
    }

    @Override
    public String getName() {
        return material.name();
    }

    @Override
    public boolean onPlace(Minecart minecart, ItemStack placedItem, MainConfigModule config, FeatureContext ctx) {
        Entity newEntity = ctx.replaceMinecart(minecart, entityType);
        transferContents(newEntity, placedItem, ctx);
        return true;
    }

    /**
     * 放置时把方块物品的库存转入新矿车（默认无操作）
     */
    protected void transferContents(Entity newEntity, ItemStack placedItem, FeatureContext ctx) {}

    @Override
    public boolean onSneakInteract(Player player, Minecart minecart, ItemStack itemInHand, MainConfigModule config, FeatureContext ctx) {
        // 配置开关：是否允许取下该类型的原版矿车（未列出的一律默认允许）
        Map<String, Boolean> pickupConfig = config.getVanillaCartPickup();
        if (pickupConfig == null || !pickupConfig.getOrDefault(material.name(), true)) return false;

        ItemStack blockItem = buildBlockItem(minecart, ctx);
        if (blockItem == null) return false;
        if (!ctx.tryPickupIntoHand(player, blockItem)) return false;

        ctx.replaceMinecart(minecart, EntityType.MINECART);
        if (config.isSoundFeedback()) ctx.playPickupSound(minecart.getLocation());
        return true;
    }

    /**
     * 构建要取下的方块物品（子类覆写库存/燃料拷贝）
     */
    protected ItemStack buildBlockItem(Minecart minecart, FeatureContext ctx) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (minecart.getCustomName() != null) {
            meta.setDisplayName(minecart.getCustomName());
        }
        if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof Container container) {
            fillContainer(minecart, container, ctx);
            bsm.setBlockState(container);
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 把矿车内容填进方块物品的容器（默认无操作）
     */
    protected void fillContainer(Minecart minecart, Container container, FeatureContext ctx) {}
}
