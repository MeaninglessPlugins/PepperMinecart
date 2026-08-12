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

    private Map<String, String> entityTransformations = new LinkedHashMap<>(Map.of(
            "HOPPER", "MINECART_HOPPER",
            "CHEST", "MINECART_CHEST",
            "TNT", "MINECART_TNT",
            "COMMAND_BLOCK", "MINECART_COMMAND",
            "FURNACE", "MINECART_FURNACE"
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
