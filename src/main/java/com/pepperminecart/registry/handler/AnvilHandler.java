package com.pepperminecart.registry.handler;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.container.CartSessionManager;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;

/**
 * 铁砧：打开铁砧界面，支持耐久损耗（完整 → 损坏 → 报废，见 AnvilDamageTracker）。
 * 耐久状态由存储物品的类型承载（ANVIL → CHIPPED_ANVIL → DAMAGED_ANVIL），取下时原样归还。
 */
public class AnvilHandler implements CartTypeHandler {

    public static final NamespacedKey ID = NamespacedKey.fromString("pepperminecart:anvil");

    private static final Set<Material> MATERIALS = Set.of(
            Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL
    );

    private final CartSessionManager sessions;

    public AnvilHandler(CartSessionManager sessions) {
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
        org.bukkit.Location loc = ctx.getLocation();
        if (loc == null || loc.getWorld() == null) {
            return false; // 实体已失效：拒绝打开界面而不是抛 NPE
        }
        InventoryView view = player.openAnvil(loc, true);
        if (view != null) {
            sessions.openAnvilView(player, ctx.getMinecart(), view);
            return true;
        }
        return false;
    }
}
