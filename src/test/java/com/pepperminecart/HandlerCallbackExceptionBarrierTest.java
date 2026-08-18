package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.PepperMinecartAPI;
import com.pepperminecart.api.VanillaCartSupport;
import com.pepperminecart.storage.CartData;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
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
 * 第三方 CartTypeHandler 回调异常屏障：扩展插件抛 RuntimeException 时，
 * 本插件必须 fail-closed、记录日志，且异常不得从交互/引擎判定路径抛出。
 */
class HandlerCallbackExceptionBarrierTest {

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
    void canPlaceExceptionIsContainedAndPlacementRejected() {
        AtomicBoolean called = new AtomicBoolean();
        CartTypeHandler throwing = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:throwing-canplace");
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of(Material.STONE);
            }

            @Override
            public boolean canPlace(Player player, org.bukkit.entity.Minecart cart) {
                called.set(true);
                throw new IllegalStateException("simulated canPlace failure");
            }
        };
        PepperMinecartAPI api = server.getServicesManager().load(PepperMinecartAPI.class);
        api.registerCartType(throwing);

        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE, 3));
        player.setSneaking(true);
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);

        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(player, cart, EquipmentSlot.HAND);
        assertDoesNotThrow(() -> server.getPluginManager().callEvent(event),
                "扩展 canPlace 异常必须被屏障吸收，不得逃出交互事件");

        assertTrue(called.get(), "前置：canPlace 确实被调用");
        assertFalse(CartData.isManaged(cart), "canPlace 异常必须按拒绝放置处理");
        assertFalse(event.isCancelled(), "未执行放置时不应错误取消原版事件");
        assertTrue(player.getInventory().getItemInMainHand().getAmount() == 3, "拒绝放置不得消耗物品");
    }

    @Test
    void containerPolicyExceptionIsContainedAndForbiddenIsEnforced() {
        CartTypeHandler throwing = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:throwing-policy");
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of(Material.BARREL);
            }

            @Override
            public boolean isContainerPickupControlled(Material material) {
                throw new IllegalStateException("simulated policy failure");
            }
        };
        PepperMinecartAPI api = server.getServicesManager().load(PepperMinecartAPI.class);
        api.registerCartType(throwing);
        plugin.getConfig().set("container-pickup-policy", "FORBIDDEN");
        plugin.pluginConfig().apply(plugin.getConfig());

        PlayerMock player = server.addPlayer();
        player.setSneaking(true);
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        CartData.setCartType(cart, "test:throwing-policy");
        CartData.setOriginalMaterial(cart, Material.BARREL);
        CartData.setItem(cart, new ItemStack(Material.BARREL));

        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(player, cart, EquipmentSlot.HAND);
        assertDoesNotThrow(() -> server.getPluginManager().callEvent(event),
                "扩展 isContainerPickupControlled 异常必须被屏障吸收");

        assertTrue(event.isCancelled(), "策略回调异常且 FORBIDDEN 时按 fail-closed 拒绝取下");
        assertTrue(CartData.isManaged(cart), "拒绝取下不得清除矿车数据");
        assertFalse(hasBarrel(player), "拒绝取下不得交付物品");
    }

    @Test
    void vanillaSupportExceptionIsContained() {
        class ThrowingVanillaSupport implements CartTypeHandler, VanillaCartSupport {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:throwing-vanilla");
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of();
            }

            @Override
            public Material materialFor(EntityType type) {
                throw new IllegalStateException("simulated vanilla support failure");
            }
        }

        PepperMinecartAPI api = server.getServicesManager().load(PepperMinecartAPI.class);
        api.registerCartType(new ThrowingVanillaSupport());

        // 用普通矿车：内置 SpecialCartHandler 对 MINECART 返回不支持并继续遍历，
        // 才能命中后注册的抛异常处理器；StorageMinecart 会被内置 handler 短路。
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);

        assertDoesNotThrow(() -> plugin.engine().isVanillaCart(cart),
                "扩展 supportsVanillaCart/materialFor 异常必须被引擎屏障吸收");
        assertFalse(plugin.engine().isVanillaCart(cart), "抛异常的处理器必须按不支持处理");
    }

    private boolean hasBarrel(PlayerMock player) {
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == Material.BARREL) {
                return true;
            }
        }
        return false;
    }
}
