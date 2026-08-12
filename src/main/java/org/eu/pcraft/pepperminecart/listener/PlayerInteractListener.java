package org.eu.pcraft.pepperminecart.listener;

import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.eu.pcraft.pepperminecart.PepperMinecart;
import org.eu.pcraft.pepperminecart.config.MainConfigModule;
import org.eu.pcraft.pepperminecart.service.MinecartService;

/**
 * 玩家交互：右键矿车（潜行放置/取下，非潜行打开界面）
 */
public class PlayerInteractListener implements Listener {

    private final PepperMinecart plugin;
    private final MinecartService service;

    public PlayerInteractListener(PepperMinecart plugin, MinecartService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        // 非主手 或 非矿车
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Minecart minecart)) {
            return;
        }

        Player player = event.getPlayer();
        MainConfigModule config = plugin.getMainConfig();

        // 权限检查
        if (!player.hasPermission("pepperminecart.use")) return;

        if (player.isSneaking()) {
            // 冷却检查
            if (!service.tryConsumeCooldown(player, config.getInteractionCooldownMillis())) return;
            event.setCancelled(service.handleSneakInteract(player, minecart, player.getInventory().getItemInMainHand(), config));
        } else if (config.isEnableCustomInteract()) {
            event.setCancelled(service.handleStandInteract(player, minecart));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.clearAnvilSession(event.getPlayer().getUniqueId());
        service.clearCooldown(event.getPlayer());
    }
}
