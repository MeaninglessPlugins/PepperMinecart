package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffResult;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;

/**
 * 容器 DROP 策略的边界：容器物品已经为空时，不需要掉落位置；
 * 位置不可用（实体已失效且无快照）不得取消取下，空容器应直接按 take-off-mode 交付。
 */
class ContainerDropEmptyNoLocationTest {

    private ServerMock server;
    private PepperMinecartPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PepperMinecartPlugin.class);
        server.addSimpleWorld("test");
        plugin.getConfig().set("container-pickup-policy", "DROP");
        plugin.pluginConfig().apply(plugin.getConfig());
        plugin.engine().setSessionManager(null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void takeOff_emptyContainerWithoutLocation_deliversEmptyContainer() {
        PlayerMock player = server.addPlayer();
        UUID id = UUID.randomUUID();
        Minecart fake = (Minecart) Proxy.newProxyInstance(
                ContainerDropEmptyNoLocationTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld":
                        case "getLocation":
                            return null;
                        case "getUniqueId":
                            return id;
                        case "getPersistentDataContainer":
                            return new PersistentDataContainerMock();
                        case "getPassengers":
                            return List.of();
                        default:
                            break;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0.0d;
                    if (rt == float.class) return 0.0f;
                    if (rt == short.class) return (short) 0;
                    if (rt == byte.class) return (byte) 0;
                    if (rt == char.class) return (char) 0;
                    return null;
                });

        CartTypeHandler emptyContainerHandler = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:empty-container");
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of();
            }

            @Override
            public boolean isContainerPickupControlled(Material material) {
                return true;
            }

            @Override
            public ItemStack getTakeOffItem(CartContext ctx) {
                return new ItemStack(Material.BARREL);
            }
        };
        var ctx = plugin.engine().contextFor(fake, emptyContainerHandler);
        assertTrue(plugin.engine().isManaged(fake), "前置：临时 context 已登记");

        plugin.engine().takeOff(player, ctx, TakeOffResult.INVENTORY);

        assertTrue(hasBarrel(player), "空容器 + 无掉落位置时仍应直接交付空容器物品");
        assertFalse(plugin.engine().isManaged(fake), "取下完成后临时 context 必须清理");
    }

    private boolean hasBarrel(PlayerMock player) {
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == Material.BARREL) {
                return true;
            }
        }
        return false;
    }
}
