package org.eu.pcraft.pepperminecart;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.eu.pcraft.pepperminecart.holder.MinecartChestHolder;
import org.eu.pcraft.pepperminecart.manager.MinecartManager;
import org.eu.pcraft.pepperminecart.manager.MinecartRegistry;

public class PepperListener implements Listener {

    private final MinecartManager manager;
    private final PepperMinecart plugin;

    public PepperListener(PepperMinecart plugin) {
        this.plugin = plugin;
        this.manager = new MinecartManager(plugin);
    }

    @EventHandler
    public void onDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Minecart minecart) {
            manager.handleCartDestruction(minecart);
        }
    }

    @EventHandler
    public void onCloseInv(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MinecartChestHolder holder) {
            // 当最后一个人关闭界面时，保存并销毁 holder
            if (event.getInventory().getViewers().size() <= 1) {
                manager.saveAndCloseShulkerBox(holder);
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        // 非主手 或 非矿车
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Minecart)) {
            return;
        }

        Minecart minecart = (Minecart) event.getRightClicked();
        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        ItemStack itemOnMinecart = manager.getItemOnMinecart(minecart);

        if (player.isSneaking()) {
            handleSneakInteract(event, player, minecart, itemInHand, itemOnMinecart);
        } else {
            handleStandInteract(event, player, minecart, itemOnMinecart);
        }
    }


    private void handleStandInteract(PlayerInteractEntityEvent event, Player player, Minecart minecart, ItemStack itemOnMinecart) {
        // 如果不允许自定义交互，直接返回
        if (!plugin.getMainConfig().isEnableCustomInteract()) return;
        // 非普通矿车，由 bugjump 管理
        if (!(minecart instanceof RideableMinecart)) return;
        if (itemOnMinecart != null) {
            // 尝试打开工作台或潜影盒
            boolean handled = manager.handleWorkstationInteract(player, itemOnMinecart)
                    || manager.handleShulkerBoxInteract(player, minecart, itemOnMinecart);

            // 禁止玩家乘坐这类矿车
            if (handled || itemOnMinecart.getType() != Material.AIR) {
                event.setCancelled(true);
            }
        }
    }

    private void handleSneakInteract(PlayerInteractEntityEvent event, Player player, Minecart minecart, ItemStack itemInHand, ItemStack itemOnMinecart) {
        // 情况0：对原版矿车的取下操作
        boolean actionSuccess = false;
        if (!(minecart instanceof RideableMinecart)) {
            ItemStack blockItem = manager.getBlockOnVanillaMinecart(minecart);
            if (blockItem == null) return;
            // 0.1 手空 -> 取下方块
            if (itemInHand.getType().isAir()) {
                player.getInventory().setItemInMainHand(blockItem);
                actionSuccess = true;
            }
            // 0.2 手中物品相同且未堆叠满 -> 回收方块（堆叠）
            else if (itemInHand.isSimilar(blockItem) && itemInHand.getAmount() < itemInHand.getMaxStackSize()) {
                itemInHand.setAmount(itemInHand.getAmount() + 1);
                actionSuccess = true;
            }
            if(actionSuccess) {//变掉落物
                manager.replaceMinecart(minecart, EntityType.MINECART, null);
                event.setCancelled(true);
            }
            return;
        }
        // 情况1：车上有方块
        if (itemOnMinecart != null) {
            // 手空 -> 取下方块
            if (itemInHand.getType().isAir()) {
                player.getInventory().setItemInMainHand(itemOnMinecart);
                manager.removeBlock(minecart);
                actionSuccess = true;
            }
            // 手中物品相同且未堆叠满 -> 回收方块
            else if (itemInHand.isSimilar(itemOnMinecart) && itemInHand.getAmount() < itemInHand.getMaxStackSize()) {
                itemInHand.setAmount(itemInHand.getAmount() + 1);
                manager.removeBlock(minecart);
                actionSuccess = true;
            }
        }
        // 情况2：车上没方块，手上有方块 -> 放置方块
        else if (itemInHand.getType().isBlock()) {
            ItemStack copyItem = itemInHand.asOne().clone();
            itemInHand.subtract(1);
            manager.placeBlock(minecart, copyItem);
            actionSuccess = true;
        }
        if(actionSuccess) {
            event.setCancelled(true);
        }
        return;
    }
}