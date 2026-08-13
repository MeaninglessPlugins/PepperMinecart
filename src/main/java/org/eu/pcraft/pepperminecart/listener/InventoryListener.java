package org.eu.pcraft.pepperminecart.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.eu.pcraft.pepperminecart.PepperMinecart;
import org.eu.pcraft.pepperminecart.service.MinecartService;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 库存事件：铁砧结果槽取走、矿车容器界面关闭
 * <p>
 * 铁砧损耗的判定由 PrepareAnvilEvent 驱动：只有当铁砧计算出了有效修复结果（且属于矿车铁砧）时
 * 才登记"待判定标记"，点击结果槽时消耗标记并只判定一次。普通方块铁砧、无效点击、同一次修复的
 * 重复点击都不会触发判定。
 */
public class InventoryListener implements Listener {

    private final PepperMinecart plugin;
    private final MinecartService service;

    /**
     * 待判定的矿车铁砧界面：PrepareAnvilEvent 计算有效结果时登记，点击结果槽取走时消耗。
     * 弱引用键保证玩家直接关闭/断线时界面被回收、标记随之消失（配合关闭时显式清理）。
     */
    private final Map<Inventory, Boolean> pendingAnvilTakes = new WeakHashMap<>();

    public InventoryListener(PepperMinecart plugin, MinecartService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler
    public void onCloseInv(InventoryCloseEvent event) {
        // 关闭界面时清理铁砧/工作站会话与待判定标记
        service.clearAnvilSession(event.getPlayer().getUniqueId());
        service.clearWorkstationSession(event.getPlayer().getUniqueId());
        if (event.getInventory().getType() == InventoryType.ANVIL) {
            pendingAnvilTakes.remove(event.getInventory());
        }

        // 当最后一个人关闭矿车容器界面时，保存并清理会话
        if (event.getInventory().getViewers().size() <= 1) {
            service.handleContainerClosed(event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!plugin.getMainConfig().isAnvilDamageEnabled()) return;
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            // 结果被清空（输入撤走/重摆）：同步解除待判定标记
            pendingAnvilTakes.remove(event.getInventory());
            return;
        }
        // 仅矿车铁砧启用：界面 viewer 中任一玩家持有矿车铁砧会话
        boolean minecartAnvil = event.getViewers().stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .anyMatch(player -> service.hasAnvilSession(player.getUniqueId()));
        if (!minecartAnvil) return;
        pendingAnvilTakes.put(event.getInventory(), Boolean.TRUE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // 仅处理铁砧结果槽（raw slot 2）取出
        if (event.getView().getTopInventory().getType() != InventoryType.ANVIL) return;
        if (event.getRawSlot() != 2) return;
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) return;
        // 铁砧层面：仅当 PrepareAnvilEvent 判定过"有效待取修复"时触发；消耗标记保证同一次修复只判定一次
        if (pendingAnvilTakes.remove(event.getView().getTopInventory()) == null) return;

        // 铁砧报废时延迟一 tick 关闭界面，避免在点击事件处理中直接 closeInventory
        if (service.handleAnvilUse(player, plugin.getMainConfig())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
        }
    }
}
