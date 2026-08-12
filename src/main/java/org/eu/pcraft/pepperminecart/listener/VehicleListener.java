package org.eu.pcraft.pepperminecart.listener;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Minecart minecart) {
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
    public void onVehicleEntityCollision(VehicleEntityCollisionEvent event) {
        // 只拦截非玩家活体生物（怪物等）进入有方块的矿车；玩家推动矿车不受影响
        if (event.getVehicle() instanceof Minecart minecart
                && service.getBlockItem(minecart) != null
                && event.getEntity() instanceof LivingEntity
                && !(event.getEntity() instanceof Player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getVehicle() instanceof Minecart minecart && service.getBlockItem(minecart) != null) {
            event.setCancelled(true);
        }
    }
}
