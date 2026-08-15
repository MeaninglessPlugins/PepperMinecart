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

    /** 放置时存储的完整方块物品（含容器内容等 NBT）；未存储（如原版特殊矿车）返回 null。 */
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

    /** 设置冷却（世界年龄语义：单调递增、跨卸载/重启持久、不受 /time set 与系统时钟影响）。 */
    void setCooldown(NamespacedKey key, int ticks);

    boolean hasCooldown(NamespacedKey key);

    /** 按取下策略把物品交给玩家（背包优先，溢出落地；DROP 模式直接落地）。 */
    void giveItem(Player player, ItemStack item);

    /** 在矿车位置掉落物品。 */
    void dropItem(ItemStack item);

    /** 替换矿车实体（如转换为特殊矿车），自动复制数据并重新跟踪。 */
    void replaceEntity(Minecart newCart);

    /** 矿车被破坏时是否掉落方块物品与容器内容（默认 true）。 */
    void setDropOnDestroy(boolean drop);
}
