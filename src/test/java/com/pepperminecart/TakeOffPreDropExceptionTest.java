package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.impl.CartContextImpl;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * takeOff 的原版库存预掉落必须与相邻分支一致地隔离 RuntimeException：
 * dropItemNaturally 抛异常时取消本次取下、保留矿车与库存、回滚替代矿车。
 */
class TakeOffPreDropExceptionTest {

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
    void takeOff_vanillaContentsDropThrows_cancelsWithoutLosingCart() {
        StorageMinecart cart = world.spawn(new Location(world, 0, 64, 0), StorageMinecart.class);
        cart.getInventory().setItem(0, new ItemStack(Material.DIAMOND));

        // 返还物品不含内嵌内容，迫使 takeOff 走“原版库存预掉落”分支
        CartTypeHandler bareChestHandler = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:bare-chest");
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
        CartContextImpl ctx = plugin.engine().contextFor(cart, bareChestHandler);

        // ItemSpawnEvent 监听器抛异常：等价于 dropItemNaturally 抛 RuntimeException
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void onItemSpawn(ItemSpawnEvent event) {
                throw new IllegalStateException("simulated drop exception");
            }
        }, plugin);

        PlayerMock player = server.addPlayer();
        assertDoesNotThrow(() -> plugin.engine().takeOff(player, ctx,
                        com.pepperminecart.api.TakeOffResult.INVENTORY),
                "预掉落 dropItemNaturally 抛异常时，takeOff 应记录并取消，而不是让异常逃出事件链");

        assertTrue(cart.isValid(), "取下取消时特殊矿车本体应保留");
        assertNotNull(cart.getInventory().getItem(0), "取下取消时原版库存不得丢失");
        assertTrue(world.getEntitiesByClass(RideableMinecart.class).isEmpty(),
                "取下取消时已生成的替代普通矿车必须回滚");
        assertFalse(plugin.engine().isManaged(cart), "临时 context 不得残留在受管集合");
    }
}
