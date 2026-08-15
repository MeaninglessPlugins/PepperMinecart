package com.pepperminecart.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.pepperminecart.api.CartTypeHandler;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

/** CartTypeRegistry id / 材质路由一致性测试（覆盖同 id 覆盖注册与注销场景）。 */
class CartTypeRegistryTest {

    private static CartTypeHandler handler(NamespacedKey id, Set<Material> materials) {
        return new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return id;
            }

            @Override
            public Set<Material> handledMaterials() {
                return materials;
            }
        };
    }

    @Test
    void reRegisterSameIdCleansOldMaterialMappings() {
        CartTypeRegistry registry = new CartTypeRegistry(null);
        CartTypeHandler first = handler(NamespacedKey.fromString("test:cart"), Set.of(Material.CHEST));
        CartTypeHandler second = handler(NamespacedKey.fromString("test:cart"), Set.of(Material.HOPPER));
        registry.register(first);
        registry.register(second);

        // 同 id 覆盖后：byId 指向新处理器，旧处理器的材质映射被清理，不再指向已覆盖的旧处理器
        assertEquals(second, registry.byId(NamespacedKey.fromString("test:cart")));
        assertNull(registry.byMaterial(Material.CHEST));
        assertEquals(second, registry.byMaterial(Material.HOPPER));
    }

    @Test
    void unregisterRemovesOnlyCurrentOwner() {
        CartTypeRegistry registry = new CartTypeRegistry(null);
        CartTypeHandler a = handler(NamespacedKey.fromString("test:a"), Set.of(Material.CHEST));
        CartTypeHandler b = handler(NamespacedKey.fromString("test:b"), Set.of(Material.CHEST));
        registry.register(a);
        registry.register(b); // b 接管 CHEST 的路由
        registry.unregister(NamespacedKey.fromString("test:b"));

        // b 注销后 CHEST 不再路由到已注销的 b；a 被 b 覆盖、未重新注册，不自动恢复
        assertNull(registry.byMaterial(Material.CHEST));
        assertNull(registry.byId(NamespacedKey.fromString("test:b")));
    }

    @Test
    void duplicateMaterialWarnedAndLastWins() {
        CartTypeRegistry registry = new CartTypeRegistry(null);
        CartTypeHandler a = handler(NamespacedKey.fromString("test:a"), Set.of(Material.CHEST));
        CartTypeHandler b = handler(NamespacedKey.fromString("test:b"), Set.of(Material.CHEST));
        registry.register(a);
        registry.register(b);

        assertEquals(b, registry.byMaterial(Material.CHEST));
        assertEquals(a, registry.byId(NamespacedKey.fromString("test:a")));
    }
}
