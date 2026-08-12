package org.eu.pcraft.pepperminecart.listener;

import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.eu.pcraft.pepperminecart.PepperMinecart;
import org.eu.pcraft.pepperminecart.service.MinecartService;

/**
 * 车辆事件：矿车销毁清理、移动激活（投掷器压轨）、禁止生物乘坐有方块矿车
 */
public class VehicleListener implements Listener {

    private final PepperMinecart plugin;
    private final MinecartService service;

    public VehicleListener(PepperMinecart plugin, MinecartService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /**
     * 矿车从世界中移除时统一清理：被破坏、/kill、remove() 等所有移除路径都走这里。
     * 区块卸载（{@link EntityRemoveEvent.Cause#UNLOAD}）不在此处理——实体仍会保存并重载，
     * 由 {@link WorldListener} 回写会话，若在此当销毁处理会重复掉落。
     */
    @EventHandler
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.getCause() == EntityRemoveEvent.Cause.UNLOAD) return;
        if (event.getEntity() instanceof Minecart minecart) {
            service.handleCartDestruction(minecart);
        }
    }

    @EventHandler
    public void onVehicleMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Minecart minecart) {
            service.handleDropperCartActivation(minecart, plugin.getMainConfig());
        }
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getVehicle() instanceof Minecart minecart && service.hasBlockOnCart(minecart)) {
            event.setCancelled(true);
        }
    }
}
