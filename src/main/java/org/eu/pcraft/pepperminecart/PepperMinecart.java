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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

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

        configManager = new ConfigManager<>(configPath, mainConfig);
        configManager.loadConfig();
        mainConfig = configManager.getConfigModule();
        minecartRegistry = new MinecartRegistry(mainConfig.getEntityTransformations());
        featureRegistry = new FeatureRegistry(mainConfig.getBlockInteractions(), mainConfig.getEntityTransformations());
        if (minecartService != null) {
            minecartService.setRegistry(minecartRegistry);
            minecartService.setFeatureRegistry(featureRegistry);
        }
    }

}
