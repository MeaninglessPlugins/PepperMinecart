package com.pepperminecart.registry.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Material;
import org.bukkit.block.Dispenser;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.inventory.meta.BlockStateMetaMock;

/** 发射器容器访问抽象的契约：读库存、持久化回调、补偿恢复槽位。 */
class DispenserContainerAccessTest {

    @BeforeAll
    static void startServer() {
        MockBukkit.mock();
    }

    @AfterAll
    static void stopServer() {
        MockBukkit.unmock();
    }

    @Test
    void inventoryIsTheDispensersOwnInventory() {
        Dispenser d = newDispenser();
        DispenserContainerAccess access = new DispenserContainerAccess(d, () -> { });

        assertSame(d.getInventory(), access.inventory());
    }

    @Test
    void persistInvokesWriteBackCallback() {
        Dispenser d = newDispenser();
        AtomicBoolean persisted = new AtomicBoolean();
        DispenserContainerAccess access = new DispenserContainerAccess(d, () -> persisted.set(true));

        access.persist();

        assertTrue(persisted.get(), "persist 必须触发回写回调");
    }

    @Test
    void restoreSlotPutsOriginalBackAndPersists() {
        Dispenser d = newDispenser();
        AtomicBoolean persisted = new AtomicBoolean();
        DispenserContainerAccess access = new DispenserContainerAccess(d, () -> persisted.set(true));
        ItemStack original = new ItemStack(Material.DIAMOND, 1);

        access.restoreSlot(0, original);

        ItemStack restored = d.getInventory().getItem(0);
        assertEquals(Material.DIAMOND, restored.getType());
        assertEquals(1, restored.getAmount(), "补偿必须恢复扣减前的完整数量");
        assertTrue(persisted.get(), "补偿恢复后必须持久化");
    }

    private static Dispenser newDispenser() {
        BlockStateMeta meta = new BlockStateMetaMock(Material.DISPENSER);
        return (Dispenser) meta.getBlockState();
    }
}