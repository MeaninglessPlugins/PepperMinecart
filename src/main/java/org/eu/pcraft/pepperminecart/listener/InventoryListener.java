package org.eu.pcraft.pepperminecart.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.eu.pcraft.pepperminecart.PepperMinecart;
import org.eu.pcraft.pepperminecart.service.MinecartService;

/**
 * 库存事件：铁砧结果槽取走、矿车容器界面关闭
 */
public class InventoryListener implements Listener {

    private final PepperMinecart plugin;
    private final MinecartService service;

    public InventoryListener(PepperMinecart plugin, MinecartService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler
    public void onCloseInv(InventoryCloseEvent event) {
        // 关闭界面时清理铁砧会话
        service.clearAnvilSession(event.getPlayer().getUniqueId());

        // 当最后一个人关闭矿车容器界面时，保存并清理会话
        if (event.getInventory().getViewers().size() <= 1) {
            service.handleContainerClosed(event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // 仅处理铁砧结果槽（raw slot 2）取出
        if (event.getView().getTopInventory().getType() != InventoryType.ANVIL) return;
        if (event.getRawSlot() != 2) return;
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) return;

        service.handleAnvilUse(player, plugin.getMainConfig());
    }
}
