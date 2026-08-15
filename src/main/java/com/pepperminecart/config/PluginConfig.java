package com.pepperminecart.config;

import com.pepperminecart.api.TakeOffResult;
import java.util.HashSet;
import java.util.List;
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
        takeOffMode = parseEnum(TakeOffResult.class, c.getString("take-off-mode"), TakeOffResult.INVENTORY);
        allowAllBlocks = c.getBoolean("allow-all-blocks", true);
        parseMaterials(c.getStringList("disabled-blocks"), disabledBlocks);
        parseMaterials(c.getStringList("enabled-blocks"), enabledBlocks);
        respectProtection = c.getBoolean("respect-protection", true);
        displayBlockOffset = c.getInt("display-block-offset", 6);
        interactionCooldownMs = Math.max(0, c.getInt("interaction-cooldown-ms", 250));
        soundPlace = c.getBoolean("sounds.place", true);
        soundTakeOff = c.getBoolean("sounds.take-off", true);
        vanillaCartPickupAllowed = c.getBoolean("vanilla-carts.allow-pickup", true);
        ContainerPickupPolicy parsedPolicy = ContainerPickupPolicy.parse(c.getString("container-pickup-policy"));
        if (parsedPolicy == null) {
            if (logger != null) {
                logger.warning("config.yml 中的 container-pickup-policy 无效（应为 PICKUP / DROP / FORBIDDEN），按 PICKUP 处理");
            }
            containerPickupPolicy = ContainerPickupPolicy.PICKUP;
        } else {
            containerPickupPolicy = parsedPolicy;
        }
        anvilDamageEnabled = c.getBoolean("anvil-damage.enabled", true);
        anvilDamageChance = Math.max(0.0, Math.min(1.0, c.getDouble("anvil-damage.chance-per-use", 0.12)));
        anvilDropOnBreak = c.getBoolean("anvil-damage.drop-on-break", false);
        dispenserCooldownTicks = Math.max(1, c.getInt("dispenser-cart.cooldown-ticks", 40));
        dispenserEjectOffset = Math.max(0.0, c.getDouble("dispenser-cart.eject-offset", 0.6));
        dispenserEjectSpeed = Math.max(0.0, c.getDouble("dispenser-cart.eject-speed", 0.5));
    }

    // ---- getters ----

    public TakeOffResult takeOffMode() {
        return takeOffMode;
    }

    public boolean allowAllBlocks() {
        return allowAllBlocks;
    }

    public Set<Material> disabledBlocks() {
        return disabledBlocks;
    }

    public Set<Material> enabledBlocks() {
        return enabledBlocks;
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
        if (allowAllBlocks) {
            return !disabledBlocks.contains(material);
        }
        return enabledBlocks.contains(material);
    }

    private void parseMaterials(List<String> names, Set<Material> out) {
        out.clear();
        for (String name : names) {
            // 注意：不调用 Material.isBlock()（该 API 依赖服务端注册表，无法在纯 JVM 单测中验证；
            // 放置路径本身会校验手持物品为方块）
            Material m = Material.matchMaterial(name);
            if (m != null) {
                out.add(m);
            } else if (logger != null) {
                logger.warning("config.yml 中的材质不存在: " + name);
            }
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E def) {
        if (value == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
