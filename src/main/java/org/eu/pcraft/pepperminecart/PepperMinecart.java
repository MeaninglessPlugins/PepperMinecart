package org.eu.pcraft.pepperminecart;

import dev.jorel.commandapi.*;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.GreedyStringArgument;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.eu.pcraft.pepperminecart.holder.MinecartChestHolder;
import org.eu.pcraft.pepperminecart.template.ConfigTemplate;

import java.util.*;

public final class PepperMinecart extends JavaPlugin {

    Map<Entity, MinecartChestHolder> holderMap = new HashMap<>();
    @Getter
    private static PepperMinecart instance;

    @Getter
    private final ConfigTemplate configTemplate = new ConfigTemplate();


    @Override
    public void onLoad() {
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

        ////Config////
        saveDefaultConfig();
        configTemplate.loadConfig();

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
                        this.reloadConfig();
                        configTemplate.loadConfig();
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
