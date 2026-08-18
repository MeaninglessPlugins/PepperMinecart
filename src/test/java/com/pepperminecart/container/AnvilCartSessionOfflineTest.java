package com.pepperminecart.container;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
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

/** 铁砧延迟确认任务：玩家在 1 tick 内离线时不得 NPE。 */
class AnvilCartSessionOfflineTest {

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
    void delayedConfirmSurvivesPlayerQuitBeforeTick() throws Exception {
        PluginMock plugin = MockBukkit.createMockPlugin("anvil-offline");
        PlayerMock player = server.addPlayer();
        Inventory top = server.createInventory(null, InventoryType.ANVIL);
        top.setItem(2, new ItemStack(Material.DIAMOND));
        InventoryView view = player.openInventory(top);

        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        Map<Inventory, AnvilCartSession.PendingAnvilTake> pending = new HashMap<>();
        pending.put(top, AnvilCartSession.PendingAnvilTake.prepared());
        AnvilCartSession session = new AnvilCartSession(plugin, null, pending, cart, top, view);

        // 离线玩家：isOnline=false 且 getOpenInventory() 返回 null（真实服务端断开后的语义）
        java.lang.reflect.Proxy playerProxy = null;
        org.bukkit.entity.Player offline = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                AnvilCartSessionOfflineTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getOpenInventory")) {
                        return null;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });

        var confirmTake = AnvilCartSession.class.getDeclaredMethod("confirmTake",
                org.bukkit.entity.Player.class, AnvilCartSession.PendingAnvilTake.class);
        confirmTake.setAccessible(true);
        AnvilCartSession.PendingAnvilTake clicked = new AnvilCartSession.PendingAnvilTake(
                new ItemStack(Material.DIAMOND), false);

        assertDoesNotThrow(() -> confirmTake.invoke(session, offline, clicked),
                "玩家离线后延迟确认任务必须安全退出，不得对 getOpenInventory() 的 null 解引用");
    }
}
