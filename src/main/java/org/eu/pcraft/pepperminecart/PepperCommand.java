package org.eu.pcraft.pepperminecart; // 请修改为你的实际包名

import org.bukkit.command.*;
import org.eu.pcraft.pepperminecart.config.ConfigManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
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
        if (!sender.isOp()) {
            sender.sendMessage("§c你没有权限执行此命令。");
            return true;
        }

        // 逻辑处理：/pepperminecart reload
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("[PepperMinecart] Reloading...");
            Path dataPath = plugin.getDataFolder().toPath();
            plugin.setConfigManager(new ConfigManager<>(dataPath.resolve("config.yml"), plugin.getMainConfig()));
            plugin.getConfigManager().loadConfig();

            sender.sendMessage("[PepperMinecart] Done!");
            return true;
        }

        // 如果输入了错误参数或没加参数，可以显示帮助信息
        sender.sendMessage("§7用法: /" + label + " reload");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.isOp()) return Collections.emptyList();

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