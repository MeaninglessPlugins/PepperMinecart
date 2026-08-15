package com.pepperminecart.api;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;

/**
 * 可选能力接口：处理器声明自己能接管某类原版特殊矿车实体（箱子/漏斗/熔炉/TNT/命令矿车）。
 *
 * <p>引擎据此识别"外来原版矿车"（无插件 PDC 数据的箱子矿车等）并路由取下/销毁逻辑，
 * 无需感知任何具体处理器。</p>
 */
public interface VanillaCartSupport {

    /**
     * 该实体类型对应的原版特殊矿车所代表的方块材质；不支持时返回 null。
     *
     * @param type 矿车实体类型（如 {@link EntityType#CHEST_MINECART}）
     */
    Material materialFor(EntityType type);

    /** 引擎内部便捷方法：该矿车是否为本能力支持的原版特殊矿车实体。 */
    default boolean supportsVanillaCart(Minecart cart) {
        return materialFor(cart.getType()) != null;
    }
}
