package org.eu.pcraft.pepperminecart.config;

import lombok.Getter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Path;

public class ConfigManager<T> {
    CommentedConfigurationNode node;

    YamlConfigurationLoader loader;

    Class<T> configType;

    @Getter
    T configModule;

    public ConfigManager(Path src, T cm){
        loader = YamlConfigurationLoader.builder()
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .path(src)
                .build();
        node=loader.createNode();
        configType = (Class<T>) cm.getClass();
        configModule = cm;
    }
    public void loadConfig(){
        try {
            node=loader.load();
            if (node.empty()) {
                // 无配置文件时生成默认配置
                node.set(configModule);
                saveConfig();
            } else {
                configModule=node.get(configType);
            }
        }catch (ConfigurateException e){
            e.printStackTrace();
            node = loader.createNode();
        }
    }
    public void saveConfig(){
        try {
            node.set(configModule);
            loader.save(node);
        }catch (ConfigurateException e){
            e.printStackTrace();
        }
    }
}
