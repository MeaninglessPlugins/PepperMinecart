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
 * <p>耐久判定流程：管理器在 {@code PrepareAnvilEvent} 登记"有效修复结果"标记（本类 {@link #handlePrepare}），
 * 点击结果槽后延迟 1 tick 对比确认真的取走（shift 进满背包不算、同一次修复只判定一次），
 * 确认后交给 {@link AnvilDamageTracker} 推进状态机。</p>
 */
final class AnvilCartSession extends VanillaCartSession {

    private final JavaPlugin plugin;
    private final AnvilDamageTracker anvilTracker;
    /** 待判定的铁砧界面标记表（管理器持有的弱引用表，键为界面 Inventory）。 */
    private final Map<Inventory, Boolean> pendingTakes;

    AnvilCartSession(JavaPlugin plugin, AnvilDamageTracker anvilTracker, Map<Inventory, Boolean> pendingTakes,
                     Minecart cart, Inventory top, InventoryView view) {
        super(cart, top, view);
        this.plugin = plugin;
        this.anvilTracker = anvilTracker;
        this.pendingTakes = pendingTakes;
    }

    /** PrepareAnvilEvent 回调：结果有效时登记待判定标记，结果被清空时解除。 */
    void handlePrepare(ItemStack result) {
        if (result == null || result.getType().isAir()) {
            // 结果被清空（输入撤走/重摆）：同步解除待判定标记
            pendingTakes.remove(top);
            return;
        }
        pendingTakes.put(top, Boolean.TRUE);
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
        // 非 shift 点击且光标非空时，点击结果槽是 no-op（无法取走），不消耗判定标记
        if (!event.isShiftClick() && !event.getView().getCursor().getType().isAir()) {
            return;
        }
        if (pendingTakes.remove(top) == null) {
            return;
        }
        // 记录点击时的结果快照，延迟 1 tick 后与结果槽对比确认真的被取走：
        // 结果仍是同一物品 = 未取走（如 shift 进已满背包），恢复标记，避免后续真实取走绕过判定
        ItemStack clickedResult = top.getItem(2);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // 视图已关闭（玩家取走结果后立即关闭界面）：不再对已关闭的 Inventory 做判定，
            // 避免读过期/无效库存导致耐久误判
            if (player.getOpenInventory().getTopInventory() != top) {
                return;
            }
            ItemStack current = top.getItem(2);
            if (current != null && clickedResult != null && current.isSimilar(clickedResult)) {
                pendingTakes.put(top, Boolean.TRUE);
                return;
            }
            // 铁砧报废时延迟关闭界面，避免在点击事件处理中直接 closeInventory
            if (anvilTracker.onResultTaken(player, cart)) {
                player.closeInventory();
            }
        });
    }

    @Override
    public void onPlayerClose(Player player) {
        pendingTakes.remove(top);
        super.onPlayerClose(player);
    }
}
