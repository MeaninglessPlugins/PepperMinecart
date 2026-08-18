package com.pepperminecart.anvil;

import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.delivery.ItemDelivery;
import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.engine.CartEngine;
import com.pepperminecart.storage.CartData;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
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
        if (!(config.anvilDamageChance() > 0.0)) {
            return false; // 防御 NaN/0：概率不是正数时永不损坏
        }
        // 提前缓存 world/location：报废分支会在清理 PDC/显示后再播放音效，
        // 实体在边界场景（卸载/第三方移除）可能已拿不到位置，音效必须用缓存值
        World world = cart.getWorld();
        Location loc = cart.getLocation();
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
            // 报废：方块从矿车消失。掉落以存储物品为准（保留自定义名等 ItemMeta），
            // 不能读显示方块——显示可能被第三方清除/未恢复，读显示会在配置要求掉落时静默少掉
            ItemStack broken = item.clone();
            if (config.anvilDropOnBreak()) {
                // 先确保返还物品真实交付（进背包或掉落成功），再清除矿车数据；
                // 交付失败则取消本次报废，物品仍可从矿车找回
                if (ItemDelivery.giveOrDrop(player, broken, world, loc) != ItemDelivery.Result.DELIVERED) {
                    Bukkit.getLogger().warning("[PepperMinecart] 铁砧报废掉落被取消或位置不可用，已取消报废: "
                            + cart.getUniqueId());
                    return false;
                }
            }
            new CartBlockDisplay(cart).clear();
            CartData.clear(cart);
            engine.removeCart(cart);
            if (world != null && loc != null) {
                world.playSound(loc, Sound.BLOCK_ANVIL_BREAK, 1f, 1f);
            }
            // 关闭所有仍打开该矿车铁砧界面的玩家，避免报废后继续使用虚拟铁砧
            engine.closeCartSessionsLater(cart);
            return true;
        }

        // 完整 → 损坏：物品类型与显示方块同步切换
        ItemStack updated = item.withType(next);
        CartData.setItem(cart, updated);
        new CartBlockDisplay(cart).set(next);
        if (world != null && loc != null) {
            world.playSound(loc, Sound.BLOCK_ANVIL_USE, 1f, 1f);
        }
        return false;
    }

    private static boolean isAnvilVariant(Material material) {
        return material == Material.ANVIL
                || material == Material.CHIPPED_ANVIL
                || material == Material.DAMAGED_ANVIL;
    }
}
