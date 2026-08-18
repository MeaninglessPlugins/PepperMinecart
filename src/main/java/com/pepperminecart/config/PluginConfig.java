package com.pepperminecart.config;

import com.pepperminecart.api.TakeOffResult;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** config.yml 配置模型：所有字段带默认值，/pm reload 后全部生效。 */
public class PluginConfig {

    private final JavaPlugin plugin;
    private final Logger logger;

    private TakeOffResult takeOffMode = TakeOffResult.INVENTORY;
    private boolean allowAllBlocks = true;
    private final Set<Material> disabledBlocks = new HashSet<>();
    private final Set<Material> enabledBlocks = new HashSet<>();
    private boolean respectProtection = true;
    private int displayBlockOffset = 6;
    private int interactionCooldownMs = 250;
    private boolean soundPlace = true;
    private boolean soundTakeOff = true;
    private boolean vanillaCartPickupAllowed = true;
    private ContainerPickupPolicy containerPickupPolicy = ContainerPickupPolicy.PICKUP;
    private boolean anvilDamageEnabled = true;
    private double anvilDamageChance = 0.12;
    private boolean anvilDropOnBreak = false;
    private int dispenserCooldownTicks = 40;
    private double dispenserEjectOffset = 0.6;
    private double dispenserEjectSpeed = 0.5;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin == null ? null : plugin.getLogger();
    }

    public void reload() {
        plugin.reloadConfig();
        apply(plugin.getConfig());
    }

    /** 从配置对象解析全部字段（可脱离服务器测试）。 */
    public void apply(FileConfiguration c) {
        String rawTakeOff = c.getString("take-off-mode");
        TakeOffResult parsedTakeOff = parseEnum(TakeOffResult.class, rawTakeOff, null);
        if (parsedTakeOff == null) {
            if (rawTakeOff != null && logger != null) {
                logger.warning("config.yml 中的 take-off-mode 无效（应为 INVENTORY / DROP / DISABLED），按 INVENTORY 处理");
            }
            takeOffMode = TakeOffResult.INVENTORY;
        } else {
            takeOffMode = parsedTakeOff;
        }
        allowAllBlocks = c.getBoolean("allow-all-blocks", true);
        parseMaterials(checkedList(c, "disabled-blocks"), disabledBlocks);
        parseMaterials(checkedList(c, "enabled-blocks"), enabledBlocks);
        respectProtection = c.getBoolean("respect-protection", true);

        displayBlockOffset = intInRange(c, "display-block-offset", 6, 0, 16);
        interactionCooldownMs = intInRange(c, "interaction-cooldown-ms", 250, 0, Integer.MAX_VALUE);

        soundPlace = c.getBoolean("sounds.place", true);
        soundTakeOff = c.getBoolean("sounds.take-off", true);
        vanillaCartPickupAllowed = c.getBoolean("vanilla-carts.allow-pickup", true);
        String rawPolicy = c.getString("container-pickup-policy");
        ContainerPickupPolicy parsedPolicy = ContainerPickupPolicy.parse(rawPolicy);
        if (parsedPolicy == null) {
            if (rawPolicy != null && logger != null) {
                logger.warning("config.yml 中的 container-pickup-policy 无效（应为 PICKUP / DROP / FORBIDDEN），按 PICKUP 处理");
            }
            containerPickupPolicy = ContainerPickupPolicy.PICKUP;
        } else {
            containerPickupPolicy = parsedPolicy;
        }
        anvilDamageEnabled = c.getBoolean("anvil-damage.enabled", true);
        anvilDamageChance = doubleInRange(c, "anvil-damage.chance-per-use", 0.12, 0.0, 1.0);
        anvilDropOnBreak = c.getBoolean("anvil-damage.drop-on-break", false);
        dispenserCooldownTicks = intInRange(c, "dispenser-cart.cooldown-ticks", 40, 1, 1200);
        dispenserEjectOffset = doubleInRange(c, "dispenser-cart.eject-offset", 0.6, 0.0, 8.0);
        dispenserEjectSpeed = doubleInRange(c, "dispenser-cart.eject-speed", 0.5, 0.0, 4.0);
    }

    // ---- getters ----

    public TakeOffResult takeOffMode() {
        return takeOffMode;
    }

    public boolean allowAllBlocks() {
        return allowAllBlocks;
    }

    public Set<Material> disabledBlocks() {
        // 不可变视图：调用方不能修改内部集合（热重载时 parseMaterials 会对同一集合 clear+重建）
        return Collections.unmodifiableSet(disabledBlocks);
    }

    public Set<Material> enabledBlocks() {
        return Collections.unmodifiableSet(enabledBlocks);
    }

    public boolean respectProtection() {
        return respectProtection;
    }

    public int displayBlockOffset() {
        return displayBlockOffset;
    }

    public int interactionCooldownMs() {
        return interactionCooldownMs;
    }

    public boolean soundPlace() {
        return soundPlace;
    }

    public boolean soundTakeOff() {
        return soundTakeOff;
    }

    /** 是否允许取下原版特殊矿车（箱子/漏斗/熔炉/TNT/命令矿车）上的物品。 */
    public boolean vanillaCartPickupAllowed() {
        return vanillaCartPickupAllowed;
    }

    /** 容器类矿车（木桶/投掷器/通用容器，不含潜影盒）取下策略。 */
    public ContainerPickupPolicy containerPickupPolicy() {
        return containerPickupPolicy;
    }

    public boolean anvilDamageEnabled() {
        return anvilDamageEnabled;
    }

    public double anvilDamageChance() {
        return anvilDamageChance;
    }

    public boolean anvilDropOnBreak() {
        return anvilDropOnBreak;
    }

    public int dispenserCooldownTicks() {
        return dispenserCooldownTicks;
    }

    public double dispenserEjectOffset() {
        return dispenserEjectOffset;
    }

    public double dispenserEjectSpeed() {
        return dispenserEjectSpeed;
    }

    /** 该方块是否允许放置到矿车上。 */
    public boolean isBlockAllowed(Material material) {
        if (material == null) {
            return false;
        }
        if (allowAllBlocks) {
            return !disabledBlocks.contains(material);
        }
        return enabledBlocks.contains(material);
    }

    /**
     * 读取整数配置并钳制到 [min, max]：按“值是否为 Number”判型，而不是按 YAML 标量实际类型判型。
     * YAML 中 0 是 Integer、10.0 是 Double，两者都是合法的数值配置。
     * 非数值按默认值处理；越界钳制并告警。
     */
    private int intInRange(FileConfiguration c, String path, int def, int min, int max) {
        Object raw = c.get(path);
        if (!(raw instanceof Number number)) {
            if (raw != null && logger != null) {
                logger.warning("config.yml 中的 " + path + " 不是整数，按默认值 " + def + " 处理");
            }
            return def;
        }
        double doubleValue = number.doubleValue();
        // NaN/Infinity 或带小数部分的值（如 250.7）不是合法的整数配置，回退默认并告警；
        // 10.0 这种“整数值的 Double”仍接受为 10。
        if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)
                || doubleValue != Math.rint(doubleValue)
                || doubleValue < Integer.MIN_VALUE || doubleValue > Integer.MAX_VALUE) {
            if (logger != null) {
                logger.warning("config.yml 中的 " + path + " 不是整数，按默认值 " + def + " 处理");
            }
            return def;
        }
        int value = number.intValue();
        int clamped = Math.max(min, Math.min(max, value));
        if (clamped != value && logger != null) {
            logger.warning("config.yml 中的 " + path + " 超出 " + min + "-" + max + " 范围，已修正为 " + clamped);
        }
        return clamped;
    }

    /**
     * 读取浮点配置并钳制到 [min, max]：NaN/Infinity 视为非法按默认值处理，
     * 否则 Math.max/min 会把 NaN 透传进运行值（如概率 NaN 导致铁砧每次都损坏）。
     */
    private double doubleInRange(FileConfiguration c, String path, double def, double min, double max) {
        Object raw = c.get(path);
        if (!(raw instanceof Number number)) {
            if (raw != null && logger != null) {
                logger.warning("config.yml 中的 " + path + " 不是数字，按默认值 " + def + " 处理");
            }
            return def;
        }
        double value = number.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            if (logger != null) {
                logger.warning("config.yml 中的 " + path + " 是 NaN/Infinity，按默认值 " + def + " 处理");
            }
            return def;
        }
        double clamped = Math.max(min, Math.min(max, value));
        if (Double.compare(clamped, value) != 0 && logger != null) {
            logger.warning("config.yml 中的 " + path + " 超出 " + min + "-" + max + " 范围，已修正为 " + clamped);
        }
        return clamped;
    }

    /** 键存在但值不是列表时（YAML 误写成标量，如 disabled-blocks: BEDROCK 少了 "-"）返回空列表并告警：
     *  Bukkit 的 getStringList 对非列表值返回空列表，若不告警，黑/白名单会被静默清空。 */
    private List<String> checkedList(FileConfiguration c, String path) {
        if (!c.contains(path) || c.isList(path)) {
            return c.getStringList(path);
        }
        if (logger != null) {
            logger.warning("config.yml 中的 " + path + " 应为列表（每行以 - 开头），当前写法无效，按空列表处理");
        }
        return List.of();
    }

    private void parseMaterials(List<String> names, Set<Material> out) {
        out.clear();
        for (String name : names) {
            // trim 掉首尾空白，避免 " BEDROCK " 匹配失败
            Material m = Material.matchMaterial(name.trim());
            if (m == null) {
                if (logger != null) {
                    logger.warning("config.yml 中的材质不存在: " + name);
                }
                continue;
            }
            // enabled-blocks 应只包含方块；非方块（物品材质如 DIAMOND）按方块语义无法放置，
            // 显式跳过避免配置误导。isBlock() 依赖服务端注册表，纯 JVM 单测中可能抛异常，
            // try 内失败时按方块接受（保持原行为，不因测试环境误过滤）
            try {
                if (!m.isBlock()) {
                    if (logger != null) {
                        logger.warning("config.yml 中的材质不是方块，已忽略: " + name);
                    }
                    continue;
                }
            } catch (RuntimeException ignored) {
                // 无服务端注册表的测试环境无法判定，接受该材质
            }
            out.add(m);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E def) {
        if (value == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
