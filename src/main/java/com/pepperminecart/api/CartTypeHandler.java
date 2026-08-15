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

    /** 放置这些方块时触发本类型；返回空集表示该类型只能通过 API 创建。 */
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
     *         引擎不再动作
     */
    default TakeOffOutcome onTakeOff(Player player, CartContext ctx, TakeOffResult result) {
        return TakeOffOutcome.DEFAULT;
    }

    /** 取下时返还的物品（默认：存储的完整物品；无存储时回退到放置材质）。 */
    default ItemStack getTakeOffItem(CartContext ctx) {
        ItemStack item = ctx.getBlockItem();
        return item != null ? item.clone() : new ItemStack(ctx.getOriginalMaterial());
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

    /** 矿车被破坏时回调（引擎掉落方块物品与容器内容之前）。 */
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
