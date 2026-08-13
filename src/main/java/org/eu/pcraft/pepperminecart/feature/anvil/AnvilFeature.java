package org.eu.pcraft.pepperminecart.feature.anvil;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
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
        player.openInventory(MenuType.ANVIL.create(player));
        return true;
    }

    @Override
    public boolean onDamageUse(Player player, Minecart minecart, MainConfigModule config, FeatureContext ctx) {
        // 概率钳制到 [0,1]，避免配置越界造成恒损坏/永不损坏
        double chance = Math.max(0.0, Math.min(1.0, config.getAnvilDamageChance()));
        if (Math.random() >= chance) return false;

        ItemStack item = ctx.getBlockItem(minecart);
        if (item == null) return false;

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
            // 不在此关闭界面：报废后界面是普通 GUI，由调用方延迟一 tick 关闭，
            // 避免在点击事件处理中直接 closeInventory
            if (config.isSoundFeedback()) playAnvilBreakSound(player, minecart.getLocation());
            return true;
        } else {
            item = item.withType(next);
            ctx.setBlockItem(minecart, item);
            minecart.setDisplayBlockData(next.createBlockData());
            if (config.isSoundFeedback()) playAnvilUseSound(player, minecart.getLocation());
            return false;
        }
    }

    private void playAnvilUseSound(Player player, Location location) {
        player.getWorld().playSound(location, Sound.BLOCK_ANVIL_USE, 0.8f, 1.0f);
    }

    private void playAnvilBreakSound(Player player, Location location) {
        player.getWorld().playSound(location, Sound.BLOCK_ANVIL_DESTROY, 0.8f, 1.0f);
    }
}
