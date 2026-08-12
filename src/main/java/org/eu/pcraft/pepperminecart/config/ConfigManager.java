package org.eu.pcraft.pepperminecart.config;

import lombok.Getter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Path;
import java.util.logging.Logger;

public class ConfigManager<T> {

    private CommentedConfigurationNode node;

    private final YamlConfigurationLoader loader;

    private final Class<T> configType;

    private final Logger logger;

    @Getter
    private T configModule;

    @SuppressWarnings("unchecked") // cm.getClass() 即 T 的运行时类型，泛型擦除下无法免掉该强转
    public ConfigManager(Path src, T cm, Logger logger) {
        this.loader = YamlConfigurationLoader.builder()
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .path(src)
                .build();
        this.node = loader.createNode();
        this.configType = (Class<T>) cm.getClass();
        this.configModule = cm;
        this.logger = logger;
    }

    public void loadConfig() {
        try {
            node = loader.load();
            if (node.empty()) {
                // 无配置文件时生成默认配置
                node.set(configModule);
                saveConfig();
            } else {
                configModule = node.get(configType);
            }
        } catch (ConfigurateException e) {
            logger.warning("配置加载失败，使用默认配置: " + e.getMessage());
            node = loader.createNode();
        }
    }

    public void saveConfig() {
        try {
            node.set(configModule);
            loader.save(node);
        } catch (ConfigurateException e) {
            logger.warning("配置保存失败: " + e.getMessage());
        }
    }
}
