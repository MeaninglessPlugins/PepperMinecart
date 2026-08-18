package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.PepperMinecartPlugin;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.handler.DispenserCartHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Dispenser;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.meta.BlockStateMetaMock;

/**
 * 发射器发射事务的原子性契约（收敛方案）：
 * 1) 先扣减并持久化，再掉落——掉落事件发生时源槽必须已扣空，矿车被移除时销毁兜底只掉剩余库存；
 * 2) 掉落被取消且实体有效时，补偿恢复槽位。
 */
class DispenserCartShootAtomicityTest {

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

    /** 真实服务端上线后 shoot 为 package-private；红绿期间经反射调用保证同一切面可观测。 */
    private static boolean shoot(DispenserCartHandler handler, Minecart cart) throws Exception {
        Method shoot = DispenserCartHandler.class.getDeclaredMethod("shoot", Minecart.class);
        shoot.setAccessible(true);
        return (boolean) shoot.invoke(handler, cart);
    }

    private static boolean isEmpty(ItemStack it) {
        return it == null || it.getType().isAir() || it.getAmount() == 0;
    }

    private World worldWith(Dispenser live, AtomicBoolean deductedAtDrop, AtomicBoolean removed,
                            java.util.function.BooleanSupplier removeOnFirstDrop) {
        return (World) Proxy.newProxyInstance(
                DispenserCartShootAtomicityTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dropItemNaturally") && args != null && args.length == 2
                            && args[0] instanceof Location loc && args[1] instanceof ItemStack is) {
                        deductedAtDrop.set(isEmpty(live.getInventory().getItem(0)));
                        if (removeOnFirstDrop.getAsBoolean() && !removed.getAndSet(true)) {
                            // MockBukkit 不自动触发 EntityRemoveEvent：按 Paper 语义补事件后移除实体
                            Minecart cart = (Minecart) liveCartHolder.get();
                            server.getPluginManager().callEvent(new EntityRemoveEvent(cart, EntityRemoveEvent.Cause.PLUGIN));
                            cart.remove();
                        }
                        return world.dropItemNaturally(loc, is);
                    }
                    return method.invoke(world, args);
                });
    }

    /** 经代理 World 注入移除逻辑时把真实矿车暴露给测试（避免 lambda 捕获顺序问题）。 */
    private final java.util.concurrent.atomic.AtomicReference<Minecart> liveCartHolder = new java.util.concurrent.atomic.AtomicReference<>();

    private Minecart proxiedCart(Minecart real, World proxiedWorld) {
        liveCartHolder.set(real);
        return (Minecart) Proxy.newProxyInstance(
                DispenserCartShootAtomicityTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getWorld")) {
                        return proxiedWorld;
                    }
                    return method.invoke(real, args);
                });
    }

    @Test
    void shootDeductsSourceBeforeDroppingWhenCartRemovedDuringDrop() throws Exception {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);

        ItemStack blockItem = new ItemStack(Material.DISPENSER, 1);
        blockItem.setItemMeta(new BlockStateMetaMock(Material.DISPENSER));

        DispenserCartHandler handler = new DispenserCartHandler(plugin.pluginConfig(), plugin.sessions());
        plugin.engine().addCart(cart, handler, blockItem);
        CartContextImpl ctx = plugin.engine().context(cart);

        assertNotNull(handler.onInteract(player, ctx), "打开发射器容器界面失败");
        Dispenser live = (Dispenser) plugin.sessions().liveContainer(cart);
        assertNotNull(live, "应有实时容器会话");
        live.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));

        AtomicBoolean deductedAtDrop = new AtomicBoolean();
        AtomicBoolean removed = new AtomicBoolean();
        World proxiedWorld = worldWith(live, deductedAtDrop, removed, () -> true);
        Minecart proxied = proxiedCart(cart, proxiedWorld);

        boolean shot = shoot(handler, proxied);

        assertTrue(deductedAtDrop.get(), "掉落发生时源槽必须已扣空（先扣后掉不变量）");
        assertTrue(shot, "已交付的发射应返回 true");
        assertTrue(removed.get(), "前置：矿车应在掉落期间被移除");
        assertTrue(isEmpty(live.getInventory().getItem(0)), "源槽应保持扣空（销毁兜底只会掉剩余库存）");
        long diamonds = world.getEntities().stream()
                .filter(e -> e instanceof Item item && item.getItemStack().getType() == Material.DIAMOND)
                .count();
        assertEquals(1, diamonds, "只应有发射出去的那 1 个钻石，不得复制");
    }

    @Test
    void shootAcceptsSingleItemLossWhenDropCancelledAndCartRemovedTogether() throws Exception {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);

        ItemStack blockItem = new ItemStack(Material.DISPENSER, 1);
        blockItem.setItemMeta(new BlockStateMetaMock(Material.DISPENSER));

        DispenserCartHandler handler = new DispenserCartHandler(plugin.pluginConfig(), plugin.sessions());
        plugin.engine().addCart(cart, handler, blockItem);
        CartContextImpl ctx = plugin.engine().context(cart);

        assertNotNull(handler.onInteract(player, ctx), "打开发射器容器界面失败");
        Dispenser live = (Dispenser) plugin.sessions().liveContainer(cart);
        live.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));

        AtomicBoolean removed = new AtomicBoolean();
        World bothWorld = (World) Proxy.newProxyInstance(
                DispenserCartShootAtomicityTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dropItemNaturally") && args != null && args.length == 2
                            && args[0] instanceof Location loc && args[1] instanceof ItemStack is) {
                        if (!removed.getAndSet(true) && cart.isValid()) {
                            server.getPluginManager().callEvent(new EntityRemoveEvent(cart, EntityRemoveEvent.Cause.PLUGIN));
                            cart.remove();
                        }
                        return null; // ItemSpawnEvent 被取消
                    }
                    return method.invoke(world, args);
                });
        Minecart proxied = proxiedCart(cart, bothWorld);

        boolean shot = shoot(handler, proxied);

        assertFalse(shot, "掉落被取消时发射应返回 false");
        assertTrue(removed.get(), "前置：矿车应在掉落期间被移除");
        assertTrue(isEmpty(live.getInventory().getItem(0)), "销毁兜底按已扣减状态掉落剩余库存");
        long diamonds = world.getEntities().stream()
                .filter(e -> e instanceof Item item && item.getItemStack().getType() == Material.DIAMOND)
                .count();
        assertEquals(0, diamonds, "双重失败窗口接受 1 件损失：克隆被取消、矿车已移除，不得再触碰失效实体");
    }

    @Test
    void shootAbortsAndRestoresMemoryStateWhenPersistFailsBeforeDrop() throws Exception {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);

        ItemStack blockItem = new ItemStack(Material.DISPENSER, 1);
        blockItem.setItemMeta(new BlockStateMetaMock(Material.DISPENSER));

        DispenserCartHandler handler = new DispenserCartHandler(plugin.pluginConfig(), plugin.sessions());
        plugin.engine().addCart(cart, handler, blockItem);
        CartContextImpl ctx = plugin.engine().context(cart);

        assertNotNull(handler.onInteract(player, ctx), "打开发射器容器界面失败");
        Dispenser live = (Dispenser) plugin.sessions().liveContainer(cart);
        live.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));

        // PDC 写失败：get 正常、set 抛异常——模拟 CartData.setItem 持久化失败
        PersistentDataContainer realPdc = cart.getPersistentDataContainer();
        PersistentDataContainer failingPdc = (PersistentDataContainer) Proxy.newProxyInstance(
                DispenserCartShootAtomicityTest.class.getClassLoader(),
                new Class<?>[]{PersistentDataContainer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("set")) {
                        throw new IllegalStateException("simulated PDC write failure");
                    }
                    return method.invoke(realPdc, args);
                });
        Minecart persistFailing = (Minecart) Proxy.newProxyInstance(
                DispenserCartShootAtomicityTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPersistentDataContainer")) {
                        return failingPdc;
                    }
                    return method.invoke(cart, args);
                });

        assertThrows(InvocationTargetException.class, () -> shoot(handler, persistFailing),
                "persist 失败必须中止发射（由引擎异常屏障记录）");
        ItemStack kept = live.getInventory().getItem(0);
        assertNotNull(kept, "persist 失败时已扣减的内存槽位必须恢复，界面不得凭空少 1 件");
        assertEquals(Material.DIAMOND, kept.getType());
        assertEquals(1, kept.getAmount());
        long diamonds = world.getEntities().stream()
                .filter(e -> e instanceof Item item && item.getItemStack().getType() == Material.DIAMOND)
                .count();
        assertEquals(0, diamonds, "persist 失败前不得产生地面物品");
    }

    @Test
    void shootRestoresSlotWhenDropCancelledAndCartStillValid() throws Exception {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);

        ItemStack blockItem = new ItemStack(Material.DISPENSER, 1);
        blockItem.setItemMeta(new BlockStateMetaMock(Material.DISPENSER));

        DispenserCartHandler handler = new DispenserCartHandler(plugin.pluginConfig(), plugin.sessions());
        plugin.engine().addCart(cart, handler, blockItem);
        CartContextImpl ctx = plugin.engine().context(cart);

        assertNotNull(handler.onInteract(player, ctx), "打开发射器容器界面失败");
        Dispenser live = (Dispenser) plugin.sessions().liveContainer(cart);
        live.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));

        AtomicBoolean deductedAtDrop = new AtomicBoolean();
        AtomicBoolean removed = new AtomicBoolean();
        World cancellingWorld = (World) Proxy.newProxyInstance(
                DispenserCartShootAtomicityTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dropItemNaturally") && args != null && args.length == 2) {
                        deductedAtDrop.set(isEmpty(live.getInventory().getItem(0)));
                        return null; // ItemSpawnEvent 被第三方取消
                    }
                    return method.invoke(world, args);
                });
        Minecart proxied = proxiedCart(cart, cancellingWorld);

        boolean shot = shoot(handler, proxied);

        assertTrue(deductedAtDrop.get(), "取消路径同样必须先扣后掉再补偿");
        assertFalse(shot, "掉落被取消时发射应返回 false");
        ItemStack restored = live.getInventory().getItem(0);
        assertNotNull(restored, "掉落被取消时必须恢复槽位");
        assertEquals(Material.DIAMOND, restored.getType());
        assertEquals(1, restored.getAmount(), "恢复后数量应与扣减前一致");
    }
}