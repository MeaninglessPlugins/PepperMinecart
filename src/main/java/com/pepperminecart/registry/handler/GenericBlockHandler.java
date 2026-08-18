package com.pepperminecart.registry.handler;

import com.pepperminecart.api.CartTypeHandler;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

/** 未注册任何类型处理器的普通方块：仅显示，无额外行为。 */
public class GenericBlockHandler implements CartTypeHandler {

    /** 无状态单例：同时作为未知/失效类型的安全兜底处理器。 */
    public static final GenericBlockHandler INSTANCE = new GenericBlockHandler();

    public static final NamespacedKey ID = NamespacedKey.fromString("pepperminecart:generic");

    @Override
    public NamespacedKey getId() {
        return ID;
    }

    @Override
    public Set<Material> handledMaterials() {
        return Set.of();
    }
}
