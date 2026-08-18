package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffResult;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 回归：返还物品内嵌原版库存内容 + 容器 DROP spill 时，同一批内容物只能掉落一次。
 * takeOff 有两条内容处置路径（原版库存预掉落 / 容器 DROP spill），当 spill 清空了
 * 返还物品中的内嵌内容后，提交阶段不得再把原版库存同一内容 drain 一次。
 */
class SpecialCartEmbeddedDropNoDuplicateTest {

    private ServerMock server;
    private World world;
    private PepperMinecartPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PepperMinecartPlugin.class);
        world = server.addSimpleWorld("test");
        plugin.getConfig().set("container-pickup-policy", "DROP");
        plugin.pluginConfig().apply(plugin.getConfig());
        plugin.engine().setSessionManager(null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private long dropped(Material type) {
        return world.getEntities().stream()
                .filter(e -> e instanceof Item)
                .map(e -> (Item) e)
                .filter(i -> i.getItemStack().getType() == type)
                .count();
    }

    @Test
    void takeOff_embeddedContentsWithContainerDrop_doesNotDropContentsTwice() {
        StorageMinecart cart = world.spawn(new Location(world, 0, 64, 0), StorageMinecart.class);
        cart.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));

        CartTypeHandler dual = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:embedded-drop");
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of(Material.CHEST);
            }

            @Override
            public boolean isContainerPickupControlled(Material material) {
                return true;
            }

            @Override
            public ItemStack getTakeOffItem(CartContext ctx) {
                ItemStack chest = new ItemStack(Material.CHEST);
                BlockStateMeta meta = (BlockStateMeta) chest.getItemMeta();
                Chest state = (Chest) meta.getBlockState();
                state.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));
                meta.setBlockState(state);
                chest.setItemMeta(meta);
                return chest;
            }
        };
        var ctx = plugin.engine().contextFor(cart, dual);
        assertTrue(plugin.engine().isManaged(cart), "前置：临时 context 已登记");

        plugin.engine().takeOff(null, ctx, TakeOffResult.DROP);

        assertEquals(1, dropped(Material.DIAMOND),
                "内容物只能掉落一次：spill 清空返还物品后，提交阶段不得再 drain 原版库存");
        for (ItemStack it : cart.getInventory().getContents()) {
            assertTrue(it == null || it.getType().isAir(), "取下后原版库存应已清空");
        }
        assertEquals(1, world.getEntitiesByClass(RideableMinecart.class).size(),
                "特殊矿车取下后应还原为一个普通矿车");
        assertFalse(plugin.engine().isManaged(cart), "取下完成后临时 context 应清理");
    }
}
