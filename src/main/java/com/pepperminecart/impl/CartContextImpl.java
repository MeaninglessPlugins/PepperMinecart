package com.pepperminecart.impl;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.engine.CartEngine;
import com.pepperminecart.storage.CartData;
import java.util.ArrayList;
import java.util.Map;
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

    public CartContextImpl(CartEngine engine, PluginConfig config, Minecart cart, CartTypeHandler type) {
        this.engine = engine;
        this.config = config;
        this.cart = cart;
        this.type = type;
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
            ItemStack updated = item.clone();
            updated.setType(material);
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
        // 世界年龄驱动（World#getFullTime：单调递增、跨区块卸载/重启持久、不受 /time set 与
        // 系统时钟影响；随实体 PDC 保存，实体替换时随 PDC 一并复制）——与引擎调度语义一致
        CartData.setLong(cart, key, cart.getWorld().getFullTime() + Math.max(0, ticks));
    }

    @Override
    public boolean hasCooldown(NamespacedKey key) {
        return cart.getWorld().getFullTime() < CartData.getLong(cart, key, 0L);
    }

    @Override
    public void giveItem(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        switch (config.takeOffMode()) {
            case INVENTORY -> giveOrDrop(player, item);
            default -> dropItem(item); // DROP / DISABLED（安全兜底）
        }
    }

    @Override
    public void dropItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        World world = cart.getWorld();
        if (world == null) {
            return; // 实体已移除且世界不可用（防御；正常路径取下前已 repoint 到有效实体）
        }
        world.dropItemNaturally(cart.getLocation(), item);
    }

    @Override
    public void replaceEntity(Minecart newCart) {
        CartData.copy(cart, newCart);
        // 迁移自定义名与可见性，尽力转移乘客（removePassenger 被取消时乘客留在旧矿车，
        // 旧矿车随后 remove() 会由服务端正常弹出，不强行转移）
        if (cart.customName() != null) {
            newCart.customName(cart.customName());
            newCart.setCustomNameVisible(cart.isCustomNameVisible());
        }
        for (Entity passenger : new ArrayList<>(cart.getPassengers())) {
            if (cart.removePassenger(passenger)) {
                newCart.addPassenger(passenger);
            }
        }
        engine.rebind(cart.getUniqueId(), newCart);
        // 旧实体已退休：清空本插件 PDC 与显示，防止其随后 remove() 时被 onEntityRemove
        // 重新解析为受管矿车造成双重掉落（第三方在 onTakeOff 中调用 replaceEntity 后尤其需要）
        CartData.clear(cart);
        new CartBlockDisplay(cart).clear();
        cart = newCart;
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

    public boolean dropOnDestroy() {
        return dropOnDestroy;
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        if (left.isEmpty()) {
            return;
        }
        World world = cart.getWorld();
        if (world == null) {
            return; // 实体已移除且世界不可用（防御）
        }
        for (ItemStack rest : left.values()) {
            if (rest != null && !rest.getType().isAir()) {
                world.dropItemNaturally(cart.getLocation(), rest);
            }
        }
    }
}
