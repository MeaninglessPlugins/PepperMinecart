package org.eu.pcraft.pepperminecart.feature;

import org.bukkit.block.Container;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 各类矿车会话状态：容器实时库存（LiveContainer）、铁砧会话、投掷器冷却
 */
final class CartSessions {

    private final Map<Minecart, LiveContainer> liveContainers = new HashMap<>();
    private final Map<Inventory, Minecart> inventoryOwners = new HashMap<>();
    private final Map<UUID, Minecart> anvilSessions = new HashMap<>();
    private final Map<UUID, Long> dropperCooldowns = new HashMap<>();

    // --- 容器会话 ---
    // getBlockState() 每次调用都会返回新快照，因此打开界面时必须持有同一个 Container 引用
    // （LiveContainer），玩家编辑、投掷器发射、关闭时回写都走这同一个引用。

    Inventory openContainer(Minecart minecart, ItemStack blockItem) {
        LiveContainer live = liveContainers.get(minecart);
        if (live == null) {
            if (!(blockItem.getItemMeta() instanceof BlockStateMeta bsm)) return null;
            if (!(bsm.getBlockState() instanceof Container container)) return null;
            live = new LiveContainer(container, container.getInventory());
            liveContainers.put(minecart, live);
            inventoryOwners.put(live.inventory, minecart);
        }
        return live.inventory;
    }

    Inventory getOpenInventory(Minecart minecart) {
        LiveContainer live = liveContainers.get(minecart);
        return live != null ? live.inventory : null;
    }

    Container getLiveContainer(Minecart minecart) {
        LiveContainer live = liveContainers.get(minecart);
        return live != null ? live.container : null;
    }

    boolean isContainerOpen(Minecart minecart) {
        return liveContainers.containsKey(minecart);
    }

    Set<Minecart> getOpenMinecarts() {
        return new HashSet<>(liveContainers.keySet());
    }

    Minecart getInventoryOwner(Inventory inventory) {
        return inventoryOwners.get(inventory);
    }

    void removeInventoryOwner(Inventory inventory) {
        inventoryOwners.remove(inventory);
    }

    void removeSession(Minecart minecart) {
        LiveContainer live = liveContainers.remove(minecart);
        if (live != null) {
            inventoryOwners.remove(live.inventory);
        }
    }

    // --- 铁砧会话 ---

    void setAnvilSession(UUID playerId, Minecart minecart) {
        anvilSessions.put(playerId, minecart);
    }

    Minecart getAnvilSession(UUID playerId) {
        return anvilSessions.get(playerId);
    }

    void clearAnvilSession(UUID playerId) {
        anvilSessions.remove(playerId);
    }

    void clearAnvilSessions(Minecart minecart) {
        anvilSessions.entrySet().removeIf(entry -> entry.getValue().equals(minecart));
    }

    // --- 投掷器冷却 ---

    boolean consumeDropperCooldown(Minecart minecart, long cooldownTicks) {
        if (cooldownTicks <= 0) return true;
        // 实体年龄单调递增且跨区块重载持久，不受 /time set 影响
        long now = minecart.getTicksLived();
        Long last = dropperCooldowns.get(minecart.getUniqueId());
        if (last != null && now - last < cooldownTicks) return false;
        dropperCooldowns.put(minecart.getUniqueId(), now);
        return true;
    }

    void removeDropperCooldown(Minecart minecart) {
        dropperCooldowns.remove(minecart.getUniqueId());
    }

    private static class LiveContainer {
        final Container container;
        final Inventory inventory;

        LiveContainer(Container container, Inventory inventory) {
            this.container = container;
            this.inventory = inventory;
        }
    }
}
