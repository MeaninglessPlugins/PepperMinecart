package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.PepperMinecartAPI;
import com.pepperminecart.storage.CartData;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** 放置事务：成功后必须消耗“放置时快照的主手物品”，而不是回调之后重新读取的主手。 */
class PlaceSnapshotConsumptionTest {

    private static final NamespacedKey SNAPSHOT_TYPE = NamespacedKey.fromString("test:snapshot-consumer");

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
        plugin.pluginConfig().apply(plugin.getConfig());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void consumesPlacedSnapshotWhenHandlerRewritesMainHand() {
        PepperMinecartAPI api = server.getServicesManager().load(PepperMinecartAPI.class);
        api.registerCartType(new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return SNAPSHOT_TYPE;
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of(Material.STONE);
            }

            @Override
            public void onPlaced(Player player, CartContext ctx) {
                // 合法扩展行为：回调中改动玩家主手（先把当前主手挪到背包，再放钻石）。
                // 放置成功后必须仍消耗 STONE 快照，而不是把 DIAMOND 当成“玩家刚才放的方块”扣掉。
                ItemStack remaining = player.getInventory().getItemInMainHand();
                player.getInventory().setItem(9, remaining);
                player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 5));
            }
        });

        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE, 3));
        player.setSneaking(true);
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);

        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(player, cart, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "潜行 + 手持方块应触发放置");

        ItemStack hand = player.getInventory().getItemInMainHand();
        assertEquals(Material.DIAMOND, hand.getType(), "处理器写入的主手物品不应被误扣");
        assertEquals(5, hand.getAmount(), "回调写回的钻石必须原样保留");
        assertEquals(2, countStone(player), "消耗的必须是放置时快照的 STONE（3 -> 2），不得消耗 DIAMOND");
        assertEquals(Material.STONE, CartData.getItem(cart).getType(), "矿车上应保存放置的 STONE");
    }

    private static int countStone(PlayerMock player) {
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == Material.STONE) {
                total += it.getAmount();
            }
        }
        return total;
    }
}
