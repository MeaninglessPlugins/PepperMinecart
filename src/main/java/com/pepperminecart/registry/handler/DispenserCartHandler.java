package com.pepperminecart.registry.handler;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.container.CartSessionManager;
import com.pepperminecart.delivery.ItemDelivery;
import com.pepperminecart.storage.CartData;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Dispenser;
import org.bukkit.block.data.type.RedstoneRail;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.util.Vector;

/** 发射器矿车：9 格容器 + 压过充能激活铁轨时随机弹出 1 个物品。
 *  发射事务为“先扣减并持久化、再掉落”：掉落事件期间任何观察者与销毁兜底看到的都是已扣减状态，
 *  从根上杜绝“克隆已落地、库存未扣”的复制窗口；掉落被取消且实体仍有效时补偿恢复槽位。 */
public class DispenserCartHandler implements CartTypeHandler {

    public static final NamespacedKey ID = NamespacedKey.fromString("pepperminecart:dispenser");

    /** 发射冷却键（世界年龄语义，见 CartContext#setCooldown）。 */
    private static final NamespacedKey COOLDOWN = new NamespacedKey("pepperminecart", "dispenser-cooldown");

    private final PluginConfig config;
    private final CartSessionManager sessions;

    public DispenserCartHandler(PluginConfig config, CartSessionManager sessions) {
        this.config = config;
        this.sessions = sessions;
    }

    @Override
    public NamespacedKey getId() {
        return ID;
    }

    @Override
    public Set<Material> handledMaterials() {
        return Set.of(Material.DISPENSER);
    }

    @Override
    public boolean isContainerPickupControlled(Material material) {
        return true; // 发射器矿车是容器类：受 container-pickup-policy 管控
    }

    @Override
    public boolean onInteract(Player player, CartContext ctx) {
        ItemStack blockItem = CartData.getItem(ctx.getMinecart());
        if (blockItem == null) {
            return false;
        }
        Inventory inv = sessions.openContainer(player, ctx.getMinecart(), blockItem);
        return inv != null;
    }

    @Override
    public void onMove(CartContext ctx) {
        // 冷却前置：冷却期内直接跳过铁轨查询（语义不变：空发射不耗冷却、成功才耗冷却）。
        // 冷却为世界年龄驱动（单调递增、跨区块重载持久、不受 /time set 影响）。
        if (ctx.hasCooldown(COOLDOWN)) {
            return;
        }
        Minecart cart = ctx.getMinecart();
        Block base = cart.getLocation().getBlock();
        Block[] candidates = {base, base.getRelative(BlockFace.DOWN)};
        for (Block b : candidates) {
            if (b.getType() != Material.ACTIVATOR_RAIL) {
                continue;
            }
            if (!(b.getBlockData() instanceof RedstoneRail rail) || !rail.isPowered()) {
                continue;
            }
            // 只有真实弹出物品才消耗冷却：空发射不消耗冷却机会
            if (shoot(cart)) {
                ctx.setCooldown(COOLDOWN, config.dispenserCooldownTicks());
            }
            return;
        }
    }

    /**
     * 发射一件物品。事务顺序为“先扣减并持久化、再掉落”：
     * <ol>
     *   <li>扣减 + persist 成功后才允许掉落——persist 失败在此处抛出（由引擎 onMove 异常屏障记录），
     *       不产生任何地面物品；</li>
     *   <li>掉落被取消（ItemSpawnEvent）且实体仍有效时，补偿恢复槽位，不丢物；</li>
     *   <li>掉落期间矿车被第三方移除：销毁兜底按“已扣减”状态只掉剩余库存，地面克隆即本次发射物，
     *       不复制；此处不再触碰失效实体的库存/PDC/位置；</li>
     *   <li>唯一残留：掉落被取消且矿车同时被移除的双重失败窗口会损失 1 件——
     *       严格原子在该事件窗口内不可达，不做失效实体上的补偿尝试。</li>
     * </ol>
     *
     * @return true 表示真的弹出了一个物品
     */
    boolean shoot(Minecart cart) {
        // 提前缓存世界/位置：ItemSpawnEvent 期间第三方插件可能移除矿车，掉落完成后
        // cart.getWorld()/getLocation() 可能为 null，后续所有步骤只用缓存值。
        World world = cart.getWorld();
        Location cartLoc = cart.getLocation();
        if (world == null || cartLoc == null) {
            return false; // 实体已失效，无法安全发射：不改库存
        }
        DispenserContainerAccess access = containerAccess(cart);
        if (access == null) {
            return false;
        }
        Inventory inv = access.inventory();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir()) {
                slots.add(i);
            }
        }
        if (slots.isEmpty()) {
            return false;
        }
        int slot = slots.get(ThreadLocalRandom.current().nextInt(slots.size()));
        ItemStack stack = inv.getItem(slot);
        ItemStack original = stack.clone();
        ItemStack single = stack.clone();
        single.setAmount(1);

        // 先扣后掉：扣减 + 持久化成功后才允许产生地面物品
        if (stack.getAmount() <= 1) {
            inv.setItem(slot, null);
        } else {
            // Paper 1.20.5+ 的 getItem 返回副本，setAmount 不会持久化到容器，
            // 必须把扣减后的副本写回，否则堆叠数量不减、每次发射都多出一个物品
            stack.setAmount(stack.getAmount() - 1);
            inv.setItem(slot, stack);
        }
        try {
            access.persist();
        } catch (RuntimeException ex) {
            // persist 失败：中止发射、不产生地面物品；live 路径的内存容器已被扣减，
            // 必须恢复，否则玩家在仍打开的界面里看到凭空少 1 件（PDC 路径快照未落盘，
            // 恢复仅为防御）。原异常抛给引擎 onMove 异常屏障记录。
            access.restoreInMemory(slot, original);
            throw ex;
        }

        Location loc = cartLoc.clone().add(0, 0.4, 0);
        double off = config.dispenserEjectOffset();
        double dx = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * off;
        double dy = ThreadLocalRandom.current().nextDouble() * off * 0.5;
        double dz = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * off;
        Item drop = ItemDelivery.dropItem(world, loc.add(dx, dy, dz), single);
        if (drop == null) {
            // 掉落被取消：只有实体仍有效时才能补偿（失效时库存/PDC 不可触碰）
            if (cart.isValid()) {
                access.restoreSlot(slot, original);
            }
            return false;
        }
        if (!cart.isValid()) {
            // 掉落期间矿车被移除：销毁兜底已按“已扣减”状态掉落剩余库存，
            // 地面克隆即本次发射物；不再触碰失效实体的库存/PDC
            return true;
        }
        Vector v = new Vector(ThreadLocalRandom.current().nextDouble() - 0.5,
                ThreadLocalRandom.current().nextDouble() * 0.4 + 0.2,
                ThreadLocalRandom.current().nextDouble() - 0.5);
        if (v.lengthSquared() == 0) {
            v = new Vector(0, 1, 0); // 防御零向量 normalize 产生 NaN
        }
        v.normalize();
        drop.setVelocity(v.multiply(config.dispenserEjectSpeed()));

        // 扣减已在掉落前持久化；音效仅在整个发射成功后播放。
        // 使用缓存的 world/location，避免掉落完成后实体失效导致 NPE。
        world.playSound(cartLoc, Sound.BLOCK_DISPENSER_DISPENSE, 1f, 1f);
        return true;
    }

    /**
     * 构造发射器的容器访问器：界面打开中复用会话的实时容器引用（同引用编辑），
     * 否则反序列化 PDC 快照——两条路径都经 {@link DispenserContainerAccess} 统一
     * “扣减 + 持久化 + 补偿”，避免双路径行为分叉。
     *
     * <p>live 路径的持久化不依赖会话存活（直接写 PDC）：若第三方插件在 ItemSpawnEvent
     * 中关闭了界面，会话 close 已回写扣减态，随后的补偿恢复仍需能落盘，否则丢 1 件。</p>
     */
    private DispenserContainerAccess containerAccess(Minecart cart) {
        Container live = sessions != null ? sessions.liveContainer(cart) : null;
        if (live instanceof Dispenser d) {
            return new DispenserContainerAccess(d, () -> persistDispenserState(cart, d));
        }
        ItemStack blockItem = CartData.getItem(cart);
        if (blockItem == null || !(blockItem.getItemMeta() instanceof BlockStateMeta m)) {
            return null;
        }
        if (!(m.getBlockState() instanceof Dispenser d)) {
            return null;
        }
        BlockStateMeta bsm = m;
        return new DispenserContainerAccess(d, () -> {
            bsm.setBlockState(d);
            blockItem.setItemMeta(bsm);
            CartData.setItem(cart, blockItem);
        });
    }

    /**
     * 把发射器容器状态直接写回矿车 PDC：从当前存储物品重建 meta、写入给定容器状态。
     * 不依赖会话存活，live 路径的扣减/补偿持久化在界面被第三方关闭后仍然生效。
     */
    static void persistDispenserState(Minecart cart, Dispenser dispenser) {
        ItemStack item = CartData.getItem(cart);
        if (item == null || !(item.getItemMeta() instanceof BlockStateMeta m)) {
            return;
        }
        m.setBlockState(dispenser);
        item.setItemMeta(m);
        CartData.setItem(cart, item);
    }
}
