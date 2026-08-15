package com.pepperminecart.api;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;

/**
 * PepperMinecart 对外服务。通过 Bukkit ServicesManager 获取：
 *
 * <pre>{@code
 * PepperMinecartAPI api = Bukkit.getServicesManager().load(PepperMinecartAPI.class);
 * }</pre>
 */
public interface PepperMinecartAPI {

    /** 注册矿车类型（重复 id 覆盖旧类型）。 */
    void registerCartType(CartTypeHandler handler);

    /** 注销矿车类型。 */
    void unregisterCartType(NamespacedKey id);

    /** 查询矿车类型。 */
    CartTypeHandler getCartType(NamespacedKey id);

    /** 该矿车是否受 PepperMinecart 管理。 */
    boolean isManagedCart(Minecart cart);

    /** 获取受管矿车的上下文；非受管矿车返回 null。 */
    CartContext getContext(Minecart cart);
}
