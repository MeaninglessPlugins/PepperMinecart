package org.eu.pcraft.pepperminecart;

import de.tr7zw.changeme.nbtapi.NBT;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.eu.pcraft.pepperminecart.holder.MinecartChestHolder;
import org.eu.pcraft.pepperminecart.util.MinecartUtil;
import org.jetbrains.annotations.NotNull;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;

import java.util.function.Consumer;


public class PepperListener implements Listener {

    private void closeHolder(MinecartChestHolder holder){
        Minecart minecart = holder.getMinecart();
        ItemStack boxItem = MinecartUtil.getItemOnMinecart(minecart);
        BlockStateMeta meta = (BlockStateMeta) boxItem.getItemMeta();
        ShulkerBox shulkerBox = (ShulkerBox) meta.getBlockState();
        shulkerBox.getInventory().setContents(holder.getInventory().getContents());
        meta.setBlockState(shulkerBox);
        boxItem.setItemMeta(meta);
        NBT.modifyPersistentData(minecart, nbt -> {
            nbt.setItemStack("BlockInfo", boxItem);
        });
        PepperMinecart.getInstance().holderMap.remove(minecart);
    }
    
    private boolean customWorkstationInteract(Player player, @NotNull ItemStack stack) {
        Material type = stack.getType();
        Consumer<Player> consumer = MinecartUtil.getBlockIntercations().get(type);
        if (consumer != null) {
            consumer.accept(player);
            return true;
        }
        return false;
    }

    private boolean customShulkerboxInteract(Player player, Minecart minecart, @NotNull ItemStack stack) {
        if (stack.getType().getKey().getKey().endsWith("shulker_box")) {
            MinecartChestHolder holder = PepperMinecart.getInstance().holderMap.get(minecart);
            if (holder == null) {
                BlockStateMeta meta = (BlockStateMeta) stack.getItemMeta();
                ShulkerBox shulkerBox = (ShulkerBox) meta.getBlockState();
                
                holder = new MinecartChestHolder(minecart);
                Inventory inv = Bukkit.createInventory(holder, 27);
                inv.setContents(shulkerBox.getInventory().getContents());
                holder.setInventory(inv);
                PepperMinecart.getInstance().holderMap.put(minecart, holder);
            }
            player.openInventory(holder.getInventory());
            return true;
        }
        return false;
    }

    private boolean doCustomInteract(Player player, Minecart minecart) {
        //自定义交互
        ItemStack item = MinecartUtil.getItemOnMinecart(minecart);
        if (item == null) return false;
        if (customWorkstationInteract(player, item)) return true;
        if (customShulkerboxInteract(player, minecart, item)) return true;
        return false;
    }

    @EventHandler
    void onDestroy(VehicleDestroyEvent event) {
        if (!(event.getVehicle() instanceof Minecart minecart)) {
            return;
        }
        PepperMinecartHolder holder = PepperMinecart.getInstance().holderMap.get(minecart);
        if (holder != null){
            for(Player p : holder.getInventory().getViewers()){
                p.closeInventory();
            }
            //closeHolder(holder);
        }
        ItemStack item = MinecartUtil.getItemOnMinecart(minecart);
        if (item != null)
            minecart.getWorld().dropItem(minecart.getLocation(), item);
    }

    @EventHandler
    void onCloseInv(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MinecartChestHolder holder) {
            if (event.getInventory().getViewers().isEmpty()) {
                //destroy holder
                closeHolder(holder);
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getRightClicked().getType() != EntityType.MINECART) {
            return;
        }

        Minecart minecart = (Minecart) event.getRightClicked();
        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        ItemStack itemOnMinecart = MinecartUtil.getItemOnMinecart(minecart);

        // 站立交互处理
        if (!player.isSneaking()) {
            //自定义交互
            if (PepperMinecart.getInstance().getConfigTemplate().isEnableCustomInteract()) {//允许交互
                boolean isSuccess = doCustomInteract(player, minecart);
                if (isSuccess) event.setCancelled(true);
            }
            if(itemOnMinecart != null) event.setCancelled(true);
            return;
        }

        // 下蹲交互处理
        if (itemOnMinecart != null) {
            if (itemInHand.getType().isAir()) {
                player.getInventory().setItemInMainHand(itemOnMinecart);
                MinecartUtil.removeBlock(minecart);
            } else if (itemInHand.asOne().equals(itemOnMinecart) && itemInHand.getAmount() < itemInHand.getMaxStackSize()) {
                itemInHand.add();
                MinecartUtil.removeBlock(minecart);
            }
        } else if (itemInHand.getType().isBlock()) {
            ItemStack copyItem = itemInHand.asOne().clone();
            itemInHand.subtract(1);
            MinecartUtil.placeBlock(minecart, copyItem);
        }
    }
}
