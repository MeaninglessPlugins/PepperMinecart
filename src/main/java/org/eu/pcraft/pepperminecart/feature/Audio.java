package org.eu.pcraft.pepperminecart.feature;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;

/**
 * 音效播放
 */
final class Audio {

    void playPickupSound(Location location) {
        World world = location.getWorld();
        if (world != null) {
            world.playSound(location, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.5f);
        }
    }

    void playPlaceSound(Location location, Material material) {
        World world = location.getWorld();
        if (world != null) {
            world.playSound(location, getPlaceSound(material), 0.8f, 1.0f);
        }
    }

    private Sound getPlaceSound(Material material) {
        // 直接取方块的音效组，避免手写材质名匹配（新版本方块自动得到正确音效）
        return material.createBlockData().getSoundGroup().getPlaceSound();
    }
}
