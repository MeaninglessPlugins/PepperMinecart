package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * takeOff 未提交回滚的异常隔离：即使替代普通矿车的 remove() 抛异常，
 * 已生成的 vanilla 内容物掉落仍必须回滚，临时 context 仍必须清理，
 * 不能因单实体回滚失败跳过其余回滚。
 */
class TakeOffRollbackReplacementFailureTest {

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
    void takeOff_deliveryFailure_continuesRollbackWhenReplacementRemoveThrows() {
        PepperMinecartPlugin plugin = MockBukkit.load(PepperMinecartPlugin.class);
        plugin.engine().setSessionManager(null);

        // 第一次 dropItemNaturally：vanilla 内容物预掉落成功；第二次：交付方块物品被取消
        Item vanillaDrop = realWorld.dropItemNaturally(new Location(realWorld, 0, 64, 0),
                new ItemStack(Material.DIAMOND));
        Queue<Item> dropResults = new LinkedList<>();
        dropResults.add(vanillaDrop);
        dropResults.add(null);

        RideableMinecart realReplacement = realWorld.spawn(
                new Location(realWorld, 0, 64, 0), RideableMinecart.class);
        // 替代矿车代理：除 remove() 抛异常外全部委托真实实体
        RideableMinecart replacementProxy = (RideableMinecart) Proxy.newProxyInstance(
                TakeOffRollbackReplacementFailureTest.class.getClassLoader(),
                new Class<?>[]{RideableMinecart.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("remove")) {
                        throw new IllegalStateException("simulated replacement remove failure");
                    }
                    return method.invoke(realReplacement, args);
                });

        World worldProxy = (World) Proxy.newProxyInstance(
                TakeOffRollbackReplacementFailureTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dropItemNaturally")) {
                        return dropResults.poll(); // null 表示 ItemSpawnEvent 被取消
                    }
                    if (method.getName().equals("spawn") && args != null && args.length >= 2
                            && args[0] instanceof Location l && args[1] instanceof Class<?> type) {
                        if (type == RideableMinecart.class) {
                            return replacementProxy;
                        }
                        return method.invoke(realWorld, new Location(realWorld, l.getX(), l.getY(), l.getZ()), type);
                    }
                    return method.invoke(realWorld, args);
                });

        StorageMinecart realCart = realWorld.spawn(new Location(realWorld, 0, 64, 0), StorageMinecart.class);
        realCart.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        Minecart cartProxy = (Minecart) Proxy.newProxyInstance(
                TakeOffRollbackReplacementFailureTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class, InventoryHolder.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld":
                            return worldProxy;
                        case "getLocation":
                            return new Location(worldProxy, 0, 64, 0);
                        case "getType":
                            return EntityType.CHEST_MINECART;
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

        CartTypeHandler bareChestHandler = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:replacement-rollback");
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of();
            }

            @Override
            public ItemStack getTakeOffItem(CartContext ctx) {
                return new ItemStack(Material.CHEST);
            }
        };
        var ctx = plugin.engine().contextFor(cartProxy, bareChestHandler);
        assertTrue(plugin.engine().isManaged(cartProxy), "前置：临时 context 已登记");

        assertDoesNotThrow(() -> plugin.engine().takeOff(null, ctx, TakeOffResult.DROP),
                "替代矿车 remove 异常必须被屏障吸收，其余回滚不得因此中断");

        assertFalse(vanillaDrop.isValid(),
                "即使替代矿车回滚失败，已生成的 vanilla 内容物掉落也必须继续回滚");
        assertFalse(plugin.engine().isManaged(cartProxy), "即使替代矿车回滚失败，临时 context 也必须清理");
    }
}
