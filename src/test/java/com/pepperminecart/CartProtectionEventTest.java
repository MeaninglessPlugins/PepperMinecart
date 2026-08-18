package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.event.CartBlockPlaceEvent;
import com.pepperminecart.api.event.CartBlockTakeOffEvent;
import com.pepperminecart.storage.CartData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** 矿车语义保护事件：其他插件取消新事件时，放置/取下必须被拒绝。 */
class CartProtectionEventTest {

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
    void cancelledPlaceEventRejectsPlacement() {
        PepperMinecartPlugin plugin = MockBukkit.load(PepperMinecartPlugin.class);
        plugin.getConfig().set("interaction-cooldown-ms", 0);
        plugin.pluginConfig().apply(plugin.getConfig());

        PluginMock guard = MockBukkit.createMockPlugin("place-guard");
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onCartPlace(CartBlockPlaceEvent event) {
                event.setCancelled(true);
            }
        }, guard);

        PlayerMock player = server.addPlayer();
        player.setSneaking(true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE, 1));
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);

        org.bukkit.event.player.PlayerInteractEntityEvent interact =
                new org.bukkit.event.player.PlayerInteractEntityEvent(player, cart, org.bukkit.inventory.EquipmentSlot.HAND);
        server.getPluginManager().callEvent(interact);

        assertFalse(CartData.isManaged(cart), "矿车放置保护事件被取消时不得放置方块");
    }

    @Test
    void cancelledTakeOffEventRejectsPickup() {
        PepperMinecartPlugin plugin = MockBukkit.load(PepperMinecartPlugin.class);
        plugin.getConfig().set("interaction-cooldown-ms", 0);
        plugin.pluginConfig().apply(plugin.getConfig());

        PlayerMock player = server.addPlayer();
        player.setSneaking(true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE, 1));
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        org.bukkit.event.player.PlayerInteractEntityEvent place =
                new org.bukkit.event.player.PlayerInteractEntityEvent(player, cart, org.bukkit.inventory.EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);
        assertTrue(CartData.isManaged(cart));

        PluginMock guard = MockBukkit.createMockPlugin("take-guard");
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onCartTakeOff(CartBlockTakeOffEvent event) {
                event.setCancelled(true);
            }
        }, guard);

        org.bukkit.event.player.PlayerInteractEntityEvent take =
                new org.bukkit.event.player.PlayerInteractEntityEvent(player, cart, org.bukkit.inventory.EquipmentSlot.HAND);
        server.getPluginManager().callEvent(take);

        assertTrue(CartData.isManaged(cart), "矿车取下保护事件被取消时不得清空矿车数据");
    }
}
