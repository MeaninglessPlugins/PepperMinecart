package com.pepperminecart.registry.handler;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.container.CartSessionManager;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;

/** 工作站类方块：工作台/砂轮/织布机/制图台/锻造台/切石机/附魔台。 */
public class WorkstationHandler implements CartTypeHandler {

    public static final NamespacedKey ID = NamespacedKey.fromString("pepperminecart:workstation");

    private static final Set<Material> MATERIALS = Set.of(
            Material.CRAFTING_TABLE,
            Material.GRINDSTONE,
            Material.LOOM,
            Material.CARTOGRAPHY_TABLE,
            Material.SMITHING_TABLE,
            Material.STONECUTTER,
            Material.ENCHANTING_TABLE
    );

    private final CartSessionManager sessions;

    public WorkstationHandler(CartSessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public NamespacedKey getId() {
        return ID;
    }

    @Override
    public Set<Material> handledMaterials() {
        return MATERIALS;
    }

    @Override
    public boolean onInteract(Player player, CartContext ctx) {
        Material m = ctx.getOriginalMaterial();
        InventoryView view = switch (m) {
            case CRAFTING_TABLE -> player.openWorkbench(ctx.getLocation(), true);
            case GRINDSTONE -> player.openGrindstone(ctx.getLocation(), true);
            case LOOM -> player.openLoom(ctx.getLocation(), true);
            case CARTOGRAPHY_TABLE -> player.openCartographyTable(ctx.getLocation(), true);
            case SMITHING_TABLE -> player.openSmithingTable(ctx.getLocation(), true);
            case STONECUTTER -> player.openStonecutter(ctx.getLocation(), true);
            case ENCHANTING_TABLE -> player.openEnchanting(ctx.getLocation(), true);
            default -> null;
        };
        if (view != null) {
            sessions.openVanillaView(player, ctx.getMinecart(), view);
            return true;
        }
        return false;
    }
}
