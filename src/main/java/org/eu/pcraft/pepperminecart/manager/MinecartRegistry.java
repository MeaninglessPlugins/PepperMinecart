package org.eu.pcraft.pepperminecart.manager;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 存储方块与实体/交互的对应关系
 */
public class MinecartRegistry {

    private static final BiMap<Material, EntityType> entityTransformations = HashBiMap.create();
    private static final BiMap<Material, Consumer<Player>> blockInteractions = HashBiMap.create();

    static {
        // 实体转换映射 (方块 -> 对应矿车类型)
        entityTransformations.put(Material.HOPPER, EntityType.MINECART_HOPPER);
        entityTransformations.put(Material.CHEST, EntityType.MINECART_CHEST);
        entityTransformations.put(Material.TNT, EntityType.MINECART_TNT);
        entityTransformations.put(Material.COMMAND_BLOCK, EntityType.MINECART_COMMAND);
        entityTransformations.put(Material.FURNACE, EntityType.MINECART_FURNACE);

        // 方块交互映射 (方块 -> 打开的界面)
        blockInteractions.put(Material.CRAFTING_TABLE, p -> p.openWorkbench(null, true));
        blockInteractions.put(Material.GRINDSTONE, p -> p.openGrindstone(null, true));
        blockInteractions.put(Material.LOOM, p -> p.openLoom(null, true));
        blockInteractions.put(Material.CARTOGRAPHY_TABLE, p -> p.openCartographyTable(null, true));
        blockInteractions.put(Material.SMITHING_TABLE, p -> p.openSmithingTable(null, true));
        blockInteractions.put(Material.STONECUTTER, p -> p.openStonecutter(null, true));
    }

    public static EntityType getTransformation(Material material) {
        return entityTransformations.get(material);
    }
    public static Material getTransformation(EntityType entityType) {
        return entityTransformations.inverse().get(entityType);
    }

    public static Consumer<Player> getInteraction(Material material) {
        return blockInteractions.get(material);
    }
}