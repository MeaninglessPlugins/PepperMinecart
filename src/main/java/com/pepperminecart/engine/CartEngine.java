package com.pepperminecart.engine;

import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffOutcome;
import com.pepperminecart.api.TakeOffResult;
import com.pepperminecart.api.VanillaCartSupport;
import com.pepperminecart.config.ContainerPickupPolicy;
import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.container.CartSessionManager;
import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.CartTypeRegistry;
import com.pepperminecart.storage.CartData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
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

    // ---- 类型解析 ----

    /** 解析矿车类型：PDC cart-type id 优先，显示方块 material 兜底（兼容第三方显示/老数据）。 */
    public CartTypeHandler resolve(Minecart cart) {
        String id = CartData.getCartType(cart);
        if (id != null) {
            try {
                CartTypeHandler handler = registry.byId(NamespacedKey.fromString(id));
                if (handler != null) {
                    return handler;
                }
            } catch (IllegalArgumentException ignored) {
            }
            // cart-type 存在但注册表已无对应类型（如第三方注销/老数据失效）：
            // 不再按显示材质兜底，避免静默重路由到拥有该材质的其它类型，
            // 导致取下/销毁走错逻辑（如 CHEST 显示 → SpecialCartHandler.getTakeOffItem 返回 null 丢物品）
            return null;
        }
        Material m = new CartBlockDisplay(cart).material();
        if (m != null && m != Material.AIR) {
            return registry.byMaterial(m);
        }
        return null;
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

    /** 查找声明支持该矿车实体类型的处理器；无则 null。 */
    private CartTypeHandler vanillaSupportHandler(Minecart cart) {
        for (CartTypeHandler handler : registry.all()) {
            if (handler instanceof VanillaCartSupport support && support.supportsVanillaCart(cart)) {
                return handler;
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
        contexts.put(cart.getUniqueId(), ctx);
        return ctx;
    }

    /** 获取受管上下文；非受管矿车返回 null。 */
    public CartContextImpl contextOrNull(Minecart cart) {
        CartContextImpl ctx = contexts.get(cart.getUniqueId());
        if (ctx == null) {
            CartTypeHandler type = resolve(cart);
            if (type == null) {
                return null;
            }
            ctx = new CartContextImpl(this, config, cart, type);
            contexts.put(cart.getUniqueId(), ctx);
        }
        return ctx;
    }

    /** 用已知类型获取/登记上下文（不写 PDC；用于外来原版矿车的取下流程）。 */
    public CartContextImpl contextFor(Minecart cart, CartTypeHandler type) {
        CartContextImpl ctx = contexts.get(cart.getUniqueId());
        if (ctx == null) {
            ctx = new CartContextImpl(this, config, cart, type);
            contexts.put(cart.getUniqueId(), ctx);
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

    // ---- 取下编排（交互监听器完成前置判定后委托） ----

    /**
     * 执行取下流程：
     * <ol>
     *   <li>回写并关闭该矿车打开中的容器界面（保证取下物品包含最新编辑）；</li>
     *   <li>调用类型 {@code onTakeOff}——返回 {@link TakeOffOutcome#HANDLED} 时处理器已完整接管，直接返回；</li>
     *   <li>特殊矿车还原为普通矿车（先生成替代实体再移除旧实体，失败回滚）；
     *       普通矿车清除显示与业务数据并解除管理；</li>
     *   <li>按取下策略分发物品：容器类 + DROP 策略先清空内容物落地，其余交给
     *       {@code giveItem}（背包优先、溢出落地）。</li>
     * </ol>
     */
    public void takeOff(Player player, CartContextImpl ctx, TakeOffResult mode) {
        Minecart cart = ctx.getMinecart();
        flushCartSessions(cart);

        TakeOffOutcome outcome = ctx.getType().onTakeOff(player, ctx, mode);
        if (outcome == TakeOffOutcome.HANDLED) {
            return;
        }

        // 提前捕获取下物品（随后清 PDC/移除实体后将无法读取原始数据）
        ItemStack item = ctx.getType().getTakeOffItem(ctx);

        Minecart current = ctx.getMinecart();
        Location loc = current.isValid() ? current.getLocation() : cart.getLocation();
        if (current.isValid()) {
            if (current.getType() != EntityType.MINECART) {
                // 特殊矿车 → 还原为普通矿车
                RideableMinecart normal;
                try {
                    // 先成功生成替代矿车再移除原矿车：避免生成被其它插件取消时旧矿车已删、内容全损
                    normal = MinecartSwap.spawnReplacement(MinecartSwap.capture(current), RideableMinecart.class);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING, "[PepperMinecart] 还原普通矿车失败，本次取下已取消", ex);
                    // 移除本次流程登记的上下文，避免外来原版矿车残留受管集合
                    removeCart(current);
                    return; // 原矿车保持原样（内容未动、物品未交付）
                }
                // 再解除管理并清数据、移除实体，防止 EntityRemoveEvent 把旧矿车误判为销毁而重复掉落
                removeCart(current);
                CartData.clear(current);
                current.remove();
                // 上下文重定向到新普通矿车：后续 giveItem/dropItem 落在有效实体上（旧实体已移除）
                ctx.repoint(normal);
            } else {
                new CartBlockDisplay(current).clear();
                CartData.clear(current);
                removeCart(current);
            }
        }

        if (item != null && !item.getType().isAir()) {
            // 容器类 + DROP 策略：内容物全部掉落地面，容器物品清空后按 take-off-mode 处理
            if (ctx.getType().isContainerPickupControlled(item.getType())
                    && config.containerPickupPolicy() == ContainerPickupPolicy.DROP) {
                spillContainerContents(item, loc);
            }
            // 交给 giveItem 按取下策略统一分发（背包优先、溢出落地；DROP 直接落地）
            ctx.giveItem(player, item);
        }

        if (config.soundTakeOff()) {
            loc.getWorld().playSound(loc, Sound.BLOCK_STONE_BREAK, 1f, 1f);
        }
    }

    /** 清空容器物品的内容物并全部掉落到矿车位置地面。 */
    private static void spillContainerContents(ItemStack item, Location loc) {
        if (!(item.getItemMeta() instanceof BlockStateMeta bsm)) {
            return;
        }
        if (!(bsm.getBlockState() instanceof org.bukkit.block.Container container)) {
            return;
        }
        for (ItemStack content : container.getInventory().getContents()) {
            if (content != null && !content.getType().isAir()) {
                loc.getWorld().dropItemNaturally(loc, content);
            }
        }
        container.getInventory().clear();
        bsm.setBlockState(container);
        item.setItemMeta(bsm);
    }

    // ---- tick ----

    private void tick() {
        if (contexts.isEmpty()) {
            return;
        }
        for (CartContextImpl ctx : Map.copyOf(contexts).values()) {
            Minecart cart = ctx.getMinecart();
            if (!cart.isValid() || cart.isDead()) {
                // 实体已失效（未触发 EntityRemoveEvent 的异常路径）：回写/关闭打开中的容器会话，
                // 避免幽灵界面与编辑残留；不在此处掉落物品，防止与实体持久化数据重复
                flushCartSessions(cart);
                removeCart(cart.getUniqueId());
                continue;
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
        CartContextImpl ctx = contextOrNull(cart);
        if (ctx == null) {
            return;
        }
        if (!ctx.getType().allowRiding()) {
            e.setCancelled(true);
            return;
        }
        ctx.getType().onMount(e.getEntity(), ctx);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getDismounted() instanceof Minecart cart)) {
            return;
        }
        CartContextImpl ctx = contextOrNull(cart);
        if (ctx == null) {
            return;
        }
        ctx.getType().onDismount(e.getEntity(), ctx);
    }

    /** 矿车移动（事件驱动，替代位置差分轮询）：分发到 onMove（如投掷器铁轨检测）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (!(e.getVehicle() instanceof Minecart cart)) {
            return;
        }
        CartContextImpl ctx = contexts.get(cart.getUniqueId());
        if (ctx == null) {
            return;
        }
        ctx.getType().onMove(ctx);
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
            // （卸载后实体脱离，无法再安全写回 PDC）
            if (e.getEntity() instanceof Minecart cart) {
                flushCartSessions(cart);
            }
            return;
        }
        if (!(e.getEntity() instanceof Minecart cart)) {
            return;
        }
        CartContextImpl ctx = contextOrNull(cart);
        if (ctx == null) {
            return;
        }
        // 先回写并关闭打开中的容器界面，保证掉落物品包含最新编辑
        flushCartSessions(cart);
        CartTypeHandler type = ctx.getType();
        // 原版特殊矿车（箱子/漏斗/熔炉/TNT/命令）的实体死亡路径（DEATH 且实体掉落开启）已由服务端
        // 掉落矿车 + 方块本体 + 内容物（并清空原版库存），跳过处理器补掉逻辑避免重复掉落；
        // 第三方 remove()/DISCARD 等无死亡路径仍需 onCartDestroyed 兜底
        boolean vanillaDeathDropped = e.getCause() == EntityRemoveEvent.Cause.DEATH
                && Boolean.TRUE.equals(cart.getWorld().getGameRuleValue(GameRule.DO_ENTITY_DROPS));
        if (vanillaDeathDropped && isVanillaCart(cart)) {
            ctx.setDropOnDestroy(false);
            removeCart(cart.getUniqueId());
            return;
        }
        type.onCartDestroyed(ctx);
        if (ctx.dropOnDestroy()) {
            ItemStack item = type.getTakeOffItem(ctx);
            if (item != null && !item.getType().isAir()) {
                cart.getWorld().dropItemNaturally(cart.getLocation(), item);
            }
        }
        removeCart(cart.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        for (Entity entity : e.getChunk().getEntities()) {
            if (!(entity instanceof Minecart cart)) {
                continue;
            }
            if (!CartData.isManaged(cart)) {
                continue;
            }
            contextOrNull(cart);
            // 显示校验：仅普通矿车（特殊矿车由实体自身渲染）；存储物品存在但显示缺失（如第三方清除）→ 恢复
            if (cart.getType() == EntityType.MINECART) {
                ItemStack item = CartData.getItem(cart);
                if (item != null && !new CartBlockDisplay(cart).has()) {
                    new CartBlockDisplay(cart).set(item.getType());
                    // 显示恢复后重新解析并登记上下文（老数据可能因显示缺失未在首次解析时登记）
                    contextOrNull(cart);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        for (Entity entity : e.getChunk().getEntities()) {
            if (entity instanceof Minecart cart) {
                // 回写并关闭该矿车仍打开中的容器界面（卸载后实体脱离，无法再安全写回）；
                // 移除上下文跟踪（投掷器冷却存 PDC，随实体保存，无需在此清理）
                flushCartSessions(cart);
                removeCart(cart.getUniqueId());
            }
        }
    }
}
