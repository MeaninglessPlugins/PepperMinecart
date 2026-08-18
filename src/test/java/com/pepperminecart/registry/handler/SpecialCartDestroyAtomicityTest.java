package com.pepperminecart.registry.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.engine.CartEngine;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.CartTypeRegistry;
import com.pepperminecart.storage.CartData;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * 特殊矿车销毁时的内容物掉落必须原子：任一内容物掉落被取消时，此前已生成的地面掉落必须回滚，
 * 否则“地面一份 + 库存一份”会在后续原版掉落路径中变成复制源。
 */
class SpecialCartDestroyAtomicityTest {

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
    void onCartDestroyed_partialDropFailure_rollsBackAlreadyDroppedContents() {
        PluginMock plugin = MockBukkit.createMockPlugin("special-destroy");
        CartEngine engine = new CartEngine(plugin, new CartTypeRegistry(null), new PluginConfig(null));
        SpecialCartHandler handler = new SpecialCartHandler();

        StorageMinecart real = world.spawn(new Location(world, 0, 64, 0), StorageMinecart.class);
        real.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        real.getInventory().setItem(1, new ItemStack(Material.EMERALD));
        CartData.setItem(real, new ItemStack(Material.CHEST));
        CartData.setOriginalMaterial(real, Material.CHEST);

        // 前两次内容物掉落：第一次真实成功，第二次返回 null（等价于 ItemSpawnEvent 被第三方取消）。
        // 后续方块物品掉落也返回 null，避免干扰断言。
        AtomicInteger drops = new AtomicInteger();
        List<Item> spawnedItems = new ArrayList<>();
        World cancelWorld = (World) Proxy.newProxyInstance(
                SpecialCartDestroyAtomicityTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dropItemNaturally")) {
                        if (drops.incrementAndGet() == 1) {
                            Item spawned = world.dropItemNaturally((Location) args[0], (ItemStack) args[1]);
                            if (spawned != null) {
                                spawnedItems.add(spawned);
                            }
                            return spawned;
                        }
                        return null;
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
        Location cancelLoc = new Location(cancelWorld, 0, 64, 0);

        // 矿车代理同时实现 Minecart 与 InventoryHolder：除了 world/location 指向可控掉落世界外全部委托真实实体
        Minecart cart = (Minecart) Proxy.newProxyInstance(
                SpecialCartDestroyAtomicityTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class, InventoryHolder.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld":
                            return cancelWorld;
                        case "getLocation":
                            return cancelLoc;
                        default:
                            return method.invoke(real, args);
                    }
                });

        CartContextImpl ctx = new CartContextImpl(engine, new PluginConfig(null), cart, handler);
        handler.onCartDestroyed(ctx);

        assertNotNull(real.getInventory().getItem(0), "部分掉落失败时库存不得清空");
        assertNotNull(real.getInventory().getItem(1), "部分掉落失败时库存不得清空");
        long diamondDrops = spawnedItems.stream()
                .filter(Item::isValid)
                .filter(item -> item.getItemStack().getType() == Material.DIAMOND)
                .count();
        long emeraldDrops = spawnedItems.stream()
                .filter(Item::isValid)
                .filter(item -> item.getItemStack().getType() == Material.EMERALD)
                .count();
        assertEquals(0, diamondDrops, "第二个掉落被取消时，第一个已生成的地面掉落必须回滚，避免与保留的库存形成复制");
        assertEquals(0, emeraldDrops, "被取消的掉落不得生成地面实体");
        assertFalse(ctx.dropOnDestroy(), "销毁处理器应关闭引擎默认掉落，避免方块物品被重复掉落");
    }
}
