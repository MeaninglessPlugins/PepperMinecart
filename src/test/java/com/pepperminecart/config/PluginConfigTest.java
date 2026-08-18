package com.pepperminecart.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.TakeOffResult;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** PluginConfig 解析与方块允许性逻辑（纯 API 类，无需服务器）。 */
class PluginConfigTest {

    private PluginConfig configWith(Map<String, Object> values) {
        YamlConfiguration c = new YamlConfiguration();
        values.forEach(c::set);
        PluginConfig config = new PluginConfig(null);
        config.apply(c);
        return config;
    }

    private PluginConfig configFromYaml(String yaml) {
        PluginConfig config = new PluginConfig(null);
        config.apply(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
        return config;
    }

    @Test
    void numericYamlScalarsAreAcceptedByActualType() {
        // 回归：0 是 Integer、10.0/500.0 是 Double；不能因 isInt/isDouble 类型不匹配把合法值重置为默认
        PluginConfig config = configFromYaml("""
                display-block-offset: 10.0
                interaction-cooldown-ms: 500.0
                anvil-damage:
                  chance-per-use: 0
                """);
        assertEquals(10, config.displayBlockOffset());
        assertEquals(500, config.interactionCooldownMs());
        assertEquals(0.0, config.anvilDamageChance(), 1e-9);
    }

    @Test
    void nanAndInfinityNeverEnterRuntimeValues() {
        PluginConfig nan = configFromYaml("""
                anvil-damage:
                  chance-per-use: .nan
                dispenser-cart:
                  eject-offset: .nan
                  eject-speed: .inf
                """);
        assertFalse(Double.isNaN(nan.anvilDamageChance()), "chance 不得为 NaN");
        assertEquals(0.12, nan.anvilDamageChance(), 1e-9);
        assertFalse(Double.isNaN(nan.dispenserEjectOffset()), "eject-offset 不得为 NaN");
        assertEquals(0.6, nan.dispenserEjectOffset(), 1e-9);
        assertFalse(Double.isInfinite(nan.dispenserEjectSpeed()), "eject-speed 不得为 Infinity");
        assertEquals(0.5, nan.dispenserEjectSpeed(), 1e-9);
    }

    @Test
    void dispenserValuesAreClampedToSafeBounds() {
        PluginConfig config = configWith(Map.of(
                "dispenser-cart.cooldown-ticks", 99999,
                "dispenser-cart.eject-offset", 999.0,
                "dispenser-cart.eject-speed", -3.0
        ));
        assertEquals(1200, config.dispenserCooldownTicks());
        assertEquals(8.0, config.dispenserEjectOffset(), 1e-9);
        assertEquals(0.0, config.dispenserEjectSpeed(), 1e-9);
    }

    @Test
    void nonNumberValuesFallBackToDefaultsWithoutThrowing() {
        PluginConfig config = configWith(Map.of(
                "display-block-offset", "tall",
                "interaction-cooldown-ms", "fast",
                "anvil-damage.chance-per-use", "maybe",
                "dispenser-cart.eject-offset", "far"
        ));
        assertEquals(6, config.displayBlockOffset());
        assertEquals(250, config.interactionCooldownMs());
        assertEquals(0.12, config.anvilDamageChance(), 1e-9);
        assertEquals(0.6, config.dispenserEjectOffset(), 1e-9);
    }

    @Test
    void nullMaterialIsNeverAllowed() {
        PluginConfig allowAll = configWith(Map.of());
        PluginConfig allowList = configWith(Map.of("allow-all-blocks", false, "enabled-blocks", List.of("STONE")));
        assertFalse(allowAll.isBlockAllowed(null));
        assertFalse(allowList.isBlockAllowed(null));
    }

    @Test
    void defaults() {
        PluginConfig config = configWith(Map.of());
        assertEquals(TakeOffResult.INVENTORY, config.takeOffMode());
        assertTrue(config.allowAllBlocks());
        assertTrue(config.isBlockAllowed(Material.DIAMOND_BLOCK));
        assertTrue(config.respectProtection());
        assertEquals(6, config.displayBlockOffset());
        assertEquals(250, config.interactionCooldownMs());
        assertTrue(config.soundPlace());
        assertTrue(config.soundTakeOff());
        assertTrue(config.anvilDamageEnabled());
        assertEquals(0.12, config.anvilDamageChance(), 1e-9);
        assertFalse(config.anvilDropOnBreak());
        assertEquals(40, config.dispenserCooldownTicks());
        assertEquals(0.6, config.dispenserEjectOffset(), 1e-9);
        assertEquals(0.5, config.dispenserEjectSpeed(), 1e-9);
    }

    @Test
    void parsesOverrides() {
        Map<String, Object> values = new HashMap<>();
        values.put("take-off-mode", "DROP");
        values.put("allow-all-blocks", false);
        values.put("enabled-blocks", List.of("CHEST", "BARREL"));
        values.put("respect-protection", false);
        values.put("display-block-offset", 3);
        values.put("interaction-cooldown-ms", 100);
        values.put("sounds.place", false);
        values.put("anvil-damage.enabled", false);
        values.put("anvil-damage.chance-per-use", 0.5);
        values.put("anvil-damage.drop-on-break", true);
        values.put("dispenser-cart.cooldown-ticks", 10);
        values.put("dispenser-cart.eject-offset", 0.3);
        values.put("dispenser-cart.eject-speed", 0.9);
        PluginConfig config = configWith(values);

        assertEquals(TakeOffResult.DROP, config.takeOffMode());
        assertFalse(config.allowAllBlocks());
        assertTrue(config.isBlockAllowed(Material.CHEST));
        assertFalse(config.isBlockAllowed(Material.DIAMOND_BLOCK));
        assertFalse(config.respectProtection());
        assertEquals(3, config.displayBlockOffset());
        assertEquals(100, config.interactionCooldownMs());
        assertFalse(config.soundPlace());
        assertFalse(config.anvilDamageEnabled());
        assertEquals(0.5, config.anvilDamageChance(), 1e-9);
        assertTrue(config.anvilDropOnBreak());
        assertEquals(10, config.dispenserCooldownTicks());
        assertEquals(0.3, config.dispenserEjectOffset(), 1e-9);
        assertEquals(0.9, config.dispenserEjectSpeed(), 1e-9);
    }

    @Test
    void blacklistModeBlocksListedMaterials() {
        PluginConfig config = configWith(Map.of("disabled-blocks", List.of("BEDROCK", "BARRIER")));
        assertTrue(config.isBlockAllowed(Material.STONE));
        assertFalse(config.isBlockAllowed(Material.BEDROCK));
        assertFalse(config.isBlockAllowed(Material.BARRIER));
    }

    @Test
    void invalidValuesFallBackToDefaults() {
        PluginConfig config = configWith(Map.of(
                "take-off-mode", "BOGUS",
                "interaction-cooldown-ms", -5,
                "anvil-damage.chance-per-use", 2.0,
                "dispenser-cart.cooldown-ticks", 0
        ));
        assertEquals(TakeOffResult.INVENTORY, config.takeOffMode());
        assertEquals(0, config.interactionCooldownMs());
        assertEquals(1.0, config.anvilDamageChance(), 1e-9);
        assertEquals(1, config.dispenserCooldownTicks());
    }

    @Test
    void invalidMaterialNamesAreIgnored() {
        PluginConfig config = configWith(Map.of("disabled-blocks", List.of("NOT_A_BLOCK", "STONE")));
        assertFalse(config.isBlockAllowed(Material.STONE));
        assertTrue(config.isBlockAllowed(Material.DIRT));
    }
}
