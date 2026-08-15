package com.pepperminecart.registry.handler;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.container.CartSessionManager;
import com.pepperminecart.storage.CartData;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
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

/** 发射器矿车：9 格容器 + 压过充能激活铁轨时随机弹出 1 个物品。 */
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
            // 冷却：世界年龄驱动（单调递增、跨区块重载持久、不受 /time set 影响）
            if (ctx.hasCooldown(COOLDOWN)) {
                return;
            }
            ctx.setCooldown(COOLDOWN, config.dispenserCooldownTicks());
            shoot(cart);
            return;
        }
    }

    private void shoot(Minecart cart) {
        // 若界面打开中，直接使用会话持有的实时容器引用（同一引用）编辑：
        // 此前对"重新反序列化快照"的扣减，会在玩家关闭界面时被陈旧快照覆盖回写，导致发射物品复制
        Container live = sessions.liveContainer(cart);
        ItemStack blockItem = null;
        BlockStateMeta bsm = null;
        Dispenser dispenser;
        if (live instanceof Dispenser d) {
            dispenser = d;
        } else {
            blockItem = CartData.getItem(cart);
            if (blockItem == null || !(blockItem.getItemMeta() instanceof BlockStateMeta m)) {
                return;
            }
            if (!(m.getBlockState() instanceof Dispenser d)) {
                return;
            }
            bsm = m;
            dispenser = d;
        }
        Inventory inv = dispenser.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir()) {
                slots.add(i);
            }
        }
        if (slots.isEmpty()) {
            return;
        }
        int slot = slots.get(ThreadLocalRandom.current().nextInt(slots.size()));
        ItemStack stack = inv.getItem(slot);
        ItemStack single = stack.clone();
        single.setAmount(1);
        if (stack.getAmount() <= 1) {
            inv.setItem(slot, null);
        } else {
            // Paper 1.20.5+ 的 getItem 返回副本，setAmount 不会持久化到容器，
            // 必须把扣减后的副本写回，否则堆叠数量不减、每次发射都多出一个物品
            stack.setAmount(stack.getAmount() - 1);
            inv.setItem(slot, stack);
        }
        if (live != null) {
            // 实时容器编辑即时生效，回写 PDC 供销毁/取下路径读取
            sessions.flushContainer(cart);
        } else {
            bsm.setBlockState(dispenser);
            blockItem.setItemMeta(bsm);
            CartData.setItem(cart, blockItem);
        }

        Location loc = cart.getLocation().clone().add(0, 0.4, 0);
        double off = config.dispenserEjectOffset();
        double dx = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * off;
        double dy = ThreadLocalRandom.current().nextDouble() * off * 0.5;
        double dz = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * off;
        Item drop = cart.getWorld().dropItem(loc.add(dx, dy, dz), single);
        Vector v = new Vector(ThreadLocalRandom.current().nextDouble() - 0.5,
                ThreadLocalRandom.current().nextDouble() * 0.4 + 0.2,
                ThreadLocalRandom.current().nextDouble() - 0.5).normalize();
        drop.setVelocity(v.multiply(config.dispenserEjectSpeed()));
        cart.getWorld().playSound(cart.getLocation(), Sound.BLOCK_DISPENSER_DISPENSE, 1f, 1f);
    }
}
