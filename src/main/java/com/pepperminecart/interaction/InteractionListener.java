package com.pepperminecart.interaction;

import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffResult;
import com.pepperminecart.api.event.CartBlockPlaceEvent;
import com.pepperminecart.api.event.CartBlockTakeOffEvent;
import com.pepperminecart.config.ContainerPickupPolicy;
import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.cooldown.InteractionCooldown;
import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.engine.CartEngine;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.CartTypeRegistry;
import com.pepperminecart.registry.handler.GenericBlockHandler;
import com.pepperminecart.storage.CartData;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 右键矿车交互入口（状态机）：
 * <ul>
 *   <li>空普通矿车 + 潜行 + 手持允许方块 → 放置</li>
 *   <li>有方块矿车 + 潜行 → 取下（按配置策略）</li>
 *   <li>有方块矿车 + 非潜行 → 打开对应界面（类型处理器决定）</li>
 *   <li>其余（空车非潜行等）→ 放行原版行为</li>
 * </ul>
 */
public class InteractionListener implements Listener {

    private final JavaPlugin plugin;
    private final CartEngine engine;
    private final CartTypeRegistry registry;
    private final PluginConfig config;
    private final InteractionCooldown cooldown;

    public InteractionListener(JavaPlugin plugin, CartEngine engine, CartTypeRegistry registry,
                               PluginConfig config, InteractionCooldown cooldown) {
        this.plugin = plugin;
        this.engine = engine;
        this.registry = registry;
        this.config = config;
        this.cooldown = cooldown;
    }

    // HIGH 而非 HIGHEST：给同监听器注册顺序在后的保护插件（HIGHEST）留出先拦截的机会，
    // 本插件只在保护插件之后执行动作；同优先级顺序风险仍存在，但 HIGHEST 保护插件已先于本插件。
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(e.getRightClicked() instanceof Minecart cart)) {
            return;
        }
        Player player = e.getPlayer();
        if (!player.hasPermission("pepperminecart.use")) {
            return;
        }
        boolean sneaking = player.isSneaking();
        CartTypeHandler type = engine.resolve(cart);

        // 冷却只作用于插件实际执行的交互；空普通矿车非潜行是原版乘坐，不消耗冷却也不拦截
        if (type == null && !sneaking) {
            return;
        }

        // 原版特殊矿车（无插件数据的箱子矿车等）：潜行时按实体类型解析取下类型（受开关控制）
        if (type == null && sneaking && config.vanillaCartPickupAllowed()) {
            type = engine.resolveVanillaPickup(cart);
        }
        // 原版特殊矿车（含插件转换生成的）：开关关闭时一律禁止取下（内容仍可通过原版界面访问）
        if (type != null && sneaking && engine.isVanillaCart(cart) && !config.vanillaCartPickupAllowed()) {
            e.setCancelled(true);
            return;
        }

        if (type == null) {
            // 空普通矿车：潜行 + 手持方块 → 放置。先完成全部纯校验，确认必然执行后再消耗冷却：
            // 被拒绝的交互（非候选方块/领地拒绝）不应消耗冷却，否则玩家纠正动作后的
            // 下一次合法点击会被误拦
            if (sneaking && isPlacementCandidate(cart, player)) {
                if (!cooldown.tryUse(player)) {
                    e.setCancelled(true);
                    return;
                }
                placeBlock(player, cart);
                e.setCancelled(true);
            }
            return;
        }

        CartContextImpl ctx;
        if (sneaking) {
            // 取下：先做全部拦截检查（不消耗冷却）——否则为外来原版矿车创建上下文后又被拦截，
            // 上下文残留受管集合，矿车之后被破坏时会被误判为销毁而额外掉落
            Material original = originalMaterialOf(cart);
            TakeOffResult mode = effectiveTakeOffMode(type, original);
            if (mode == TakeOffResult.DISABLED) {
                if (safeContainerControlled(type, original)) {
                    player.sendMessage("容器类方块取下已在配置中禁止");
                }
                e.setCancelled(true);
                return;
            }
            if (!cart.getPassengers().isEmpty()) {
                player.sendMessage("矿车上有人乘坐，无法取下");
                e.setCancelled(true);
                return;
            }
            if (config.respectProtection() && !checkBreakProtection(player, cart)) {
                e.setCancelled(true);
                return;
            }
            if (!cooldown.tryUse(player)) {
                e.setCancelled(true);
                return;
            }
            ctx = engine.contextFor(cart, type);
            // 先取消原版交互再执行取下：takeOff 内部异常时也不会继续触发原版上车/打开界面
            e.setCancelled(true);
            engine.takeOff(player, ctx, mode);
        } else {
            // 非潜行交互只对真正受管（有插件 PDC）的矿车生效：contextFor 会无条件登记上下文，
            // 若用于仅显示方块/第三方数据的矿车会把外车"收养"进受管集合（禁乘/错误掉落）。
            // contextOrNull 只在 PDC 受管时登记/复用上下文，显示-only 矿车直接放行原版行为。
            ctx = engine.contextOrNull(cart);
            if (ctx == null) {
                // 仅显示方块/第三方数据的矿车不受管：直接放行原版行为，不消耗冷却
                return;
            }
            if (!cooldown.tryUse(player)) {
                e.setCancelled(true);
                return;
            }
            if (engine.safeInteract(player, ctx)) {
                e.setCancelled(true);
            }
        }
    }

    /**
     * 计算有效取下策略：
     * <ul>
     *   <li>容器类方块（木桶/投掷器/通用容器，<b>不含潜影盒</b>）：由 container-pickup-policy 管控，
     *       FORBIDDEN → 禁止取下；PICKUP/DROP → 容器物品本身按 take-off-mode 处理（差异在内容物，
     *       DROP 时内容物掉落地面，见 {@link CartEngine#takeOff}）。</li>
     *   <li>其余方块（含潜影盒）：一律按 take-off-mode，<b>无任何例外</b>。</li>
     * </ul>
     */
    private TakeOffResult effectiveTakeOffMode(CartTypeHandler type, Material material) {
        if (safeContainerControlled(type, material)
                && config.containerPickupPolicy() == ContainerPickupPolicy.FORBIDDEN) {
            return TakeOffResult.DISABLED;
        }
        return config.takeOffMode();
    }

    /** 第三方容器策略回调异常按“受管控”保守处理：FORBIDDEN 配置下不会静默放行取下。 */
    private boolean safeContainerControlled(CartTypeHandler type, Material material) {
        try {
            return type.isContainerPickupControlled(material);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 矿车类型 " + type.getId() + " isContainerPickupControlled 异常，按受管控处理",
                    ex);
            return true;
        }
    }

    /** 取下流程计算原始方块材质（不创建上下文）：存储物品优先，显示方块兜底。 */
    private Material originalMaterialOf(Minecart cart) {
        ItemStack stored = CartData.getItem(cart);
        return stored != null ? stored.getType() : new CartBlockDisplay(cart).material();
    }

    /** 第三方 canPlace 回调异常按“禁止放置”处理，避免异常逃出交互事件。 */
    private boolean safeCanPlace(CartTypeHandler type, Player player, Minecart cart) {
        try {
            return type.canPlace(player, cart);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 矿车类型 " + type.getId() + " canPlace 异常，按禁止放置处理: "
                            + cart.getUniqueId(), ex);
            return false;
        }
    }

    private boolean isPlacementCandidate(Minecart cart, Player player) {
        // 仅允许放在普通矿车上（原版特殊矿车走取下/原版交互路径）
        if (cart.getType() != EntityType.MINECART) {
            return false;
        }
        if (!(cart instanceof RideableMinecart)) {
            return false;
        }
        if (!cart.getPassengers().isEmpty()) {
            return false;
        }
        if (new CartBlockDisplay(cart).has()) {
            return false;
        }
        if (CartData.isManaged(cart)) {
            return false;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR || !hand.getType().isBlock()) {
            return false;
        }
        if (!config.isBlockAllowed(hand.getType())) {
            return false;
        }
        CartTypeHandler type = registry.byMaterial(hand.getType());
        if (type != null && !safeCanPlace(type, player, cart)) {
            return false;
        }
        if (config.respectProtection() && !checkPlaceProtection(player, cart)) {
            return false;
        }
        return true;
    }

    /**
     * 放置方块：先执行放置逻辑（写类型与物品、转换实体），异常时不消耗物品（回滚）；
     * 放置成功后再消耗主手物品并显式写回，不依赖活引用的实现细节，防刷物品/吞物品。
     */
    private void placeBlock(Player player, Minecart cart) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        Material material = hand.getType();
        CartTypeHandler type = registry.byMaterial(material);
        if (type == null) {
            // 兜底类型可能被公开 API 注销，直接使用单例而不是查注册表
            type = GenericBlockHandler.INSTANCE;
        }
        ItemStack placed = hand.clone();
        placed.setAmount(1);
        // 放置事务：先消耗“放置时快照”，再执行可能改变背包的回调；回调失败时恢复快照。
        // 成功后再读主手会读到回调修改后的物品，导致扣错物品或复制方块。
        ItemStack handSnapshot = hand.clone();
        consumeOneFromMainHand(player, hand);

        // 提前捕获位置与世界：特殊矿车转换会移除原矿车实体，之后再读 getWorld/getLocation 不安全
        Location loc = cart.getLocation();
        World world = cart.getWorld();

        CartContextImpl ctx = null;
        try {
            ctx = engine.addCart(cart, type, placed);
            type.onPlaced(player, ctx);
            // 默认显示方块（普通矿车；特殊矿车转换后由实体自身渲染）
            Minecart current = ctx.getMinecart();
            if (current.isValid() && current.getType() == EntityType.MINECART && !new CartBlockDisplay(current).has()) {
                ctx.setDisplay(material);
                ctx.setDisplayOffset(config.displayBlockOffset());
            }
        } catch (RuntimeException ex) {
            // 放置异常（如实体生成失败）：恢复放置前的主手快照，回滚已写入的 PDC/上下文，
            // 使矿车恢复为空车，避免"矿车带块 + 物品已消耗"或反向的复制
            plugin.getLogger().log(Level.WARNING, "[PepperMinecart] 放置方块到矿车时发生异常，本次放置已取消", ex);
            try {
                player.getInventory().setItemInMainHand(handSnapshot);
                engine.removeCart(cart);
                CartData.clear(cart, type.getId().getNamespace());
                new CartBlockDisplay(cart).clear();
                // 特殊矿车转换可能已生成替代实体：以 replaceEntity 显式记录的产物为准回收，
                // 不能靠"ctx.getMinecart() != cart"推断——替换中途抛异常时 ctx 尚未重定向，
                // 替代实体（已复制 PDC）会被漏掉，形成幽灵矿车与物品复制
                if (ctx != null) {
                    Minecart replaced = ctx.lastReplacement();
                    if (replaced != null) {
                        engine.removeCart(replaced);
                        CartData.clear(replaced, type.getId().getNamespace());
                        // 内容物已在转换时转移进原版库存：回滚移除替代实体前清空，
                        // 防止 remove() 触发原版 dropContents（DISCARDED.shouldDestroy()==true）
                        // 把内容物掉到地面，而放置物品仍在玩家手中，造成内容物复制
                        if (replaced instanceof InventoryHolder holder) {
                            holder.getInventory().clear();
                        }
                        if (replaced.isValid()) {
                            replaced.remove();
                        }
                    }
                }
            } catch (RuntimeException rollbackEx) {
                // 回滚失败（如实体已被替换/移除）不再抛出，但必须留日志，否则幽灵矿车无从排查
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 放置方块回滚未完全成功，请检查矿车残留数据", rollbackEx);
            }
            return;
        }

        if (config.soundPlace()) {
            world.playSound(loc, Sound.BLOCK_STONE_PLACE, 1f, 1f);
        }
    }

    /** 从主手扣减 1 个物品（扣减的是传入的快照，而不是方法调用时的主手）。 */
    private static void consumeOneFromMainHand(Player player, ItemStack snapshot) {
        if (snapshot.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            snapshot.setAmount(snapshot.getAmount() - 1);
            player.getInventory().setItemInMainHand(snapshot);
        }
    }

    // ---- 领地保护协作：模拟原版方块事件，被其他插件取消则禁止操作 ----

    private boolean checkPlaceProtection(Player player, Minecart cart) {
        Location location = cart.getLocation();
        if (location == null || location.getWorld() == null) {
            // 拿不到位置时拒绝操作：宁可拦下合法操作，也不静默绕过领地保护
            return false;
        }
        Material material = player.getInventory().getItemInMainHand().getType();
        // 矿车语义保护事件（新）：让保护插件按“往矿车上放方块”精确判定
        CartBlockPlaceEvent cartEvent = new CartBlockPlaceEvent(player, cart, material);
        player.getServer().getPluginManager().callEvent(cartEvent);
        if (cartEvent.isCancelled()) {
            return false;
        }
        // 兼容旧的方块事件模拟：以矿车所在方块（通常为铁轨）作为代理
        Block block = location.getBlock();
        Block against = block.getRelative(BlockFace.DOWN);
        ItemStack item = new ItemStack(material);
        BlockPlaceEvent event = new BlockPlaceEvent(block, block.getState(), against, item, player, true,
                EquipmentSlot.HAND);
        player.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    private boolean checkBreakProtection(Player player, Minecart cart) {
        Location location = cart.getLocation();
        if (location == null || location.getWorld() == null) {
            // 拿不到位置时拒绝操作：宁可拦下合法操作，也不静默绕过领地保护
            return false;
        }
        Material material = originalMaterialOf(cart);
        // 矿车语义保护事件（新）：让保护插件按“从矿车上取下方块”精确判定
        CartBlockTakeOffEvent cartEvent = new CartBlockTakeOffEvent(player, cart, material);
        player.getServer().getPluginManager().callEvent(cartEvent);
        if (cartEvent.isCancelled()) {
            return false;
        }
        // 兼容旧的方块事件模拟：以矿车所在方块（通常为铁轨）作为代理
        Block block = location.getBlock();
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        player.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }
}
