package com.pepperminecart.registry.handler;

import org.bukkit.block.Dispenser;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 发射器矿车的容器访问抽象：把“实时会话容器”与“PDC 反序列化快照”两条路径收敛为
 * 同一个三操作接口——读库存、持久化、补偿恢复槽位。
 *
 * <p>持久化动作由调用方注入：界面打开中回写会话实时容器，否则写回 PDC。
 * 这样 Paper 版本间 {@code Inventory#getItem} 是否返回副本的差异只需在调用方
 * 处理一次，扣减与补偿逻辑不再分叉。</p>
 */
final class DispenserContainerAccess {

    private final Dispenser dispenser;
    private final Runnable persist;

    DispenserContainerAccess(Dispenser dispenser, Runnable persist) {
        this.dispenser = dispenser;
        this.persist = persist;
    }

    Inventory inventory() {
        return dispenser.getInventory();
    }

    /** 把当前容器状态持久化回存储（live 会话回写 / PDC 写入）。 */
    void persist() {
        persist.run();
    }

    /** 仅恢复内存槽位（不持久化）：persist 失败中止发射时使用，防止界面可见状态被扣空。 */
    void restoreInMemory(int slot, ItemStack original) {
        dispenser.getInventory().setItem(slot, original);
    }

    /** 补偿：把扣减前的槽位内容放回并持久化（仅在实体仍有效时调用）。 */
    void restoreSlot(int slot, ItemStack original) {
        restoreInMemory(slot, original);
        persist.run();
    }
}
