package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffOutcome;
import com.pepperminecart.api.TakeOffResult;
import com.pepperminecart.engine.CartEngine;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.CartTypeRegistry;
import com.pepperminecart.registry.handler.GenericBlockHandler;
import com.pepperminecart.registry.handler.SpecialCartHandler;
import com.pepperminecart.storage.CartData;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;

/**
 * takeOff 简化重构：必须“先交付成功，再清数据/移除实体”。
 * 当交付失败（掉落被取消，dropItemNaturally 返回 null）时，矿车 PDC/显示必须原样保留，可重试。
 */
class TakeOffAtomicityTest {

    private ServerMock server;
    private World worldProxy;
    private Location loc;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        worldProxy = (World) Proxy.newProxyInstance(
                TakeOffAtomicityTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dropItemNaturally")) {
                        return null; // 模拟 ItemSpawnEvent 被取消
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
        loc = new Location(worldProxy, 0, 64, 0);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Minecart fakeManagedCart() {
        return fakeManagedCart(UUID.randomUUID(), new PersistentDataContainerMock());
    }

    /** 已从世界移除的矿车代理：setDisplayBlockData 模拟服务端抛 IllegalStateException。 */
    private Minecart fakeRemovedCart(UUID id, PersistentDataContainerMock pdc) {
        org.bukkit.block.data.BlockData[] display = {Material.AIR.createBlockData()};
        return (Minecart) Proxy.newProxyInstance(
                TakeOffAtomicityTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld":
                        case "getLocation":
                            return null;
                        case "getUniqueId":
                            return id;
                        case "isValid":
                        case "isDead":
                            return false;
                        case "getType":
                            return EntityType.MINECART;
                        case "getPersistentDataContainer":
                            return pdc;
                        case "getDisplayBlockData":
                            return display[0];
                        case "setDisplayBlockData":
                            throw new IllegalStateException("entity already removed");
                        case "getDisplayBlockOffset":
                            return 0;
                        case "setDisplayBlockOffset":
                            throw new IllegalStateException("entity already removed");
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
    }

    /** 模拟“实体已被移除、PDC 不可读”的矿车：任何 PDC 访问都抛 IllegalStateException。 */
    private Minecart fakeCartWithBrokenPdc() {
        UUID id = UUID.randomUUID();
        return (Minecart) Proxy.newProxyInstance(
                TakeOffAtomicityTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld":
                            return worldProxy;
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
                            throw new IllegalStateException("simulated removed-entity PDC access");
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
    }

    @Test
    void handledTakeOff_entityRemovedAndPdcUnreadable_doesNotThrow() {
        PepperMinecartPlugin plugin = MockBukkit.load(PepperMinecartPlugin.class);
        plugin.engine().setSessionManager(null);
        Minecart cart = fakeCartWithBrokenPdc();

        CartTypeHandler removedHandler = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:removed-handled");
            }

            @Override
            public java.util.Set<Material> handledMaterials() {
                return java.util.Set.of();
            }

            @Override
            public TakeOffOutcome onTakeOff(Player player, CartContext ctx, TakeOffResult result) {
                ctx.getMinecart().remove(); // 模拟处理器自行移除实体
                return TakeOffOutcome.HANDLED;
            }
        };
        CartContextImpl ctx = plugin.engine().contextFor(cart, removedHandler);

        PlayerMock player = server.addPlayer();
        assertDoesNotThrow(() -> plugin.engine().takeOff(player, ctx, TakeOffResult.INVENTORY),
                "HANDLED 取下时实体已被移除导致 PDC 不可读，不得抛出异常逃出事件链");
    }

    private Minecart fakeManagedCart(UUID id, PersistentDataContainerMock pdc) {
        org.bukkit.block.data.BlockData[] display = {Material.AIR.createBlockData()};
        return (Minecart) Proxy.newProxyInstance(
                TakeOffAtomicityTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld":
                            return worldProxy;
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

    @Test
    void takeOff_dropDeliveryFailure_keepsPdcAndDisplay() {
        // 加载插件以取得 engine/config
        PepperMinecartPlugin plugin = MockBukkit.load(PepperMinecartPlugin.class);
        Minecart cart = fakeManagedCart();
        CartContextImpl ctx = plugin.engine().addCart(cart, GenericBlockHandler.INSTANCE,
                new ItemStack(Material.STONE));
        ctx.setDisplay(Material.STONE);
        assertTrue(CartData.isManaged(cart), "前置：矿车应受管");

        PlayerMock player = server.addPlayer();
        // DROP 模式会直接调用 dropItemNaturally，worldProxy 返回 null 表示交付失败
        plugin.engine().takeOff(player, ctx, TakeOffResult.DROP);

        assertTrue(CartData.isManaged(cart), "交付失败时不得清除 PDC");
        assertTrue(plugin.engine().isManaged(cart), "交付失败时引擎仍应跟踪，允许重试");
        assertTrue(ctx.hasDisplay(), "交付失败时显示方块不得被清除");
    }

    @Test
    void handledTakeOff_replaceEntityThenClearsPdc_removesNewContext() {
        PepperMinecartPlugin plugin = MockBukkit.load(PepperMinecartPlugin.class);
        Minecart oldCart = fakeManagedCart();
        PersistentDataContainerMock newPdc = new PersistentDataContainerMock();
        Minecart newCart = fakeManagedCart(UUID.randomUUID(), newPdc);
        CartContextImpl ctx = plugin.engine().addCart(oldCart, GenericBlockHandler.INSTANCE,
                new ItemStack(Material.STONE));

        CartTypeHandler handled = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:handled-replace");
            }

            @Override
            public java.util.Set<Material> handledMaterials() {
                return java.util.Set.of();
            }

            @Override
            public TakeOffOutcome onTakeOff(Player player, CartContext ctx, TakeOffResult result) {
                ctx.replaceEntity(newCart);
                // 模拟处理器替换后明确清空 PDC，但忘记/不调用 releaseCart
                CartData.clear(newCart);
                return TakeOffOutcome.HANDLED;
            }
        };
        // 通过 contextFor 换成 HANDLED 处理器（不写 PDC，仅临时 context）
        CartContextImpl handledCtx = plugin.engine().contextFor(oldCart, handled);

        PlayerMock player = server.addPlayer();
        plugin.engine().takeOff(player, handledCtx, TakeOffResult.INVENTORY);

        assertFalse(plugin.engine().isManaged(newCart), "替换后清空 PDC 的新矿车不应残留受管 context");
        assertFalse(plugin.engine().isManaged(oldCart), "旧矿车不应再受管");
    }

    @Test
    void takeOff_deliverySucceedsButCartAlreadyRemoved_removesContextWithoutThrowing() {
        PepperMinecartPlugin plugin = MockBukkit.load(PepperMinecartPlugin.class);
        Minecart cart = fakeRemovedCart(UUID.randomUUID(), new PersistentDataContainerMock());
        CartContextImpl ctx = plugin.engine().contextFor(cart, GenericBlockHandler.INSTANCE);
        CartData.setItem(cart, new ItemStack(Material.STONE));

        // 使用真实可掉落世界作为最后快照，使 DROP 交付成功
        World dropWorld = server.addSimpleWorld("drop-world");
        Location dropLoc = new Location(dropWorld, 1, 64, 1);
        ctx.updateSnapshot(dropWorld, dropLoc);
        assertTrue(plugin.engine().isManaged(cart), "前置：context 已登记");

        PlayerMock player = server.addPlayer();
        plugin.engine().takeOff(player, ctx, TakeOffResult.DROP);

        // setDisplayBlockData 会在 fake 上抛 IllegalStateException；没有异常即证明提交阶段
        // 未触碰失效实体的显示/PDC 清理。PDC 数据仍可读（fake 保留），说明我们没有非法清数据。
        assertTrue(CartData.isManaged(cart), "失效实体的 PDC 不应在提交阶段被清理（避免触发异常）");
    }

    @Test
    void takeOff_specialCartRemovedDuringDelivery_logsInvalidEntityWarning() {
        PepperMinecartPlugin plugin = MockBukkit.load(PepperMinecartPlugin.class);
        World dropWorld = server.addSimpleWorld("special-drop-world");
        PlayerMock player = server.addPlayer();
        StorageMinecart cart = dropWorld.spawn(new Location(dropWorld, 0, 64, 0), StorageMinecart.class);
        cart.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));
        plugin.engine().addCart(cart, new SpecialCartHandler(), new ItemStack(Material.CHEST));
        CartContextImpl ctx = plugin.engine().context(cart);

        List<String> warnings = new ArrayList<>();
        Handler logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        plugin.getLogger().addHandler(logHandler);

        AtomicBoolean removed = new AtomicBoolean();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onItemSpawn(ItemSpawnEvent e) {
                if (!removed.get() && e.getEntity().getItemStack().getType() == Material.CHEST) {
                    removed.set(true);
                    cart.remove(); // 模拟第三方插件在 ItemSpawnEvent 中移除矿车
                }
            }
        }, plugin);

        plugin.engine().takeOff(player, ctx, TakeOffResult.DROP);

        plugin.getLogger().removeHandler(logHandler);
        assertFalse(cart.isValid(), "前置：矿车应已在交付期间被移除");
        assertTrue(warnings.stream().anyMatch(m -> m.contains("已失效")),
                "提交阶段发现实体已失效时应记录告警: " + warnings);
    }

    @Test
    void handledTakeOff_releaseCartDoesNotLogMissingPdcWarning() {
        PepperMinecartPlugin plugin = MockBukkit.load(PepperMinecartPlugin.class);
        World world = server.addSimpleWorld("handled-world");
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);

        CartTypeHandler handled = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("pepperminecart:handled-release");
            }

            @Override
            public java.util.Set<Material> handledMaterials() {
                return java.util.Set.of(Material.STONE);
            }

            @Override
            public TakeOffOutcome onTakeOff(Player player, CartContext ctx, TakeOffResult result) {
                ctx.releaseCart();
                return TakeOffOutcome.HANDLED;
            }
        };
        plugin.engine().addCart(cart, handled, new ItemStack(Material.STONE));
        CartContextImpl ctx = plugin.engine().context(cart);

        List<String> warnings = new ArrayList<>();
        Handler logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        plugin.getLogger().addHandler(logHandler);
        plugin.engine().takeOff(player, ctx, TakeOffResult.INVENTORY);
        plugin.getLogger().removeHandler(logHandler);

        assertFalse(warnings.stream().anyMatch(m -> m.contains("HANDLED 取下未写 PDC")),
                "处理器已正确 releaseCart 时不得误报未写 PDC: " + warnings);
    }

    @Test
    void contextRebuild_migratesLastReplacement() throws Exception {
        PepperMinecartPlugin plugin = MockBukkit.load(PepperMinecartPlugin.class);
        Field regField = CartEngine.class.getDeclaredField("registry");
        regField.setAccessible(true);
        CartTypeRegistry registry = (CartTypeRegistry) regField.get(plugin.engine());

        NamespacedKey id = NamespacedKey.fromString("pepperminecart:rebuild-test");
        CartTypeHandler typeA = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return id;
            }

            @Override
            public java.util.Set<Material> handledMaterials() {
                return java.util.Set.of(Material.STONE);
            }
        };
        CartTypeHandler typeB = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return id;
            }

            @Override
            public java.util.Set<Material> handledMaterials() {
                return java.util.Set.of(Material.STONE);
            }
        };
        registry.register(typeA);

        World world = server.addSimpleWorld("rebuild-world");
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        RideableMinecart replacement = world.spawn(new Location(world, 1, 64, 0), RideableMinecart.class);
        plugin.engine().addCart(cart, typeA, new ItemStack(Material.STONE));
        CartContextImpl ctx = plugin.engine().context(cart);
        ctx.replaceEntity(replacement);

        registry.register(typeB); // 同 id 覆盖，触发 contextOrNull 重建

        CartContextImpl rebuilt = plugin.engine().contextOrNull(replacement);
        assertNotNull(rebuilt, "重建后的 context 应存在");
        assertSame(replacement, rebuilt.lastReplacement(),
                "重建 context 必须迁移 lastReplacement，否则放置回滚会漏掉替代实体");
    }
}
