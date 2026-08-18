package com.pepperminecart.api;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 扩展运行时上下文：封装受管矿车的显示、元数据（PDC 持久化）、冷却、物品分发等能力，
 * 类型实现方无需接触引擎内部。
 */
public interface CartContext {

    /** 当前矿车实体（可能在 {@link #replaceEntity} 后指向新实体）。 */
    Minecart getMinecart();

    /** 本矿车的类型处理器。 */
    CartTypeHandler getType();

    World getWorld();

    Location getLocation();

    /** 首个存活乘客（LivingEntity），无则 null。 */
    Entity getRider();

    boolean hasRider();

    /** 放置时记录的方块材质（存储物品优先，缺失时回退显示方块）。 */
    Material getOriginalMaterial();

    /** 放置时存储的完整方块物品（含容器内容等 NBT）；未存储（如原版特殊矿车）返回 null。
     *  返回的是独立副本：对返回值的修改不会写回存储。 */
    ItemStack getBlockItem();

    /** 设置显示方块（同时写入持久化备份）。 */
    void setDisplay(Material material);

    void setDisplay(BlockData data);

    Material getDisplayMaterial();

    boolean hasDisplay();

    void setDisplayOffset(int offset);

    int getDisplayOffset();

    /** 写入持久化元数据（PDC，随矿车保存）。 */
    void setMetaInt(NamespacedKey key, int value);

    int getMetaInt(NamespacedKey key, int defaultValue);

    void setMetaString(NamespacedKey key, String value);

    String getMetaString(NamespacedKey key);

    /** 设置冷却（世界年龄语义：使用 World#getGameTime，单调递增、跨卸载/重启持久、
     *  不受 /time set 与系统时钟影响）。 */
    void setCooldown(NamespacedKey key, int ticks);

    boolean hasCooldown(NamespacedKey key);

    /** 按取下策略把物品交给玩家（背包优先，溢出落地；DROP 模式直接落地）。 */
    void giveItem(Player player, ItemStack item);

    /**
     * 按本次取下策略交付物品，并如实返回是否成功：进背包全部成功或掉落全部成功才返回 true；
     * 掉落被取消/位置不可用时返回 false，调用方据此决定是否继续清空源数据。
     * <p>默认实现委托 {@link #giveItem} 并乐观返回 true；实现类应覆盖为精确结果。</p>
     */
    default boolean deliverItem(Player player, ItemStack item, TakeOffResult mode) {
        giveItem(player, item);
        return true;
    }

    /** 在矿车位置掉落物品。 */
    void dropItem(ItemStack item);

    /**
     * 尝试在矿车位置（失效时回退到最后已知位置）掉落物品。
     *
     * @return true 仅当物品无需处理或掉落实体成功生成；掉落被取消/位置不可用返回 false，
     *         调用方必须保留源物品，避免静默丢失
     */
    default boolean tryDropItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return true;
        }
        World world = getWorld();
        Location location = getLocation();
        if (world == null || location == null) {
            return false;
        }
        return world.dropItemNaturally(location, item.clone()) != null;
    }

    /**
     * 尝试在矿车位置批量掉落物品；任一次掉落被取消/位置不可用返回 false，null 批按空批处理。
     * <p>默认实现只是顺序调用 {@link #tryDropItem}，<b>不提供</b>“失败回收已生成掉落”的
     * 原子保证；需要原子语义的实现应覆盖本方法（参见 {@code ItemDelivery#dropAll}）。</p>
     */
    default boolean tryDropItems(ItemStack... items) {
        if (items == null) {
            return true; // 空批视为无需处理
        }
        for (ItemStack item : items) {
            if (!tryDropItem(item)) {
                return false;
            }
        }
        return true;
    }

    /** 替换矿车实体（如转换为特殊矿车），自动复制数据并重新跟踪。 */
    void replaceEntity(Minecart newCart);

    /**
     * 解除本矿车的插件管理：清除插件 PDC 数据与显示方块，并移除引擎跟踪。
     * 供 {@link CartTypeHandler#onTakeOff} 返回 {@link TakeOffOutcome#HANDLED} 且矿车
     * 应还原为空车（不再受本插件管理）时调用；若矿车应继续保持受管
     * （如转换为另一类型并重新写入类型数据），不要调用。
     * <p>返回 {@code HANDLED} 的处理器必须自行完成管理清理——引擎在 HANDLED 路径
     * 不再动作，若既不调用本方法也不改写 PDC，矿车会继续被引擎跟踪
     * （禁止乘坐、每 tick 回调、破坏时按原数据掉落）。</p>
     */
    void releaseCart();

    /** 矿车被破坏时是否掉落方块物品与容器内容（默认 true）。 */
    void setDropOnDestroy(boolean drop);
}
