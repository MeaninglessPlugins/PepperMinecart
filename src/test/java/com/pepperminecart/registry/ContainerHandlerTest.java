package com.pepperminecart.registry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.registry.handler.ContainerHandler;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** ContainerHandler 静态判定逻辑测试（无需服务器）。 */
class ContainerHandlerTest {

    @Test
    void isShulkerBoxRecognizesAllShulkerBoxMaterials() {
        // 无染色潜影盒（SHULKER_BOX）此前因后缀 "_SHULKER_BOX" 漏判
        assertTrue(ContainerHandler.isShulkerBox(Material.SHULKER_BOX));
        assertTrue(ContainerHandler.isShulkerBox(Material.WHITE_SHULKER_BOX));
    }

    @Test
    void isShulkerBoxRejectsSimilarMaterials() {
        assertFalse(ContainerHandler.isShulkerBox(Material.SHULKER_SHELL));
        assertFalse(ContainerHandler.isShulkerBox(Material.SHULKER_SPAWN_EGG));
        assertFalse(ContainerHandler.isShulkerBox(Material.CHEST));
    }
}
