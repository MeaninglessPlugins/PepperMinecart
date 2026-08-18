package com.pepperminecart.command;

import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.cooldown.InteractionCooldown;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/** /pepperminecart reload（别名 /pm reload）—— 热重载配置。 */
public class PepperMinecartCommand implements CommandExecutor {

    private final PluginConfig config;
    private final InteractionCooldown cooldown;

    public PepperMinecartCommand(PluginConfig config) {
        this(config, null);
    }

    public PepperMinecartCommand(PluginConfig config, InteractionCooldown cooldown) {
        this.config = config;
        this.cooldown = cooldown;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("用法: /" + label + " reload —— 热重载配置"));
            return true;
        }
        if (!args[0].equalsIgnoreCase("reload") || args.length > 1) {
            sender.sendMessage(Component.text("未知子命令或多余参数: " + String.join(" ", args)));
            return true;
        }
        if (!sender.hasPermission("pepperminecart.reload")) {
            sender.sendMessage(Component.text("你没有权限执行此命令"));
            return true;
        }
        config.reload();
        // /pm reload 后清空交互冷却记录，避免旧配置下的冷却条目残留影响新配置语义
        if (cooldown != null) {
            cooldown.clear();
        }
        sender.sendMessage(Component.text("PepperMinecart 配置已热重载"));
        return true;
    }
}
