package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.handler.DispenserCartHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Dispenser;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.meta.BlockStateMetaMock;

/**
 * 发射器矿车发射的原子性回归：shoot 在 ItemSpawnEvent 返回后不得再访问 cart.getWorld()/getLocation()。
 * 若访问，第三方插件在 ItemSpawnEvent 中移除矿车时会 NPE 在扣库存之前，地面已掉出物品而库存未扣。
 */
class DispenserCartShootRaceTest {

    private ServerMock server;
    private World world;
    private PepperMinecartPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PepperMinecartPlugin.class);
        world = server.addSimpleWorld("test");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void shootUsesCachedWorldAndLocationAfterDrop() throws Exception {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);

        ItemStack blockItem = new ItemStack(Material.DISPENSER, 1);
        BlockStateMeta meta = new BlockStateMetaMock(Material.DISPENSER);
        blockItem.setItemMeta(meta);

        DispenserCartHandler handler = new DispenserCartHandler(plugin.pluginConfig(), plugin.sessions());
        plugin.engine().addCart(cart, handler, blockItem);
        CartContextImpl ctx = plugin.engine().context(cart);

        assertNotNull(handler.onInteract(player, ctx), "打开发射器容器界面失败");
        Dispenser live = (Dispenser) plugin.sessions().liveContainer(cart);
        assertNotNull(live, "应有实时容器会话");
        Inventory liveInv = live.getInventory();
        liveInv.setItem(0, new ItemStack(Material.DIAMOND, 1));

        // 代理矿车：getWorld/getLocation 只允许各调用一次；第二次起返回 null，
        // 模拟 ItemSpawnEvent 期间矿车被第三方移除后实体已失效。
        AtomicInteger worldCalls = new AtomicInteger();
        AtomicInteger locCalls = new AtomicInteger();
        Minecart proxied = (Minecart) Proxy.newProxyInstance(
                DispenserCartShootRaceTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getWorld")) {
                        return worldCalls.incrementAndGet() == 1 ? world : null;
                    }
                    if (method.getName().equals("getLocation")) {
                        return locCalls.incrementAndGet() == 1 ? cart.getLocation() : null;
                    }
                    return method.invoke(cart, args);
                });

        Method shoot = DispenserCartHandler.class.getDeclaredMethod("shoot", Minecart.class);
        shoot.setAccessible(true);
        boolean shot;
        try {
            shot = (boolean) shoot.invoke(handler, proxied);
        } catch (InvocationTargetException ex) {
            throw new AssertionError(
                    "shoot 在掉落完成后不得再访问 cart.getWorld()/getLocation()，"
                            + "否则 ItemSpawnEvent 期间矿车被移除会 NPE", ex.getCause());
        }

        assertTrue(shot, "发射应成功");
        ItemStack remaining = liveInv.getItem(0);
        assertTrue(remaining == null || remaining.getType().isAir() || remaining.getAmount() == 0,
                "发射成功后库存必须扣减");
        long diamonds = world.getEntities().stream()
                .filter(e -> e instanceof Item item && item.getItemStack().getType() == Material.DIAMOND)
                .count();
        assertEquals(1, diamonds, "发射应恰好掉落 1 个钻石");
    }
}
