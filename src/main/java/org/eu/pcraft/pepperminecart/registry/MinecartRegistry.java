package org.eu.pcraft.pepperminecart.registry;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Locale;
import java.util.Map;

/**
 * 存储方块与原版特殊矿车实体的对应关系，由配置文件驱动
 */
public class MinecartRegistry {

    private final BiMap<Material, EntityType> entityTransformations = HashBiMap.create();

    public MinecartRegistry(Map<String, String> transformations) {
        loadTransformations(transformations);
    }

    private void loadTransformations(Map<String, String> config) {
        entityTransformations.clear();
        if (config == null) return;
        for (Map.Entry<String, String> entry : config.entrySet()) {
            Material material = parseMaterial(entry.getKey());
            EntityType type = parseEntityType(entry.getValue());
            if (material == null || type == null) {
                warnInvalid("entityTransformations", entry.getKey(), entry.getValue());
                continue;
            }
            try {
                entityTransformations.put(material, type);
            } catch (IllegalArgumentException e) {
                Bukkit.getLogger().warning("[PepperMinecart] 配置 entityTransformations 中存在重复实体类型: '"
                        + entry.getKey() + "=" + entry.getValue() + "'，已跳过");
            }
        }
    }

    public EntityType getTransformation(Material material) {
        return entityTransformations.get(material);
    }

    public Material getTransformation(EntityType entityType) {
        return entityTransformations.inverse().get(entityType);
    }

    private static Material parseMaterial(String name) {
        if (name == null) return null;
        return Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }

    private static EntityType parseEntityType(String name) {
        if (name == null) return null;
        try {
            return EntityType.valueOf(normalize(name));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalize(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static void warnInvalid(String section, String key, String value) {
        Bukkit.getLogger().warning("[PepperMinecart] 配置 " + section + " 中存在无效项: '" + key + "=" + value + "'，已跳过");
    }
}
