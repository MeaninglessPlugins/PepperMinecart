package com.pepperminecart.container;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.pepperminecart.config.PluginConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** flushAndClose 的关闭路径必须单一：同一会话不得被 flush 两次（一次显式 + 一次关闭事件）。 */
class CartSessionManagerCloseTest {

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
    void flushAndCloseFlushesSessionExactlyOnce() throws Exception {
        PluginMock plugin = MockBukkit.createMockPlugin("session-close");
        CartSessionManager manager = new CartSessionManager(plugin, new PluginConfig(null), null);
        server.getPluginManager().registerEvents(manager, plugin);

        PlayerMock player = server.addPlayer();
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        Inventory top = server.createInventory(null, 9);
        top.setItem(0, new ItemStack(Material.DIAMOND));
        InventoryView view = player.openInventory(top);

        AtomicInteger flushes = new AtomicInteger();
        CartSession counting = new CartSession(cart, top, view) {
            @Override
            public void flush() {
                flushes.incrementAndGet();
            }

            @Override
            public void onPlayerClose(Player player) {
                flush();
            }
        };
        Field openField = CartSessionManager.class.getDeclaredField("open");
        openField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, CartSession> open = (Map<UUID, CartSession>) openField.get(manager);
        open.put(player.getUniqueId(), counting);

        manager.flushAndClose(cart);

        assertEquals(1, flushes.get(), "flushAndClose 只能回写一次，不得经关闭事件二次 flush");
    }

    @Test
    void closeAllOfflineSessionStillCallsCloseWithoutPlayer() throws Exception {
        PluginMock plugin = MockBukkit.createMockPlugin("session-close-offline");
        CartSessionManager manager = new CartSessionManager(plugin, new PluginConfig(null), null);
        server.getPluginManager().registerEvents(manager, plugin);

        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        AtomicInteger closed = new AtomicInteger();
        CartSession offline = new CartSession(cart, null, null) {
            @Override
            public void onPlayerClose(Player player) {
                closed.incrementAndGet();
            }

            @Override
            public void closeWithoutPlayer() {
                closed.incrementAndGet();
            }
        };

        Field openField = CartSessionManager.class.getDeclaredField("open");
        openField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, CartSession> open = (Map<UUID, CartSession>) openField.get(manager);
        open.put(UUID.randomUUID(), offline); // 该 UUID 不在服务器在线列表中

        manager.closeAll();

        assertEquals(1, closed.get(), "离线玩家的会话必须走 closeWithoutPlayer 兜底关闭");
    }

    @Test
    void flushAndCloseSurvivesViewerCloseFailureAndContinues() throws Exception {
        PluginMock plugin = MockBukkit.createMockPlugin("session-close-throwing");
        CartSessionManager manager = new CartSessionManager(plugin, new PluginConfig(null), null);
        server.getPluginManager().registerEvents(manager, plugin);

        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        AtomicInteger closeAttempts = new AtomicInteger();
        AtomicInteger viewerListCalls = new AtomicInteger();
        Player first = throwingViewer(UUID.randomUUID(), closeAttempts);
        Player second = throwingViewer(UUID.randomUUID(), closeAttempts);

        Inventory top = (Inventory) Proxy.newProxyInstance(
                CartSessionManagerCloseTest.class.getClassLoader(),
                new Class<?>[]{Inventory.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getViewers")) {
                        // 第一次迭代返回两个 viewer；关闭回调同步移除后，第二次外层迭代视图已空
                        return viewerListCalls.incrementAndGet() == 1
                                ? new ArrayList<>(List.of(first, second))
                                : List.of();
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
        CartSession throwing = new CartSession(cart, top, null) {
            @Override
            public void onPlayerClose(Player player) {
                // 无持久化行为：本测试只关注 closeInventory 异常隔离
            }
        };

        Field openField = CartSessionManager.class.getDeclaredField("open");
        openField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, CartSession> open = (Map<UUID, CartSession>) openField.get(manager);
        open.put(first.getUniqueId(), throwing);
        open.put(second.getUniqueId(), throwing);

        assertDoesNotThrow(() -> manager.flushAndClose(cart),
                "单个 viewer 关闭视图异常不得中断 flushAndClose");
        assertEquals(2, closeAttempts.get(), "第一个 viewer 关闭异常后，第二个 viewer 仍应被尝试关闭");
    }

    /** 每次 closeInventory 都抛异常的假玩家。 */
    private static Player throwingViewer(UUID id, AtomicInteger closeAttempts) {
        return (Player) Proxy.newProxyInstance(
                CartSessionManagerCloseTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return id;
                        case "closeInventory":
                            closeAttempts.incrementAndGet();
                            throw new IllegalStateException("simulated closeInventory failure");
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
