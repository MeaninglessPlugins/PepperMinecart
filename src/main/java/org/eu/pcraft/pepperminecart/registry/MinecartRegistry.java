package org.eu.pcraft.pepperminecart.registry;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.eu.pcraft.pepperminecart.util.EntityNameUtil;

import java.util.Map;

/**
 * 存储方块与原版特殊矿车实体的对应关系。
 * 实体类型由插件按服务器版本解析，配置只决定每项是否启用。
 */
public class MinecartRegistry {

    private final BiMap<Material, EntityType> entityTransformations = HashBiMap.create();

    public MinecartRegistry(Map<String, Boolean> conversions) {
        loadTransformations(conversions);
    }

    private void loadTransformations(Map<String, Boolean> conversions) {
        entityTransformations.clear();
        if (conversions == null) return;
        for (Map.Entry<String, Boolean> entry : conversions.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) continue;
            Material material = EntityNameUtil.parseMaterial(entry.getKey());
            EntityType type = EntityNameUtil.resolveMinecartType(entry.getKey());
            if (material == null || type == null) {
                warnInvalid("vanilla-cart-conversions", entry.getKey(), entry.getValue());
                continue;
            }
            try {
                entityTransformations.put(material, type);
            } catch (IllegalArgumentException e) {
                Bukkit.getLogger().warning("[PepperMinecart] 配置 vanilla-cart-conversions 中存在重复实体类型: '"
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

    private static void warnInvalid(String section, String key, Object value) {
        Bukkit.getLogger().warning("[PepperMinecart] 配置 " + section + " 中存在无效项: '" + key + "=" + value + "'，已跳过");
    }
}
