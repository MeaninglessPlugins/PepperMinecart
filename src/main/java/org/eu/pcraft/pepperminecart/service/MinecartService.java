package org.eu.pcraft.pepperminecart.service;

import lombok.Setter;
import org.bukkit.Chunk;
import org.bukkit.Location;
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
import org.eu.pcraft.pepperminecart.feature.container.DropperFeature;
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

    /** 空矿车放置结果：区分"已放置 / 配置禁用 / 未处理（放行原版）" */
    private enum PlaceResult { PLACED, DISABLED, IGNORED }

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

    /**
     * 判定矿车上是否有方块：先用 Bukkit 内存属性（DisplayBlock）快速判定，
     * 显示方块为空时再回退到轻量的 NBT 键存在性判断（不反序列化整个物品），
     * 避免把 NBT 反序列化放进热路径。
     */
    public boolean hasBlockOnCart(Minecart minecart) {
        if (minecart.getDisplayBlockData().getMaterial() != Material.AIR) {
            return true;
        }
        return featureContext.hasBlockInfo(minecart);
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
        // 先 DisplayBlock 快速判定是否有方块，再按需读 NBT 取物品
        if (!hasBlockOnCart(minecart)) return false;
        ItemStack itemOnCart = getBlockItem(minecart);
        if (itemOnCart == null) return false;

        CartFeature feature = featureRegistry.get(itemOnCart.getType());
        if (feature != null && feature.onStandInteract(player, minecart, itemOnCart, featureContext)) {
            return true;
        }
        // 不可交互的方块：仍取消事件，禁止乘坐
        return true;
    }

    /**
     * 潜行交互统一分发：自定义矿车与原版特殊矿车都按"代表的方块材质"解析特性
     */
    public boolean handleSneakInteract(Player player, Minecart minecart, ItemStack itemInHand, MainConfigModule config) {
        Material material = resolveMaterial(minecart);
        if (material == null) {
            // 空矿车：仅普通矿车上允许放置方块
            if (minecart.getType() != EntityType.MINECART) return false;
            PlaceResult result = placeOnEmpty(player, minecart, itemInHand, config);
            if (result == PlaceResult.PLACED) {
                recordInteraction(player);
                return true;
            }
            // 配置禁用项同样取消事件（禁止放置），其余情况放行原版
            return result == PlaceResult.DISABLED;
        }

        CartFeature feature = featureRegistry.get(material);
        // 特性的取下逻辑（原版特殊矿车）
        if (feature != null && feature.onSneakInteract(player, minecart, itemInHand, config, featureContext)) {
            recordInteraction(player);
            return true;
        }
        // 原版特殊矿车：取下失败/被禁用时放行，保持原版交互
        if (registry.getTransformation(minecart.getType()) != null) {
            return false;
        }
        // 自定义矿车：通用取下；无论成败都取消事件（禁止乘坐）
        if (genericPickup(player, minecart, config)) {
            recordInteraction(player);
        }
        return true;
    }

    /**
     * 统一识别矿车代表的方块材质：自定义矿车取车上 BlockInfo，原版特殊矿车按实体类型反查
     */
    private Material resolveMaterial(Minecart minecart) {
        if (hasBlockOnCart(minecart)) {
            ItemStack item = featureContext.getBlockItem(minecart);
            if (item != null) return item.getType();
        }
        return registry.getTransformation(minecart.getType());
    }

    /**
     * 按矿车代表的方块材质解析特性（原版特殊矿车也能解析到）
     */
    private CartFeature resolveFeature(Minecart minecart) {
        Material material = resolveMaterial(minecart);
        return material != null ? featureRegistry.get(material) : null;
    }

    private PlaceResult placeOnEmpty(Player player, Minecart minecart, ItemStack itemInHand, MainConfigModule config) {
        Material type = itemInHand.getType();
        if (!type.isBlock()) return PlaceResult.IGNORED;
        // 写死转换表中被禁用的方块：禁止放到矿车上（不消耗物品、不存储为自定义方块）
        if (featureRegistry.isDisabledVanillaMaterial(type)) {
            player.sendMessage("§c[PepperMinecart] 该方块矿车类型已在配置中禁用");
            return PlaceResult.DISABLED;
        }
        // 有人乘坐时不允许放置：转换类方块会 replaceMinecart 弹出乘客，非转换类会让乘客坐上自定义矿车
        if (!minecart.getPassengers().isEmpty()) return PlaceResult.IGNORED;
        ItemStack copyItem = itemInHand.asOne().clone();
        itemInHand.subtract(1);
        placeBlock(minecart, copyItem, config);
        if (config.isSoundFeedback()) featureContext.playPlaceSound(minecart.getLocation(), copyItem.getType());
        return PlaceResult.PLACED;
    }

    /**
     * 自定义矿车通用取下：把车上的方块物品放进主手并清空矿车（含会话回写/清理）
     */
    private boolean genericPickup(Player player, Minecart minecart, MainConfigModule config) {
        if (!featureContext.pickupBlockIntoHand(player, minecart)) return false;
        if (config.isSoundFeedback()) featureContext.playPickupSound(minecart.getLocation());
        return true;
    }

    /**
     * 交互冷却：返回 true 表示玩家仍处于冷却中（仅查询）
     */
    public boolean isCoolingDown(Player player, long cooldownMillis) {
        return cooldownManager.isCoolingDown(player, cooldownMillis);
    }

    public void clearCooldown(Player player) {
        cooldownManager.clear(player);
    }

    /** 记录一次成功的交互（放置/取下确实生效后调用） */
    private void recordInteraction(Player player) {
        cooldownManager.markInteraction(player);
    }

    // --- 铁砧 ---

    /**
     * 玩家从矿车铁砧取走修复结果时调用，按配置概率造成损坏。
     * 返回 true 表示铁砧已报废（调用方应延迟关闭玩家界面，避免在点击事件处理中直接关闭）
     */
    public boolean handleAnvilUse(Player player, MainConfigModule config) {
        if (!config.isAnvilDamageEnabled()) return false;
        Minecart minecart = featureContext.getAnvilSession(player.getUniqueId());
        if (minecart == null || !minecart.isValid()) return false;
        ItemStack item = getBlockItem(minecart);
        if (item == null) return false;
        CartFeature feature = featureRegistry.get(item.getType());
        if (feature != null) {
            return feature.onDamageUse(player, minecart, config, featureContext);
        }
        return false;
    }

    /**
     * 关闭界面时清理铁砧会话
     */
    public void clearAnvilSession(UUID playerId) {
        featureContext.clearAnvilSession(playerId);
    }

    /**
     * 是否存在指定玩家的矿车铁砧会话（PrepareAnvilEvent 用于区分矿车铁砧与普通方块铁砧）
     */
    public boolean hasAnvilSession(UUID playerId) {
        return featureContext.getAnvilSession(playerId) != null;
    }

    // --- 工作站会话 ---

    /**
     * 关闭界面时清理工作站会话（矿车销毁时据此关闭仍打开的工作台/附魔台界面）
     */
    public void clearWorkstationSession(UUID playerId) {
        featureContext.clearWorkstationSession(playerId);
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
        // 热路径：显示方块为空且无 BlockInfo 标记 → 直接返回，避免逐格移动反序列化 NBT
        if (!hasBlockOnCart(minecart)) return;
        Material display = minecart.getDisplayBlockData().getMaterial();
        // 显示方块非 AIR 且非投掷器：无需读取 NBT（仅投掷器矿车有激活逻辑）
        if (display != Material.AIR && !(featureRegistry.get(display) instanceof DropperFeature)) return;
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
        featureContext.clearWorkstationSessions(minecart);
        CartFeature feature = resolveFeature(minecart);
        if (feature != null) {
            feature.onDestroy(minecart, featureContext);
        }
        // 关闭打开中的容器界面并回写清理（关界面会触发保存，兜底强制回写）
        flushAndCloseSession(minecart);
        // 在回写之后重新读取物品，保证包含界面编辑，再交给特性决定如何掉落
        ItemStack freshItem = getBlockItem(minecart);
        if (feature != null) {
            feature.onDrop(minecart, freshItem, featureContext);
        } else if (freshItem != null) {
            minecart.getWorld().dropItem(minecart.getLocation(), freshItem);
        }
    }

    /**
     * 区块卸载时清理该区块内的会话，防止内存泄漏。
     * 用坐标比较替代 getChunk()，避免卸载期间对实体强制加载；仅扫描目标区块所在世界。
     */
    public void handleChunkUnload(Chunk chunk) {
        long cx = chunk.getX();
        long cz = chunk.getZ();
        for (Minecart minecart : featureContext.getOpenMinecarts()) {
            if (!minecart.isValid()) {
                flushAndCloseSession(minecart);
                featureContext.removeDropperCooldown(minecart);
                continue;
            }
            Location loc = minecart.getLocation();
            if (!loc.getWorld().equals(chunk.getWorld())) continue;
            if ((loc.getBlockX() >> 4) == cx && (loc.getBlockZ() >> 4) == cz) {
                flushAndCloseSession(minecart);
                featureContext.removeDropperCooldown(minecart);
            }
        }
    }

    /**
     * 插件停用前保存所有打开的容器会话
     */
    public void saveAllSessions() {
        for (Minecart minecart : featureContext.getOpenMinecarts()) {
            flushAndCloseSession(minecart);
        }
    }

    /**
     * 关闭该矿车仍打开中的容器界面并强制回写清理（销毁/区块卸载/停用共用）
     */
    private void flushAndCloseSession(Minecart minecart) {
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
