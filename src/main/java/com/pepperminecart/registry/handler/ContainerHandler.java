package com.pepperminecart.registry.handler;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffOutcome;
import com.pepperminecart.api.TakeOffResult;
import com.pepperminecart.container.CartSessionManager;
import com.pepperminecart.storage.CartData;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 容器类方块：桶/投掷器/潜影盒及通用容器（显式白名单，避免启动时在主线程全量枚举材质并创建
 * BlockState——省启动时间）。内容存于物品 BlockStateMeta 的 Container 中（整物品持久化），
 * 编辑同引用即时生效，取下/掉落时原样归还带内容的物品——潜影盒内容天然随物品回收，无需特判。
 */
public class ContainerHandler implements CartTypeHandler {

    public static final NamespacedKey ID = NamespacedKey.fromString("pepperminecart:container");

    /**
     * 显式白名单：容器方块中实现 {@code org.bukkit.block.Container}、且不属于特殊矿车
     * （CHEST/HOPPER/FURNACE/TNT/命令）或发射器的方块（发射器由 DispenserCartHandler 接管）。
     * 讲台/雕纹书架虽实现 TileStateInventoryHolder，但不是 Container，openContainer 无法打开，
     * 因此不列入（按普通方块处理，不承诺原版界面）。
     */
    private static final Set<Material> KNOWN_CONTAINERS = Set.of(
            Material.BARREL, Material.DROPPER, Material.TRAPPED_CHEST,
            Material.SMOKER, Material.BLAST_FURNACE, Material.BREWING_STAND,
            Material.CRAFTER
    );

    private final Set<Material> materials = new HashSet<>();
    private final CartSessionManager sessions;

    public ContainerHandler(CartSessionManager sessions) {
        this.sessions = sessions;
        materials.addAll(KNOWN_CONTAINERS);
        // 潜影盒：后缀扫描（仅字符串比较，不创建 BlockState，成本极低，动态覆盖 16 色 + 无染色）
        for (Material m : Material.values()) {
            if (m.isLegacy()) {
                continue; // LEGACY_* 潜影盒是 1.13 前的历史材质，不应注册进材质表
            }
            if (m.name().endsWith("SHULKER_BOX")) {
                materials.add(m);
            }
        }
    }

    /** 潜影盒判定（潜影盒不受取下策略限制，始终进背包）。 */
    public static boolean isShulkerBox(Material material) {
        return material != null && !material.isLegacy() && material.name().endsWith("SHULKER_BOX");
    }

    @Override
    public NamespacedKey getId() {
        return ID;
    }

    @Override
    public Set<Material> handledMaterials() {
        // 不可变视图：注册表会遍历该集合，外部 clear/修改会破坏路由表
        return Collections.unmodifiableSet(materials);
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
        if (ctx.getOriginalMaterial() == Material.BARREL
                && ctx.getWorld() != null && ctx.getLocation() != null) {
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
