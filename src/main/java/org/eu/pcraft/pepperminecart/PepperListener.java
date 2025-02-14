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

import java.util.function.Consumer;


public class PepperListener implements Listener {

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
        if (stack.getType().getKey().getKey().endsWith("_shulker_box")) {
            MinecartChestHolder holder = PepperMinecart.getInstance().holderMap.get(minecart);
            if (holder == null) {
                BlockStateMeta meta = (BlockStateMeta) stack.getItemMeta();
                ShulkerBox shulkerBox = (ShulkerBox) meta.getBlockState();
                holder = new MinecartChestHolder(shulkerBox.getInventory(), minecart);
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
        ItemStack item = MinecartUtil.getItemOnMinecart(minecart);
        if (item != null)
            minecart.getWorld().dropItem(minecart.getLocation(), item);
    }

    @EventHandler
    void onCloseInv(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MinecartChestHolder) {
            System.out.println(1);
            if (event.getInventory().getViewers().isEmpty()) {
                //destroy holder
                System.out.println(2);
                Minecart minecart = ((MinecartChestHolder) event.getInventory().getHolder()).getMinecart();
                ItemStack boxItem = MinecartUtil.getItemOnMinecart(minecart);
                BlockStateMeta meta = (BlockStateMeta) boxItem.getItemMeta();
                ShulkerBox shulkerBox = (ShulkerBox) meta.getBlockState();
                shulkerBox.getInventory().setContents(event.getInventory().getContents());
                meta.setBlockState(shulkerBox);
                boxItem.setItemMeta(meta);
                NBT.modifyPersistentData(minecart, nbt -> {
                    nbt.setItemStack("BlockInfo", boxItem);
                });
                PepperMinecart.getInstance().holderMap.remove(minecart);
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
            if (PepperMinecart.getInstance().getConfigTemplate().isEnableCustomInteract() && doCustomInteract(player, minecart)) {
                event.setCancelled(true);
            }
            if (itemOnMinecart != null) {
                event.setCancelled(true);
            }
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
