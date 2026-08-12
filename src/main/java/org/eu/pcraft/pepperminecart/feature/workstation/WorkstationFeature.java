package org.eu.pcraft.pepperminecart.feature.workstation;

import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.eu.pcraft.pepperminecart.feature.CartFeature;
import org.eu.pcraft.pepperminecart.feature.FeatureContext;

/**
 * 工作站矿车：右键打开对应界面（工作台/砂轮/织布机/制图台/锻造台/切石机/附魔台）
 */
public class WorkstationFeature implements CartFeature {

    private final String name;
    private final MenuType menuType;

    public WorkstationFeature(String name, MenuType menuType) {
        this.name = name;
        this.menuType = menuType;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean onStandInteract(Player player, Minecart minecart, ItemStack itemOnCart, FeatureContext ctx) {
        player.openInventory(menuType.create(player, null));
        return true;
    }
}
