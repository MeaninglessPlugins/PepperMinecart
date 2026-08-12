package org.eu.pcraft.pepperminecart.feature.container;

import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.eu.pcraft.pepperminecart.feature.CartFeature;
import org.eu.pcraft.pepperminecart.feature.FeatureContext;

/**
 * 容器矿车：直接打开方块物品 BlockStateMeta 里 Container 的实时库存（同一引用），
 * 关闭/发射时由 FeatureContext 统一回写，杜绝拷贝与刷物品。
 */
public class ContainerFeature implements CartFeature {

    @Override
    public String getName() {
        return "CONTAINER";
    }

    @Override
    public boolean onStandInteract(Player player, Minecart minecart, ItemStack itemOnCart, FeatureContext ctx) {
        Inventory inv = ctx.openContainer(minecart, itemOnCart);
        if (inv == null) return false;
        player.openInventory(inv);
        return true;
    }

    @Override
    public void onContainerClosed(Minecart minecart, FeatureContext ctx) {
        ctx.closeContainer(minecart);
    }
}
