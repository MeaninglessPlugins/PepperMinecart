package org.eu.pcraft.pepperminecart.feature.anvil;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.eu.pcraft.pepperminecart.config.MainConfigModule;
import org.eu.pcraft.pepperminecart.feature.CartFeature;
import org.eu.pcraft.pepperminecart.feature.FeatureContext;

/**
 * 铁砧矿车：右键打开铁砧界面，取走修复结果时按配置概率造成损坏链（完整->损坏->报废）
 */
public class AnvilFeature implements CartFeature {

    @Override
    public String getName() {
        return "ANVIL";
    }

    @Override
    public boolean onStandInteract(Player player, Minecart minecart, ItemStack itemOnCart, FeatureContext ctx) {
        ctx.setAnvilSession(player.getUniqueId(), minecart);
        player.openAnvil(null, true);
        return true;
    }

    @Override
    public void onDamageUse(Player player, Minecart minecart, MainConfigModule config, FeatureContext ctx) {
        if (Math.random() >= config.getAnvilDamageChance()) return;

        ItemStack item = ctx.getBlockItem(minecart);
        if (item == null) return;

        Material next;
        switch (item.getType()) {
            case ANVIL -> next = Material.CHIPPED_ANVIL;
            case CHIPPED_ANVIL -> next = Material.DAMAGED_ANVIL;
            default -> next = null;
        }

        if (next == null) {
            // 损坏铁砧再次损坏 -> 报废消失
            ctx.removeBlockItem(minecart);
            minecart.setDisplayBlockData(Material.AIR.createBlockData());
            ctx.clearAnvilSession(player.getUniqueId());
            // 铁砧界面是普通 GUI，报废后仍可免费使用，直接关闭
            player.closeInventory();
            if (config.isSoundFeedback()) playAnvilBreakSound(player, minecart.getLocation());
        } else {
            item.setType(next);
            ctx.setBlockItem(minecart, item);
            minecart.setDisplayBlockData(next.createBlockData());
            if (config.isSoundFeedback()) playAnvilUseSound(player, minecart.getLocation());
        }
    }

    private void playAnvilUseSound(Player player, Location location) {
        player.getWorld().playSound(location, Sound.BLOCK_ANVIL_USE, 0.8f, 1.0f);
    }

    private void playAnvilBreakSound(Player player, Location location) {
        player.getWorld().playSound(location, Sound.BLOCK_ANVIL_DESTROY, 0.8f, 1.0f);
    }
}
