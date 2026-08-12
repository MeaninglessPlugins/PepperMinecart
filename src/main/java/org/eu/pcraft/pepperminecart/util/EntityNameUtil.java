package org.eu.pcraft.pepperminecart.util;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Locale;
import java.util.Map;

/**
 * 配置名 -> Bukkit 枚举 的解析工具：Material 名与 EntityType 名共用的归一化与别名兜底。
 * FeatureRegistry 与 MinecartRegistry 共用，避免两处解析逻辑/别名表漂移。
 */
public final class EntityNameUtil {

    /**
     * 1.20.5+ Bukkit 枚举重命名（MINECART_X -> X_MINECART，命令方块为特例）。
     * 旧名在新版 API 上 valueOf 会抛异常，这里显式映射到新名兜底。
     */
    private static final Map<String, String> MINECART_NAME_ALIASES = Map.of(
            "MINECART_CHEST", "CHEST_MINECART",
            "MINECART_HOPPER", "HOPPER_MINECART",
            "MINECART_FURNACE", "FURNACE_MINECART",
            "MINECART_TNT", "TNT_MINECART",
            "MINECART_COMMAND", "COMMAND_BLOCK_MINECART",
            "MINECART_MOB_SPAWNER", "SPAWNER_MINECART"
    );

    private EntityNameUtil() {}

    public static Material parseMaterial(String name) {
        if (name == null) return null;
        return Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }

    public static EntityType parseEntityType(String name) {
        if (name == null) return null;
        String normalized = normalize(name);
        try {
            return EntityType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            // 旧枚举名在新版 API 上不存在，走别名回退
        }
        String alias = MINECART_NAME_ALIASES.get(normalized);
        if (alias != null) {
            try {
                return EntityType.valueOf(alias);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    /** 归一化配置值：大写、连字符/空格转下划线 */
    public static String normalize(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
