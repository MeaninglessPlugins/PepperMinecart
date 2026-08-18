package com.pepperminecart.registry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.container.CartSessionManager;
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

    @Test
    void isShulkerBoxRejectsLegacyMaterials() {
        assertFalse(ContainerHandler.isShulkerBox(Material.LEGACY_WHITE_SHULKER_BOX));
    }

    @Test
    void handledMaterialsReturnsUnmodifiableView() {
        ContainerHandler handler = new ContainerHandler(
                new CartSessionManager(null, new PluginConfig(null), null));
        assertThrows(UnsupportedOperationException.class, () -> handler.handledMaterials().clear(),
                "材质集合是注册表依据，不得暴露可变内部集合");
    }

    @Test
    void lecternAndChiseledBookshelfAreNotRoutedAsOpenableContainers() {
        // 它们不是 org.bukkit.block.Container，走 openContainer 会永远返回 null（死功能）；
        // 移出白名单后按普通方块处理（可放置/取下，不承诺原版界面）。
        ContainerHandler handler = new ContainerHandler(
                new CartSessionManager(null, new PluginConfig(null), null));
        assertFalse(handler.handledMaterials().contains(Material.LECTERN));
        assertFalse(handler.handledMaterials().contains(Material.CHISELED_BOOKSHELF));
    }
}
