package org.eu.pcraft.pepperminecart.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.eu.pcraft.pepperminecart.PepperMinecart;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PepperCommand implements CommandExecutor, TabCompleter {
    private final PepperMinecart plugin;

    public PepperCommand(PepperMinecart plugin) {
        this.plugin = plugin;
        // 获取主命令并注册
        PluginCommand command = plugin.getCommand("PepperMinecart");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // 权限检查
        if (!sender.hasPermission("pepperminecart.reload")) {
            sender.sendMessage("§c你没有权限执行此命令。");
            return true;
        }

        // 逻辑处理：/pepperminecart reload
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("[PepperMinecart] Reloading...");
            plugin.loadPluginConfig();

            sender.sendMessage("[PepperMinecart] Done!");
            return true;
        }

        // 无参数或参数错误时显示用法
        sender.sendMessage("§7用法: /" + label + " reload");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("pepperminecart.reload")) return Collections.emptyList();

        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            suggestions.add("reload");
            return startsWith(suggestions, args[0]);
        }

        return Collections.emptyList();
    }

    /**
     * 辅助方法：过滤匹配开头的补全项
     */
    private List<String> startsWith(List<String> list, String input) {
        if (input == null || input.isEmpty()) return list;
        String lowerInput = input.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase().startsWith(lowerInput)) {
                result.add(s);
            }
        }
        return result;
    }
}
