package com.pepperminecart.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.registry.CartTypeRegistry;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

/** 公开 API 边界：非法参数必须在入口给出明确异常，而不是深层 NPE。 */
class PepperMinecartAPIImplTest {

    private final PepperMinecartAPIImpl api =
            new PepperMinecartAPIImpl(new CartTypeRegistry(null), null);

    @Test
    void isManagedCartRejectsNullCart() {
        assertThrows(IllegalArgumentException.class, () -> api.isManagedCart(null));
    }

    @Test
    void getContextRejectsNullCart() {
        assertThrows(IllegalArgumentException.class, () -> api.getContext(null));
    }

    @Test
    void registerCartTypeRejectsNullMaterialSet() {
        CartTypeHandler broken = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:broken");
            }

            @Override
            public Set<org.bukkit.Material> handledMaterials() {
                return null;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> api.registerCartType(broken));
    }
}
