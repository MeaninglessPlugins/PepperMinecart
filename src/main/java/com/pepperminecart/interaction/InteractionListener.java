package com.pepperminecart.interaction;

import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffResult;
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
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
        if (!cooldown.tryUse(player)) {
            return;
        }

        boolean sneaking = player.isSneaking();
        CartTypeHandler type = engine.resolve(cart);

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
            // 空普通矿车：潜行 + 手持方块 → 放置
            if (sneaking && isPlacementCandidate(cart, player)) {
                placeBlock(player, cart);
                e.setCancelled(true);
            }
            return;
        }

        CartContextImpl ctx;
        if (sneaking) {
            // 取下：先做全部拦截检查再登记上下文——否则为外来原版矿车创建上下文后又被拦截，
            // 上下文残留受管集合，矿车之后被破坏时会被误判为销毁而额外掉落
            Material original = originalMaterialOf(cart);
            TakeOffResult mode = effectiveTakeOffMode(type, original);
            if (mode == TakeOffResult.DISABLED) {
                if (type.isContainerPickupControlled(original)) {
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
            ctx = engine.contextFor(cart, type);
            engine.takeOff(player, ctx, mode);
            e.setCancelled(true);
        } else {
            ctx = engine.contextFor(cart, type);
            if (type.onInteract(player, ctx)) {
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
        if (type.isContainerPickupControlled(material)
                && config.containerPickupPolicy() == ContainerPickupPolicy.FORBIDDEN) {
            return TakeOffResult.DISABLED;
        }
        return config.takeOffMode();
    }

    /** 取下流程计算原始方块材质（不创建上下文）：存储物品优先，显示方块兜底。 */
    private Material originalMaterialOf(Minecart cart) {
        ItemStack stored = CartData.getItem(cart);
        return stored != null ? stored.getType() : new CartBlockDisplay(cart).material();
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
        if (type != null && !type.canPlace(player, cart)) {
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
            type = registry.byId(GenericBlockHandler.ID);
        }
        ItemStack placed = hand.clone();
        placed.setAmount(1);

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
            // 放置异常（如实体生成失败）：物品尚未消耗，回滚已写入的 PDC/上下文，
            // 使矿车恢复为空车，避免"矿车带块 + 物品未消耗"的复制
            plugin.getLogger().log(Level.WARNING, "[PepperMinecart] 放置方块到矿车时发生异常，本次放置已取消", ex);
            try {
                engine.removeCart(cart);
                CartData.clear(cart);
                new CartBlockDisplay(cart).clear();
                // 特殊矿车转换若已部分生成替代实体（replaceEntity 之后抛异常），一并回收，避免泄漏
                if (ctx != null && ctx.getMinecart() != cart) {
                    Minecart replaced = ctx.getMinecart();
                    engine.removeCart(replaced);
                    CartData.clear(replaced);
                    replaced.remove();
                }
            } catch (RuntimeException rollbackEx) {
                // 回滚失败（如实体已被替换/移除）时不再抛出，避免掩盖原始异常
            }
            return;
        }

        // 放置成功后再消耗物品，显式写回主手
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            inHand.setAmount(inHand.getAmount() - 1);
            player.getInventory().setItemInMainHand(inHand);
        }

        if (config.soundPlace()) {
            world.playSound(loc, Sound.BLOCK_STONE_PLACE, 1f, 1f);
        }
    }

    // ---- 领地保护协作：模拟原版方块事件，被其他插件取消则禁止操作 ----

    private boolean checkPlaceProtection(Player player, Minecart cart) {
        Block block = cart.getLocation().getBlock();
        Block against = block.getRelative(BlockFace.DOWN);
        ItemStack item = new ItemStack(player.getInventory().getItemInMainHand().getType());
        BlockPlaceEvent event = new BlockPlaceEvent(block, block.getState(), against, item, player, true,
                EquipmentSlot.HAND);
        player.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    private boolean checkBreakProtection(Player player, Minecart cart) {
        Block block = cart.getLocation().getBlock();
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        player.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }
}
