package org.eu.pcraft.pepperminecart.config;

import lombok.Getter;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigSerializable
@Getter
public class MainConfigModule {
    private boolean enableCustomInteract = true;
    private boolean soundFeedback = true;
    private long interactionCooldownMillis = 0;
    private boolean anvilDamageEnabled = false;
    private double anvilDamageChance = 0.12;

    private int dropperCartCooldownTicks = 4;
    private double dropperCartXzOffset = 0.3;

    private Map<String, Boolean> vanillaCartPickup = orderedMap(
            Map.entry("CHEST", true),
            Map.entry("HOPPER", true),
            Map.entry("FURNACE", true),
            Map.entry("TNT", true),
            Map.entry("COMMAND_BLOCK", true)
    );

    /** 方块 -> 原版特殊矿车实体类型（Material 名 -> EntityType 名，删除条目即禁用） */
    private Map<String, String> vanillaCartConversions = orderedMap(
            Map.entry("CHEST", "CHEST_MINECART"),
            Map.entry("HOPPER", "HOPPER_MINECART"),
            Map.entry("FURNACE", "FURNACE_MINECART"),
            Map.entry("TNT", "TNT_MINECART"),
            Map.entry("COMMAND_BLOCK", "COMMAND_BLOCK_MINECART")
    );

    private Map<String, String> blockInteractions = orderedMap(
            Map.entry("CRAFTING_TABLE", "WORKBENCH"),
            Map.entry("GRINDSTONE", "GRINDSTONE"),
            Map.entry("LOOM", "LOOM"),
            Map.entry("CARTOGRAPHY_TABLE", "CARTOGRAPHY_TABLE"),
            Map.entry("SMITHING_TABLE", "SMITHING_TABLE"),
            Map.entry("STONECUTTER", "STONECUTTER"),
            Map.entry("ANVIL", "ANVIL"),
            Map.entry("CHIPPED_ANVIL", "ANVIL"),
            Map.entry("DAMAGED_ANVIL", "ANVIL"),
            Map.entry("ENCHANTING_TABLE", "ENCHANTING_TABLE"),
            Map.entry("BARREL", "BARREL"),
            Map.entry("DROPPER", "DROPPER")
    );

    /** 有序构建 LinkedHashMap（避免 Map.of 随机迭代顺序导致首次生成 config.yml 键序不稳定） */
    @SafeVarargs
    private static <K, V> LinkedHashMap<K, V> orderedMap(Map.Entry<K, V>... entries) {
        LinkedHashMap<K, V> map = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }
}
