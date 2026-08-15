package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.storage.CartData;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 核心交互路径集成测试（MockBukkit 真实服务器环境）：
 * 放置 → 显示/持久化/消耗物品；取下三策略；销毁掉落不复制；容器会话回写；铁砧耐久状态机。
 */
class MinecartFlowTest {

    private ServerMock server;
    private PepperMinecartPlugin plugin;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PepperMinecartPlugin.class);
        world = server.addSimpleWorld("test");
        // 测试配置：零冷却、关音效（避免时序抖动与无关断言）
        plugin.getConfig().set("interaction-cooldown-ms", 0);
        plugin.getConfig().set("sounds.place", false);
        plugin.getConfig().set("sounds.take-off", false);
        applyConfig();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 把 getConfig() 中的值灌入 PluginConfig（等价 /pm reload，但跳过磁盘往返）。 */
    private void applyConfig() {
        plugin.pluginConfig().apply(plugin.getConfig());
    }

    private void setConfig(String key, Object value) {
        plugin.getConfig().set(key, value);
        applyConfig();
    }

    private RideableMinecart spawnCart() {
        return world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
    }

    private PlayerInteractEntityEvent interact(PlayerMock player, Minecart cart) {
        PlayerInteractEntityEvent e = new PlayerInteractEntityEvent(player, cart, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        return e;
    }

    private RideableMinecart placeBlock(PlayerMock player, Material material, int amount) {
        RideableMinecart cart = spawnCart();
        player.setSneaking(true);
        player.getInventory().setItemInMainHand(new ItemStack(material, amount));
        PlayerInteractEntityEvent e = interact(player, cart);
        assertTrue(e.isCancelled(), "潜行 + 手持方块应触发放置");
        return cart;
    }

    private List<Item> droppedItems(Material material) {
        return world.getEntities().stream()
                .filter(en -> en instanceof Item i && i.getItemStack().getType() == material)
                .map(en -> (Item) en)
                .toList();
    }

    /** 全背包统计某材质数量（MockBukkit 中 AIR 占位槽使返还物品不一定落在主手，按全背包断言）。 */
    private int countInInventory(PlayerMock player, Material material) {
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == material) {
                total += it.getAmount();
            }
        }
        return total;
    }

    private ItemStack findInInventory(PlayerMock player, Material material) {
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == material) {
                return it;
            }
        }
        return null;
    }

    // ---- 放置 ----

    @Test
    void place_consumesOneItemAndShowsDisplay() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.GRINDSTONE, 3);

        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(CartData.isManaged(cart));
        assertTrue(new CartBlockDisplay(cart).has());
        assertEquals(Material.GRINDSTONE, CartData.getItem(cart).getType());
    }

    @Test
    void place_rejectsDisallowedBlock() {
        setConfig("disabled-blocks", List.of("DIAMOND_BLOCK"));
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = spawnCart();
        player.setSneaking(true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_BLOCK, 2));

        PlayerInteractEntityEvent e = interact(player, cart);
        assertFalse(e.isCancelled(), "黑名单方块不应触发放置");
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertFalse(CartData.isManaged(cart));
    }

    // ---- 取下：三种策略 ----

    @Test
    void takeOff_inventoryMode_returnsItemToPlayer() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.GRINDSTONE, 3);

        PlayerInteractEntityEvent e = interact(player, cart); // 仍潜行 → 取下
        assertTrue(e.isCancelled());
        assertEquals(3, countInInventory(player, Material.GRINDSTONE), "取下后方块应回到背包");
        assertFalse(new CartBlockDisplay(cart).has());
        assertFalse(CartData.isManaged(cart));
        assertTrue(droppedItems(Material.GRINDSTONE).isEmpty());
    }

    @Test
    void takeOff_dropMode_dropsOnGround() {
        setConfig("take-off-mode", "DROP");
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.GRINDSTONE, 3);

        interact(player, cart);
        assertEquals(2, countInInventory(player, Material.GRINDSTONE), "DROP 模式不进背包");
        assertEquals(1, droppedItems(Material.GRINDSTONE).size(), "方块应掉落在地面");
        assertFalse(CartData.isManaged(cart));
    }

    @Test
    void takeOff_disabledMode_keepsBlock() {
        setConfig("take-off-mode", "DISABLED");
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.GRINDSTONE, 3);

        PlayerInteractEntityEvent e = interact(player, cart);
        assertTrue(e.isCancelled());
        assertEquals(2, countInInventory(player, Material.GRINDSTONE), "禁止取下不返还物品");
        assertTrue(CartData.isManaged(cart), "禁止取下方块仍在矿车上");
        assertTrue(new CartBlockDisplay(cart).has());
    }

    // ---- 销毁：掉落一次、不复制 ----

    @Test
    void destroy_dropsBlockExactlyOnce() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.GRINDSTONE, 1);

        EntityRemoveEvent remove = new EntityRemoveEvent(cart, EntityRemoveEvent.Cause.DISCARD);
        server.getPluginManager().callEvent(remove);

        assertEquals(1, droppedItems(Material.GRINDSTONE).size(), "销毁应恰好掉落 1 个方块");
    }

    // ---- 容器会话：编辑回写 PDC ----

    /**
     * 容器编辑回写链路：打开实时容器 → 编辑 → 关闭 → PDC 物品被重写。
     * 注意：MockBukkit 的 BlockStateMetaMock 字节序列化不含库存内容（反序列化告警并丢内容），
     * 内容级断言由真实服务器冒烟覆盖；此处断言"回写发生且无异常、物品仍为带容器 meta 的木桶"。
     */
    @Test
    void container_editsArePersistedToPdcOnClose() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.BARREL, 1);

        player.setSneaking(false);
        PlayerInteractEntityEvent e = interact(player, cart);
        assertTrue(e.isCancelled(), "非潜行右键容器矿车应打开界面");

        player.getOpenInventory().getTopInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        player.closeInventory();

        ItemStack stored = CartData.getItem(cart);
        assertNotNull(stored, "关闭后 PDC 物品应存在（回写发生）");
        assertEquals(Material.BARREL, stored.getType());
        assertTrue(stored.getItemMeta() instanceof BlockStateMeta, "回写后物品应带容器 meta");
    }

    /**
     * 取下容器矿车：PICKUP 策略下物品回到背包且仍带容器 meta。
     * 内容物随物品 NBT 保留的断言受 MockBukkit BlockStateMetaMock 序列化限制，由真实服务器冒烟覆盖。
     */
    @Test
    void takeOff_containerKeepsContentsInItem() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.BARREL, 1);

        player.setSneaking(false);
        interact(player, cart);
        player.getOpenInventory().getTopInventory().setItem(3, new ItemStack(Material.EMERALD, 2));
        player.closeInventory();

        player.setSneaking(true);
        interact(player, cart); // 取下：PICKUP 策略，内容随物品带走

        ItemStack taken = findInInventory(player, Material.BARREL);
        assertNotNull(taken, "取下后木桶物品应回到背包");
        assertTrue(taken.getItemMeta() instanceof BlockStateMeta, "取下的木桶应带容器 meta");
        assertTrue(droppedItems(Material.BARREL).isEmpty(), "PICKUP 策略不应落地");
    }

    // ---- 铁砧耐久状态机 ----

    @Test
    void anvilDamage_progressesAnvilChippedDamagedBroken() {
        setConfig("anvil-damage.chance-per-use", 1.0);
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.ANVIL, 1);

        assertFalse(plugin.anvilTracker().onResultTaken(player, cart));
        assertEquals(Material.CHIPPED_ANVIL, CartData.getItem(cart).getType());
        assertEquals(Material.CHIPPED_ANVIL, new CartBlockDisplay(cart).material());

        assertFalse(plugin.anvilTracker().onResultTaken(player, cart));
        assertEquals(Material.DAMAGED_ANVIL, CartData.getItem(cart).getType());

        assertTrue(plugin.anvilTracker().onResultTaken(player, cart), "第三次损坏应报废");
        assertFalse(CartData.isManaged(cart), "报废后矿车不再是受管矿车");
        assertFalse(new CartBlockDisplay(cart).has(), "报废后显示方块应清除");
    }

    @Test
    void anvilDamage_chanceZero_neverDamages() {
        setConfig("anvil-damage.chance-per-use", 0.0);
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.ANVIL, 1);

        for (int i = 0; i < 10; i++) {
            assertFalse(plugin.anvilTracker().onResultTaken(player, cart));
        }
        assertEquals(Material.ANVIL, CartData.getItem(cart).getType());
    }

    // ---- 冷却（世界年龄语义） ----

    @Test
    void cooldown_worldAgeSemantics() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.GRINDSTONE, 1);

        var key = org.bukkit.NamespacedKey.fromString("pepperminecart:test-cooldown");
        var ctx = plugin.engine().contextOrNull(cart);
        assertNotNull(ctx);

        assertFalse(ctx.hasCooldown(key), "未设置冷却时应放行");
        ctx.setCooldown(key, 40);
        assertTrue(ctx.hasCooldown(key), "设置 40 tick 冷却后立即检查应拦截");

        world.setFullTime(world.getFullTime() + 41);
        assertFalse(ctx.hasCooldown(key), "世界年龄超过冷却时长后应放行");
    }

    // ---- 特殊矿车转换 ----

    @Test
    void place_chest_convertsToStorageMinecartAndBack() {
        PlayerMock player = server.addPlayer();
        placeBlock(player, Material.CHEST, 1);

        List<org.bukkit.entity.Entity> minecarts = world.getEntities().stream()
                .filter(en -> en instanceof Minecart).toList();
        assertEquals(1, minecarts.size());
        Minecart converted = (Minecart) minecarts.get(0);
        assertEquals(org.bukkit.entity.EntityType.CHEST_MINECART, converted.getType(),
                "箱子放置后应转换为箱子矿车");

        player.setSneaking(true);
        interact(player, converted);

        minecarts = world.getEntities().stream().filter(en -> en instanceof Minecart).toList();
        assertEquals(1, minecarts.size());
        assertEquals(org.bukkit.entity.EntityType.MINECART, ((Minecart) minecarts.get(0)).getType(),
                "取下后应还原为普通矿车");
        assertNotNull(findInInventory(player, Material.CHEST), "取下后箱子物品应回到背包");
    }
}
