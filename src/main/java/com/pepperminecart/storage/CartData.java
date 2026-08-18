package com.pepperminecart.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 矿车业务数据（实体 PDC）读写。
 *
 * <p>采用"整物品"存储模型（对齐参考项目 BlockInfo 方案，但不引入 NBT-API 依赖）：
 * 方块物品（含 BlockStateMeta 中的容器内容）经 {@link ItemStack#serializeAsBytes()} 序列化
 * 存入单一键 {@link #ITEM}，取下/掉落时原样归还——容器内容天然随物品回收，无需逐格搬运。
 * {@link #CART_TYPE} 记录类型 id 用于路由；特殊矿车（原版转换）不存物品，内容在原版库存中。
 */
public final class CartData {

    public static final NamespacedKey CART_TYPE = key("cart-type");
    public static final NamespacedKey ITEM = key("item");
    /** 放置时记录的原始方块材质：供 {@link com.pepperminecart.api.CartContext#getOriginalMaterial} 使用，
     *  不随铁砧损坏链/显示方块变化（ANVIL → CHIPPED_ANVIL 等仍返回最初的放置材质）。 */
    public static final NamespacedKey ORIGINAL = key("original-material");

    private CartData() {
    }

    private static NamespacedKey key(String k) {
        return new NamespacedKey("pepperminecart", k);
    }

    // ---- 类型 ----

    public static void setCartType(Minecart cart, String id) {
        setString(cart, CART_TYPE, id);
    }

    public static String getCartType(Minecart cart) {
        return getString(cart, CART_TYPE);
    }

    public static boolean isManaged(Minecart cart) {
        PersistentDataContainer pdc = cart.getPersistentDataContainer();
        return pdc.has(CART_TYPE) || pdc.has(ITEM);
    }

    // ---- 整物品存储 ----

    public static void setItem(Minecart cart, ItemStack item) {
        if (item == null) {
            throw new IllegalArgumentException("存储物品不能为 null（清除请使用 clearItem）");
        }
        setBytes(cart, ITEM, item.serializeAsBytes());
    }

    public static ItemStack getItem(Minecart cart) {
        byte[] b = getBytes(cart, ITEM);
        if (b == null) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(b);
        } catch (RuntimeException ex) {
            // 损坏/不兼容的持久化字节（旧版本、其他插件乱写、世界降级）按"无物品"处理：
            // getItem 处于 resolve() 调用链上，反序列化异常若传播会中断事件链路
            // （销毁处理中断、context 泄漏、物品不返还）
            Bukkit.getLogger().log(java.util.logging.Level.WARNING,
                    "[PepperMinecart] 矿车存储物品数据损坏，已按缺失处理并清除损坏键: " + cart.getUniqueId(), ex);
            clearItem(cart);
            return null;
        }
    }

    public static void clearItem(Minecart cart) {
        cart.getPersistentDataContainer().remove(ITEM);
    }

    // ---- 原始材质 ----

    public static void setOriginalMaterial(Minecart cart, Material material) {
        if (material == null) {
            throw new IllegalArgumentException("原始材质不能为 null");
        }
        setString(cart, ORIGINAL, material.name());
    }

    public static Material getOriginalMaterial(Minecart cart) {
        String name = getString(cart, ORIGINAL);
        return name == null ? null : Material.matchMaterial(name);
    }

    // ---- 通用 PDC 访问（任意 NamespacedKey，供扩展类型使用） ----

    public static void setString(Minecart cart, NamespacedKey key, String value) {
        PersistentDataContainer pdc = cart.getPersistentDataContainer();
        if (value == null) {
            pdc.remove(key); // PDC 不接受 null：显式按“删除键”语义处理，避免调用方猜 NPE
            return;
        }
        pdc.set(key, PersistentDataType.STRING, value);
    }

    public static String getString(Minecart cart, NamespacedKey key) {
        return cart.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public static void setInt(Minecart cart, NamespacedKey key, int value) {
        cart.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, value);
    }

    public static int getInt(Minecart cart, NamespacedKey key, int def) {
        return cart.getPersistentDataContainer().getOrDefault(key, PersistentDataType.INTEGER, def);
    }

    public static void setLong(Minecart cart, NamespacedKey key, long value) {
        cart.getPersistentDataContainer().set(key, PersistentDataType.LONG, value);
    }

    public static long getLong(Minecart cart, NamespacedKey key, long def) {
        return cart.getPersistentDataContainer().getOrDefault(key, PersistentDataType.LONG, def);
    }

    public static void setBytes(Minecart cart, NamespacedKey key, byte[] value) {
        PersistentDataContainer pdc = cart.getPersistentDataContainer();
        if (value == null) {
            pdc.remove(key); // 与 setString 一致：null 表示删除键
            return;
        }
        pdc.set(key, PersistentDataType.BYTE_ARRAY, value);
    }

    public static byte[] getBytes(Minecart cart, NamespacedKey key) {
        return cart.getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY);
    }

    /** 清空本插件写入的全部数据（pepperminecart 命名空间下所有键，含扩展写入的元数据），
     *  避免取下/报废后空矿车残留脏数据；不动其他插件命名空间的键。
     *  注意：必须快照键集再删除 —— getKeys() 返回的是活视图，边遍历边删除会抛
     *  ConcurrentModificationException（MockBukkit 与部分服务端实现上必现）。 */
    public static void clear(Minecart cart) {
        clear(cart, new String[0]);
    }

    /** 清空本插件数据，并额外清理扩展处理器命名空间下的键。
     *  扩展元数据应使用与 {@code CartTypeHandler.getId()} 相同的 namespace，
     *  这样取下/销毁/报废时能随矿车数据一起清除，避免旧状态残留到下一次使用。 */
    public static void clear(Minecart cart, String... extraNamespaces) {
        if (extraNamespaces == null) {
            throw new IllegalArgumentException("extraNamespaces 不能为 null");
        }
        PersistentDataContainer pdc = cart.getPersistentDataContainer();
        Set<String> namespaces = new HashSet<>(Set.of("pepperminecart"));
        for (String namespace : extraNamespaces) {
            if (namespace == null) {
                throw new IllegalArgumentException("extraNamespaces 不能包含 null");
            }
            if ("minecraft".equals(namespace) || "bukkit".equals(namespace)) {
                throw new IllegalArgumentException("拒绝清理公共命名空间 " + namespace
                        + "：这可能删除原版/其他插件的 PDC 数据");
            }
            namespaces.add(namespace);
        }
        for (NamespacedKey k : new ArrayList<>(pdc.getKeys())) {
            if (namespaces.contains(k.getNamespace())) {
                pdc.remove(k);
            }
        }
    }

    /**
     * 实体替换时整 PDC 复制（含扩展类型写入的元数据）。
     * replace=false：不覆盖目标实体已存在的键，避免清空其他插件写在目标实体上的数据。
     * 约定目标实体必须是新建实体，因此插件自己的键不会产生陈旧覆盖。
     */
    public static void copy(Minecart from, Minecart to) {
        from.getPersistentDataContainer().copyTo(to.getPersistentDataContainer(), false);
    }
}
