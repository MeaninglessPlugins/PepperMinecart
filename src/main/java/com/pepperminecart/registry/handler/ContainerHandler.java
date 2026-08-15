package com.pepperminecart.registry.handler;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffOutcome;
import com.pepperminecart.api.TakeOffResult;
import com.pepperminecart.container.CartSessionManager;
import com.pepperminecart.storage.CartData;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * 容器类方块：桶/投掷器（仅容量）/潜影盒及通用容器（任何带 InventoryHolder 的方块）。
 * 内容存于物品 BlockStateMeta 的 Container 中（整物品持久化），编辑同引用即时生效，
 * 取下/掉落时原样归还带内容的物品——潜影盒内容天然随物品回收，无需特判。
 */
public class ContainerHandler implements CartTypeHandler {

    public static final NamespacedKey ID = NamespacedKey.fromString("pepperminecart:container");

    /** 由特殊矿车转换或其他类型接管的方块，不得作为普通容器。 */
    private static final Set<Material> EXCLUDED = Set.of(
            Material.CHEST, Material.HOPPER, Material.FURNACE, Material.TNT,
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.DISPENSER // 投掷器由 DispenserCartHandler 接管（容器 + 铁轨发射）
    );

    private final Set<Material> materials = new HashSet<>();
    private final CartSessionManager sessions;

    public ContainerHandler(CartSessionManager sessions) {
        this.sessions = sessions;
        materials.add(Material.BARREL);
        materials.add(Material.DROPPER);
        for (Material m : Material.values()) {
            if (m.name().endsWith("SHULKER_BOX")) {
                materials.add(m);
            }
        }
        // 通用容器：任何可创建 BlockState 且带 InventoryHolder 的方块
        for (Material m : Material.values()) {
            if (EXCLUDED.contains(m) || materials.contains(m)) {
                continue;
            }
            try {
                // isBlock() 依赖服务端注册表，测试环境（MockBukkit）对 LEGACY_* 材质会抛
                // UnimplementedOperationException —— 与 createBlockData 一同纳入 try 防御
                if (!m.isBlock()) {
                    continue;
                }
                BlockState state = m.createBlockData().createBlockState();
                if (state instanceof InventoryHolder holder) {
                    int size = holder.getInventory().getSize();
                    if (size > 0 && size <= 54) {
                        materials.add(m);
                    }
                }
            } catch (Throwable ignored) {
                // 个别方块（如 LEGACY_*）无法创建 BlockState，忽略
            }
        }
    }

    /** 潜影盒判定（潜影盒不受取下策略限制，始终进背包）。 */
    public static boolean isShulkerBox(Material material) {
        return material != null && material.name().endsWith("SHULKER_BOX");
    }

    @Override
    public NamespacedKey getId() {
        return ID;
    }

    @Override
    public Set<Material> handledMaterials() {
        return materials;
    }

    @Override
    public boolean onInteract(Player player, CartContext ctx) {
        ItemStack blockItem = CartData.getItem(ctx.getMinecart());
        if (blockItem == null) {
            return false;
        }
        Inventory inv = sessions.openContainer(player, ctx.getMinecart(), blockItem);
        if (inv == null) {
            return false;
        }
        if (ctx.getOriginalMaterial() == Material.BARREL) {
            // 木桶开合音效（完整开合动画需发包伪造方块，列为后续增强）
            ctx.getWorld().playSound(ctx.getLocation(), Sound.BLOCK_BARREL_OPEN, 1f, 1f);
        }
        return true;
    }

    @Override
    public TakeOffOutcome onTakeOff(Player player, CartContext ctx, TakeOffResult result) {
        // 打开中的容器会话由取下流程统一回写（flushCartSessions）；
        // 内容随整物品回收，无需在此处理
        Minecart cart = ctx.getMinecart();
        if (cart != null) {
            sessions.flushContainer(cart);
        }
        return TakeOffOutcome.DEFAULT;
    }

    @Override
    public boolean isContainerPickupControlled(Material material) {
        // 潜影盒不受容器取下策略管控（始终按 take-off-mode 处理）
        return !isShulkerBox(material);
    }
}
