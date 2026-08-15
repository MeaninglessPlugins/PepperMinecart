package com.pepperminecart.cooldown;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.config.PluginConfig;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** InteractionCooldown 逻辑测试（Player 用动态代理模拟）。 */
class InteractionCooldownTest {

    private static Player fakePlayer(UUID id) {
        return (Player) Proxy.newProxyInstance(
                InteractionCooldownTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return id;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0.0d;
                    if (rt == float.class) return 0.0f;
                    if (rt == short.class) return (short) 0;
                    if (rt == byte.class) return (byte) 0;
                    if (rt == char.class) return (char) 0;
                    return null;
                });
    }

    private static PluginConfig configWithCooldown(int ms) {
        YamlConfiguration c = new YamlConfiguration();
        c.set("interaction-cooldown-ms", ms);
        PluginConfig config = new PluginConfig(null);
        config.apply(c);
        return config;
    }

    @Test
    void blocksRapidUseWithinCooldown() {
        InteractionCooldown cooldown = new InteractionCooldown(configWithCooldown(1000));
        Player player = fakePlayer(UUID.randomUUID());
        assertTrue(cooldown.tryUse(player));
        assertFalse(cooldown.tryUse(player));
    }

    @Test
    void zeroCooldownAllowsEverything() {
        InteractionCooldown cooldown = new InteractionCooldown(configWithCooldown(0));
        Player player = fakePlayer(UUID.randomUUID());
        assertTrue(cooldown.tryUse(player));
        assertTrue(cooldown.tryUse(player));
    }

    @Test
    void cooldownIsPerPlayer() {
        InteractionCooldown cooldown = new InteractionCooldown(configWithCooldown(1000));
        Player a = fakePlayer(UUID.randomUUID());
        Player b = fakePlayer(UUID.randomUUID());
        assertTrue(cooldown.tryUse(a));
        assertTrue(cooldown.tryUse(b));
        assertFalse(cooldown.tryUse(a));
    }
}
