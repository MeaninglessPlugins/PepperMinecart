package com.pepperminecart.container;

import com.pepperminecart.storage.CartData;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Container;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/**
 * 插件容器会话：直接打开物品 BlockStateMeta 中 Container 的实时库存（同一引用），
 * 编辑即时生效；关闭/回写时把容器写回物品再存 PDC——杜绝拷贝与刷物品。
 * 同一矿车的容器会话复用同一实时容器引用：多玩家共享同一份快照编辑，
 * 避免各持独立快照、关闭时互相覆盖造成数据丢失（此前为 per-player 独立快照）。
 */
final class ContainerCartSession extends CartSession {

    private final BlockStateMeta meta;
    private final Container container;

    ContainerCartSession(Minecart cart, Inventory top, InventoryView view,
                         BlockStateMeta meta, Container container) {
        super(cart, top, view);
        this.meta = meta;
        this.container = container;
    }

    @Override
    public Container liveContainer() {
        return container;
    }

    /** 直接用会话持有的 meta/container 引用回写存储物品。 */
    @Override
    public void flush() {
        ItemStack blockItem = CartData.getItem(cart);
        if (blockItem == null) {
            return;
        }
        // 容器是物品 BlockStateMeta 中的未放置快照：setBlockState 把实时库存写回物品再存 PDC
        meta.setBlockState(container);
        blockItem.setItemMeta(meta);
        CartData.setItem(cart, blockItem);
    }

    @Override
    public void onPlayerClose(Player player) {
        flush();
        // 多玩家共享同一容器时，仅当最后一名查看者关闭才播放关合音效
        boolean hasOtherViewer = top.getViewers().stream().anyMatch(viewer -> !viewer.equals(player));
        if (!hasOtherViewer && container.getType() == Material.BARREL && cart.isValid()) {
            cart.getWorld().playSound(cart.getLocation(), Sound.BLOCK_BARREL_CLOSE, 1f, 1f);
        }
    }
}
