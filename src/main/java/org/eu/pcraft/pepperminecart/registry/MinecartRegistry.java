package org.eu.pcraft.pepperminecart.registry;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Map;

/**
 * 存储方块与原版特殊矿车实体的对应关系。
 * 映射表写死（{@link #DEFAULT_CONVERSIONS}），配置仅控制各条目启用/禁用，
 * 避免热重载改变映射后与已存在的自定义矿车形态冲突。
 */
public class MinecartRegistry {

    /** 写死的原版特殊矿车转换表（Material 名 -> 矿车实体类型） */
    public static final Map<Material, EntityType> DEFAULT_CONVERSIONS = Map.of(
            Material.CHEST, EntityType.CHEST_MINECART,
            Material.HOPPER, EntityType.HOPPER_MINECART,
            Material.FURNACE, EntityType.FURNACE_MINECART,
            Material.TNT, EntityType.TNT_MINECART,
            Material.COMMAND_BLOCK, EntityType.COMMAND_BLOCK_MINECART
    );

    private final BiMap<Material, EntityType> entityTransformations = HashBiMap.create();

    public MinecartRegistry(Map<String, Boolean> conversions) {
        loadTransformations(conversions);
    }

    private void loadTransformations(Map<String, Boolean> conversions) {
        entityTransformations.clear();
        if (conversions == null) return;
        for (Map.Entry<Material, EntityType> entry : DEFAULT_CONVERSIONS.entrySet()) {
            // 未列出的条目一律默认启用；显式 false 才禁用
            if (!conversions.getOrDefault(entry.getKey().name(), true)) continue;
            entityTransformations.put(entry.getKey(), entry.getValue());
        }
    }

    public EntityType getTransformation(Material material) {
        return entityTransformations.get(material);
    }

    public Material getTransformation(EntityType entityType) {
        return entityTransformations.inverse().get(entityType);
    }
}
