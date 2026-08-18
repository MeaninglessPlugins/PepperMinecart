package com.pepperminecart.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** 实体替换时的 PDC 复制契约：插件数据迁移，但不得清空目标实体已有的第三方数据。 */
class CartDataCopyTest {

    private ServerMock server;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("test");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void copyPreservesExistingTargetKeys() {
        RideableMinecart from = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        RideableMinecart to = world.spawn(new Location(world, 1, 64, 0), RideableMinecart.class);

        CartData.setCartType(from, "pepperminecart:test");
        CartData.setItem(from, new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND));

        NamespacedKey thirdParty = new NamespacedKey("thirdparty", "marker");
        to.getPersistentDataContainer().set(thirdParty, PersistentDataType.STRING, "keep-me");

        CartData.copy(from, to);

        assertTrue(to.getPersistentDataContainer().has(thirdParty), "复制插件 PDC 不得清空目标实体已有的第三方键");
        assertEquals("keep-me", to.getPersistentDataContainer().get(thirdParty, PersistentDataType.STRING));
        assertFalse(CartData.isManaged(to) && CartData.getItem(to) == null,
                "插件的受管数据应已随复制迁移到目标实体");
        assertEquals("pepperminecart:test", CartData.getCartType(to));
        assertEquals(org.bukkit.Material.DIAMOND, CartData.getItem(to).getType());
    }
}
