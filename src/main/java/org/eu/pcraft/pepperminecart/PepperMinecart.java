package org.eu.pcraft.pepperminecart;

import java.nio.file.Path;
import dev.jorel.commandapi.*;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.GreedyStringArgument;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.eu.pcraft.pepperminecart.holder.MinecartChestHolder;
import org.eu.pcraft.pepperminecart.config.ConfigManager;
import org.eu.pcraft.pepperminecart.config.MainConfigModule;

import java.util.*;

public final class PepperMinecart extends JavaPlugin {

    Map<Entity, MinecartChestHolder> holderMap = new HashMap<>();
    @Getter
    private static PepperMinecart instance;

    @Getter
    private ConfigManager<MainConfigModule> configManager;
    @Getter
    public MainConfigModule mainConfig = new MainConfigModule();

    @Override
    public void onLoad() {
        Path dataPath = getDataFolder().toPath();

        //load
        configManager=new ConfigManager<>(dataPath.resolve("config.yml"), mainConfig);
        configManager.loadConfig();
        CommandAPI.onLoad(new CommandAPIBukkitConfig(this).silentLogs(true));
    }

    @Override
    public void onEnable() {
        ////bStats////
        int pluginId = 21763;
        new Metrics(this, pluginId);

        ////Init////
        Bukkit.getPluginManager().registerEvents(new PepperListener(), this);
        instance = this;

        ////Commands////
        CommandAPI.onEnable();
        new CommandAPICommand("PepperMinecart")
                .withArguments(
                        new GreedyStringArgument("subCommand")
                                .includeSuggestions(
                                        ArgumentSuggestions.strings("reload")
                                )
                )
                .withPermission(CommandPermission.OP)
                .withAliases("pm", "minecart")
                .executes((sender, args) -> {
                    if(Objects.equals(args.get("subCommand"), "reload")){
                        sender.sendMessage("[PepperMinecart] reloading...");
                        configManager.loadConfig();
                        sender.sendMessage("[PepperMinecart] Done!");
                    }
                })
                .register();
    }

    @Override
    public void onDisable() {
        CommandAPI.onDisable();
    }

}
