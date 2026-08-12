package org.eu.pcraft.pepperminecart.util;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;

import java.util.Locale;

/**
 * 配置名 -> Bukkit 枚举 的解析工具：Material / EntityType 名归一化解析。
 * FeatureRegistry 与 MinecartRegistry 共用，避免两处解析逻辑漂移。
 */
public final class EntityNameUtil {

    private EntityNameUtil() {}

    public static Material parseMaterial(String name) {
        if (name == null) return null;
        return Material.matchMaterial(normalize(name));
    }

    /** 解析实体类型名，仅接受矿车实体类型（防止配置把转换映射到非矿车实体） */
    public static EntityType parseEntityType(String name) {
        if (name == null) return null;
        EntityType type = valueOfSafe(normalize(name));
        if (type == null) return null;
        return Minecart.class.isAssignableFrom(type.getEntityClass()) ? type : null;
    }

    private static EntityType valueOfSafe(String name) {
        try {
            return EntityType.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 归一化配置值：大写、连字符/空格转下划线 */
    public static String normalize(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
