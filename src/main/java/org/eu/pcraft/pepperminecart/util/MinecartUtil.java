package org.eu.pcraft.pepperminecart.util;

import de.tr7zw.changeme.nbtapi.NBT;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

public final class MinecartUtil {
    @Getter
    private static final EnumMap<Material, EntityType> entityTransformations = new EnumMap<>(Material.class);
    @Getter
    private static final Map<Material, Consumer<Player>> blockInteractions = new EnumMap<>(Material.class);
    static {
        //Entity Transformations Map
        entityTransformations.put(Material.HOPPER, EntityType.MINECART_HOPPER);
        entityTransformations.put(Material.CHEST, EntityType.MINECART_CHEST);
        entityTransformations.put(Material.TNT, EntityType.MINECART_TNT);
        entityTransformations.put(Material.COMMAND_BLOCK, EntityType.MINECART_COMMAND);
        entityTransformations.put(Material.FURNACE, EntityType.MINECART_FURNACE);

        //Block Interactions Map
        blockInteractions.put(Material.CRAFTING_TABLE, p -> p.openWorkbench(null, true));
        blockInteractions.put(Material.GRINDSTONE, p -> p.openGrindstone(null, true));
        blockInteractions.put(Material.LOOM, p -> p.openLoom(null, true));
        blockInteractions.put(Material.CARTOGRAPHY_TABLE, p -> p.openCartographyTable(null, true));
        blockInteractions.put(Material.SMITHING_TABLE, p -> p.openSmithingTable(null, true));
        blockInteractions.put(Material.STONECUTTER, p -> p.openStonecutter(null, true));
    }
    public static ItemStack getItemOnMinecart(Minecart minecart){
        return NBT.getPersistentData(minecart, nbt ->
                nbt.getItemStack("BlockInfo"));
    }
    public static void replaceMinecart(Minecart oldCart, EntityType newType, ItemStack placedItem) {
        Entity newEntity = oldCart.getWorld().spawnEntity(oldCart.getLocation(), newType);
        newEntity.setVelocity(oldCart.getVelocity());
        newEntity.setRotation(oldCart.getLocation().getYaw(), oldCart.getLocation().getPitch());
        if (placedItem.getType() == Material.CHEST) {
            BlockStateMeta meta = (BlockStateMeta) placedItem.getItemMeta();
            Chest chest = (Chest) meta.getBlockState();
            ((StorageMinecart) newEntity).getInventory().setContents(chest.getInventory().getContents());
        }
        oldCart.remove();
    }
    public static void placeBlock(Minecart minecart, ItemStack placedItem){
        Material material = placedItem.getType();
        EntityType newType = entityTransformations.get(material);
        if(newType != null){
            MinecartUtil.replaceMinecart(minecart, newType, placedItem);
            return;
        }
        NBT.modifyPersistentData(minecart, nbt -> {
            nbt.setItemStack("BlockInfo", placedItem);
        });
        minecart.setDisplayBlockData(material.createBlockData());
    }
    public static void removeBlock(Minecart minecart){
        NBT.modifyPersistentData(minecart, nbt -> {
            nbt.removeKey("BlockInfo");
        });
        minecart.setDisplayBlockData(Material.AIR.createBlockData());
    }
}
