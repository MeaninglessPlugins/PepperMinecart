package com.pepperminecart.container;

import java.util.Map;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * 原版虚拟界面会话（工作台/砂轮/织布机/制图台/锻造台/切石机/附魔台）：关闭时回收残留物品，
 * 防止原版把物品丢在虚拟位置导致"复制/丢失"。
 */
class VanillaCartSession extends CartSession {

    VanillaCartSession(Minecart cart, Inventory top, InventoryView view) {
        super(cart, top, view);
    }

    @Override
    public void onPlayerClose(Player player) {
        recoverLeftovers(player);
    }

    /**
     * 把界面中的残留物品并入玩家背包，溢出落地。
     * <b>结果/输出槽（SlotType.RESULT）是派生预览，直接丢弃</b>——回收它会白送合成/修复/附魔产物
     * （材料未消耗却得到输出 = 复制），与原版"关闭界面丢弃结果、返还材料"行为一致。
     */
    protected void recoverLeftovers(Player player) {
        for (int i = 0; i < top.getSize(); i++) {
            if (view.getSlotType(i) == InventoryType.SlotType.RESULT) {
                continue;
            }
            ItemStack item = top.getItem(i);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            Map<Integer, ItemStack> left = player.getInventory().addItem(item);
            for (ItemStack rest : left.values()) {
                if (rest != null && !rest.getType().isAir()) {
                    cart.getWorld().dropItemNaturally(cart.getLocation(), rest);
                }
            }
            top.setItem(i, null);
        }
    }
}
