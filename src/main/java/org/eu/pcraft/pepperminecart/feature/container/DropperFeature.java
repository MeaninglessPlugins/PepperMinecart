package org.eu.pcraft.pepperminecart.feature.container;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Dropper;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.util.Vector;
import org.eu.pcraft.pepperminecart.config.MainConfigModule;
import org.eu.pcraft.pepperminecart.feature.FeatureContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 投掷器矿车：容器交互 + 压过充能激活铁轨时随机投出物品（对标 TNT 矿车）
 */
public class DropperFeature extends ContainerFeature {

    private static final Random RANDOM = new Random();

    @Override
    public String getName() {
        return "DROPPER";
    }

    @Override
    public void onRailActivate(Minecart minecart, MainConfigModule config, FeatureContext ctx) {
        ItemStack dropperItem = ctx.getBlockItem(minecart);
        if (dropperItem == null) return;

        Block rail = findActivatorRail(minecart);
        if (rail == null) return;
        if (!(rail.getBlockData() instanceof Powerable powerable) || !powerable.isPowered()) return;

        if (!ctx.consumeDropperCooldown(minecart, config.getDropperCartCooldownTicks())) return;

        ejectDropperItem(minecart, dropperItem, config, ctx);
    }

    @Override
    public void onDestroy(Minecart minecart, FeatureContext ctx) {
        ctx.removeDropperCooldown(minecart);
    }

    /**
     * 找到矿车所在的充能激活铁轨方块（矿车位置或其下方一格）
     */
    private Block findActivatorRail(Minecart minecart) {
        Block at = minecart.getLocation().getBlock();
        if (at.getType() == Material.ACTIVATOR_RAIL) return at;
        Block down = at.getRelative(BlockFace.DOWN);
        if (down.getType() == Material.ACTIVATOR_RAIL) return down;
        return null;
    }

    private void ejectDropperItem(Minecart minecart, ItemStack dropperItem, MainConfigModule config, FeatureContext ctx) {
        if (!(dropperItem.getItemMeta() instanceof BlockStateMeta meta)) return;
        if (!(meta.getBlockState() instanceof Dropper dropper)) return;

        // 有玩家正打开界面时，用界面中的实时库存（同一 Container 引用）；否则从物品新解析
        Inventory source = ctx.getOpenInventory(minecart);
        if (source == null) {
            source = dropper.getInventory();
        }

        int slotIndex = pickRandomNonEmpty(source);
        if (slotIndex < 0) return;

        ItemStack toEject = source.getItem(slotIndex);
        ItemStack dropItem = toEject.asOne();
        if (toEject.getAmount() <= 1) {
            source.setItem(slotIndex, null);
        } else {
            toEject.setAmount(toEject.getAmount() - 1);
        }

        // 持久化：界面打开中回写实时库存，否则直接写回物品
        if (ctx.isContainerOpen(minecart)) {
            ctx.flushContainer(minecart);
        } else {
            meta.setBlockState(dropper);
            dropperItem.setItemMeta(meta);
            ctx.setBlockItem(minecart, dropperItem);
        }

        // 竖直向上为主（y ≈ 1），XZ 仅作小幅散布
        Item itemEntity = minecart.getWorld().dropItem(minecart.getLocation(), dropItem);
        itemEntity.setVelocity(ejectVelocity(config));
    }

    /**
     * 随机取一个非空格子，无可用格子返回 -1
     */
    private int pickRandomNonEmpty(Inventory inv) {
        List<Integer> nonEmpty = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack slot = inv.getItem(i);
            if (slot != null && !slot.getType().isAir()) nonEmpty.add(i);
        }
        if (nonEmpty.isEmpty()) return -1;
        return nonEmpty.get(RANDOM.nextInt(nonEmpty.size()));
    }

    private Vector ejectVelocity(MainConfigModule config) {
        double xz = config.getDropperCartXzOffset();
        double x = xz > 0 ? (RANDOM.nextDouble() * 2 - 1) * xz : 0;
        double z = xz > 0 ? (RANDOM.nextDouble() * 2 - 1) * xz : 0;
        // 竖直分量为主（≈1），明显向上弹起
        double y = 0.95 + RANDOM.nextDouble() * 0.1;
        return new Vector(x, y, z);
    }
}
