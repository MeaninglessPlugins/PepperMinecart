package org.eu.pcraft.pepperminecart.feature;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

/**
 * 各特性的共享上下文 Facade：统一暴露持久化/会话/音效/物品助手，内部委托给
 * CartPersistence / CartSessions / Audio。MinecartService 与所有 CartFeature 共用同一实例。
 */
public class FeatureContext {

    private final CartPersistence persistence = new CartPersistence();
    private final CartSessions sessions = new CartSessions();
    private final Audio audio = new Audio();

    // --- NBT 持久化委托 ---

    public ItemStack getBlockItem(Minecart minecart) {
        return persistence.getBlockItem(minecart);
    }

    /** 轻量判断：矿车上是否挂有 BlockInfo 键（不反序列化物品，供热路径使用） */
    public boolean hasBlockInfo(Minecart minecart) {
        return persistence.hasBlockInfo(minecart);
    }

    public void setBlockItem(Minecart minecart, ItemStack item) {
        persistence.setBlockItem(minecart, item);
    }

    public void removeBlockItem(Minecart minecart) {
        persistence.removeBlockItem(minecart);
    }

    // --- 容器会话 ---

    public Inventory openContainer(Minecart minecart, ItemStack blockItem) {
        return sessions.openContainer(minecart, blockItem);
    }

    public Inventory getOpenInventory(Minecart minecart) {
        return sessions.getOpenInventory(minecart);
    }

    public boolean isContainerOpen(Minecart minecart) {
        return sessions.isContainerOpen(minecart);
    }

    public Set<Minecart> getOpenMinecarts() {
        return sessions.getOpenMinecarts();
    }

    public Minecart getInventoryOwner(Inventory inventory) {
        return sessions.getInventoryOwner(inventory);
    }

    public void removeInventoryOwner(Inventory inventory) {
        sessions.removeInventoryOwner(inventory);
    }

    /** 把打开中的实时库存回写方块物品（不关闭界面）。投掷器发射后必须调用。 */
    public void flushContainer(Minecart minecart) {
        Container container = sessions.getLiveContainer(minecart);
        if (container == null) return;
        ItemStack item = getBlockItem(minecart);
        if (item == null) return;
        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) return;
        // 容器是物品 BlockStateMeta 中的未放置快照，update(true) 无实际效果；
        // 回写靠 setBlockState 把快照写回物品后再写 NBT
        meta.setBlockState(container);
        item.setItemMeta(meta);
        setBlockItem(minecart, item);
    }

    /** 关闭容器界面：回写方块物品并清理会话 */
    public void closeContainer(Minecart minecart) {
        flushContainer(minecart);
        sessions.removeSession(minecart);
    }

    /** 强制清理该矿车的容器会话（不回写，调用方需先 flush） */
    public void removeSession(Minecart minecart) {
        sessions.removeSession(minecart);
    }

    // --- 铁砧会话 ---

    public void setAnvilSession(UUID playerId, Minecart minecart) {
        sessions.setAnvilSession(playerId, minecart);
    }

    public Minecart getAnvilSession(UUID playerId) {
        return sessions.getAnvilSession(playerId);
    }

    public void clearAnvilSession(UUID playerId) {
        sessions.clearAnvilSession(playerId);
    }

    /**
     * 清理该矿车的全部铁砧会话，并关闭仍开着铁砧界面的玩家。
     * 所有 GUI（容器/铁砧/工作站）在矿车被取下或销毁时统一关闭，避免残留"幽灵界面"。
     */
    public void clearAnvilSessions(Minecart minecart) {
        for (Player player : sessions.clearAnvilSessions(minecart)) {
            player.closeInventory();
        }
    }

    // --- 工作站会话 ---

    public void setWorkstationSession(UUID playerId, Minecart minecart) {
        sessions.setWorkstationSession(playerId, minecart);
    }

    public void clearWorkstationSession(UUID playerId) {
        sessions.clearWorkstationSession(playerId);
    }

    /** 清理该矿车的全部工作站会话，并关闭仍开着对应界面的玩家 */
    public void clearWorkstationSessions(Minecart minecart) {
        for (Player player : sessions.clearWorkstationSessions(minecart)) {
            player.closeInventory();
        }
    }

    // --- 投掷器冷却 ---

    public boolean consumeDropperCooldown(Minecart minecart, long cooldownTicks) {
        return sessions.consumeDropperCooldown(minecart, cooldownTicks);
    }

    public void removeDropperCooldown(Minecart minecart) {
        sessions.removeDropperCooldown(minecart);
    }

    // --- 矿车形态助手 ---

    public Entity replaceMinecart(Minecart oldCart, EntityType newType) {
        return persistence.replaceMinecart(oldCart, newType);
    }

    public void storeCustomBlock(Minecart minecart, ItemStack item) {
        persistence.storeCustomBlock(minecart, item);
    }

    public void clearCustomBlock(Minecart minecart) {
        persistence.clearCustomBlock(minecart);
    }

    // --- 物品与音效助手 ---

    /**
     * 把物品放进玩家主手（空手放入 / 相同物品 +1）。
     * 不处理任何持久化：若 source 来自矿车 NBT（getBlockItem），调用方须先
     * 清 NBT 再转移；带 NBT 的取下统一走 {@link #pickupBlockIntoHand}。
     */
    public boolean tryPickupIntoHand(Player player, ItemStack source) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            player.getInventory().setItemInMainHand(source);
            return true;
        }
        if (hand.isSimilar(source) && hand.getAmount() < hand.getMaxStackSize()) {
            hand.setAmount(hand.getAmount() + 1);
            // 显式写回主手，不依赖 getItemInMainHand 返回活引用的实现细节
            player.getInventory().setItemInMainHand(hand);
            return true;
        }
        return false;
    }

    /**
     * 通用取下：先把打开的容器会话回写进 NBT（避免玩家编辑丢失），再取出方块物品，
     * 关闭仍打开该矿车容器/铁砧/工作站界面的玩家，然后清空矿车上的方块。
     * service 兜底与 feature 共用同一逻辑。
     */
    public boolean pickupBlockIntoHand(Player player, Minecart minecart) {
        if (isContainerOpen(minecart)) {
            flushContainer(minecart);
        }
        ItemStack item = getBlockItem(minecart);
        if (item == null) return false;
        if (!tryPickupIntoHand(player, item)) return false;
        // 先关闭界面：关闭事件中 handleContainerClosed 仍能读到物品并执行 onContainerClosed
        // （如木桶合盖），随后再清空矿车上的方块
        Inventory open = getOpenInventory(minecart);
        if (open != null) {
            new ArrayList<>(open.getViewers()).forEach(HumanEntity::closeInventory);
        }
        clearCustomBlock(minecart);
        // 关闭仍开着该矿车铁砧/工作站界面的玩家并清理会话
        clearAnvilSessions(minecart);
        clearWorkstationSessions(minecart);
        if (isContainerOpen(minecart)) {
            removeSession(minecart);
        }
        return true;
    }

    public void playPickupSound(Location location) {
        audio.playPickupSound(location);
    }

    public void playPlaceSound(Location location, Material material) {
        audio.playPlaceSound(location, material);
    }
}
