package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.event.CartBlockPlaceEvent;
import com.pepperminecart.api.event.CartBlockTakeOffEvent;
import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.storage.CartData;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 可取消事件取消路径覆盖：
 * 其他插件先取消事件时，本插件监听器（ignoreCancelled=true）不得执行副作用；
 * 本插件模拟的 BlockPlaceEvent/BlockBreakEvent 被取消时，放置/取下必须中止。
 */
class CancellableEventCoverageTest {

    private ServerMock server;
    private PepperMinecartPlugin plugin;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PepperMinecartPlugin.class);
        world = server.addSimpleWorld("test");
        plugin.getConfig().set("interaction-cooldown-ms", 0);
        plugin.getConfig().set("sounds.place", false);
        plugin.getConfig().set("sounds.take-off", false);
        plugin.pluginConfig().apply(plugin.getConfig());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private RideableMinecart spawnCart() {
        return world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
    }

    private PlayerInteractEntityEvent interact(PlayerMock player, Minecart cart) {
        PlayerInteractEntityEvent e = new PlayerInteractEntityEvent(player, cart, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        return e;
    }

    private void setMainHand(PlayerMock player, Material material, int amount) {
        player.getInventory().setItemInMainHand(new ItemStack(material, amount));
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
    void preCancelledPlayerInteract_doesNotPlace() {
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void cancel(PlayerInteractEntityEvent event) {
                event.setCancelled(true);
            }
        }, MockBukkit.createMockPlugin("cancel-interact"));

        PlayerMock player = server.addPlayer();
        RideableMinecart cart = spawnCart();
        player.setSneaking(true);
        setMainHand(player, Material.STONE, 3);

        interact(player, cart);

        assertFalse(CartData.isManaged(cart), "被其他插件取消的交互不得触发放置");
        assertEquals(3, countInInventory(player, Material.STONE), "物品不得被消耗");
    }

    @Test
    void preCancelledMount_doesNotInvokeHandler() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        CartTypeHandler handler = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:mount-cancel");
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of();
            }

            @Override
            public boolean allowRiding() {
                return true;
            }

            @Override
            public void onMount(org.bukkit.entity.Entity rider, CartContext ctx) {
                invoked.set(true);
            }
        };
        RideableMinecart cart = spawnCart();
        plugin.engine().addCart(cart, handler, new ItemStack(Material.STONE));

        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void cancel(EntityMountEvent event) {
                event.setCancelled(true);
            }
        }, MockBukkit.createMockPlugin("cancel-mount"));

        PlayerMock player = server.addPlayer();
        server.getPluginManager().callEvent(new EntityMountEvent(player, cart));

        assertFalse(invoked.get(), "事件已被其他插件取消时，本插件 MONITOR/HIGHEST 处理器不应再回调 onMount");
    }

    @Test
    void cancelledCartBlockPlaceEvent_blocksPlacement() {
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void cancel(CartBlockPlaceEvent event) {
                event.setCancelled(true);
            }
        }, MockBukkit.createMockPlugin("cancel-cart-place"));

        PlayerMock player = server.addPlayer();
        RideableMinecart cart = spawnCart();
        player.setSneaking(true);
        setMainHand(player, Material.STONE, 3);

        interact(player, cart);

        assertFalse(CartData.isManaged(cart), "CartBlockPlaceEvent 被取消时不得放置");
        assertEquals(3, countInInventory(player, Material.STONE), "物品不得被消耗");
    }

    @Test
    void cancelledBlockPlaceEvent_blocksPlacement() {
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void cancel(BlockPlaceEvent event) {
                event.setCancelled(true);
            }
        }, MockBukkit.createMockPlugin("cancel-block-place"));

        PlayerMock player = server.addPlayer();
        RideableMinecart cart = spawnCart();
        player.setSneaking(true);
        setMainHand(player, Material.STONE, 3);

        interact(player, cart);

        assertFalse(CartData.isManaged(cart), "模拟 BlockPlaceEvent 被取消时不得放置");
        assertEquals(3, countInInventory(player, Material.STONE), "物品不得被消耗");
    }

    @Test
    void cancelledCartBlockTakeOffEvent_blocksTakeOff() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = spawnCart();
        player.setSneaking(true);
        setMainHand(player, Material.STONE, 3);
        interact(player, cart);
        assertTrue(CartData.isManaged(cart), "前置：放置成功");

        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void cancel(CartBlockTakeOffEvent event) {
                event.setCancelled(true);
            }
        }, MockBukkit.createMockPlugin("cancel-cart-takeoff"));

        interact(player, cart);

        assertTrue(CartData.isManaged(cart), "CartBlockTakeOffEvent 被取消时方块应保留在矿车上");
        assertTrue(new CartBlockDisplay(cart).has(), "显示方块不得被清除");
    }

    @Test
    void inventoryClickHandlerIgnoresCancelledEvents() throws Exception {
        var method = com.pepperminecart.container.CartSessionManager.class.getMethod(
                "onClick", org.bukkit.event.inventory.InventoryClickEvent.class);
        EventHandler annotation = method.getAnnotation(EventHandler.class);
        assertTrue(annotation.ignoreCancelled(), "InventoryClickEvent 被取消时不得继续处理铁砧点击");
    }

    @Test
    void cancelledBlockBreakEvent_blocksTakeOff() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = spawnCart();
        player.setSneaking(true);
        setMainHand(player, Material.STONE, 3);
        interact(player, cart);
        assertTrue(CartData.isManaged(cart), "前置：放置成功");

        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void cancel(BlockBreakEvent event) {
                event.setCancelled(true);
            }
        }, MockBukkit.createMockPlugin("cancel-block-break"));

        interact(player, cart);

        assertTrue(CartData.isManaged(cart), "模拟 BlockBreakEvent 被取消时方块应保留在矿车上");
        assertTrue(new CartBlockDisplay(cart).has(), "显示方块不得被清除");
    }

}
