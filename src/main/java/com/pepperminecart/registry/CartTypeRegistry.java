package com.pepperminecart.registry;

import com.pepperminecart.api.CartTypeHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

/** id / Material → CartTypeHandler 注册表。 */
public class CartTypeRegistry {

    private final Logger logger;
    private final Map<NamespacedKey, CartTypeHandler> byId = new HashMap<>();
    private final Map<Material, CartTypeHandler> byMaterial = new HashMap<>();
    /** 内置且不可被第三方覆盖/注销的类型 id（防止特殊矿车失去 VanillaCartSupport 后重复掉落）。 */
    private final Set<NamespacedKey> protectedIds = new HashSet<>();

    public CartTypeRegistry(Logger logger) {
        this.logger = logger;
    }

    public void register(CartTypeHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler 不能为 null");
        }
        if (handler.getId() == null) {
            throw new IllegalArgumentException("handler.getId() 不能为 null");
        }
        String namespace = handler.getId().getNamespace();
        if ("minecraft".equals(namespace) || "bukkit".equals(namespace)) {
            // CartData.clear 拒绝清理公共命名空间；若这里放行，取下/销毁/回滚会在交付后抛异常，
            // 已交付物品无法回滚、PDC 仍残留，再次取下就会复制物品。因此必须在注册入口拒绝。
            throw new IllegalArgumentException("矿车类型 id 不能使用公共命名空间 " + namespace
                    + "（与 PDC 清理安全策略冲突）: " + handler.getId());
        }
        // 先完整校验输入，再修改注册表：避免先改 byId、遍历材质时抛异常造成半更新
        Set<Material> materials = handler.handledMaterials();
        if (materials == null) {
            throw new IllegalArgumentException("handler.handledMaterials() 不能为 null: " + handler.getId());
        }
        for (Material material : materials) {
            if (material == null) {
                throw new IllegalArgumentException("handler.handledMaterials() 不能包含 null 材质: " + handler.getId());
            }
        }

        CartTypeHandler old = byId.get(handler.getId());
        if (old != null) {
            if (old != handler && protectedIds.contains(handler.getId())) {
                throw new IllegalArgumentException("内置受保护类型不可覆盖: " + handler.getId());
            }
            if (old != handler) {
                warn("矿车类型 id 重复注册，已覆盖: " + handler.getId());
            }
            // 撤销旧处理器仍持有的全部材质映射：按 byMaterial 当前路由清理，
            // 不依赖 old.handledMaterials() 的当前返回值（同一实例的动态集合可能已经变化）
            for (Map.Entry<Material, CartTypeHandler> entry : new ArrayList<>(byMaterial.entrySet())) {
                if (entry.getValue() == old) {
                    byMaterial.remove(entry.getKey(), old);
                }
            }
        }
        byId.put(handler.getId(), handler);
        for (Material m : materials) {
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

    /** 标记内置类型为受保护：注册表会拒绝第三方覆盖与注销。 */
    public void protect(NamespacedKey id) {
        if (id == null) {
            throw new IllegalArgumentException("id 不能为 null");
        }
        protectedIds.add(id);
    }

    public void unregister(NamespacedKey id) {
        if (protectedIds.contains(id)) {
            throw new IllegalArgumentException("内置受保护类型不可注销: " + id);
        }
        CartTypeHandler handler = byId.get(id);
        if (handler == null) {
            return;
        }
        // 先快照并完整校验材质集合，再修改 byId/byMaterial：handledMaterials 抛异常时注册表保持原状
        Set<Material> materials = snapshotMaterials(handler);
        byId.remove(id);
        for (Material m : materials) {
            byMaterial.remove(m, handler);
        }
    }

    /** 快照 handler 当前材质集合；null 集合或含 null 元素在修改注册表前直接拒绝。 */
    private Set<Material> snapshotMaterials(CartTypeHandler handler) {
        Set<Material> materials = handler.handledMaterials();
        if (materials == null) {
            throw new IllegalArgumentException("handler.handledMaterials() 不能为 null: " + handler.getId());
        }
        Set<Material> snapshot = new HashSet<>();
        for (Material material : materials) {
            if (material == null) {
                throw new IllegalArgumentException("handler.handledMaterials() 不能包含 null 材质: " + handler.getId());
            }
            snapshot.add(material);
        }
        return snapshot;
    }

    public CartTypeHandler byId(NamespacedKey id) {
        return id == null ? null : byId.get(id);
    }

    public CartTypeHandler byMaterial(Material material) {
        return material == null ? null : byMaterial.get(material);
    }

    public Collection<CartTypeHandler> all() {
        // 返回快照而非活视图：调用方（如引擎的 vanillaSupportHandler）遍历期间可能发生
        // register/unregister，byId.values() 活视图会抛 ConcurrentModificationException
        return Collections.unmodifiableCollection(new ArrayList<>(byId.values()));
    }
}
