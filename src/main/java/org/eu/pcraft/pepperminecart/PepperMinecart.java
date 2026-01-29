package org.eu.pcraft.pepperminecart;

import java.nio.file.Path;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.eu.pcraft.pepperminecart.holder.MinecartChestHolder;
import org.eu.pcraft.pepperminecart.config.ConfigManager;
import org.eu.pcraft.pepperminecart.config.MainConfigModule;

import java.util.*;

public final class PepperMinecart extends JavaPlugin {

    @Getter
    Map<Entity, MinecartChestHolder> holderMap = new HashMap<>();
    @Getter
    private static PepperMinecart instance;

    @Getter
    @Setter
    private ConfigManager<MainConfigModule> configManager;
    @Getter
    private MainConfigModule mainConfig = new MainConfigModule();

    @Override
    public void onLoad() {
        Path dataPath = getDataFolder().toPath();
        //load
        configManager = new ConfigManager<>(dataPath.resolve("config.yml"), mainConfig);
        configManager.loadConfig();
        this.mainConfig = configManager.getConfigModule();
    }

    @Override
    public void onEnable() {
        ////bStats////
        int pluginId = 21763;
        new Metrics(this, pluginId);

        ////Init////
        Bukkit.getPluginManager().registerEvents(new PepperListener(instance), this);
        instance = this;

        ////Command////
        new PepperCommand(this);
    }

    @Override
    public void onDisable() {
        super.onDisable();
    }

}
