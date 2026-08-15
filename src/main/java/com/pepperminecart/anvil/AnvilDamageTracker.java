package com.pepperminecart.anvil;

import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.engine.CartEngine;
import com.pepperminecart.storage.CartData;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 铁砧耐久损耗：玩家从铁砧视图取走修复结果时按概率推进状态机。
 * 状态由存储物品的类型承载（ANVIL → CHIPPED_ANVIL → DAMAGED_ANVIL → 报废消失），
 * 与显示方块同步更新。
 */
public class AnvilDamageTracker {

    private final PluginConfig config;
    private final CartEngine engine;

    public AnvilDamageTracker(PluginConfig config, CartEngine engine) {
        this.config = config;
        this.engine = engine;
    }

    /**
     * 玩家从铁砧视图取走结果格（rawSlot == 2）物品时调用（调用方已确认"真的取走"）。
     *
     * @return true 表示铁砧已报废（调用方应关闭玩家界面）
     */
    public boolean onResultTaken(Player player, Minecart cart) {
        if (!config.anvilDamageEnabled() || !cart.isValid() || !CartData.isManaged(cart)) {
            return false;
        }
        if (ThreadLocalRandom.current().nextDouble() >= config.anvilDamageChance()) {
            return false;
        }
        ItemStack item = CartData.getItem(cart);
        if (item == null || !isAnvilVariant(item.getType())) {
            return false; // 物品缺失或非铁砧类型（异常状态）不推进状态机，避免误报废
        }
        Material next = switch (item.getType()) {
            case ANVIL -> Material.CHIPPED_ANVIL;
            case CHIPPED_ANVIL -> Material.DAMAGED_ANVIL;
            default -> null; // DAMAGED_ANVIL 再次损坏 → 报废
        };

        if (next == null) {
            // 报废：方块从矿车消失
            Material displayed = new CartBlockDisplay(cart).material();
            new CartBlockDisplay(cart).clear();
            CartData.clear(cart);
            engine.removeCart(cart);
            if (config.anvilDropOnBreak() && displayed != Material.AIR) {
                cart.getWorld().dropItemNaturally(cart.getLocation(), new ItemStack(displayed));
            }
            cart.getWorld().playSound(cart.getLocation(), Sound.BLOCK_ANVIL_BREAK, 1f, 1f);
            return true;
        }

        // 完整 → 损坏：物品类型与显示方块同步切换
        ItemStack updated = item.withType(next);
        CartData.setItem(cart, updated);
        new CartBlockDisplay(cart).set(next);
        cart.getWorld().playSound(cart.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1f);
        return false;
    }

    private static boolean isAnvilVariant(Material material) {
        return material == Material.ANVIL
                || material == Material.CHIPPED_ANVIL
                || material == Material.DAMAGED_ANVIL;
    }
}
