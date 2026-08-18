package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.pepperminecart.api.TakeOffResult;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.handler.GenericBlockHandler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 已确证 Bug #2 的契约：DISABLED 模式下 deliverItem 必须如实返回 false，
 * 且不得把物品放进背包或掉到地面（调用方据此保留源数据，杜绝静默丢物）。
 */
class CartContextDeliverDisabledTest {

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
    void deliverItemNullModeReturnsFalseAndDeliversNothing() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        CartContextImpl ctx = plugin.engine().addCart(cart, GenericBlockHandler.INSTANCE,
                new ItemStack(Material.STONE));

        boolean delivered = ctx.deliverItem(player, new ItemStack(Material.DIAMOND, 1), null);

        assertFalse(delivered, "null 策略必须按未交付处理，调用方才能保留源数据");
        long inInventory = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == Material.DIAMOND) {
                inInventory += it.getAmount();
            }
        }
        assertEquals(0, inInventory, "null 策略不得把物品放进背包");
        long onGround = world.getEntities().stream()
                .filter(e -> e instanceof Item item && item.getItemStack().getType() == Material.DIAMOND)
                .count();
        assertEquals(0, onGround, "null 策略不得把物品掉到地面");
    }

    @Test
    void deliverItemDisabledReturnsFalseAndDeliversNothing() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        CartContextImpl ctx = plugin.engine().addCart(cart, GenericBlockHandler.INSTANCE,
                new ItemStack(Material.STONE));

        boolean delivered = ctx.deliverItem(player, new ItemStack(Material.DIAMOND, 1),
                TakeOffResult.DISABLED);

        assertFalse(delivered, "DISABLED 模式必须如实报告未交付，调用方才能保留源数据");
        long inInventory = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == Material.DIAMOND) {
                inInventory += it.getAmount();
            }
        }
        assertEquals(0, inInventory, "DISABLED 模式不得把物品放进背包");
        long onGround = world.getEntities().stream()
                .filter(e -> e instanceof Item item && item.getItemStack().getType() == Material.DIAMOND)
                .count();
        assertEquals(0, onGround, "DISABLED 模式不得把物品掉到地面");
    }
}
