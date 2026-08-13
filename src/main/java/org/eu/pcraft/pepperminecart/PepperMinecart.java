package org.eu.pcraft.pepperminecart;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.eu.pcraft.pepperminecart.command.PepperCommand;
import org.eu.pcraft.pepperminecart.config.ConfigManager;
import org.eu.pcraft.pepperminecart.config.MainConfigModule;
import org.eu.pcraft.pepperminecart.feature.FeatureRegistry;
import org.eu.pcraft.pepperminecart.listener.InventoryListener;
import org.eu.pcraft.pepperminecart.listener.PlayerInteractListener;
import org.eu.pcraft.pepperminecart.listener.VehicleListener;
import org.eu.pcraft.pepperminecart.listener.WorldListener;
import org.eu.pcraft.pepperminecart.registry.MinecartRegistry;
import org.eu.pcraft.pepperminecart.service.MinecartService;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class PepperMinecart extends JavaPlugin {

    private MinecartService minecartService;
    private MinecartRegistry minecartRegistry;
    private FeatureRegistry featureRegistry;

    @Getter
    @Setter
    private ConfigManager<MainConfigModule> configManager;
    @Getter
    @Setter
    private MainConfigModule mainConfig = new MainConfigModule();

    @Override
    public void onLoad() {
        loadPluginConfig();
    }

    @Override
    public void onEnable() {
        // bStats 统计
        new Metrics(this, 21763);

        // 初始化
        minecartService = new MinecartService(minecartRegistry, featureRegistry);
        Bukkit.getPluginManager().registerEvents(new VehicleListener(this, minecartService), this);
        Bukkit.getPluginManager().registerEvents(new InventoryListener(this, minecartService), this);
        Bukkit.getPluginManager().registerEvents(new PlayerInteractListener(this, minecartService), this);
        Bukkit.getPluginManager().registerEvents(new WorldListener(minecartService), this);

        // 命令注册
        new PepperCommand(this);
    }

    @Override
    public void onDisable() {
        if (minecartService != null) {
            minecartService.saveAllSessions();
        }
    }

    /**
     * 加载/重载配置文件（onLoad 与 reload 命令共用），并重建注册表
     */
    public void loadPluginConfig() {
        Path configPath = getDataFolder().toPath().resolve("config.yml");
        // 首次运行：复制带注释的默认配置文件
        if (!Files.exists(configPath)) {
            try (InputStream in = getResource("config.yml")) {
                if (in != null) {
                    Files.createDirectories(configPath.getParent());
                    Files.copy(in, configPath);
                }
            } catch (IOException e) {
                getLogger().warning("无法生成默认配置文件: " + e.getMessage());
            }
        }

        // 迁移旧版 vanilla-cart-conversions（Material->EntityType 字符串）为布尔启用开关
        if (Files.exists(configPath)) {
            migrateVanillaCartConversions(configPath);
        }

        configManager = new ConfigManager<>(configPath, mainConfig, getLogger());
        configManager.loadConfig();
        mainConfig = configManager.getConfigModule();

        // 数值范围校验
        double anvilChance = mainConfig.getAnvilDamageChance();
        if (anvilChance < 0 || anvilChance > 1) {
            getLogger().warning("[PepperMinecart] anvil-damage-chance 应在 [0,1] 区间，当前 " + anvilChance + "，将按边界值处理");
        }

        minecartRegistry = new MinecartRegistry(mainConfig.getVanillaCartConversions());
        featureRegistry = new FeatureRegistry(mainConfig.getBlockInteractions(), mainConfig.getVanillaCartConversions());
        // 注意：FeatureContext（打开中的容器/铁砧/工作站会话、投掷器冷却）在重载后保留，
        // 只重建注册表。因此 CartFeature 实现必须保持无状态（或自行处理重载），
        // 否则旧会话会引用已重建的特性对象。
        if (minecartService != null) {
            minecartService.setRegistry(minecartRegistry);
            minecartService.setFeatureRegistry(featureRegistry);
        }
    }

    /**
     * 迁移旧版 vanilla-cart-conversions 配置（Material -> EntityType 字符串值）为布尔启用开关。
     * 旧值（任何字符串）一律视为启用；已是布尔值的新配置保持不变。
     */
    private void migrateVanillaCartConversions(Path configPath) {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .path(configPath)
                .build();
        try {
            CommentedConfigurationNode root = loader.load();
            ConfigurationNode conversions = root.node("vanilla-cart-conversions");
            if (conversions.childrenMap().isEmpty()) return;
            boolean changed = false;
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : conversions.childrenMap().entrySet()) {
                if (entry.getValue().raw() instanceof String) {
                    entry.getValue().set(true);
                    changed = true;
                }
            }
            if (changed) {
                loader.save(root);
                getLogger().info("[PepperMinecart] 已迁移 vanilla-cart-conversions 为布尔启用开关（旧值一律视为启用）");
            }
        } catch (Exception e) {
            getLogger().warning("[PepperMinecart] vanilla-cart-conversions 配置迁移失败: " + e.getMessage());
        }
    }

}
