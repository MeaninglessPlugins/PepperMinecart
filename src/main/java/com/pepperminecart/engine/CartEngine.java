package com.pepperminecart.engine;

import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffOutcome;
import com.pepperminecart.api.TakeOffResult;
import com.pepperminecart.api.VanillaCartSupport;
import com.pepperminecart.config.ContainerPickupPolicy;
import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.container.CartSessionManager;
import com.pepperminecart.delivery.ItemDelivery;
import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.CartTypeRegistry;
import com.pepperminecart.registry.handler.GenericBlockHandler;
import com.pepperminecart.storage.CartData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 受管矿车引擎：维护受管矿车集合（放置/区块加载加入，销毁/卸载/取下移除），
 * 每 tick 调度 onTick、路由移动/乘坐/下车/销毁事件，执行取下编排，处理破坏掉落与显示校验。
 */
public class CartEngine implements Listener {

    private final Plugin plugin;
    private final CartTypeRegistry registry;
    private final PluginConfig config;
    private final Map<UUID, CartContextImpl> contexts = new HashMap<>();
    /** 会话管理器（构造顺序：engine → tracker → sessions 后经 setter 注入） */
    private CartSessionManager sessionManager;
    private BukkitTask tickTask;

    public CartEngine(Plugin plugin, CartTypeRegistry registry, PluginConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
    }

    public void setSessionManager(CartSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void start() {
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        contexts.clear();
    }

    /** 插件启用时扫描已加载区块中的存量受管矿车（ChunkLoadEvent 不会为已加载区块再次触发）。 */
    public void registerLoadedCarts() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                forEachMinecartInChunk(chunk, this::registerManagedCart);
            }
        }
    }

    /** 类型注册表变化后刷新全部已跟踪 context：把停留于旧 handler 的矿车按新注册表重解析。 */
    public void rescanManagedContexts() {
        for (CartContextImpl ctx : new ArrayList<>(contexts.values())) {
            Minecart cart = ctx.getMinecart();
            try {
                if (!CartData.isManaged(cart)) {
                    // PDC 已确认不再受管（被外部清理/损坏恢复）：context 已失效，立即移除，
                    // 避免旧 handler 继续被 tick 或交互路径复用
                    removeCart(cart.getUniqueId());
                    continue;
                }
                CartTypeHandler resolved = resolve(cart);
                if (resolved != null) {
                    // 主动重扫允许重建为通用兜底，确保注销类型后旧 handler 不再继续生效
                    contextForInternal(cart, resolved, true);
                }
            } catch (RuntimeException ex) {
                // 实体已失效/PDC 不可读时跳过本次重扫：保留现有 context，由 tick 的失效清理统一回收，
                // 单个矿车异常不得中断 unregisterCartType 触发的整批重扫
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 重扫受管 context 时 PDC 不可读，已跳过该矿车: " + cart.getUniqueId(), ex);
            }
        }
    }

    // ---- 类型解析 ----

    /**
     * 解析矿车类型：PDC cart-type id 优先，显示方块 material 兜底（兼容第三方显示/老数据）。
     * 对“cart-type 存在但注册表已无对应类型”或“普通矿车显示特殊矿车材质”的情况，
     * 回退到安全通用处理，避免矿车变成无法取下/销毁时丢物品的“死车”。
     */
    public CartTypeHandler resolve(Minecart cart) {
        String id = CartData.getCartType(cart);
        if (id != null) {
            try {
                CartTypeHandler handler = registry.byId(NamespacedKey.fromString(id));
                if (handler != null) {
                    if (handler instanceof VanillaCartSupport && cart.getType() == EntityType.MINECART) {
                        // 普通矿车 + 特殊类型 PDC：转换中间状态（addCart 写入 PDC 后、onPlaced
                        // 完成实体转换前中断，如服务器崩溃/插件禁用）。此时不能路由到
                        // SpecialCartHandler——它按实体类型反查材质与库存，普通矿车取不到容器内容，
                        // 取下/销毁会重建白板物品导致存储物品（含容器内容）丢失。回退通用处理器，
                        // 按存储物品原样归还。
                        return genericFallback();
                    }
                    return handler;
                }
            } catch (IllegalArgumentException ex) {
                // PDC cart-type 被第三方写入非法字符串时记录日志，便于排查“矿车不按预期路由”
                plugin.getLogger().warning("[PepperMinecart] 矿车 PDC cart-type 含非法 NamespacedKey，已走回退解析: "
                        + id + " (" + cart.getUniqueId() + ")");
            }
            // 类型已失效：若实体本身是原版特殊矿车（箱子/漏斗等），仍交给支持它的处理器安全接管；
            // 否则回退通用处理器，至少能按存储物品/原始材质/显示材质取下或掉落，避免数据丢失。
            CartTypeHandler vanilla = resolveVanillaPickup(cart);
            return vanilla != null ? vanilla : genericFallback();
        }
        Material m = new CartBlockDisplay(cart).material();
        if (m != null && m != Material.AIR) {
            CartTypeHandler handler = registry.byMaterial(m);
            if (handler instanceof VanillaCartSupport && cart.getType() == EntityType.MINECART) {
                // 普通矿车显示 CHEST/HOPPER/FURNACE 等特殊矿车材质时，不能路由到 SpecialCartHandler：
                // 它按实体类型反查材质，普通矿车会返回 null，导致取下/销毁丢物品。
                return genericFallback();
            }
            return handler != null ? handler : genericFallback();
        }
        // 显示缺失但仍有存储物品（老数据/第三方清除了显示）：按存储物品类型安全解析
        ItemStack stored = CartData.getItem(cart);
        if (stored != null) {
            CartTypeHandler handler = registry.byMaterial(stored.getType());
            if (handler instanceof VanillaCartSupport && cart.getType() == EntityType.MINECART) {
                return genericFallback();
            }
            return handler != null ? handler : genericFallback();
        }
        return null;
    }

    /** 未知/异常类型的安全兜底：按存储物品、原始材质或显示方块执行通用取下/掉落。 */
    private CartTypeHandler genericFallback() {
        return GenericBlockHandler.INSTANCE;
    }

    public boolean isManaged(Minecart cart) {
        return contexts.containsKey(cart.getUniqueId()) || CartData.isManaged(cart);
    }

    // ---- 原版特殊矿车（外来无插件数据的箱子矿车等） ----

    /** 是否为原版特殊矿车（箱子/漏斗/熔炉/TNT/命令矿车）——按注册表中声明 VanillaCartSupport 的类型识别。 */
    public boolean isVanillaCart(Minecart cart) {
        return vanillaSupportHandler(cart) != null;
    }

    /** 按实体类型反查支持原版特殊矿车的处理器（仅用于取下外来原版矿车）。 */
    public CartTypeHandler resolveVanillaPickup(Minecart cart) {
        return vanillaSupportHandler(cart);
    }

    /** 查找声明支持该矿车实体类型的处理器；单个第三方处理器异常按不支持处理，不污染事件链路。 */
    private CartTypeHandler vanillaSupportHandler(Minecart cart) {
        for (CartTypeHandler handler : registry.all()) {
            if (handler instanceof VanillaCartSupport support) {
                try {
                    if (support.supportsVanillaCart(cart)) {
                        return handler;
                    }
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 矿车类型 " + handler.getId() + " supportsVanillaCart 异常，已跳过: "
                                    + cart.getUniqueId(), ex);
                }
            }
        }
        return null;
    }

    // ---- 上下文管理 ----

    /** 放置流程：写入类型 id、整物品与原始材质，登记上下文。 */
    public CartContextImpl addCart(Minecart cart, CartTypeHandler type, ItemStack placedItem) {
        CartData.setCartType(cart, type.getId().toString());
        CartData.setItem(cart, placedItem);
        CartData.setOriginalMaterial(cart, placedItem.getType());
        CartContextImpl ctx = new CartContextImpl(this, config, cart, type);
        World world = cart.getWorld();
        Location loc = cart.getLocation();
        if (world != null && loc != null) {
            ctx.updateSnapshot(world, loc);
        }
        contexts.put(cart.getUniqueId(), ctx);
        return ctx;
    }

    /** 获取受管上下文；非受管矿车返回 null。注册表类型变化时自动重建 context。
     *  只有已写入插件 PDC 的矿车才会被纳入受管集合；仅有显示方块/第三方数据的矿车
     *  不会被意外收养（临时取下流程请使用 {@link #contextFor}）。 */
    public CartContextImpl contextOrNull(Minecart cart) {
        CartContextImpl ctx = contexts.get(cart.getUniqueId());
        if (ctx == null && !CartData.isManaged(cart)) {
            return null;
        }
        return contextForInternal(cart, resolve(cart));
    }

    /** 事件入口使用的安全解析：PDC 损坏/实体失效时按未受管处理，异常不得逃出事件链。 */
    private CartContextImpl contextOrNullSafe(Minecart cart) {
        try {
            return contextOrNull(cart);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 读取矿车 PDC/解析 context 异常，按未受管处理: "
                            + cart.getUniqueId(), ex);
            return null;
        }
    }

    /** 用已知类型获取/登记上下文（不写 PDC；用于外来原版矿车的取下流程）。 */
    public CartContextImpl contextFor(Minecart cart, CartTypeHandler type) {
        return contextForInternal(cart, type);
    }

    /** 统一上下文获取/刷新入口：已有 context 且类型变化时重建，避免交互路径继续使用旧 handler。 */
    private CartContextImpl contextForInternal(Minecart cart, CartTypeHandler type) {
        return contextForInternal(cart, type, false);
    }

    /**
     * 统一上下文获取/刷新入口。
     *
     * @param forceGenericRebuild 为 true 时，即使解析结果是通用兜底也重建 context（用于注销类型后的主动重扫）；
     *                            为 false 时保留旧 context，避免正常路径中临时解析失败打断正在使用的类型。
     */
    private CartContextImpl contextForInternal(Minecart cart, CartTypeHandler type, boolean forceGenericRebuild) {
        CartContextImpl ctx = contexts.get(cart.getUniqueId());
        if (type == null) {
            // 当前无法解析时：已有 context 保留（避免正在使用的旧类型被临时注销打断），无 context 则返回 null
            return ctx;
        }
        if (ctx == null) {
            ctx = new CartContextImpl(this, config, cart, type);
            World world = cart.getWorld();
            Location loc = cart.getLocation();
            if (world != null && loc != null) {
                ctx.updateSnapshot(world, loc);
            }
            contexts.put(cart.getUniqueId(), ctx);
        } else if (ctx.getType() != type && (type != GenericBlockHandler.INSTANCE || forceGenericRebuild)) {
            // 同一矿车的类型被覆盖/重注册后，旧 context 仍持有旧 handler；这里重建以使用最新类型。
            // 若解析结果只是“未知/失效类型”的通用兜底，则默认保留已有 context，
            // 避免正在使用的旧类型被临时注销时被打断；主动重扫（注销类型）时强制重建为通用兜底。
            // 用对象身份比较：同 id 但不同实例（重注册替换 handler）也应刷新为新实例（EngineStateFixTest）。
            CartContextImpl rebuilt = new CartContextImpl(this, config, cart, type);
            // 迁移旧 context 的运行时状态：掉落位置快照、销毁掉落开关、替换产物引用
            // 不能随重建静默重置（替换产物引用丢失会漏掉放置回滚要回收的替代实体）
            rebuilt.updateSnapshot(ctx.lastWorld(), ctx.lastLocation());
            rebuilt.setDropOnDestroy(ctx.dropOnDestroy());
            rebuilt.inheritReplacementFrom(ctx);
            World world = cart.getWorld();
            Location loc = cart.getLocation();
            if (world != null && loc != null) {
                rebuilt.updateSnapshot(world, loc);
            }
            contexts.put(cart.getUniqueId(), rebuilt);
            ctx = rebuilt;
        }
        return ctx;
    }

    /** 获取受管上下文；非受管矿车抛出异常。 */
    public CartContextImpl context(Minecart cart) {
        CartContextImpl ctx = contextOrNull(cart);
        if (ctx == null) {
            throw new IllegalStateException("矿车不受管理: " + cart.getUniqueId());
        }
        return ctx;
    }

    public void removeCart(Minecart cart) {
        removeCart(cart.getUniqueId());
    }

    public void removeCart(UUID id) {
        contexts.remove(id);
    }

    /** 实体替换后重新绑定跟踪（特殊矿车转换）。 */
    public void rebind(UUID oldId, Minecart newCart) {
        CartContextImpl ctx = contexts.remove(oldId);
        if (ctx != null) {
            contexts.put(newCart.getUniqueId(), ctx);
        }
    }

    // ---- 会话联动 ----

    /** 回写并关闭该矿车仍打开中的容器界面（取下/销毁前调用，防编辑丢失与幽灵界面）。 */
    public void flushCartSessions(Minecart cart) {
        if (sessionManager != null) {
            sessionManager.flushAndClose(cart);
        }
    }

    /** 延迟 1 tick 关闭该矿车全部界面（用于铁砧报废等不能在事件链中同步 close 的场景）。 */
    public void closeCartSessionsLater(Minecart cart) {
        plugin.getServer().getScheduler().runTask(plugin, () -> flushCartSessions(cart));
    }

    /** PDC 管理标记的安全读取：实体已失效/第三方损坏时按未受管处理，不让异常逃出事件链。 */
    private boolean isManagedSafe(Minecart cart) {
        try {
            return CartData.isManaged(cart);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 读取矿车 PDC 管理标记异常，按未受管处理: " + cart.getUniqueId(), ex);
            return false;
        }
    }

    /** 统一异常屏障：单个 handler 回调异常只记录并跳过，不污染事件链路。 */
    private void safeHandler(String action, CartTypeHandler handler, Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 矿车类型 " + handler.getId() + " " + action + " 异常，已跳过该矿车", ex);
        }
    }

    /** 交互回调的异常安全入口（供 InteractionListener 使用）。 */
    public boolean safeInteract(Player player, CartContextImpl ctx) {
        try {
            return ctx.getType().onInteract(player, ctx);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " onInteract 异常，已按未处理交互跳过", ex);
            return false;
        }
    }

    // ---- 取下编排（交互监听器完成前置判定后委托） ----

    /**
     * 执行取下流程：
     * <ol>
     *   <li>回写并关闭该矿车打开中的容器界面（保证取下物品包含最新编辑）；</li>
     *   <li>调用类型 {@code onTakeOff}——返回 {@link TakeOffOutcome#HANDLED} 时处理器已完整接管，直接返回；</li>
     *   <li>先生成特殊矿车的普通替代实体（可回滚），再交付物品；</li>
     *   <li>交付成功后才清除 PDC/显示/移除旧实体——交付失败时矿车保持原样，可安全重试。</li>
     * </ol>
     */
    public void takeOff(Player player, CartContextImpl ctx, TakeOffResult mode) {
        Minecart cart = ctx.getMinecart();
        if (mode == null) {
            // 公开 API 防御：null 策略按“取消本次取下”处理，不进入交付分支；
            // 临时 context（无 PDC）需要移除，常驻受管矿车保留跟踪
            plugin.getLogger().warning("[PepperMinecart] takeOff 收到 null 策略，按取消处理: "
                    + cart.getUniqueId());
            if (!isManagedSafe(cart)) {
                removeCart(cart.getUniqueId());
            }
            return;
        }
        if (mode == TakeOffResult.DISABLED) {
            // 禁止取下：不还原矿车、不交付物品。正常路径由 InteractionListener 前置拦截，
            // 这里防御 API 直接调用（配置为 DISABLED 或容器策略 FORBIDDEN 时不应执行取下流程）。
            // 若调用方刚用 contextFor 登记了临时 context（非 PDC 常驻矿车），取下未发生时应移除，
            // 防止残留受管集合（禁止乘坐/每 tick 回调/销毁误判）；常驻受管矿车（有 PDC）保留跟踪。
            if (!isManagedSafe(cart)) {
                removeCart(cart.getUniqueId());
            }
            return;
        }
        // 提前记录位置快照：即使实体在流程中失效，后续 giveItem/dropItem 仍有可用的掉落位置
        World snapshotWorld = cart.getWorld();
        Location snapshotLoc = cart.getLocation();
        if (snapshotWorld != null && snapshotLoc != null) {
            ctx.updateSnapshot(snapshotWorld, snapshotLoc);
        }
        boolean handled = false;
        boolean committed = false;
        boolean vanillaContentsDropped = false;
        /** 返还物品中原本内嵌的内容是否已被容器 DROP spill 清空（提交阶段不得再 drain 原版库存） */
        boolean itemContentsSpilled = false;
        Minecart replacement = null;
        List<Item> contentDrops = new ArrayList<>();
        try {
            try {
                flushCartSessions(cart);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 取下前回写/关闭矿车界面异常，本次取下已取消: " + cart.getUniqueId(), ex);
                return;
            }

            TakeOffOutcome outcome;
            try {
                outcome = ctx.getType().onTakeOff(player, ctx, mode);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " onTakeOff 异常，本次取下已取消: "
                                + cart.getUniqueId(), ex);
                return;
            }
            if (outcome == null) {
                // 第三方 handler 违反契约返回 null：按 DEFAULT 继续执行引擎默认取下，
                // 但必须留痕，避免静默改变语义
                plugin.getLogger().warning("[PepperMinecart] 矿车类型 " + ctx.getType().getId()
                        + " onTakeOff 返回 null，按 DEFAULT 处理: " + cart.getUniqueId());
                outcome = TakeOffOutcome.DEFAULT;
            }
            if (outcome == TakeOffOutcome.HANDLED) {
                // HANDLED 处理器若未写 PDC（外来原版矿车的临时 context），不得泄漏在受管集合：
                // 否则矿车被永久禁乘/每 tick 回调/销毁误掉落。
                // 注意必须用当前实体（处理器可能已 replaceEntity），不能用进入方法时的旧引用。
                Minecart current = ctx.getMinecart();
                boolean managed;
                try {
                    managed = CartData.isManaged(current);
                } catch (RuntimeException ex) {
                    // 实体已被处理器移除时 PDC 访问会抛异常：按“未写 PDC”处理并解除跟踪，
                    // 不能让异常逃出取下流程/事件链
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] HANDLED 取下判定 PDC 异常，按未受管清理: " + current.getUniqueId(), ex);
                    managed = false;
                }
                if (!managed) {
                    // 处理器若已调用 releaseCart()，context 已被解除跟踪，无需告警；
                    // 只有 context 仍残留时，才说明处理器既未写 PDC 也未清理跟踪。
                    boolean stillTracked = contexts.containsKey(current.getUniqueId());
                    if (stillTracked) {
                        plugin.getLogger().warning("[PepperMinecart] HANDLED 取下未写 PDC 且未清理 context，已移除临时 context: "
                                + current.getUniqueId());
                    }
                    removeCart(current.getUniqueId());
                }
                handled = true;
                return;
            }

            // 提前捕获取下物品（交付成功前不清 PDC，仍可安全读取）
            ItemStack item;
            try {
                item = ctx.getType().getTakeOffItem(ctx);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " getTakeOffItem 异常，本次取下已取消: "
                                + cart.getUniqueId(), ex);
                return;
            }
            if (item == null || item.getType().isAir()) {
                // 拿不到可返还物品时不要清空矿车数据，避免矿车被还原但玩家一无所得；
                // 同时必须移除本次流程登记的 context，防止外来原版矿车残留受管集合
                plugin.getLogger().warning("[PepperMinecart] 取下矿车时未能取得可返还物品，已取消取下: "
                        + cart.getUniqueId());
                return;
            }
            // 在 spill/交付任何步骤修改 item 之前，记录返还物品是否原本内嵌容器内容：
            // 这是提交阶段判断“原版库存该 drain 还是只需清空”的唯一可靠依据
            boolean itemHadEmbeddedContents = containsEmbeddedContents(item);

            Minecart current = ctx.getMinecart();
            Location loc = current.isValid()
                    ? current.getLocation()
                    : (cart.getLocation() != null ? cart.getLocation() : ctx.lastLocation());
            boolean special = current.isValid() && current.getType() != EntityType.MINECART;
            if (special) {
                try {
                    // 先生成替代普通矿车但不移除旧实体；交付失败时可直接移除替代实体回滚
                    replacement = MinecartSwap.spawnReplacement(MinecartSwap.capture(current), RideableMinecart.class);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING, "[PepperMinecart] 还原普通矿车失败，本次取下已取消", ex);
                    return; // 原矿车保持原样（内容未动、物品未交付）
                }
                // 迁移自定义名，避免命名过的特殊矿车取下后名字丢失
                if (current.customName() != null) {
                    replacement.customName(current.customName());
                    replacement.setCustomNameVisible(current.isCustomNameVisible());
                }
                // 若返还物品未内嵌原版库存内容，则必须在交付前先把库存内容原子掉落；
                // 否则交付成功后再发现掉不掉，就会陷入“移除旧车丢内容 / 不移除复制方块”的两难。
                if (current instanceof InventoryHolder holder && !itemHadEmbeddedContents) {
                    org.bukkit.inventory.Inventory inv = holder.getInventory();
                    List<ItemStack> vanillaContents = new ArrayList<>();
                    for (ItemStack content : inv.getContents()) {
                        if (content != null && !content.getType().isAir()) {
                            vanillaContents.add(content);
                        }
                    }
                    if (!vanillaContents.isEmpty()) {
                        ItemDelivery.DropResult drop;
                        try {
                            drop = ItemDelivery.dropAllTracked(loc.getWorld(), loc,
                                    vanillaContents.toArray(ItemStack[]::new));
                        } catch (RuntimeException ex) {
                            // dropAllTracked 已在内部回滚已生成掉落；这里按“本次取下取消”处理，
                            // 与容器 DROP/交付分支保持同样的异常屏障，异常不得逃出事件链。
                            plugin.getLogger().log(Level.WARNING,
                                    "[PepperMinecart] 特殊矿车原版库存内容物掉落异常，本次取下已取消: "
                                            + cart.getUniqueId(), ex);
                            return;
                        }
                        if (drop.result() != ItemDelivery.Result.DELIVERED) {
                            plugin.getLogger().warning("[PepperMinecart] 特殊矿车原版库存内容物掉落失败，本次取下已取消: "
                                    + cart.getUniqueId());
                            return;
                        }
                        contentDrops.addAll(drop.spawned());
                        vanillaContentsDropped = true;
                    }
                }
            }

            // 容器类 + DROP 策略：先掉落内容物并记录已生成实体，后续交付失败时可回滚
            try {
                if (ctx.getType().isContainerPickupControlled(item.getType())
                        && config.containerPickupPolicy() == ContainerPickupPolicy.DROP) {
                    List<Item> spilled = spillContainerContentsTracked(item, loc);
                    if (spilled == null) {
                        plugin.getLogger().warning("[PepperMinecart] 容器内容物掉落失败，本次取下已取消: "
                                + cart.getUniqueId());
                        return;
                    }
                    // 合并而非覆盖：vanilla 预掉落（特殊矿车原版库存）已在 contentDrops 中，
                    // 覆盖会丢失其回滚跟踪，交付失败时残留地面造成复制
                    contentDrops.addAll(spilled);
                    // 若 spill 清空的是返还物品中原本内嵌的内容，记录该状态：
                    // 提交阶段不得再走 drainOrClearVanillaInventory，否则同一批原版库存会被再掉一次
                    if (itemHadEmbeddedContents) {
                        itemContentsSpilled = true;
                    }
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " isContainerPickupControlled 异常，已取消取下: "
                                + cart.getUniqueId(), ex);
                return;
            }

            // 交付物品；失败时回滚内容物掉落并保持矿车原样
            boolean delivered;
            try {
                delivered = ctx.deliverItem(player, item, mode);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 取下物品交付异常: " + cart.getUniqueId(), ex);
                delivered = false;
            }
            if (!delivered) {
                plugin.getLogger().warning("[PepperMinecart] 取下物品交付失败（掉落可能被其他插件取消或位置不可用），矿车数据已保留: "
                        + cart.getUniqueId());
                return;
            }

            // 交付成功，进入不可回滚的提交阶段
            committed = true;
            if (special) {
                if (current.isValid()) {
                    try {
                        // 解除管理并清数据、移除实体，防止 EntityRemoveEvent 把旧矿车误判为销毁而重复掉落
                        removeCart(current);
                        CartData.clear(current, ctx.getType().getId().getNamespace());
                        if (vanillaContentsDropped || itemContentsSpilled) {
                            // 内容物已通过“原版库存预掉落”或“容器 DROP spill”确定性处置，
                            // 这里只需清掉矿车原版库存里的“副本”，绝不能再次 drain 造成重复掉落
                            if (current instanceof InventoryHolder holder) {
                                holder.getInventory().clear();
                            }
                        } else if (!drainOrClearVanillaInventory(item, current, loc)) {
                            // 正常内置特殊矿车不会走到这里；若自定义处理器仍返回 false，至少保留日志
                            plugin.getLogger().warning("[PepperMinecart] 特殊矿车原版库存未能全部掉落，已保留库存交由原版 dropContents 兜底: "
                                    + cart.getUniqueId());
                        }
                        // 迁移乘客（API 直接调用时旧矿车可能仍有乘客）
                        for (Entity passenger : new ArrayList<>(current.getPassengers())) {
                            if (current.removePassenger(passenger) && !replacement.addPassenger(passenger)
                                    && current.isValid()) {
                                current.addPassenger(passenger);
                            }
                        }
                        current.remove();
                    } catch (RuntimeException ex) {
                        // 交付已成功，提交阶段异常不能回滚物品；尽力移除旧实体并继续重定向
                        plugin.getLogger().log(Level.WARNING,
                                "[PepperMinecart] 特殊矿车取下提交阶段异常，已尽力继续清理: " + cart.getUniqueId(), ex);
                        try {
                            if (current.isValid()) {
                                current.remove();
                            }
                        } catch (RuntimeException ignore) {
                            // 旧实体已无法移除时只能记录，避免覆盖原始异常
                        }
                    }
                } else {
                    // 交付已成功但实体在提交前已被移除（如第三方在 ItemSpawnEvent 中 remove）：
                    // 销毁兜底已运行，这里不得再触碰失效实体的 PDC/库存/显示，只需解除跟踪。
                    plugin.getLogger().warning("[PepperMinecart] 特殊矿车取下提交时实体已失效，仅移除跟踪: "
                            + current.getUniqueId());
                    removeCart(current);
                }
                // 上下文重定向到新普通矿车：后续音效/清理落在有效实体上
                ctx.repoint(replacement);
            } else if (current.isValid()) {
                // 已交付成功的清理段也必须隔离异常：清 PDC 与清显示各自独立尝试，
                // 任一步失败都不能让异常逃出事件链，且 context 必须移除
                try {
                    CartData.clear(current, ctx.getType().getId().getNamespace());
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 取下提交阶段清理 PDC 异常，已尽力继续: "
                                    + current.getUniqueId(), ex);
                }
                try {
                    new CartBlockDisplay(current).clear();
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 取下提交阶段清理显示方块异常，已尽力继续: "
                                    + current.getUniqueId(), ex);
                } finally {
                    removeCart(current);
                }
            } else {
                // 交付已成功但实体在提交前已被移除：不得对失效实体调用显示/PDC 清理，
                // 否则 setDisplayBlockData/getPersistentDataContainer 会抛异常逃出事件链。
                // 此时物品已交付，实体数据随实体消失，只需解除跟踪。
                plugin.getLogger().warning("[PepperMinecart] 取下交付成功时矿车实体已失效，仅移除跟踪: "
                        + current.getUniqueId());
                removeCart(current);
            }

            if (config.soundTakeOff() && loc != null && loc.getWorld() != null) {
                try {
                    loc.getWorld().playSound(loc, Sound.BLOCK_STONE_BREAK, 1f, 1f);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 取下音效播放失败: " + cart.getUniqueId(), ex);
                }
            }
        } finally {
            // 未提交时回滚：移除已生成的替代普通矿车与已掉落的内容物
            if (!committed) {
                if (replacement != null && replacement.isValid()) {
                    try {
                        replacement.remove();
                    } catch (RuntimeException ex) {
                        // 单个实体回滚失败不得中断其余回滚：地面内容物与临时 context 仍必须清理
                        plugin.getLogger().log(Level.WARNING,
                                "[PepperMinecart] 回滚替代普通矿车失败，继续回滚内容物: "
                                        + replacement.getUniqueId(), ex);
                    }
                }
                for (Item e : contentDrops) {
                    try {
                        e.remove();
                    } catch (RuntimeException ignored) {
                        // 回滚尽力而为：单个实体已无法移除时继续处理其余实体
                    }
                }
            }
            // 非 HANDLED 路径：只有在矿车数据已清空/临时登记时移除 context。
            // 提前取消且 PDC 仍在（getTakeOffItem 失败等）时保留跟踪，静止矿车也不会失去 tick 回调。
            if (!handled && !committed) {
                Minecart current = ctx.getMinecart();
                boolean managed;
                try {
                    managed = CartData.isManaged(current);
                } catch (RuntimeException ex) {
                    managed = false;
                }
                if (!managed) {
                    removeCart(current.getUniqueId());
                }
            }
        }
    }

    /**
     * 清空容器物品的内容物并全部掉落到矿车位置地面；返回已生成的掉落实体供后续回滚。
     *
     * @return 内容物掉落成功时返回已生成实体列表（可能为空）；无法掉落/被取消时返回 null（已回滚，无残留）
     */
    private static List<Item> spillContainerContentsTracked(ItemStack item, Location loc) {
        if (!(item.getItemMeta() instanceof BlockStateMeta bsm)) {
            return List.of();
        }
        if (!(bsm.getBlockState() instanceof org.bukkit.block.Container container)) {
            return List.of();
        }
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack content : container.getInventory().getContents()) {
            if (content != null && !content.getType().isAir()) {
                contents.add(content);
            }
        }
        if (contents.isEmpty()) {
            return List.of(); // 空容器无需掉落，即使位置不可用也不应取消取下
        }
        if (loc == null || loc.getWorld() == null) {
            return null; // 位置/世界不可用，无法掉落内容物，本次取下应取消
        }
        ItemDelivery.DropResult drop = ItemDelivery.dropAllTracked(loc.getWorld(), loc,
                contents.toArray(ItemStack[]::new));
        if (drop.result() != ItemDelivery.Result.DELIVERED) {
            return null; // dropAllTracked 已在内部回滚已生成实体
        }
        container.getInventory().clear();
        bsm.setBlockState(container);
        item.setItemMeta(bsm);
        return drop.spawned();
    }

    /**
     * 移除原版特殊矿车前显式处置其原版库存内容物（不依赖 Paper 内部 dropContents）：
     * <ul>
     *   <li>若返还物品已内嵌容器内容（BlockStateMeta 的 inventory 非空），说明内容已安全转移到
     *       返还物品，直接清空原版库存（随后 remove() 即便触发原版 dropContents 也读到空库存，不重复掉落）；</li>
     *   <li>否则必须先把原版库存逐格掉落，全部掉落成功才清空。任一掉落被取消或位置不可用时
     *       保留库存不清空，交由原版 remove() 的 dropContents 或后续路径兜底，绝不静默清空。</li>
     * </ul>
     *
     * @return true 表示原版库存已被本方法安全清空
     */
    private static boolean drainOrClearVanillaInventory(ItemStack returnedItem, Minecart cart, Location loc) {
        if (!(cart instanceof InventoryHolder holder)) {
            return true; // 非容器矿车（熔炉/TNT/命令矿车）无原版库存
        }
        org.bukkit.inventory.Inventory inv = holder.getInventory();
        if (containsEmbeddedContents(returnedItem)) {
            inv.clear();
            return true;
        }
        // 未内嵌内容：逐格掉落，全部成功才清空
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack content : inv.getContents()) {
            if (content != null && !content.getType().isAir()) {
                contents.add(content);
            }
        }
        if (ItemDelivery.dropAll(loc.getWorld(), loc, contents.toArray(ItemStack[]::new))
                != ItemDelivery.Result.DELIVERED) {
            return false;
        }
        inv.clear();
        return true;
    }

    /** 返还物品的 BlockStateMeta 容器库存中是否已内嵌内容（非空）。 */
    private static boolean containsEmbeddedContents(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof BlockStateMeta bsm)) {
            return false;
        }
        if (!(bsm.getBlockState() instanceof org.bukkit.block.Container container)) {
            return false;
        }
        for (ItemStack it : container.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    // ---- tick ----

    private void tick() {
        if (contexts.isEmpty()) {
            return;
        }
        // 快照遍历：onTick/失效清理可能触发 removeCart 修改 contexts，直接遍历会抛 CME；
        // 用 ArrayList 快照而非 Map.copyOf（每 tick 全表拷贝时不必要的不可变包装开销）
        for (CartContextImpl ctx : new ArrayList<>(contexts.values())) {
            Minecart cart = ctx.getMinecart();
            if (!cart.isValid() || cart.isDead()) {
                // 实体已失效（未触发 EntityRemoveEvent 的异常路径）：回写/关闭打开中的容器会话，
                // 避免幽灵界面与编辑残留；不在此处掉落物品，防止与实体持久化数据重复
                try {
                    flushCartSessions(cart);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 清理失效矿车会话时异常，已跳过该矿车: " + cart.getUniqueId(), ex);
                }
                removeCart(cart.getUniqueId());
                continue;
            }
            World world = cart.getWorld();
            Location loc = cart.getLocation();
            if (world != null && loc != null) {
                ctx.updateSnapshot(world, loc);
            }
            try {
                ctx.getType().onTick(ctx);
            } catch (RuntimeException ex) {
                // 单个矿车类型的 tick 异常只跳过该矿车：repeating task 抛未捕获异常会被
                // Bukkit 调度器整体取消，导致所有受管矿车的 tick/失效清理停摆直到重启
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " onTick 异常，已跳过该矿车: "
                                + cart.getUniqueId(), ex);
            }
        }
    }

    // ---- 事件路由 ----

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMount(EntityMountEvent e) {
        if (!(e.getMount() instanceof Minecart cart)) {
            return;
        }
        CartContextImpl ctx = contextOrNullSafe(cart);
        if (ctx == null) {
            return;
        }
        boolean allowRiding;
        try {
            allowRiding = ctx.getType().allowRiding();
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " allowRiding 异常，按禁止乘坐处理", ex);
            allowRiding = false;
        }
        if (!allowRiding) {
            e.setCancelled(true);
            return;
        }
        safeHandler("onMount", ctx.getType(), () -> ctx.getType().onMount(e.getEntity(), ctx));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getDismounted() instanceof Minecart cart)) {
            return;
        }
        CartContextImpl ctx = contextOrNullSafe(cart);
        if (ctx == null) {
            return;
        }
        safeHandler("onDismount", ctx.getType(), () -> ctx.getType().onDismount(e.getEntity(), ctx));
    }

    /** 矿车移动（事件驱动，替代位置差分轮询）：分发到 onMove（如投掷器铁轨检测）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (!(e.getVehicle() instanceof Minecart cart)) {
            return;
        }
        CartContextImpl ctx = contextOrNullSafe(cart);
        if (ctx == null) {
            return;
        }
        World world = cart.getWorld();
        Location loc = cart.getLocation();
        if (world != null && loc != null) {
            ctx.updateSnapshot(world, loc);
        }
        try {
            ctx.getType().onMove(ctx);
        } catch (RuntimeException ex) {
            // 与 onTick 一致：单个矿车类型的移动回调异常只跳过该矿车，
            // 避免异常传播污染其他监听器
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " onMove 异常，已跳过该矿车: "
                            + cart.getUniqueId(), ex);
        }
    }

    /**
     * 矿车从世界移除时统一清理（破坏、/kill、插件 remove() 等所有路径）。
     * 区块卸载（UNLOAD）不在此处理——实体仍会保存并重载，由 onChunkUnload 清理跟踪，
     * 会话由 {@link #flushCartSessions} 的调用方（取下流程）处理。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveEvent e) {
        if (e.getCause() == EntityRemoveEvent.Cause.UNLOAD) {
            // 区块卸载：实体仍会保存并重载，但先回写并关闭该矿车仍打开中的容器界面
            // （卸载后实体脱离，无法再安全写回 PDC），并移除 context 防止残留过期引用
            if (e.getEntity() instanceof Minecart cart) {
                try {
                    flushCartSessions(cart);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 矿车卸载时回写/关闭会话异常: " + cart.getUniqueId(), ex);
                } finally {
                    removeCart(cart.getUniqueId());
                }
            }
            return;
        }
        if (!(e.getEntity() instanceof Minecart cart)) {
            return;
        }
        CartContextImpl ctx;
        try {
            ctx = contextOrNull(cart);
        } catch (RuntimeException ex) {
            // 实体已失效时 PDC 访问可能抛异常；不能因此跳过清理
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 销毁事件解析矿车 context 异常，已尽力清理: " + cart.getUniqueId(), ex);
            try {
                flushCartSessions(cart);
            } catch (RuntimeException flushEx) {
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 销毁异常路径关闭会话失败: " + cart.getUniqueId(), flushEx);
            } finally {
                removeCart(cart.getUniqueId());
            }
            return;
        }
        if (ctx == null) {
            return;
        }
        // 提前缓存世界/位置：EntityRemoveEvent 触发时实体可能已不在世界中，
        // 后续 getWorld()/getLocation() 可能返回 null，避免 NPE 或静默丢失掉落
        World world = cart.getWorld();
        Location loc = cart.getLocation();
        ctx.updateSnapshot(world, loc);
        // 先回写并关闭打开中的容器界面，保证掉落物品包含最新编辑
        try {
            flushCartSessions(cart);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 矿车销毁时回写/关闭会话异常: " + cart.getUniqueId(), ex);
        }
        CartTypeHandler type = ctx.getType();
        // 死亡掉落判定：实体可能已脱离世界（getWorld 为 null），此时回退到最后已知世界快照，
        // 避免“判定不到原版掉落”导致插件又掉一份、玩家拿双份。
        World ruleWorld = world != null ? world : ctx.lastWorld();
        boolean vanillaDeathDropped = e.getCause() == EntityRemoveEvent.Cause.DEATH
                && ruleWorld != null && Boolean.TRUE.equals(ruleWorld.getGameRuleValue(GameRule.DO_ENTITY_DROPS));
        // Paper 的 EntityRemoveEvent 在 Entity#setRemoved 内、levelCallback.onRemove 之前触发，
        // 此时实体的 getWorld() 通常仍非 null；而原版 VehicleEntity.destroy 在事件返回后才会
        // 生成特殊矿车物品。因此只要是 DEATH + DO_ENTITY_DROPS=true 的原版特殊矿车，就一律
        // 交给原版掉落，插件只清理跟踪，不补掉方块模板，避免 CHEST/FURNACE/TNT/COMMAND_BLOCK
        // 与原版 CHEST_MINECART/FURNACE_MINECART/... 重复掉落。
        // 创造模式 discard(DEATH) 同样走这里：与原版创造模式破坏行为一致，不额外掉落。
        if (vanillaDeathDropped && isVanillaCart(cart)) {
            removeCart(cart.getUniqueId());
            return;
        }
        try {
            try {
                // 走到这里的受管特殊矿车没有原版死亡掉落（DO_ENTITY_DROPS=false 的死亡、
                // 第三方 remove()、自然消失等）：模板 ItemMeta/内容物由插件确定性掉落；
                // onCartDestroyed 内部会清空原版库存并 setDropOnDestroy(false)，避免默认掉落重复。
                type.onCartDestroyed(ctx);
            } catch (RuntimeException ex) {
                // 单个类型销毁回调异常只跳过该回调，仍走默认掉落与上下文清理，避免 ctx 残留
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 矿车类型 " + type.getId() + " onCartDestroyed 异常，已继续默认掉落逻辑: "
                                + cart.getUniqueId(), ex);
            }
            if (ctx.dropOnDestroy()) {
                ItemStack item = null;
                try {
                    item = type.getTakeOffItem(ctx);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 矿车类型 " + type.getId() + " getTakeOffItem 异常，已跳过掉落: "
                                    + cart.getUniqueId(), ex);
                }
                if (item != null && !item.getType().isAir()) {
                    try {
                        // 统一走 ctx.tryDropItem：实体 world/location 不可用时自动回退到最后已知快照，
                        // 避免 EntityRemoveEvent 阶段实体已失效导致默认掉落静默丢失；掉落被取消时如实记录。
                        if (!ctx.tryDropItem(item)) {
                            plugin.getLogger().warning("[PepperMinecart] 矿车类型 " + type.getId()
                                    + " 掉落物品被取消（ItemSpawnEvent），物品未生成: " + cart.getUniqueId());
                        }
                    } catch (RuntimeException ex) {
                        // 掉落异常（如世界不可用）只记录，不阻断上下文清理
                        plugin.getLogger().log(Level.WARNING,
                                "[PepperMinecart] 矿车类型 " + type.getId() + " 掉落物品异常，已跳过掉落: "
                                        + cart.getUniqueId(), ex);
                    }
                }
            }
        } finally {
            // 无论正常完成/回调异常/掉落异常，都确保不泄漏受管 context
            removeCart(cart.getUniqueId());
        }
    }

    /** 安全遍历区块内矿车：单个矿车处理异常只跳过该矿车，不中断整个区块事件。 */
    private void forEachMinecartInChunk(Chunk chunk, Consumer<Minecart> action) {
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Minecart cart) {
                try {
                    action.accept(cart);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 处理区块矿车异常，已跳过该矿车: " + cart.getUniqueId(), ex);
                }
            }
        }
    }

    /** 登记单个受管矿车：解析 context，并保留 ChunkLoad 的显示一致性处理。 */
    private void registerManagedCart(Minecart cart) {
        if (!CartData.isManaged(cart)) {
            return;
        }
        contextOrNull(cart);
        // 显示校验：仅普通矿车（特殊矿车由实体自身渲染）；存储物品存在但显示缺失 → 恢复。
        // 仅对方块材质恢复显示：非方块 ITEM（数据损坏/API 写入异常数据）直接跳过，
        // 避免 setDisplayBlockData(createBlockData) 抛 IllegalArgumentException 打断区块遍历
        if (cart.getType() == EntityType.MINECART) {
            ItemStack item = CartData.getItem(cart);
            if (item != null && item.getType().isBlock() && !new CartBlockDisplay(cart).has()) {
                try {
                    new CartBlockDisplay(cart).set(item.getType());
                    // 显示恢复后重新解析并登记上下文（老数据可能因显示缺失未在首次解析时登记）
                    contextOrNull(cart);
                } catch (RuntimeException ex) {
                    // 显示恢复失败（如数据异常）：矿车仍按 PDC 保持受管（tick/销毁兜底仍生效），
                    // 仅记录并跳过显示恢复，不让异常扩散到整个区块遍历
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 恢复矿车显示方块失败，已跳过: " + cart.getUniqueId(), ex);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        forEachMinecartInChunk(e.getChunk(), this::registerManagedCart);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent e) {
        forEachMinecartInChunk(e.getChunk(), cart -> {
            // 回写并关闭该矿车仍打开中的容器界面（卸载后实体脱离，无法再安全写回）；
            // 移除上下文跟踪（投掷器冷却存 PDC，随实体保存，无需在此清理）
            try {
                flushCartSessions(cart);
            } finally {
                removeCart(cart.getUniqueId());
            }
        });
    }
}
