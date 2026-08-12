package org.eu.pcraft.pepperminecart.feature;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * 音效播放
 */
final class Audio {

    void playPickupSound(Player player, Location location) {
        player.getWorld().playSound(location, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.5f);
    }

    void playPlaceSound(Player player, Location location, Material material) {
        player.getWorld().playSound(location, getPlaceSound(material), 0.8f, 1.0f);
    }

    private Sound getPlaceSound(Material material) {
        String name = material.name();
        if (name.contains("GLASS")) return Sound.BLOCK_GLASS_PLACE;
        if (name.contains("WOOL")) return Sound.BLOCK_WOOL_PLACE;
        if (name.contains("WOOD") || name.contains("PLANK") || name.contains("_LOG")) return Sound.BLOCK_WOOD_PLACE;
        if (name.contains("IRON") || name.contains("GOLD") || name.contains("_COPPER") || name.contains("ANVIL")) return Sound.BLOCK_METAL_PLACE;
        if (name.contains("SAND")) return Sound.BLOCK_SAND_PLACE;
        if (name.contains("GRAVEL")) return Sound.BLOCK_GRAVEL_PLACE;
        if (name.contains("SNOW")) return Sound.BLOCK_SNOW_PLACE;
        return Sound.BLOCK_STONE_PLACE;
    }
}
