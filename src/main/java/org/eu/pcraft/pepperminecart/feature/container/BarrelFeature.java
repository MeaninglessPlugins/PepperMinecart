package org.eu.pcraft.pepperminecart.feature.container;

import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.eu.pcraft.pepperminecart.feature.FeatureContext;

/**
 * 木桶矿车：容器交互 + 桶盖开合动画
 */
public class BarrelFeature extends ContainerFeature {

    @Override
    public String getName() {
        return "BARREL";
    }

    @Override
    public boolean onStandInteract(Player player, Minecart minecart, ItemStack itemOnCart, FeatureContext ctx) {
        boolean handled = super.onStandInteract(player, minecart, itemOnCart, ctx);
        if (handled) setBarrelLid(minecart, true);
        return handled;
    }

    @Override
    public void onContainerClosed(Minecart minecart, FeatureContext ctx) {
        super.onContainerClosed(minecart, ctx);
        setBarrelLid(minecart, false);
    }

    private void setBarrelLid(Minecart minecart, boolean open) {
        try {
            if (open) {
                minecart.setDisplayBlockData(Material.BARREL.createBlockData("[open=true]"));
            } else {
                minecart.setDisplayBlockData(Material.BARREL.createBlockData());
            }
        } catch (IllegalArgumentException e) {
            // 版本不支持 open 属性时忽略
        }
    }
}
