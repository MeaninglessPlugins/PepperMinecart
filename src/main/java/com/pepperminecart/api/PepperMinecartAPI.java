package com.pepperminecart.api;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;

/**
 * PepperMinecart 对外服务。通过 Bukkit ServicesManager 获取：
 *
 * <pre>{@code
 * PepperMinecartAPI api = Bukkit.getServicesManager().load(PepperMinecartAPI.class);
 * if (api == null) {
 *     getLogger().warning("PepperMinecart 未启用，无法注册矿车类型");
 *     return;
 * }
 * api.registerCartType(handler);
 * }</pre>
 *
 * 注意：load() 在服务未注册时返回 null。服务在 PepperMinecart 的 onEnable 内注册，
 * 因此依赖方需在 plugin.yml 声明 depend: [PepperMinecart] 并在自己的 onEnable 中获取
 * （onLoad 阶段拿不到）。所有方法要求在主线程调用。
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
