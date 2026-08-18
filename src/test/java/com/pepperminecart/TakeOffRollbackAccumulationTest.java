package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffResult;
import java.lang.reflect.Proxy;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * takeOff 回滚跟踪回归：vanilla 预掉落与容器 DROP 溢出的回滚实体列表必须合并。
 * 容器 spill 分支不得覆盖 vanilla 预掉落的跟踪，否则交付失败时前者残留地面、
 * 矿车库存未清，重试取下会造成物品复制。
 */
class TakeOffRollbackAccumulationTest {

    private ServerMock server;
    private World realWorld;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        realWorld = server.addSimpleWorld("test");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void takeOff_deliveryFailure_rollsBackVanillaDropsEvenWhenContainerSpillRuns() {
        PepperMinecartPlugin plugin = MockBukkit.load(PepperMinecartPlugin.class);
        plugin.getConfig().set("container-pickup-policy", "DROP");
        plugin.pluginConfig().apply(plugin.getConfig());
        plugin.engine().setSessionManager(null);

        // 第一个 dropItemNaturally 返回真实掉落实体（vanilla 预掉落），第二个返回 null（交付被取消）
        Item vanillaDrop = realWorld.dropItemNaturally(new Location(realWorld, 0, 64, 0),
                new ItemStack(Material.DIAMOND));
        Queue<Item> dropResults = new LinkedList<>();
        dropResults.add(vanillaDrop);
        dropResults.add(null);

        World worldProxy = (World) Proxy.newProxyInstance(
                TakeOffRollbackAccumulationTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dropItemNaturally")) {
                        return dropResults.poll(); // null 表示 ItemSpawnEvent 被取消
                    }
                    if (method.getName().equals("spawn") && args != null && args.length >= 2
                            && args[0] instanceof Location l && args[1] instanceof Class<?> type) {
                        // 用真实世界坐标重新投放，避免代理世界与真实世界不一致
                        return method.invoke(realWorld, new Location(realWorld, l.getX(), l.getY(), l.getZ()), type);
                    }
                    return method.invoke(realWorld, args);
                });

        StorageMinecart realCart = realWorld.spawn(new Location(realWorld, 0, 64, 0), StorageMinecart.class);
        realCart.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        Minecart cartProxy = (Minecart) Proxy.newProxyInstance(
                TakeOffRollbackAccumulationTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class, InventoryHolder.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld":
                            return worldProxy;
                        case "getLocation":
                            return new Location(worldProxy, 0, 64, 0);
                        case "isValid":
                            return true;
                        case "isDead":
                            return false;
                        case "getPassengers":
                            return List.of();
                        default:
                            return method.invoke(realCart, args);
                    }
                });

        // 自定义类型：既是原版特殊矿车（InventoryHolder），又受容器 DROP 策略管控，
        // 且返还物品未内嵌原版库存内容 —— 同时触发 vanilla 预掉落与容器 spill 分支
        CartTypeHandler dual = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:dual-rollback");
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
                return new ItemStack(Material.STONE);
            }
        };
        var ctx = plugin.engine().contextFor(cartProxy, dual);

        plugin.engine().takeOff(null, ctx, TakeOffResult.DROP);

        assertFalse(vanillaDrop.isValid(),
                "交付失败时必须回滚 vanilla 预掉落；容器 spill 分支不得覆盖其回滚跟踪");
        assertTrue(realCart.isValid(), "交付失败时特殊矿车本体应保留，可安全重试");
    }
}
