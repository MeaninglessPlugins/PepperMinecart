package com.pepperminecart.container;

import com.pepperminecart.anvil.AnvilDamageTracker;
import com.pepperminecart.config.PluginConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.bukkit.Bukkit;
import org.bukkit.block.Container;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 矿车界面会话管理器：登记/查找/关闭玩家与矿车绑定的界面会话，并把界面事件
 * （铁砧准备/点击、界面关闭）分派给对应会话类型。
 *
 * <p>会话类型见 {@link CartSession} 子类；事件分派不再依赖 Kind 枚举，改用多态。</p>
 */
public class CartSessionManager implements Listener {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final AnvilDamageTracker anvilTracker;
    /** 玩家 UUID → 其打开中的矿车会话。 */
    private final Map<UUID, CartSession> open = new HashMap<>();
    /** 待判定的矿车铁砧界面：PrepareAnvilEvent 计算有效结果时登记，点击结果槽取走时消耗。弱引用键防泄漏。 */
    private final Map<Inventory, AnvilCartSession.PendingAnvilTake> pendingAnvilTakes = new WeakHashMap<>();

    public CartSessionManager(JavaPlugin plugin, PluginConfig config, AnvilDamageTracker anvilTracker) {
        this.plugin = plugin;
        this.config = config;
        this.anvilTracker = anvilTracker;
    }

    /**
     * 打开容器类虚拟库存：使用物品 BlockStateMeta 内 Container 的同一个实时库存引用，
     * 编辑即时生效；返回 null 表示物品不是容器（调用方应视为不可交互）。
     */
    public Inventory openContainer(Player player, Minecart cart, ItemStack blockItem) {
        if (blockItem == null) {
            return null;
        }
        if (!(blockItem.getItemMeta() instanceof BlockStateMeta bsm)) {
            return null;
        }
        if (!(bsm.getBlockState() instanceof Container container)) {
            return null;
        }
        // 同一矿车的容器会话复用同一实时容器引用（同引用模型）：多玩家共享同一份快照编辑，
        // 避免各持独立快照、关闭时互相覆盖造成数据丢失（此前为 per-player 独立快照）
        CartSession existing = findSession(cart);
        if (existing instanceof ContainerCartSession containerSession) {
            // 先开界面再登记：openInventory 会同步触发旧视图关闭（onClose → open.remove），
            // 若先登记会被旧视图的关闭回调误删，导致本会话后续关闭不再回写
            InventoryView view = player.openInventory(containerSession.top());
            if (view == null) {
                return null; // 打开被取消时不要登记会话，避免泄漏与后续 NPE
            }
            open.put(player.getUniqueId(), containerSession);
            return containerSession.top();
        }
        Inventory inv = container.getInventory();
        InventoryView view = player.openInventory(inv);
        if (view == null) {
            return null; // 打开被取消时不要登记会话，避免泄漏与后续 NPE
        }
        open.put(player.getUniqueId(), new ContainerCartSession(cart, inv, view, bsm, container));
        return inv;
    }

    /** 登记一个已打开的原版虚拟界面（工作台/附魔台等）。 */
    public void openVanillaView(Player player, Minecart cart, InventoryView view) {
        open.put(player.getUniqueId(), new VanillaCartSession(cart, view.getTopInventory(), view));
    }

    /** 登记铁砧界面。 */
    public void openAnvilView(Player player, Minecart cart, InventoryView view) {
        open.put(player.getUniqueId(),
                new AnvilCartSession(plugin, anvilTracker, pendingAnvilTakes, cart, view.getTopInventory(), view));
    }

    /** 返回该矿车当前打开中容器会话的实时容器引用（同引用回写）；未打开或非容器会话返回 null。 */
    public Container liveContainer(Minecart cart) {
        CartSession s = findSession(cart);
        return s != null ? s.liveContainer() : null;
    }

    /** 把该矿车打开中的容器实时库存回写存储物品（不关闭界面）。 */
    public void flushContainer(Minecart cart) {
        CartSession s = findSession(cart);
        if (s != null) {
            s.flush();
        }
    }

    /** 回写并关闭该矿车仍打开中的界面（取下/销毁前调用，防编辑丢失与幽灵界面）。
     *  工作台/铁砧等原版虚拟界面每个玩家有独立 top Inventory/session，必须遍历全部会话关闭。
     *  先移除登记再手动 onPlayerClose，保证同一会话只回写一次（closeInventory 同步触发的
     *  onClose 不会再处理该会话）。 */
    public void flushAndClose(Minecart cart) {
        UUID id = cart.getUniqueId();
        for (CartSession s : new ArrayList<>(open.values())) {
            if (s.cart().getUniqueId().equals(id)) {
                for (HumanEntity viewer : new ArrayList<>(s.top().getViewers())) {
                    if (open.remove(viewer.getUniqueId()) == s && viewer instanceof Player player) {
                        try {
                            s.onPlayerClose(player);
                        } catch (RuntimeException ex) {
                            plugin.getLogger().log(java.util.logging.Level.WARNING,
                                    "[PepperMinecart] 关闭矿车界面时处理异常: " + viewer.getUniqueId(), ex);
                        }
                    }
                    try {
                        viewer.closeInventory();
                    } catch (RuntimeException closeEx) {
                        // 单个 viewer 关闭视图异常不得中断循环，否则剩余 viewer 的会话回写会被跳过
                        // （与 closeAll 的守卫保持一致）。
                        plugin.getLogger().log(java.util.logging.Level.WARNING,
                                "[PepperMinecart] 关闭矿车界面视图异常: " + viewer.getUniqueId(), closeEx);
                    }
                }
            }
        }
    }

    /** 按矿车 UUID 查找会话（打开中的会话数量极少，线性扫描即可；UUID 比较避免实体引用漂移）。 */
    private CartSession findSession(Minecart cart) {
        UUID id = cart.getUniqueId();
        for (CartSession s : open.values()) {
            if (s.cart().getUniqueId().equals(id)) {
                return s;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!config.anvilDamageEnabled()) {
            return;
        }
        ItemStack result = event.getResult();
        // 结果被清空时同步解除待判定标记；有有效结果时再判定是否为矿车铁砧
        if (result == null || result.getType().isAir()) {
            pendingAnvilTakes.remove(event.getInventory());
            return;
        }
        // 仅矿车铁砧启用：界面 viewer 中任一玩家持有矿车铁砧会话
        boolean cartAnvil = event.getViewers().stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .anyMatch(player -> open.get(player.getUniqueId()) instanceof AnvilCartSession);
        if (!cartAnvil) {
            return;
        }
        pendingAnvilTakes.put(event.getInventory(), AnvilCartSession.PendingAnvilTake.prepared());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        CartSession s = open.get(player.getUniqueId());
        if (s instanceof AnvilCartSession anvil) {
            anvil.handleClick(player, event);
        }
        // 容器会话：同引用编辑即时生效，无需逐次回写
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        CartSession s = open.remove(player.getUniqueId());
        if (s == null) {
            return;
        }
        try {
            s.onPlayerClose(player);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "[PepperMinecart] 玩家关闭矿车界面时处理异常: " + player.getUniqueId(), ex);
        }
    }

    /** 玩家退出时主动清理其矿车会话，避免离线后残留虚拟界面物品。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        CartSession s = open.remove(player.getUniqueId());
        if (s == null) {
            return;
        }
        try {
            s.onPlayerClose(player);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "[PepperMinecart] 玩家退出时关闭矿车界面异常: " + player.getUniqueId(), ex);
        }
    }

    /** 服务器关闭/插件卸载时兜底保存与回收。 */
    public void closeAll() {
        // 快照迭代：closeInventory 会同步触发 onClose 修改 open（移除该玩家条目），
        // 直接遍历 entrySet 会抛 ConcurrentModificationException
        for (Map.Entry<UUID, CartSession> entry : new ArrayList<>(open.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            CartSession s = entry.getValue();
            if (player != null && player.isOnline()) {
                // 先移除再手动 onPlayerClose：closeInventory 触发的 onClose 不会再处理同一会话，
                // 避免重复 flush / 重复音效
                open.remove(entry.getKey());
                try {
                    s.onPlayerClose(player);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "[PepperMinecart] 关闭玩家矿车界面异常: " + entry.getKey(), ex);
                } finally {
                    // 即使 onPlayerClose 失败也要关闭视图，避免服务端关闭时残留幽灵界面；
                    // 关闭视图本身异常也不能中断循环——否则剩余会话的 onPlayerClose 回写被跳过
                    try {
                        player.closeInventory();
                    } catch (RuntimeException closeEx) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING,
                                "[PepperMinecart] 关闭玩家矿车界面视图异常: " + entry.getKey(), closeEx);
                    }
                }
            } else {
                // 玩家已离线（崩溃/关服顺序导致 onQuit 未触发）：会话仍必须回写/回收，
                // 不能让容器编辑静默丢失或虚拟界面物品残留在已关闭视图中
                open.remove(entry.getKey());
                try {
                    s.closeWithoutPlayer();
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "[PepperMinecart] 离线会话兜底关闭异常: " + entry.getKey(), ex);
                }
            }
        }
        open.clear();
    }
}
