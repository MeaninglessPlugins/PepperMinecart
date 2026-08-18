package com.pepperminecart.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * ItemDelivery 原子性：掉落/背包放入部分成功时，如果后续掉落失败（等价于 ItemSpawnEvent 被取消，
 * dropItemNaturally 返回 null），必须回滚已产生的物品，绝不能“地上有一份、源里还有一份”或“背包多收、源未清”。
 */
class ItemDeliveryAtomicityTest {

    private ServerMock server;
    private Location loc;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        loc = new Location(server.addSimpleWorld("test"), 0, 64, 0);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 记录 remove() 的假 Item。 */
    private static final class FakeItem {
        private boolean removed;

        Item proxy() {
            return (Item) Proxy.newProxyInstance(
                    ItemDeliveryAtomicityTest.class.getClassLoader(),
                    new Class<?>[]{Item.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("remove")) {
                            removed = true;
                            return null;
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

    /** 按队列返回 dropItemNaturally 结果的 World 代理；null 表示掉落被取消。 */
    private World worldReturning(Queue<Item> results) {
        return (World) Proxy.newProxyInstance(
                ItemDeliveryAtomicityTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dropItemNaturally")) {
                        Item next = results.poll();
                        return next;
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

    /** 第 n 次 dropItemNaturally 调用抛异常，其余调用返回真实掉落。 */
    private World worldThrowingOnCall(int n, FakeItem firstResult) {
        AtomicInteger count = new AtomicInteger();
        return (World) Proxy.newProxyInstance(
                ItemDeliveryAtomicityTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dropItemNaturally")) {
                        int call = count.incrementAndGet();
                        if (call == n) {
                            throw new IllegalStateException("simulated dropItemNaturally failure");
                        }
                        return firstResult != null ? firstResult.proxy() : null;
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

    private int countInInventory(PlayerMock player, Material material) {
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == material) {
                total += it.getAmount();
            }
        }
        return total;
    }

    @Test
    void dropAll_rollsBackAlreadySpawnedItemsWhenLaterDropCancelled() {
        FakeItem first = new FakeItem();
        Queue<Item> results = new LinkedList<>();
        results.add(first.proxy());
        results.add(null); // 第二个掉落被取消

        ItemDelivery.Result result = ItemDelivery.dropAll(worldReturning(results), loc,
                new ItemStack(Material.DIAMOND, 1),
                new ItemStack(Material.EMERALD, 1));

        assertEquals(ItemDelivery.Result.CANCELED, result, "第二个掉落被取消时应返回 CANCELED");
        assertTrue(first.removed, "已成功生成的第一个掉落也必须回滚，不能留在地面");
    }

    @Test
    void giveOrDrop_rollsBackInventoryAddWhenLeftoverDropCancelled() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 60));
        // 占满其余所有格子，确保 10 个钻石只能合并进 60 的堆叠，产生 6 个溢出
        ItemStack filler = new ItemStack(Material.STONE, 64);
        for (int i = 1; i < player.getInventory().getSize(); i++) {
            player.getInventory().setItem(i, filler.clone());
        }
        Queue<Item> results = new LinkedList<>();
        results.add(null); // 溢出掉落被取消

        ItemDelivery.Result result = ItemDelivery.giveOrDrop(player,
                new ItemStack(Material.DIAMOND, 10), worldReturning(results), loc);

        assertEquals(ItemDelivery.Result.CANCELED, result, "溢出掉落被取消时应返回 CANCELED");
        assertEquals(60, countInInventory(player, Material.DIAMOND),
                "已放入背包的 4 个钻石也必须回滚，不能多收");
    }

    @Test
    void dropAll_cancelledDropLeavesNoSourceSideEffects() {
        Queue<Item> results = new LinkedList<>();
        results.add(null);

        ItemDelivery.Result result = ItemDelivery.dropAll(worldReturning(results), loc,
                new ItemStack(Material.DIAMOND, 1));

        assertEquals(ItemDelivery.Result.CANCELED, result);
    }

    @Test
    void dropAll_rollsBackSpawnedItemsWhenDropThrows() {
        FakeItem first = new FakeItem();
        World world = worldThrowingOnCall(2, first);

        assertThrows(IllegalStateException.class, () -> ItemDelivery.dropAll(world, loc,
                new ItemStack(Material.DIAMOND, 1),
                new ItemStack(Material.EMERALD, 1)),
                "dropItemNaturally 抛异常时应保留异常供调用方诊断");

        assertTrue(first.removed, "异常前已生成的掉落必须回滚，不能留在地面");
    }

    @Test
    void giveOrDrop_rollsBackInventoryAddWhenDropThrows() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 60));
        ItemStack filler = new ItemStack(Material.STONE, 64);
        for (int i = 1; i < player.getInventory().getSize(); i++) {
            player.getInventory().setItem(i, filler.clone());
        }
        World world = worldThrowingOnCall(1, null);

        assertThrows(IllegalStateException.class, () -> ItemDelivery.giveOrDrop(player,
                new ItemStack(Material.DIAMOND, 10), world, loc),
                "dropItemNaturally 抛异常时应保留异常供调用方诊断");

        assertEquals(60, countInInventory(player, Material.DIAMOND),
                "异常时已放入背包的部分也必须回滚，不能多收");
    }

    @Test
    void giveOrDrop_rollbackInventoryAddThrowsWhenRemoveItemReturnsLeftovers() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 60));
        ItemStack filler = new ItemStack(Material.STONE, 64);
        for (int i = 1; i < player.getInventory().getSize(); i++) {
            player.getInventory().setItem(i, filler.clone());
        }

        // removeItem 不抛异常但返回未移除项：模拟 addItem 后第三方动了背包导致回滚只完成一半
        Inventory realInv = player.getInventory();
        Inventory partialFailInv = (Inventory) Proxy.newProxyInstance(
                ItemDeliveryAtomicityTest.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("removeItem")) {
                        Map<Integer, ItemStack> leftover = new HashMap<>();
                        leftover.put(0, new ItemStack(Material.DIAMOND, 1));
                        return leftover;
                    }
                    return method.invoke(realInv, args);
                });
        Player playerProxy = (Player) Proxy.newProxyInstance(
                ItemDeliveryAtomicityTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getInventory")) {
                        return partialFailInv;
                    }
                    return method.invoke(player, args);
                });

        Queue<Item> results = new LinkedList<>();
        results.add(null); // 溢出掉落被取消，触发背包回滚

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ItemDelivery.giveOrDrop(playerProxy, new ItemStack(Material.DIAMOND, 10),
                        worldReturning(results), loc),
                "removeItem 返回未移除项时必须视为回滚失败并抛出，调用方才能保留源数据");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("回滚"),
                "异常必须说明是背包回滚失败: " + ex);
    }

    @Test
    void giveOrDrop_rollbackInventoryFailureIsWrappedWithContext() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 60));
        ItemStack filler = new ItemStack(Material.STONE, 64);
        for (int i = 1; i < player.getInventory().getSize(); i++) {
            player.getInventory().setItem(i, filler.clone());
        }

        // 背包 addItem 正常工作，但 removeItem 抛异常：模拟回滚本身失败。
        Inventory realInv = player.getInventory();
        Inventory failingInv = (Inventory) Proxy.newProxyInstance(
                ItemDeliveryAtomicityTest.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("removeItem")) {
                        throw new IllegalStateException("simulated removeItem failure");
                    }
                    return method.invoke(realInv, args);
                });
        Player playerProxy = (Player) Proxy.newProxyInstance(
                ItemDeliveryAtomicityTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getInventory")) {
                        return failingInv;
                    }
                    return method.invoke(player, args);
                });

        Queue<Item> results = new LinkedList<>();
        results.add(null); // 溢出掉落被取消，触发背包回滚

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ItemDelivery.giveOrDrop(playerProxy, new ItemStack(Material.DIAMOND, 10),
                        worldReturning(results), loc),
                "背包回滚失败必须以异常上报，调用方才能保留源数据");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("回滚"),
                "回滚失败异常必须说明是背包回滚失败: " + ex);
        assertTrue(ex.getCause() instanceof IllegalStateException,
                "原始 removeItem 异常必须作为 cause 保留");
    }
}
