package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.handler.GenericBlockHandler;
import com.pepperminecart.registry.handler.SpecialCartHandler;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 受管特殊矿车死亡掉落回归：DEATH + DO_ENTITY_DROPS=true 时，原版会生成特殊矿车物品，
 * 插件必须只清理 context，不再补掉方块模板，避免 CHEST + CHEST_MINECART 复制。
 */
class SpecialCartDeathNoExtraDropTest {

    private ServerMock server;
    private World world;
    private PepperMinecartPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PepperMinecartPlugin.class);
        world = server.addSimpleWorld("test");
        world.setGameRule(GameRule.DO_ENTITY_DROPS, true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private long droppedItems(Material material) {
        return world.getEntities().stream()
                .filter(e -> e instanceof Item item && item.getItemStack().getType() == material)
                .count();
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, CartContextImpl> contexts() throws Exception {
        Field field = plugin.engine().getClass().getDeclaredField("contexts");
        field.setAccessible(true);
        return (Map<UUID, CartContextImpl>) field.get(plugin.engine());
    }

    @Test
    void managedSpecialCartDeathWithVanillaDrops_skipsPluginSupplement() throws Exception {
        StorageMinecart cart = world.spawn(new Location(world, 0, 64, 0), StorageMinecart.class);
        cart.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        AtomicBoolean destroyedCalled = new AtomicBoolean();
        SpecialCartHandler spy = new SpecialCartHandler() {
            @Override
            public void onCartDestroyed(CartContext ctx) {
                destroyedCalled.set(true);
                super.onCartDestroyed(ctx);
            }
        };
        plugin.engine().addCart(cart, spy, new ItemStack(Material.CHEST));
        assertTrue(plugin.engine().isManaged(cart), "前置：特殊矿车应受管");

        server.getPluginManager().callEvent(new EntityRemoveEvent(cart, EntityRemoveEvent.Cause.DEATH));

        assertFalse(destroyedCalled.get(),
                "DEATH + DO_ENTITY_DROPS=true 时引擎必须直接采用原版掉落，不得再回调 onCartDestroyed");
        assertEquals(0, droppedItems(Material.CHEST),
                "插件不得补掉 CHEST 方块物品，否则会与原版 CHEST_MINECART 重复");
        assertFalse(contexts().containsKey(cart.getUniqueId()), "死亡短路后 context 必须清理");
    }

    @Test
    void managedSpecialCartDeathWithoutVanillaDrops_stillDropsBlock() {
        world.setGameRule(GameRule.DO_ENTITY_DROPS, false);
        StorageMinecart cart = world.spawn(new Location(world, 0, 64, 0), StorageMinecart.class);
        plugin.engine().addCart(cart, new SpecialCartHandler(), new ItemStack(Material.CHEST));

        server.getPluginManager().callEvent(new EntityRemoveEvent(cart, EntityRemoveEvent.Cause.DEATH));

        assertEquals(1, droppedItems(Material.CHEST),
                "DO_ENTITY_DROPS=false 时原版不会生成矿车物品，插件仍须掉落方块物品");
    }

    @Test
    void managedNormalCartDeath_stillDropsBlock() throws Exception {
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        plugin.engine().addCart(cart, GenericBlockHandler.INSTANCE, new ItemStack(Material.STONE));

        server.getPluginManager().callEvent(new EntityRemoveEvent(cart, EntityRemoveEvent.Cause.DEATH));

        assertEquals(1, droppedItems(Material.STONE),
                "普通方块矿车不属于原版特殊矿车，死亡掉落行为不得被死亡短路改变");
        assertFalse(contexts().containsKey(cart.getUniqueId()), "普通矿车死亡后 context 仍应清理");
    }
}
