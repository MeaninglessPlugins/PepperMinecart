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

    private Map<String, Boolean> vanillaCartPickup = new LinkedHashMap<>(Map.of(
            "CHEST", true,
            "HOPPER", true,
            "FURNACE", true,
            "TNT", true,
            "COMMAND_BLOCK", true
    ));

    /** 方块 -> 是否启用原版特殊矿车转换（实体类型由插件按服务器版本自动选择） */
    private Map<String, Boolean> vanillaCartConversions = new LinkedHashMap<>(Map.of(
            "CHEST", true,
            "HOPPER", true,
            "FURNACE", true,
            "TNT", true,
            "COMMAND_BLOCK", true
    ));

    private Map<String, String> blockInteractions = new LinkedHashMap<>(Map.ofEntries(
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
    ));
}
