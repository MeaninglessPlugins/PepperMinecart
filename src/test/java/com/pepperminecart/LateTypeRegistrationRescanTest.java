package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.PepperMinecartAPI;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.handler.GenericBlockHandler;
import com.pepperminecart.storage.CartData;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** 扩展插件注册类型后，已加载区块中的存量矿车必须被重解析，而不是停留在通用兜底 handler。 */
class LateTypeRegistrationRescanTest {

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
    void registerCartTypeRescansAlreadyLoadedCarts() throws Exception {
        // 模拟重启：插件启用前已加载区块里的矿车 PDC 写着扩展类型 id
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        NamespacedKey lateId = NamespacedKey.fromString("test:late-type");
        CartData.setCartType(cart, lateId.toString());
        CartData.setItem(cart, new ItemStack(Material.STONE));

        PepperMinecartPlugin plugin = MockBukkit.load(PepperMinecartPlugin.class);
        // 启用时扩展类型尚未注册：存量扫描只能解析成通用兜底
        CartContextImpl before = plugin.engine().contextOrNull(cart);
        assertSame(GenericBlockHandler.INSTANCE, before.getType());

        CartTypeHandler lateHandler = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return lateId;
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of();
            }
        };
        PepperMinecartAPI api = server.getServicesManager().load(PepperMinecartAPI.class);
        api.registerCartType(lateHandler);

        // 不调用 contextOrNull（它会自行解析），直接检查引擎内部登记表是否已被重扫刷新
        Field contextsField = plugin.engine().getClass().getDeclaredField("contexts");
        contextsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, CartContextImpl> contexts = (Map<UUID, CartContextImpl>) contextsField.get(plugin.engine());
        assertSame(lateHandler, contexts.get(cart.getUniqueId()).getType(),
                "扩展类型注册后，已加载矿车的 context 必须自动重解析为新 handler");
        assertNotSame(before, contexts.get(cart.getUniqueId()), "重解析应重建 context 而不是沿用旧 handler");
    }
}
