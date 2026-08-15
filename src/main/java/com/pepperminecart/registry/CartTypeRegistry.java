package com.pepperminecart.registry;

import com.pepperminecart.api.CartTypeHandler;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

/** id / Material → CartTypeHandler 注册表。 */
public class CartTypeRegistry {

    private final Logger logger;
    private final Map<NamespacedKey, CartTypeHandler> byId = new HashMap<>();
    private final Map<Material, CartTypeHandler> byMaterial = new HashMap<>();

    public CartTypeRegistry(Logger logger) {
        this.logger = logger;
    }

    public void register(CartTypeHandler handler) {
        CartTypeHandler old = byId.put(handler.getId(), handler);
        if (old != null && old != handler) {
            warn("矿车类型 id 重复注册，已覆盖: " + handler.getId());
            // 旧处理器被同 id 覆盖：撤销其材质映射（仅当仍指向旧处理器时），
            // 避免 byId 与 byMaterial 路由分叉（覆盖后旧材质仍指向已注销的旧处理器）
            for (Material m : old.handledMaterials()) {
                byMaterial.remove(m, old);
            }
        }
        for (Material m : handler.handledMaterials()) {
            CartTypeHandler prev = byMaterial.put(m, handler);
            if (prev != null && prev != handler) {
                warn("方块 " + m + " 已被 " + prev.getId() + " 注册，现由 " + handler.getId() + " 接管");
            }
        }
    }

    private void warn(String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }

    public void unregister(NamespacedKey id) {
        CartTypeHandler handler = byId.remove(id);
        if (handler != null) {
            for (Material m : handler.handledMaterials()) {
                byMaterial.remove(m, handler);
            }
        }
    }

    public CartTypeHandler byId(NamespacedKey id) {
        return id == null ? null : byId.get(id);
    }

    public CartTypeHandler byMaterial(Material material) {
        return material == null ? null : byMaterial.get(material);
    }

    public Collection<CartTypeHandler> all() {
        return Collections.unmodifiableCollection(byId.values());
    }
}
