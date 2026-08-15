package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 插件冒烟测试：MockBukkit 真实加载插件，验证 onEnable / /pm reload / onDisable 全路径。 */
class PluginSmokeTest {

    private ServerMock server;
    private PepperMinecartPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PepperMinecartPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginEnablesAndRegistersService() {
        assertTrue(plugin.isEnabled());
        assertNotNull(server.getServicesManager().load(com.pepperminecart.api.PepperMinecartAPI.class));
    }

    @Test
    void reloadCommandWorks() {
        var player = server.addPlayer();
        player.addAttachment(plugin, "pepperminecart.reload", true);
        assertDoesNotThrow(() -> server.execute("pm", player, "reload"));
    }
}
