package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.engine.CartEngine;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.CartTypeRegistry;
import com.pepperminecart.registry.handler.GenericBlockHandler;
import com.pepperminecart.storage.CartData;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** replaceEntity 事务性：复制/迁移中途失败时，不得留下两个带插件 PDC 的“受管”实体。 */
class ReplaceEntityAtomicityTest {

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
    void rebindFailureRollsBackCopiedPdcOnReplacement() {
        PluginMock plugin = MockBukkit.createMockPlugin("atomicity");
        CartEngine boomEngine = new CartEngine(plugin, new CartTypeRegistry(null), new PluginConfig(null)) {
            @Override
            public void rebind(UUID oldId, Minecart newCart) {
                throw new IllegalStateException("simulated rebind failure");
            }
        };

        RideableMinecart oldCart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        RideableMinecart newCart = world.spawn(new Location(world, 1, 64, 0), RideableMinecart.class);
        CartData.setCartType(oldCart, "pepperminecart:test");
        CartData.setItem(oldCart, new ItemStack(Material.DIAMOND));

        CartContextImpl ctx = new CartContextImpl(boomEngine, new PluginConfig(null), oldCart,
                GenericBlockHandler.INSTANCE);

        assertThrows(IllegalStateException.class, () -> ctx.replaceEntity(newCart));

        assertTrue(CartData.isManaged(oldCart), "失败时旧实体必须保留原有受管数据");
        assertFalse(CartData.isManaged(newCart), "失败时替代实体上的复制 PDC 必须被回滚清除");
    }

    @Test
    void failureAfterRebind_removesEngineContextForReplacement() {
        PluginMock plugin = MockBukkit.createMockPlugin("atomicity");
        org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock delegate =
                new org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock();
        AtomicBoolean armed = new AtomicBoolean(false);
        PersistentDataContainer flakyPdc = (PersistentDataContainer) Proxy.newProxyInstance(
                ReplaceEntityAtomicityTest.class.getClassLoader(),
                new Class<?>[]{PersistentDataContainer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getKeys") && armed.get()) {
                        throw new IllegalStateException("simulated post-rebind clear failure");
                    }
                    return method.invoke(delegate, args);
                });

        Location oldLoc = new Location(world, 0, 64, 0);
        Minecart oldCart = fakeMinecart(oldLoc, UUID.randomUUID(), flakyPdc);
        RideableMinecart newCart = world.spawn(new Location(world, 1, 64, 0), RideableMinecart.class);

        CartEngine engine = new CartEngine(plugin, new CartTypeRegistry(null), new PluginConfig(null)) {
            @Override
            public void rebind(UUID oldId, Minecart newCart) {
                super.rebind(oldId, newCart);
                armed.set(true); // rebind 成功后，下一次旧 PDC 清理抛异常
            }
        };

        CartContextImpl ctx = engine.addCart(oldCart, GenericBlockHandler.INSTANCE,
                new ItemStack(Material.STONE));

        assertThrows(IllegalStateException.class, () -> ctx.replaceEntity(newCart));

        assertFalse(engine.isManaged(newCart), "rebind 后异常必须清除替代实体的引擎 context，不能留下幽灵受管矿车");
        assertFalse(CartData.isManaged(newCart), "替代实体 PDC 应被回滚清除");
    }

    /** 最小 Minecart 代理：只提供 replaceEntity 需要的实体/PDC/显示/乘客方法。 */
    private static Minecart fakeMinecart(Location loc, UUID id, PersistentDataContainer pdc) {
        org.bukkit.block.data.BlockData[] display = {Material.AIR.createBlockData()};
        return (Minecart) Proxy.newProxyInstance(
                ReplaceEntityAtomicityTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld":
                            return loc.getWorld();
                        case "getLocation":
                            return loc;
                        case "getUniqueId":
                            return id;
                        case "isValid":
                            return true;
                        case "isDead":
                            return false;
                        case "getType":
                            return EntityType.MINECART;
                        case "getPersistentDataContainer":
                            return pdc;
                        case "getDisplayBlockData":
                            return display[0];
                        case "setDisplayBlockData":
                            display[0] = (org.bukkit.block.data.BlockData) args[0];
                            return null;
                        case "getDisplayBlockOffset":
                            return 0;
                        case "setDisplayBlockOffset":
                            return null;
                        case "getPassengers":
                            return List.of();
                        case "customName":
                            return null;
                        case "isCustomNameVisible":
                            return false;
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
    }
}
