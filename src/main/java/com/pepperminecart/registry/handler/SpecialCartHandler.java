package com.pepperminecart.registry.handler;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.VanillaCartSupport;
import com.pepperminecart.engine.MinecartSwap;
import com.pepperminecart.storage.CartData;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.Container;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/**
 * 特殊矿车转换：箱子/漏斗/熔炉/TNT/命令方块 放置后转换为原版特殊矿车实体。
 * 取下/销毁时还原为普通矿车或掉落，内容物组装进"带库存的容器物品"随物品回收（对齐参考项目
 * ChestCartFeature 语义）；不存整物品（内容在原版库存中），按实体类型反查方块材质。
 */
public class SpecialCartHandler implements CartTypeHandler, VanillaCartSupport {

    public static final NamespacedKey ID = NamespacedKey.fromString("pepperminecart:special");

    private static final Set<Material> MATERIALS = Set.of(
            Material.CHEST, Material.HOPPER, Material.FURNACE, Material.TNT,
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK
    );

    /** 实体类型 → 方块材质 反查表（特殊矿车不存物品，内容在原版库存中）。 */
    private static final Map<EntityType, Material> BY_ENTITY = Map.of(
            EntityType.CHEST_MINECART, Material.CHEST,
            EntityType.HOPPER_MINECART, Material.HOPPER,
            EntityType.FURNACE_MINECART, Material.FURNACE,
            EntityType.TNT_MINECART, Material.TNT,
            EntityType.COMMAND_BLOCK_MINECART, Material.COMMAND_BLOCK
    );

    @Override
    public Material materialFor(EntityType type) {
        return BY_ENTITY.get(type);
    }

    /** 反查该特殊矿车代表的方块材质：存储的原始材质优先（命令方块三变体），实体类型反查兜底（外来原版矿车）。
     *  原始材质由引擎放置流程统一写入 CartData.ORIGINAL（addCart 时记录，实体替换时随 PDC 复制）。 */
    private static Material resolveMaterial(Minecart cart) {
        Material stored = CartData.getOriginalMaterial(cart);
        return stored != null ? stored : BY_ENTITY.get(cart.getType());
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
    public void onPlaced(Player player, CartContext ctx) {
        Minecart cart = ctx.getMinecart();
        Material m = ctx.getOriginalMaterial();
        MinecartSwap.Motion motion = MinecartSwap.capture(cart);

        Minecart replacement = switch (m) {
            case CHEST -> MinecartSwap.spawnReplacement(motion, StorageMinecart.class);
            case HOPPER -> MinecartSwap.spawnReplacement(motion, HopperMinecart.class);
            case FURNACE -> MinecartSwap.spawnReplacement(motion, PoweredMinecart.class);
            case TNT -> MinecartSwap.spawnReplacement(motion, ExplosiveMinecart.class);
            default -> {
                CommandMinecart cm = MinecartSwap.spawnReplacement(motion, CommandMinecart.class);
                // 从物品 BlockEntityTag 恢复命令（放置带命令的命令方块物品时保留命令，不丢失）
                ItemStack blockItem = ctx.getBlockItem();
                if (blockItem != null && blockItem.getItemMeta() instanceof BlockStateMeta bsm
                        && bsm.getBlockState() instanceof CommandBlock cb) {
                    cm.setCommand(cb.getCommand());
                } else {
                    cm.setCommand("");
                }
                yield cm;
            }
        };

        // 物品携带的容器内容（如"带库存的箱子物品"）转移到原版矿车库存
        transferContents(replacement, ctx.getBlockItem());

        ctx.replaceEntity(replacement); // 内部已清空旧矿车 PDC/显示
        // 原始材质（命令方块三变体区分）由 CartData.ORIGINAL 承载：addCart 时已写入，
        // replaceEntity 随 PDC 复制到新实体；特殊矿车不存整物品（内容在原版库存中）；
        // 旧矿车随后 remove() 时数据已清空，不会再触发 EntityRemoveEvent 误判为销毁而重复掉落
        CartData.clearItem(replacement);
        cart.remove();
    }

    /** 把放置物品 BlockStateMeta 中 Container 的内容转移到新矿车库存（有内容才转移；
     *  目标库存更小时只转移能容纳的部分，防止 setContents 因数组超长抛 IllegalArgumentException）。 */
    private static void transferContents(Minecart replacement, ItemStack placedItem) {
        if (!(replacement instanceof InventoryHolder holder)) {
            return;
        }
        if (placedItem == null || !(placedItem.getItemMeta() instanceof BlockStateMeta bsm)) {
            return;
        }
        if (!(bsm.getBlockState() instanceof Container container)) {
            return;
        }
        ItemStack[] contents = container.getInventory().getContents();
        boolean hasItems = Arrays.stream(contents).anyMatch(i -> i != null && !i.getType().isAir());
        if (!hasItems) {
            return;
        }
        Inventory target = holder.getInventory();
        if (contents.length <= target.getSize()) {
            target.setContents(contents);
        } else {
            // 如熔炉内容（3 格）→ 熔炉矿车（更少格）：逐格放入能容纳的部分
            for (int i = 0; i < target.getSize(); i++) {
                if (contents[i] != null && !contents[i].getType().isAir()) {
                    target.setItem(i, contents[i]);
                }
            }
        }
    }

    @Override
    public boolean onInteract(Player player, CartContext ctx) {
        return false; // 箱子/漏斗/熔炉矿车使用原版界面（TNT/命令矿车无界面）
    }

    @Override
    public ItemStack getTakeOffItem(CartContext ctx) {
        Minecart cart = ctx.getMinecart();
        Material m = resolveMaterial(cart);
        if (m == null) {
            return null;
        }
        ItemStack item = new ItemStack(m);
        // 命令矿车：把已设置的命令写回物品（取下后仍保留命令）
        if (cart instanceof CommandMinecart cm) {
            String command = cm.getCommand();
            if (command != null && !command.isEmpty()
                    && item.getItemMeta() instanceof BlockStateMeta bsm
                    && bsm.getBlockState() instanceof CommandBlock cb) {
                cb.setCommand(command);
                bsm.setBlockState(cb);
                item.setItemMeta(bsm);
            }
            return item;
        }
        // 箱子/漏斗：把原版库存组装进"带库存的容器物品"随物品回收
        if (cart instanceof InventoryHolder holder) {
            ItemStack[] contents = holder.getInventory().getContents();
            boolean hasItems = Arrays.stream(contents).anyMatch(i -> i != null && !i.getType().isAir());
            if (hasItems && item.getItemMeta() instanceof BlockStateMeta bsm
                    && bsm.getBlockState() instanceof Container container) {
                container.getInventory().setContents(contents);
                bsm.setBlockState(container);
                item.setItemMeta(bsm);
            }
        }
        return item;
    }

    @Override
    public void onCartDestroyed(CartContext ctx) {
        Minecart cart = ctx.getMinecart();
        // 原版破坏/爆炸/击杀路径在实体死亡时已由服务端掉落并清空原版库存（die() → dropContents）；
        // 第三方 remove()/自然消失等无死亡路径不会触发掉落，这里兜底把库存剩余内容掉落，
        // 防止内容凭空消失（死亡路径库存已空，不会重复掉落）
        if (cart instanceof InventoryHolder holder) {
            for (ItemStack content : holder.getInventory().getContents()) {
                if (content != null && !content.getType().isAir()) {
                    ctx.dropItem(content);
                }
            }
            holder.getInventory().clear();
        }
        // 补掉方块本体；关闭引擎的默认整物品掉落，避免与内容物重复
        Material m = resolveMaterial(cart);
        if (m != null) {
            ctx.dropItem(new ItemStack(m));
        }
        ctx.setDropOnDestroy(false);
    }
}
