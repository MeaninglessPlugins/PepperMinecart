package com.pepperminecart.api;

import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 矿车类型扩展点。
 *
 * <p>新增一种"矿车类型"：实现本接口并调用
 * {@link PepperMinecartAPI#registerCartType(CartTypeHandler)} 注册。
 * 玩家把 {@link #handledMaterials()} 中的方块放到空普通矿车上时，矿车自动成为该类型，
 * 引擎接管其生命周期（显示方块、持久化、取下还原、破坏掉落、乘坐策略等默认行为），
 * 本接口只需覆盖需要的行为钩子。全部方法均有默认实现。</p>
 */
public interface CartTypeHandler {

    /** 唯一类型 id（命名空间建议使用所属插件的 namespace，避免跨插件冲突）。 */
    NamespacedKey getId();

    /**
     * 放置这些方块时触发本类型；返回空集表示该类型只能通过 API 创建。
     * 必须返回非 null、不含 null 元素、且调用方不应修改的集合。
     */
    Set<Material> handledMaterials();

    /** 是否允许在该矿车上放置。 */
    default boolean canPlace(Player player, Minecart cart) {
        return true;
    }

    /** 是否允许乘坐。默认 false（需求 2.3：挂有方块的矿车禁止玩家/生物乘坐）。 */
    default boolean allowRiding() {
        return false;
    }

    /** 放置完成后回调（默认行为：引擎自动设置显示方块并写入类型数据）。 */
    default void onPlaced(Player player, CartContext ctx) {
    }

    /**
     * 非潜行右键矿车时回调，用于打开界面。
     *
     * @return true 表示已处理该交互（引擎会取消原版行为）
     */
    default boolean onInteract(Player player, CartContext ctx) {
        return false;
    }

    /**
     * 潜行右键取下时回调（引擎随后按结果执行默认流程：还原普通矿车并返还方块物品）。
     * 内容物回收（如容器库存）应在此完成。
     *
     * @return {@link TakeOffOutcome#DEFAULT} 由引擎执行默认取下流程；
     *         {@link TakeOffOutcome#HANDLED} 表示本处理器已完整处理（含实体替换与物品交付），
     *         引擎不再动作——此时处理器必须自行完成管理清理：若矿车应还原为空车，
     *         调用 {@link CartContext#releaseCart()}；若通过 {@link CartContext#replaceEntity}
     *         替换实体后仍需受管，则保持/改写 PDC 使引擎继续跟踪。遗漏清理会导致矿车
     *         继续被引擎视为受管（禁止乘坐、每 tick 回调、破坏时按原数据掉落）。
     */
    default TakeOffOutcome onTakeOff(Player player, CartContext ctx, TakeOffResult result) {
        return TakeOffOutcome.DEFAULT;
    }

    /** 取下时返还的物品（默认：存储的完整物品；无存储时回退到放置材质）。
     *  原始材质缺失或为空气等无法构造物品时返回 null——引擎会取消取下并保留矿车数据，
     *  而不是返还空气物品。 */
    default ItemStack getTakeOffItem(CartContext ctx) {
        ItemStack item = ctx.getBlockItem();
        if (item != null) {
            return item.clone();
        }
        Material material = ctx.getOriginalMaterial();
        return (material == null || material.isAir()) ? null : new ItemStack(material);
    }

    /** 引擎每 tick 调度（仅受管矿车）。 */
    default void onTick(CartContext ctx) {
    }

    /** 矿车发生移动时回调（引擎位置差分触发，如投掷器铁轨检测）。 */
    default void onMove(CartContext ctx) {
    }

    /** 实体坐上矿车时回调（仅当 {@link #allowRiding()} 返回 true 时触发）。 */
    default void onMount(Entity rider, CartContext ctx) {
    }

    /** 实体下车时回调。 */
    default void onDismount(Entity rider, CartContext ctx) {
    }

    /**
     * 矿车被破坏时回调（引擎掉落方块物品与容器内容之前）。
     * <p>注意：若本类型实现 {@link VanillaCartSupport} 且矿车以 DEATH 原因移除、
     * DO_ENTITY_DROPS=true，则原版会自行掉落特殊矿车物品与内容物，
     * 引擎只清理跟踪，不会调用本回调，也不补掉方块物品。</p>
     */
    default void onCartDestroyed(CartContext ctx) {
    }

    /**
     * 本类型是否受"容器取下策略"（container-pickup-policy）管控。
     * <p>引擎据此决定：策略为 FORBIDDEN 时禁止取下本类型矿车；策略为 DROP 时取下时
     * 清空内容物落地、容器物品本身按 take-off-mode 处理。</p>
     *
     * @param material 取下物品的材质（潜影盒等特例由其自身判定排除）
     */
    default boolean isContainerPickupControlled(Material material) {
        return false;
    }
}
