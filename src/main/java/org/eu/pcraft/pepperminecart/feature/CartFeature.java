package org.eu.pcraft.pepperminecart.feature;

import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.eu.pcraft.pepperminecart.config.MainConfigModule;

/**
 * 矿车特性接口：每种特殊矿车/方块对应一个实现类。
 * 只覆写自己关心的钩子，其余走默认空实现，MinecartService 只负责分发。
 */
public interface CartFeature {

    /** 特性名称（配置 block-interactions 的值，如 ANVIL / WORKBENCH / DROPPER） */
    String getName();

    /**
     * 在矿车上放置方块时调用，创建对应矿车形态。
     * 默认返回 false，由框架走自定义矿车路径（存 BlockInfo + 显示方块）。
     * 原版特殊矿车覆写此方法转换为实体形态，返回 true。
     */
    default boolean onPlace(Minecart minecart, ItemStack placedItem, MainConfigModule config, FeatureContext ctx) {
        return false;
    }

    /** 右键（非潜行）打开界面，返回 true 表示已处理并应取消事件 */
    default boolean onStandInteract(Player player, Minecart minecart, ItemStack itemOnCart, FeatureContext ctx) {
        return false;
    }

    /** 潜行交互（放置/取下）特例，返回 true 表示已处理；默认走通用放置/取下逻辑 */
    default boolean onSneakInteract(Player player, Minecart minecart, ItemStack itemInHand, MainConfigModule config, FeatureContext ctx) {
        return false;
    }

    /** 矿车经过充能激活铁轨时触发（如投掷器矿车） */
    default void onRailActivate(Minecart minecart, MainConfigModule config, FeatureContext ctx) {}

    /** 矿车对应物品栏界面最后一个人关闭时触发（保存数据、清理会话） */
    default void onContainerClosed(Minecart minecart, FeatureContext ctx) {}

    /** 矿车被销毁时触发（清理特性自己的状态） */
    default void onDestroy(Minecart minecart, FeatureContext ctx) {}

    /** 矿车被销毁时，决定车上的方块物品如何掉落。默认：重新读取后原样掉落 */
    default void onDrop(Minecart minecart, ItemStack blockItem, FeatureContext ctx) {
        if (blockItem != null) {
            minecart.getWorld().dropItem(minecart.getLocation(), blockItem);
        }
    }

    /** 玩家从铁砧界面取走修复结果时触发（耐久损耗） */
    default void onDamageUse(Player player, Minecart minecart, MainConfigModule config, FeatureContext ctx) {}
}
