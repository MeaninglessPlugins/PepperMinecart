package org.eu.pcraft.pepperminecart.service;

import lombok.Setter;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.eu.pcraft.pepperminecart.config.MainConfigModule;
import org.eu.pcraft.pepperminecart.feature.CartFeature;
import org.eu.pcraft.pepperminecart.feature.FeatureContext;
import org.eu.pcraft.pepperminecart.feature.FeatureRegistry;
import org.eu.pcraft.pepperminecart.registry.MinecartRegistry;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 矿车核心业务分发器：自定义矿车与原版特殊矿车统一按"代表的方块材质"解析特性，
 * 不再按实体类型 instanceof 分支。通用放置/取下/会话生命周期留在此处。
 */
public class MinecartService {

    private final FeatureContext featureContext = new FeatureContext();
    private final PlayerCooldownManager cooldownManager = new PlayerCooldownManager();

    /**
     *  配置重载后替换注册表
     */
    @Setter
    private MinecartRegistry registry;
    @Setter
    private FeatureRegistry featureRegistry;

    public MinecartService(MinecartRegistry registry, FeatureRegistry featureRegistry) {
        this.registry = registry;
        this.featureRegistry = featureRegistry;
    }

    // --- 数据获取 ---

    public ItemStack getBlockItem(Minecart minecart) {
        return featureContext.getBlockItem(minecart);
    }

    // --- 核心业务操作 ---

    /**
     * 在矿车上放置方块（或转换为特殊矿车）
     */
    public void placeBlock(Minecart minecart, ItemStack placedItem, MainConfigModule config) {
        CartFeature feature = featureRegistry.get(placedItem.getType());
        if (feature == null || !feature.onPlace(minecart, placedItem, config, featureContext)) {
            // 默认：自定义矿车形态
            featureContext.storeCustomBlock(minecart, placedItem);
        }
    }

    // --- 交互逻辑 ---

    /**
     * 非潜行交互，返回是否应取消事件
     */
    public boolean handleStandInteract(Player player, Minecart minecart) {
        if (!(minecart instanceof RideableMinecart)) return false;
        ItemStack itemOnCart = getBlockItem(minecart);
        if (itemOnCart == null) return false;

        CartFeature feature = featureRegistry.get(itemOnCart.getType());
        if (feature != null && feature.onStandInteract(player, minecart, itemOnCart, featureContext)) {
            return true;
        }
        // 不可交互的方块：仍取消事件，禁止乘坐
        return itemOnCart.getType() != Material.AIR;
    }

    /**
     * 潜行交互统一分发：自定义矿车与原版特殊矿车都按"代表的方块材质"解析特性
     */
    public boolean handleSneakInteract(Player player, Minecart minecart, ItemStack itemInHand, MainConfigModule config) {
        Material material = resolveMaterial(minecart);
        if (material == null) {
            // 空矿车：仅普通矿车上允许放置方块
            if (minecart.getType() != EntityType.MINECART) return false;
            return placeOnEmpty(player, minecart, itemInHand, config);
        }

        CartFeature feature = featureRegistry.get(material);
        // 特性的取下逻辑（原版特殊矿车）
        if (feature != null && feature.onSneakInteract(player, minecart, itemInHand, config, featureContext)) {
            return true;
        }
        // 原版特殊矿车：取下失败/被禁用时放行，保持原版交互
        if (registry.getTransformation(minecart.getType()) != null) {
            return false;
        }
        // 自定义矿车：通用取下；无论成败都取消事件（禁止乘坐）
        genericPickup(player, minecart, config);
        return true;
    }

    /**
     * 统一识别矿车代表的方块材质：自定义矿车取车上 BlockInfo，原版特殊矿车按实体类型反查
     */
    private Material resolveMaterial(Minecart minecart) {
        ItemStack item = featureContext.getBlockItem(minecart);
        if (item != null) return item.getType();
        return registry.getTransformation(minecart.getType());
    }

    /**
     * 按矿车代表的方块材质解析特性（原版特殊矿车也能解析到）
     */
    private CartFeature resolveFeature(Minecart minecart) {
        Material material = resolveMaterial(minecart);
        return material != null ? featureRegistry.get(material) : null;
    }

    private boolean placeOnEmpty(Player player, Minecart minecart, ItemStack itemInHand, MainConfigModule config) {
        if (!itemInHand.getType().isBlock()) return false;
        ItemStack copyItem = itemInHand.asOne().clone();
        itemInHand.subtract(1);
        placeBlock(minecart, copyItem, config);
        if (config.isSoundFeedback()) featureContext.playPlaceSound(player, minecart.getLocation(), copyItem.getType());
        return true;
    }

    /**
     * 自定义矿车通用取下：把车上的方块物品放进主手并清空矿车
     */
    private void genericPickup(Player player, Minecart minecart, MainConfigModule config) {
        ItemStack item = featureContext.getBlockItem(minecart);
        if (item == null) return;
        if (!featureContext.tryPickupIntoHand(player, item)) return;
        featureContext.clearCustomBlock(minecart);
        if (config.isSoundFeedback()) featureContext.playPickupSound(player, minecart.getLocation());
    }

    /**
     * 交互冷却：返回 true 表示允许本次交互
     */
    public boolean tryConsumeCooldown(Player player, long cooldownMillis) {
        return cooldownManager.tryConsume(player, cooldownMillis);
    }

    public void clearCooldown(Player player) {
        cooldownManager.clear(player);
    }

    // --- 铁砧 ---

    /**
     * 玩家从矿车铁砧取走修复结果时调用，按配置概率造成损坏
     */
    public void handleAnvilUse(Player player, MainConfigModule config) {
        if (!config.isAnvilDamageEnabled()) return;
        Minecart minecart = featureContext.getAnvilSession(player.getUniqueId());
        if (minecart == null || !minecart.isValid()) return;
        ItemStack item = getBlockItem(minecart);
        if (item == null) return;
        CartFeature feature = featureRegistry.get(item.getType());
        if (feature != null) {
            feature.onDamageUse(player, minecart, config, featureContext);
        }
    }

    /**
     * 关闭界面时清理铁砧会话
     */
    public void clearAnvilSession(UUID playerId) {
        featureContext.clearAnvilSession(playerId);
    }

    // --- 会话生命周期 ---

    /**
     * 容器界面关闭时保存数据并清理会话
     */
    public void handleContainerClosed(Inventory inventory) {
        Minecart minecart = featureContext.getInventoryOwner(inventory);
        if (minecart == null) return;
        featureContext.removeInventoryOwner(inventory);
        ItemStack item = getBlockItem(minecart);
        CartFeature feature = item != null ? featureRegistry.get(item.getType()) : null;
        if (feature != null) {
            feature.onContainerClosed(minecart, featureContext);
        } else {
            featureContext.closeContainer(minecart);
        }
    }

    /**
     * 矿车移动激活分发（如投掷器压过充能激活铁轨）
     */
    public void handleDropperCartActivation(Minecart minecart, MainConfigModule config) {
        ItemStack item = getBlockItem(minecart);
        if (item == null) return;
        CartFeature feature = featureRegistry.get(item.getType());
        if (feature != null) {
            feature.onRailActivate(minecart, config, featureContext);
        }
    }

    /**
     * 销毁矿车时的清理工作
     */
    public void handleCartDestruction(Minecart minecart) {
        featureContext.clearAnvilSessions(minecart);
        CartFeature feature = resolveFeature(minecart);
        if (feature != null) {
            feature.onDestroy(minecart, featureContext);
        }
        // 关闭打开中的容器界面（关界面会回写保存）
        Inventory open = featureContext.getOpenInventory(minecart);
        if (open != null) {
            new ArrayList<>(open.getViewers()).forEach(HumanEntity::closeInventory);
        }
        // 兜底：若会话仍在，强制回写并清理
        if (featureContext.isContainerOpen(minecart)) {
            featureContext.flushContainer(minecart);
            featureContext.removeSession(minecart);
        }
        // 在回写之后重新读取物品，保证包含界面编辑，再交给特性决定如何掉落
        ItemStack freshItem = getBlockItem(minecart);
        if (feature != null) {
            feature.onDrop(minecart, freshItem, featureContext);
        } else if (freshItem != null) {
            minecart.getWorld().dropItem(minecart.getLocation(), freshItem);
        }
    }

    /**
     * 区块卸载时清理该区块内的会话，防止内存泄漏
     */
    public void handleChunkUnload(Chunk chunk) {
        for (Minecart minecart : featureContext.getOpenMinecarts()) {
            if (!minecart.isValid() || minecart.getChunk().equals(chunk)) {
                Inventory open = featureContext.getOpenInventory(minecart);
                if (open != null) {
                    new ArrayList<>(open.getViewers()).forEach(HumanEntity::closeInventory);
                }
                if (featureContext.isContainerOpen(minecart)) {
                    featureContext.flushContainer(minecart);
                    featureContext.removeSession(minecart);
                }
            }
        }
    }

    /**
     * 插件停用前保存所有打开的容器会话
     */
    public void saveAllSessions() {
        for (Minecart minecart : featureContext.getOpenMinecarts()) {
            Inventory open = featureContext.getOpenInventory(minecart);
            if (open != null) {
                new ArrayList<>(open.getViewers()).forEach(HumanEntity::closeInventory);
            }
            if (featureContext.isContainerOpen(minecart)) {
                featureContext.flushContainer(minecart);
                featureContext.removeSession(minecart);
            }
        }
    }
}
