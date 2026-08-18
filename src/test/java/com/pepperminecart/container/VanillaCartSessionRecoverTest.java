package com.pepperminecart.container;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
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

/**
 * VanillaCartSession 残留物品回收的边界回归：
 * 矿车 world 仍可读但 location 为 null（实体已失效的边界情况）时，
 * 剩余物品必须回退到玩家位置掉落，而不是被 top.setItem(i, null) 静默清空。
 */
class VanillaCartSessionRecoverTest {

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
    void recoverLeftovers_fallsBackToPlayerLocationWhenCartLocationNull() {
        PlayerMock player = server.addPlayer();
        // 背包全满：残留物品无法并入背包 → 走掉落路径
        player.getInventory().clear();
        ItemStack filler = new ItemStack(Material.STONE, 64);
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            player.getInventory().setItem(i, filler.clone());
        }

        Inventory top = server.createInventory(null, 9);
        top.setItem(0, new ItemStack(Material.DIAMOND, 2));
        InventoryView view = (InventoryView) Proxy.newProxyInstance(
                VanillaCartSessionRecoverTest.class.getClassLoader(),
                new Class<?>[]{InventoryView.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getSlotType")) {
                        return InventoryType.SlotType.CONTAINER;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });

        // 模拟实体已失效的边界：getWorld 非 null 但 getLocation 为 null
        Minecart deadCart = (Minecart) Proxy.newProxyInstance(
                VanillaCartSessionRecoverTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld":
                            return world;
                        case "getLocation":
                            return null;
                        case "getUniqueId":
                            return UUID.randomUUID();
                        default:
                            break;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == double.class) return 0.0d;
                    if (rt == float.class) return 0.0f;
                    return null;
                });

        VanillaCartSession session = new VanillaCartSession(deadCart, top, view);
        session.onPlayerClose(player);

        assertTrue(player.getWorld().getEntities().stream()
                        .anyMatch(e -> e instanceof Item item && item.getItemStack().getType() == Material.DIAMOND),
                "矿车 location 不可用时，残留物品应回退到玩家位置掉落，不得静默丢失");
    }

    @Test
    void recoverLeftovers_keepsSlotWhenItemSpawnCancelled() {
        PlayerMock player = server.addPlayer();
        // 背包全满 → 残留物品只能走掉落路径
        player.getInventory().clear();
        ItemStack filler = new ItemStack(Material.STONE, 64);
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            player.getInventory().setItem(i, filler.clone());
        }

        // Bukkit 契约：ItemSpawnEvent 被其他插件取消时 dropItemNaturally 返回 null。
        // MockBukkit 未模拟该返回值，因此这里用代理 World 直接建模返回值语义。
        World cancelDropWorld = (World) Proxy.newProxyInstance(
                VanillaCartSessionRecoverTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dropItemNaturally")) {
                        return null;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0.0d;
                    if (rt == float.class) return 0.0f;
                    return null;
                });

        Inventory top = server.createInventory(null, 9);
        top.setItem(0, new ItemStack(Material.DIAMOND, 2));
        InventoryView view = (InventoryView) Proxy.newProxyInstance(
                VanillaCartSessionRecoverTest.class.getClassLoader(),
                new Class<?>[]{InventoryView.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getSlotType")) {
                        return InventoryType.SlotType.CONTAINER;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });

        Minecart cart = (Minecart) Proxy.newProxyInstance(
                VanillaCartSessionRecoverTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld":
                            return cancelDropWorld;
                        case "getLocation":
                            return new Location(cancelDropWorld, 0, 64, 0);
                        case "getUniqueId":
                            return UUID.randomUUID();
                        default:
                            break;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == double.class) return 0.0d;
                    if (rt == float.class) return 0.0f;
                    return null;
                });

        VanillaCartSession session = new VanillaCartSession(cart, top, view);
        session.onPlayerClose(player);

        ItemStack slot = top.getItem(0);
        assertNotNull(slot, "dropItemNaturally 返回 null（掉落被取消）时，残留物品必须保留在原槽位");
        assertTrue(slot.getType() == Material.DIAMOND, "槽位中应仍是未掉落成功的钻石");
    }
}
