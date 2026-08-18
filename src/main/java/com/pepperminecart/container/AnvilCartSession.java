package com.pepperminecart.container;

import com.pepperminecart.anvil.AnvilDamageTracker;
import java.util.Map;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 铁砧会话：回收残留 + 耐久判定。
 *
 * <p>耐久判定流程：管理器在 {@code PrepareAnvilEvent} 登记“有效修复结果”标记；点击结果槽后
 * 创建 {@link PendingAnvilTake}，并延迟 1 tick 对比确认真的取走（shift 进满背包不算、
 * 同一次修复只判定一次）。若玩家在确认前关闭界面，则根据点击时确定性的“是否已取走”
 * （普通点击必然取走；shift 点击用背包容量预判）在关闭回调中补判，避免快速关闭跳过耐久。</p>
 */
final class AnvilCartSession extends VanillaCartSession {

    private final JavaPlugin plugin;
    private final AnvilDamageTracker anvilTracker;
    /** 待判定的铁砧界面标记表（管理器持有的弱引用表，键为界面 Inventory）。 */
    private final Map<Inventory, PendingAnvilTake> pendingTakes;

    AnvilCartSession(JavaPlugin plugin, AnvilDamageTracker anvilTracker, Map<Inventory, PendingAnvilTake> pendingTakes,
                     Minecart cart, Inventory top, InventoryView view) {
        super(cart, top, view);
        this.plugin = plugin;
        this.anvilTracker = anvilTracker;
        this.pendingTakes = pendingTakes;
    }

    /** 点击事件回调：结果槽（rawSlot 2）被真实取走时推进铁砧耐久状态机。 */
    void handleClick(Player player, InventoryClickEvent event) {
        if (event.getView().getTopInventory().getType() != InventoryType.ANVIL) {
            return;
        }
        if (event.getRawSlot() != 2) {
            return;
        }
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) {
            return;
        }
        // 非 shift 点击且光标非空时，点击结果槽是 no-op（无法取走），不消耗判定标记；
        // getCursor() 对非玩家 whoClicked 或异常状态可能为 null，先判空避免 NPE
        if (!event.isShiftClick() && event.getView().getCursor() != null
                && !event.getView().getCursor().getType().isAir()) {
            return;
        }

        PendingAnvilTake pending = pendingTakes.get(top);
        if (pending == null || !pending.isPrepared()) {
            return;
        }

        ItemStack clickedResult = top.getItem(2);
        if (clickedResult == null || clickedResult.getType().isAir()) {
            return;
        }

        // 普通点击（光标为空）取走结果是确定行为；shift 点击是否取走取决于玩家背包能否容纳结果。
        // 这里先按确定性规则记录 knownTaken，再延迟 1 tick 对“视图仍打开”的情况做最终校验。
        boolean knownTaken = !event.isShiftClick() || canFitInInventory(player, clickedResult);
        PendingAnvilTake clicked = new PendingAnvilTake(clickedResult, knownTaken);
        pendingTakes.put(top, clicked);

        plugin.getServer().getScheduler().runTask(plugin, () -> confirmTake(player, clicked));
    }

    /**
     * 延迟确认：视图仍打开时对比结果槽；视图已关闭时按点击时记录的 knownTaken 补判。
     * 正常情况下 {@link #onPlayerClose} 已处理关闭场景，这里作为兜底。
     */
    private void confirmTake(Player player, PendingAnvilTake pending) {
        if (pending.isHandled()) {
            return;
        }
        // 延迟 1 tick 期间玩家可能退出：离线后 getOpenInventory() 返回 null，必须先守卫，
        // 否则对 null 解引用会 NPE。退出路径已由 onPlayerClose/onQuit 兜底处理。
        if (!player.isOnline()) {
            pendingTakes.remove(top);
            return;
        }
        InventoryView open = player.getOpenInventory();
        if (open == null) {
            pendingTakes.remove(top);
            return;
        }
        if (open.getTopInventory() == top) {
            ItemStack current = top.getItem(2);
            if (current != null && pending.clickedResult() != null && current.isSimilar(pending.clickedResult())) {
                // 结果仍在原处：shift 进满背包等未真正取走，恢复 prepared 供后续点击再次判定
                pendingTakes.put(top, PendingAnvilTake.prepared());
                return;
            }
            // 已取走
            applyDamage(player, pending, true);
        } else if (pending.knownTaken()) {
            // 视图已关闭但点击时已确定取走（普通点击，或 shift 且背包可容纳）
            applyDamage(player, pending, false);
        } else {
            pendingTakes.remove(top);
        }
    }

    /** 执行耐久判定；closeOnBreak=false 用于关闭回调中，避免在 InventoryCloseEvent 里再次 closeInventory。
     *  markHandled() 为原子单次消费：延迟确认任务与关闭回调两条路径同时到达时，只有先成功置位的
     *  一方执行耐久判定，另一方直接返回（重复/漏判均被收敛）。 */
    private void applyDamage(Player player, PendingAnvilTake pending, boolean closeOnBreak) {
        if (!pending.markHandled()) {
            return; // 已被其他路径消费，本次不再处理
        }
        pendingTakes.remove(top);
        // 铁砧报废时延迟关闭界面，避免在点击事件处理中直接 closeInventory
        if (anvilTracker.onResultTaken(player, cart) && closeOnBreak) {
            player.closeInventory();
        }
    }

    /** 预判 shift 点击结果是否能进入玩家背包（只读模拟，不修改背包）。 */
    private static boolean canFitInInventory(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        int remaining = item.getAmount();
        int maxStack = item.getMaxStackSize();
        for (ItemStack existing : player.getInventory().getStorageContents()) {
            if (remaining <= 0) {
                return true;
            }
            if (existing == null || existing.getType().isAir()) {
                remaining -= maxStack;
            } else if (existing.isSimilar(item) && existing.getAmount() < existing.getMaxStackSize()) {
                remaining -= existing.getMaxStackSize() - existing.getAmount();
            }
        }
        return remaining <= 0;
    }

    @Override
    public void onPlayerClose(Player player) {
        PendingAnvilTake pending = pendingTakes.get(top);
        if (pending != null && !pending.isHandled()) {
            if (pending.isPrepared() || !pending.knownTaken()) {
                // 尚未点击，或 shift 点击未真正取走：不推进耐久
                pendingTakes.remove(top);
            } else {
                // 玩家取走结果后立刻关闭界面：在这里补判，避免延迟任务因视图关闭而跳过
                applyDamage(player, pending, false);
            }
        } else {
            pendingTakes.remove(top);
        }
        super.onPlayerClose(player);
    }

    /** 铁砧“有效结果/已点击待确认”状态。 */
    static final class PendingAnvilTake {

        private final ItemStack clickedResult;
        private final boolean knownTaken;
        private boolean handled;

        static PendingAnvilTake prepared() {
            return new PendingAnvilTake(null, false);
        }

        PendingAnvilTake(ItemStack clickedResult, boolean knownTaken) {
            this.clickedResult = clickedResult;
            this.knownTaken = knownTaken;
        }

        boolean isPrepared() {
            return clickedResult == null;
        }

        ItemStack clickedResult() {
            return clickedResult;
        }

        boolean knownTaken() {
            return knownTaken;
        }

        boolean isHandled() {
            return handled;
        }

        /** 原子单次消费：仅当未处理时置位并返回 true；已被消费则返回 false。
         *  同步化保证跨 tick 延迟任务与关闭回调并发到达时只一方成功。 */
        synchronized boolean markHandled() {
            if (handled) {
                return false;
            }
            handled = true;
            return true;
        }
    }
}
