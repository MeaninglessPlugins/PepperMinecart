package com.pepperminecart.impl;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffResult;
import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.delivery.ItemDelivery;
import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.engine.CartEngine;
import com.pepperminecart.storage.CartData;
import java.util.ArrayList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/** CartContext 默认实现：委托 CartData（PDC 整物品存储）与 CartBlockDisplay（显示方块）。 */
public class CartContextImpl implements CartContext {

    private final CartEngine engine;
    private final PluginConfig config;
    private Minecart cart;
    private final CartTypeHandler type;
    private boolean dropOnDestroy = true;
    /** 实体可能已失效时仍可安全掉落的位置快照（销毁/卸载等回调前由引擎刷新）。 */
    private World lastWorld;
    private Location lastLocation;
    /** 最近一次 replaceEntity 的替换产物：即使替换中途异常（尚未重定向 cart），
     *  调用方也能据此精确回收已生成的替代实体，避免带 PDC 的幽灵矿车泄漏。 */
    private Minecart lastReplacement;

    public CartContextImpl(CartEngine engine, PluginConfig config, Minecart cart, CartTypeHandler type) {
        this.engine = engine;
        this.config = config;
        this.cart = cart;
        this.type = type;
    }

    /** 更新最后已知有效位置；供引擎在实体移除/销毁回调前调用，避免 handler 依赖已失效实体。
     *  仅在拿到有效位置时更新，避免实体已失效时用 null 覆盖旧快照。 */
    public void updateSnapshot(World world, Location location) {
        if (world != null && location != null) {
            this.lastWorld = world;
            this.lastLocation = location;
        }
    }

    /** 返回最后已知有效位置；实体已失效且无法取得当前位置时供引擎回退使用。 */
    public Location lastLocation() {
        return lastLocation;
    }

    /** 返回最后已知有效世界；实体已失效时供引擎回退使用。 */
    public World lastWorld() {
        return lastWorld;
    }

    /** 最近一次 {@link #replaceEntity} 接收/生成的实体（放置回滚清理用）。
     *  替换中途抛异常时尚未重定向，本字段与 getMinecart() 可能指向不同实体。 */
    public Minecart lastReplacement() {
        return lastReplacement;
    }

    /** 重建 context 时从旧 context 迁移最近一次 replaceEntity 的替换产物。 */
    public void inheritReplacementFrom(CartContextImpl previous) {
        this.lastReplacement = previous.lastReplacement;
    }

    @Override
    public Minecart getMinecart() {
        return cart;
    }

    @Override
    public CartTypeHandler getType() {
        return type;
    }

    @Override
    public World getWorld() {
        return cart.getWorld();
    }

    @Override
    public Location getLocation() {
        return cart.getLocation();
    }

    @Override
    public Entity getRider() {
        for (Entity passenger : cart.getPassengers()) {
            if (passenger instanceof LivingEntity living && !living.isDead()) {
                return passenger;
            }
        }
        return null;
    }

    @Override
    public boolean hasRider() {
        return getRider() != null;
    }

    @Override
    public Material getOriginalMaterial() {
        // 优先返回放置时记录的原始材质（不随铁砧损坏链/显示方块变化）；
        // 老数据（无 ORIGINAL 键）回退到存储物品类型，再回退到显示方块
        Material original = CartData.getOriginalMaterial(cart);
        if (original != null) {
            return original;
        }
        ItemStack item = getBlockItem();
        return item != null ? item.getType() : getDisplayMaterial();
    }

    @Override
    public ItemStack getBlockItem() {
        return CartData.getItem(cart);
    }

    @Override
    public void setDisplay(Material material) {
        new CartBlockDisplay(cart).set(material);
        syncItemType(material);
    }

    @Override
    public void setDisplay(BlockData data) {
        new CartBlockDisplay(cart).set(data);
        syncItemType(data.getMaterial());
    }

    /** 显示方块变更时同步存储物品的类型（保持显示与物品一致，如铁砧损坏链）。 */
    private void syncItemType(Material material) {
        ItemStack item = getBlockItem();
        if (item != null && item.getType() != material) {
            // 带 BlockStateMeta（容器状态）的物品不重写类型，避免把容器 NBT 挂到无关材质上
            if (item.getItemMeta() instanceof BlockStateMeta) {
                return;
            }
            // withType 保留其余 ItemMeta（setType 已弃用）
            ItemStack updated = item.withType(material);
            CartData.setItem(cart, updated);
        }
    }

    @Override
    public Material getDisplayMaterial() {
        return new CartBlockDisplay(cart).material();
    }

    @Override
    public boolean hasDisplay() {
        return new CartBlockDisplay(cart).has();
    }

    @Override
    public void setDisplayOffset(int offset) {
        cart.setDisplayBlockOffset(offset);
    }

    @Override
    public int getDisplayOffset() {
        return cart.getDisplayBlockOffset();
    }

    @Override
    public void setMetaInt(NamespacedKey key, int value) {
        CartData.setInt(cart, key, value);
    }

    @Override
    public int getMetaInt(NamespacedKey key, int defaultValue) {
        return CartData.getInt(cart, key, defaultValue);
    }

    @Override
    public void setMetaString(NamespacedKey key, String value) {
        CartData.setString(cart, key, value);
    }

    @Override
    public String getMetaString(NamespacedKey key) {
        return CartData.getString(cart, key);
    }

    @Override
    public void setCooldown(NamespacedKey key, int ticks) {
        // 世界年龄驱动（World#getGameTime：level.dat GameTime，单调递增、跨区块卸载/重启持久、
        // 不受 /time set 与系统时钟影响；随实体 PDC 保存，实体替换时随 PDC 一并复制）——与引擎调度语义一致。
        // 注意不能用 getFullTime()：它对应 DayTime，/time set 与 /time add 会直接改写它，
        // 时间回拨会让冷却长期不结束、快拨则瞬间到期
        World world = cart.getWorld();
        if (world == null) {
            return; // 实体已移除/区块卸载：无世界老化参考，跳过设置（避免 NPE，下次有效时再设）
        }
        CartData.setLong(cart, key, world.getGameTime() + Math.max(0, ticks));
    }

    @Override
    public boolean hasCooldown(NamespacedKey key) {
        World world = cart.getWorld();
        if (world == null) {
            return false; // 实体已失效无法判定，视为无冷却
        }
        return world.getGameTime() < CartData.getLong(cart, key, 0L);
    }

    @Override
    public void giveItem(Player player, ItemStack item) {
        // 注意：此公开入口按全局 take-off-mode 分发；取下流程内部应使用 deliverItem
        // (player, item, mode) 以遵循本次实际策略（如容器 DROP 策略）
        deliverItem(player, item, config.takeOffMode());
    }

    /** 旧签名兼容入口：新代码请使用 {@link #deliverItem(Player, ItemStack, TakeOffResult)} 获取交付结果。 */
    @Deprecated
    public void giveItem(Player player, ItemStack item, TakeOffResult mode) {
        deliverItem(player, item, mode);
    }

    /** 引擎内部按本次实际取下策略分发，并如实报告是否安全交付。 */
    @Override
    public boolean deliverItem(Player player, ItemStack item, TakeOffResult mode) {
        if (item == null || item.getType().isAir()) {
            return true;
        }
        if (mode == null) {
            // 公开 API 防御：null 策略按“未交付”处理，调用方必须保留源数据
            Bukkit.getLogger().warning("[PepperMinecart] deliverItem 收到 null 策略，本次未交付物品");
            return false;
        }
        return switch (mode) {
            case INVENTORY -> giveOrDrop(player, item);
            case DROP -> tryDropItem(item);
            case DISABLED -> {
                // 禁止取下：不交付任何物品。必须返回 false 如实上报，否则调用方会误以为
                // 交付成功并清空源数据，造成静默丢物。giveItem 两个入口都经本分支，
                // 告警只在这里输出一次，避免重复刷屏。
                Bukkit.getLogger().warning(
                        "[PepperMinecart] 取下策略为 DISABLED 时不应调用物品交付，本次未交付物品（调用方必须保留源数据）");
                yield false;
            }
        };
    }

    @Override
    public void dropItem(ItemStack item) {
        // 兼容旧 API：调用方忽略结果时，掉落失败无法上报；新代码请使用 tryDropItem
        tryDropItem(item);
    }

    @Override
    public boolean tryDropItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return true;
        }
        World world = cart.getWorld();
        Location loc = cart.getLocation();
        if (world == null || loc == null) {
            // 实体已移除/失效时回退到最后已知位置，避免销毁回调静默丢失掉落
            world = lastWorld;
            loc = lastLocation;
        }
        return ItemDelivery.dropItem(world, loc, item) != null;
    }

    @Override
    public boolean tryDropItems(ItemStack... items) {
        if (items == null || items.length == 0) {
            return true;
        }
        World world = cart.getWorld();
        Location loc = cart.getLocation();
        if (world == null || loc == null) {
            world = lastWorld;
            loc = lastLocation;
        }
        return ItemDelivery.dropAll(world, loc, items) == ItemDelivery.Result.DELIVERED;
    }

    @Override
    public void replaceEntity(Minecart newCart) {
        // 第一步先记录替换产物：后续任何步骤抛异常时，放置回滚仍能通过 lastReplacement() 回收该实体
        this.lastReplacement = newCart;
        boolean rebound = false;
        try {
            CartData.copy(cart, newCart);
            // 迁移自定义名与可见性，尽力转移乘客（removePassenger 被取消时乘客留在旧矿车，
            // 旧矿车随后 remove() 会由服务端正常弹出，不强行转移）
            if (cart.customName() != null) {
                newCart.customName(cart.customName());
                newCart.setCustomNameVisible(cart.isCustomNameVisible());
            }
            for (Entity passenger : new ArrayList<>(cart.getPassengers())) {
                if (cart.removePassenger(passenger) && !newCart.addPassenger(passenger) && cart.isValid()) {
                    // 新实体拒绝承载时把乘客放回旧实体，避免乘客被丢在中间状态
                    cart.addPassenger(passenger);
                }
            }
            // 显示方块/偏移是矿车实体属性，实体替换时应一并迁移
            BlockData displayData = cart.getDisplayBlockData();
            if (displayData != null && !displayData.getMaterial().isAir()) {
                newCart.setDisplayBlockData(displayData);
            }
            newCart.setDisplayBlockOffset(cart.getDisplayBlockOffset());
            engine.rebind(cart.getUniqueId(), newCart);
            rebound = true;
            // 旧实体已退休：清空本插件 PDC 与显示，防止其随后 remove() 时被 onEntityRemove
            // 重新解析为受管矿车造成双重掉落（第三方在 onTakeOff 中调用 replaceEntity 后尤其需要）
            CartData.clear(cart, type.getId().getNamespace());
            new CartBlockDisplay(cart).clear();
            cart = newCart;
            World world = newCart.getWorld();
            Location loc = newCart.getLocation();
            if (world != null && loc != null) {
                updateSnapshot(world, loc);
            }
        } catch (RuntimeException ex) {
            // 事务回滚：清除已复制到替代实体的插件 PDC/显示，避免新旧两个实体都带业务数据；
            // 替代实体本身由外层放置回滚（lastReplacement）统一移除。
            CartData.clear(newCart, type.getId().getNamespace());
            new CartBlockDisplay(newCart).clear();
            // 如果异常发生在 engine.rebind 之后，引擎 context 已经指向新实体，必须一并移除，
            // 否则会留下“引擎跟踪新实体但 ctx.cart 仍指向旧实体”的幽灵受管状态。
            if (rebound) {
                engine.removeCart(newCart.getUniqueId());
            }
            throw ex;
        }
    }

    /**
     * 取下流程中实体被替换为新的普通矿车后重定向内部引用：
     * 仅更新实体字段（上下文随后即废弃），不复制 PDC、不重绑定引擎跟踪，
     * 避免新矿车被引擎误跟踪（其无业务数据，也不应再视为受管矿车）。
     */
    public void repoint(Minecart newCart) {
        this.cart = newCart;
    }

    @Override
    public void setDropOnDestroy(boolean drop) {
        this.dropOnDestroy = drop;
    }

    @Override
    public void releaseCart() {
        // HANDLED 取下路径的清理助手：清除插件 PDC/显示并解除引擎跟踪，
        // 使矿车还原为空车（等效于引擎默认取下流程对普通矿车的清理）
        engine.removeCart(cart);
        CartData.clear(cart, type.getId().getNamespace());
        new CartBlockDisplay(cart).clear();
    }

    public boolean dropOnDestroy() {
        return dropOnDestroy;
    }

    private boolean giveOrDrop(Player player, ItemStack item) {
        World world = cart.getWorld();
        Location loc = cart.getLocation();
        if (world == null || loc == null) {
            // 实体已移除/失效时回退到最后已知位置，避免溢出物品静默丢失
            world = lastWorld;
            loc = lastLocation;
        }
        return ItemDelivery.giveOrDrop(player, item, world, loc) == ItemDelivery.Result.DELIVERED;
    }
}
