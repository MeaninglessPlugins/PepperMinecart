package org.eu.pcraft.pepperminecart.config;

import lombok.Getter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Path;
import java.util.Map;
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
                // 合并缺失键：配置文件中不存在的字段保留上次运行时的值，
                // 而不是回落为类字段默认值（用户删掉某个键不等于重置该功能）
                mergeMissingKeys();
                configModule = node.get(configType);
            }
        } catch (ConfigurateException e) {
            logger.warning("配置加载失败，保留上次配置: " + e.getMessage());
            node = loader.createNode();
        }
    }

    /**
     * 把 configModule（上次运行值/默认值）中配置文件缺失的顶层键合并进 node，
     * 保证热重载时被删除的配置项保留旧值而非回落为默认值
     */
    private void mergeMissingKeys() throws ConfigurateException {
        ConfigurationNode previous = loader.createNode();
        previous.set(configModule);
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : previous.childrenMap().entrySet()) {
            if (!node.hasChild(entry.getKey())) {
                node.node(entry.getKey()).from(entry.getValue());
            }
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
