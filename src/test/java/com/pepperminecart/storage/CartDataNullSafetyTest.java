package com.pepperminecart.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** CartData 边界契约：null 写入有明确语义，损坏字节被清理而不是反复告警。 */
class CartDataNullSafetyTest {

    private static final NamespacedKey KEY = new NamespacedKey("test", "meta");

    private ServerMock server;
    private World world;
    private RideableMinecart cart;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("test");
        cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void nullStringValueRemovesKey() {
        CartData.setString(cart, KEY, "hello");
        CartData.setString(cart, KEY, null);
        assertNull(CartData.getString(cart, KEY), "setString(null) 应等价于删除键，而不是 NPE");
    }

    @Test
    void nullByteValueRemovesKey() {
        CartData.setBytes(cart, KEY, new byte[]{1, 2, 3});
        CartData.setBytes(cart, KEY, null);
        assertNull(CartData.getBytes(cart, KEY), "setBytes(null) 应等价于删除键，而不是 NPE");
    }

    @Test
    void nullItemAndMaterialAreRejectedClearly() {
        assertThrows(IllegalArgumentException.class, () -> CartData.setItem(cart, null));
        assertThrows(IllegalArgumentException.class, () -> CartData.setOriginalMaterial(cart, null));
    }

    @Test
    void clearRejectsNullExtraNamespaces() {
        assertThrows(IllegalArgumentException.class, () -> CartData.clear(cart, (String[]) null));
    }

    @Test
    void damagedItemBytesAreRemovedAfterReadFailure() {
        cart.getPersistentDataContainer().set(CartData.ITEM, PersistentDataType.BYTE_ARRAY, new byte[]{9, 9, 9});
        assertNull(CartData.getItem(cart), "损坏字节应按缺失处理");
        assertFalse(cart.getPersistentDataContainer().has(CartData.ITEM),
                "损坏键必须被清除，否则每次 getItem 都会重复告警且矿车永远被视为受管");
    }
}
