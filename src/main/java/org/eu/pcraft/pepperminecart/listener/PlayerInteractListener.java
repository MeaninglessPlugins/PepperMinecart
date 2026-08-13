package org.eu.pcraft.pepperminecart.listener;

import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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

    /**
     * HIGHEST + ignoreCancelled：领地/保护插件（通常 HIGH）取消交互后本监听器不再执行，
     * 避免在受保护区域仍然放置/取下方块。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        // 非主手 或 非矿车
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Minecart minecart)) {
            return;
        }

        Player player = event.getPlayer();
        MainConfigModule config = plugin.getMainConfig();

        // 权限检查
        if (!player.hasPermission("pepperminecart.use")) return;

        // 冷却检查（仅查询；记录在 service 内成功交互后）：命中也取消事件，保持与常规路径一致
        if (service.isCoolingDown(player, config.getInteractionCooldownMillis())) {
            event.setCancelled(true);
            return;
        }

        if (player.isSneaking()) {
            event.setCancelled(service.handleSneakInteract(player, minecart, player.getInventory().getItemInMainHand(), config));
        } else if (config.isEnableCustomInteract()) {
            event.setCancelled(service.handleStandInteract(player, minecart));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.clearAnvilSession(event.getPlayer().getUniqueId());
        service.clearWorkstationSession(event.getPlayer().getUniqueId());
        service.clearCooldown(event.getPlayer());
    }
}
