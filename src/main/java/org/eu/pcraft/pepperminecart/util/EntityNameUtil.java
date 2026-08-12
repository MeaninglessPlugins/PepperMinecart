package org.eu.pcraft.pepperminecart.util;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Locale;
import java.util.Map;

/**
 * 配置名 -> Bukkit 枚举 的解析工具：Material 名归一化，以及原版矿车实体类型的
 * 版本无关解析（新旧两代枚举名由本类按当前服务器自动选择）。
 * FeatureRegistry 与 MinecartRegistry 共用，避免两处解析逻辑/映射表漂移。
 */
public final class EntityNameUtil {

    /**
     * 原版特殊矿车固定映射：Material 名 -> 新旧两代枚举名。
     * 1.20.5+ Bukkit 重命名（MINECART_X -> X_MINECART，命令方块为特例），
     * 当前服务器存在哪一代由 {@link #resolveMinecartType} 在运行时决定。
     */
    private static final Map<String, String[]> VANILLA_CART_TYPES = Map.of(
            "CHEST", new String[]{"MINECART_CHEST", "CHEST_MINECART"},
            "HOPPER", new String[]{"MINECART_HOPPER", "HOPPER_MINECART"},
            "FURNACE", new String[]{"MINECART_FURNACE", "FURNACE_MINECART"},
            "TNT", new String[]{"MINECART_TNT", "TNT_MINECART"},
            "COMMAND_BLOCK", new String[]{"MINECART_COMMAND", "COMMAND_BLOCK_MINECART"},
            "MOB_SPAWNER", new String[]{"MINECART_MOB_SPAWNER", "SPAWNER_MINECART"}
    );

    private EntityNameUtil() {}

    public static Material parseMaterial(String name) {
        if (name == null) return null;
        return Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }

    /**
     * 解析方块材质对应的原版特殊矿车实体类型，取当前服务器上存在的那一代枚举名。
     * 表外材质返回 null（配置不再直接写枚举名，只做开关）。
     */
    public static EntityType resolveMinecartType(String materialName) {
        if (materialName == null) return null;
        String[] names = VANILLA_CART_TYPES.get(normalize(materialName));
        if (names == null) return null;
        EntityType type = valueOfSafe(names[0]);
        return type != null ? type : valueOfSafe(names[1]);
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
