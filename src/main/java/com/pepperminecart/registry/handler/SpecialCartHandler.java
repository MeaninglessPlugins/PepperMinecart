package com.pepperminecart.registry.handler;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.VanillaCartSupport;
import com.pepperminecart.delivery.ItemDelivery;
import com.pepperminecart.engine.MinecartSwap;
import com.pepperminecart.storage.CartData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.Container;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
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
        if (m == null) {
            // 异常数据（无原始材质）：switch 匹配 null 会抛 NPE；显式失败让 placeBlock 走回滚，
            // 物品不消耗、矿车恢复为空车
            throw new IllegalStateException("特殊矿车放置时无法解析原始材质，本次放置已取消");
        }
        MinecartSwap.Motion motion = MinecartSwap.capture(cart);

        Minecart replacement = switch (m) {
            case CHEST -> MinecartSwap.spawnReplacement(motion, StorageMinecart.class);
            case HOPPER -> MinecartSwap.spawnReplacement(motion, HopperMinecart.class);
            case FURNACE -> MinecartSwap.spawnReplacement(motion, PoweredMinecart.class);
            case TNT -> MinecartSwap.spawnReplacement(motion, ExplosiveMinecart.class);
            case COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK -> {
                CommandMinecart cm = MinecartSwap.spawnReplacement(motion, CommandMinecart.class);
                // 从物品 BlockEntityTag 恢复命令（放置带命令的命令方块物品时保留命令，不丢失）
                ItemStack blockItem = ctx.getBlockItem();
                if (blockItem != null && blockItem.getItemMeta() instanceof BlockStateMeta bsm
                        && bsm.getBlockState() instanceof CommandBlock cb) {
                    String command = cb.getCommand();
                    cm.setCommand(command != null ? command : "");
                } else {
                    cm.setCommand("");
                }
                yield cm;
            }
            // 未知材质绝不能静默当作命令矿车处理：将来往 MATERIALS 新增材质而漏写 case 时应立即暴露
            default -> throw new IllegalStateException("未处理的特殊矿车材质: " + m);
        };

        // 先替换上下文：让 placeBlock 的异常回滚能通过 ctx.lastReplacement() 清理 replacement
        ctx.replaceEntity(replacement); // 内部已清空旧矿车 PDC/显示

        // 物品携带的容器内容（如"带库存的箱子物品"）转移到原版矿车库存；
        // 目标库存放不下时返回剩余物品，随后掉落到矿车位置，避免静默丢失。
        ItemStack placed = ctx.getBlockItem();
        List<ItemStack> leftovers = transferContents(replacement, placed);

        // 先原子掉落溢出内容并记录已生成实体：任一掉落被取消/失败时抛异常让 placeBlock
        // 回滚整个放置（dropAllTracked 内部已回滚部分成功）；不能先 strip 模板再掉落——
        // 否则 leftover 丢失后无法找回。
        ItemDelivery.DropResult leftoverDrop = null;
        if (!leftovers.isEmpty()) {
            leftoverDrop = ItemDelivery.dropAllTracked(ctx.getWorld(), ctx.getLocation(),
                    leftovers.toArray(ItemStack[]::new));
            if (leftoverDrop.result() != ItemDelivery.Result.DELIVERED) {
                throw new IllegalStateException("特殊矿车放置时溢出内容掉落失败，本次放置已取消");
            }
        }

        // 掉落成功后仍有两个可失败步骤（PDC 写入模板、移除旧实体）：任一失败都必须回收
        // 已生成的溢出掉落，否则 placeBlock 回滚恢复主手物品后地面残留一份 = 复制。
        try {
            // 原始材质（命令方块三变体区分）由 CartData.ORIGINAL 承载：addCart 时已写入，
            // replaceEntity 随 PDC 复制到新实体；内容已转移进原版库存，不随模板重复保存。
            // 把"去内容模板"写入 PDC：取下/销毁时基于模板重建物品，保留放置物品的自定义名/
            // Lore 等 ItemMeta（否则还原成白板物品，改名/附魔等全部丢失）。
            if (placed != null) {
                CartData.setItem(replacement, stripContents(placed));
            }
            cart.remove();
        } catch (RuntimeException ex) {
            if (leftoverDrop != null) {
                for (Item e : leftoverDrop.spawned()) {
                    try {
                        e.remove();
                    } catch (RuntimeException ignored) {
                        // 单个实体无法移除时继续回收其余实体，保留原始异常供 placeBlock 回滚
                    }
                }
            }
            throw ex;
        }
    }

    /** 把放置物品 BlockStateMeta 中 Container 的内容转移到新矿车库存（有内容才转移；
     *  目标库存更小时只转移能容纳的部分，防止 setContents 因数组超长抛 IllegalArgumentException）。
     *  目标矿车没有原版库存时（如 PoweredMinecart 熔炉矿车不实现 InventoryHolder），
     *  内容必须全部作为 leftovers 返回给调用方掉落——绝不能静默丢弃。
     *  @return 目标库存放不下（或目标无库存）时需要掉落的剩余物品 */
    static List<ItemStack> transferContents(Minecart replacement, ItemStack placedItem) {
        List<ItemStack> leftovers = new ArrayList<>();
        if (placedItem == null || !(placedItem.getItemMeta() instanceof BlockStateMeta bsm)) {
            return leftovers;
        }
        if (!(bsm.getBlockState() instanceof Container container)) {
            return leftovers;
        }
        if (!(replacement instanceof InventoryHolder holder)) {
            // 目标矿车没有库存（熔炉矿车等）：内容无法承载，全部退回掉落，避免 stripContents 静默清空
            for (ItemStack content : container.getInventory().getContents()) {
                if (content != null && !content.getType().isAir()) {
                    leftovers.add(content);
                }
            }
            return leftovers;
        }
        ItemStack[] contents = container.getInventory().getContents();
        boolean hasItems = Arrays.stream(contents).anyMatch(i -> i != null && !i.getType().isAir());
        if (!hasItems) {
            return leftovers;
        }
        Inventory target = holder.getInventory();
        int copyCount = Math.min(contents.length, target.getSize());
        // 逐格放入，避免不同 Paper 版本对 setContents 数组长度要求不一致
        for (int i = 0; i < copyCount; i++) {
            if (contents[i] != null && !contents[i].getType().isAir()) {
                target.setItem(i, contents[i]);
            }
        }
        // 目标库存比源大时清空剩余槽（新实体本应为空，防御性处理）
        for (int i = copyCount; i < target.getSize(); i++) {
            target.setItem(i, null);
        }
        // 源比目标大时，多出的部分返回给调用方掉落
        for (int i = copyCount; i < contents.length; i++) {
            if (contents[i] != null && !contents[i].getType().isAir()) {
                leftovers.add(contents[i]);
            }
        }
        return leftovers;
    }

    /** 移除容器物品的内容物，保留其余 ItemMeta（自定义名/Lore 等）作为重建模板；
     *  内容已转移进原版矿车库存，模板中重复保存会导致取下时内容翻倍。 */
    private static ItemStack stripContents(ItemStack item) {
        ItemStack stripped = item.clone();
        if (stripped.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof Container container) {
            container.getInventory().clear();
            bsm.setBlockState(container);
            stripped.setItemMeta(bsm);
        }
        return stripped;
    }

    /** 生成特殊矿车对应的方块物品：存储模板优先（保留放置时的自定义名/Lore 等 ItemMeta），
     *  无模板时按材质新建；命令矿车把实体当前命令写回物品。
     *  @param embedContents true 时把原版库存内容组装进容器物品（取下路径）；
     *                       false 时内容由调用方另行处理（销毁路径逐格掉落，避免重复） */
    private static ItemStack buildTakeOffItem(Minecart cart, Material material, boolean embedContents) {
        ItemStack template = CartData.getItem(cart);
        ItemStack item = template != null ? template.clone() : new ItemStack(material);
        if (cart instanceof CommandMinecart cm) {
            String command = cm.getCommand();
            if (command != null
                    && item.getItemMeta() instanceof BlockStateMeta bsm
                    && bsm.getBlockState() instanceof CommandBlock cb) {
                // 空命令也要写回：玩家清空实体命令后取下，模板物品里的旧命令必须被覆盖掉
                cb.setCommand(command);
                bsm.setBlockState(cb);
                item.setItemMeta(bsm);
            }
        } else if (embedContents && cart instanceof InventoryHolder holder) {
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
    public boolean onInteract(Player player, CartContext ctx) {
        return false; // 箱子/漏斗/熔炉矿车使用原版界面（TNT/命令矿车无界面）
    }

    @Override
    public ItemStack getTakeOffItem(CartContext ctx) {
        Minecart cart = ctx.getMinecart();
        // 转换中间状态防御：实体不是特殊矿车（普通矿车带 special PDC，如转换中断）且存储物品
        // 仍在时，原样归还存储物品（含容器内容），避免重建白板物品导致内容丢失
        if (ctx.getBlockItem() != null && !(cart instanceof InventoryHolder) && !(cart instanceof CommandMinecart)) {
            return ctx.getBlockItem().clone();
        }
        Material m = resolveMaterial(cart);
        if (m == null) {
            return null;
        }
        // 模板优先保留 ItemMeta；箱子/漏斗把原版库存组装进"带库存的容器物品"随物品回收
        return buildTakeOffItem(cart, m, true);
    }

    @Override
    public void onCartDestroyed(CartContext ctx) {
        Minecart cart = ctx.getMinecart();
        // DEATH 且 DO_ENTITY_DROPS=true 时引擎直接采用原版掉落，不会进入本方法；
        // 这里处理 DO_ENTITY_DROPS=false 的死亡以及第三方 remove()/自然消失等无原版掉落路径，
        // 把库存剩余内容掉落，防止内容凭空消失。
        if (cart instanceof InventoryHolder holder) {
            List<ItemStack> contents = new ArrayList<>();
            for (ItemStack content : holder.getInventory().getContents()) {
                if (content != null && !content.getType().isAir()) {
                    contents.add(content);
                }
            }
            // 批量掉落具备原子语义（引擎的 CartContextImpl 走 ItemDelivery.dropAll，失败回滚已生成掉落）：
            // 若逐格掉落，中途被取消时“地面一份 + 库存一份”会在后续原版掉落路径中变成复制源。
            // 只有全部内容都真实掉落才清空；失败时保留原版库存，已生成的地面掉落由批量原语回滚。
            if (ctx.tryDropItems(contents.toArray(ItemStack[]::new))) {
                holder.getInventory().clear();
            }
        }
        // 补掉方块物品：模板优先保留 ItemMeta（内容已逐格掉落，不再嵌入物品）；
        // 命令矿车会保留命令，避免销毁后命令丢失
        Material m = resolveMaterial(cart);
        if (m != null) {
            ItemStack blockItem = buildTakeOffItem(cart, m, false);
            if (!ctx.tryDropItem(blockItem)) {
                Bukkit.getLogger().warning("[PepperMinecart] 特殊矿车销毁时方块物品掉落被取消: " + cart.getUniqueId());
            }
        }
        ctx.setDropOnDestroy(false);
    }
}
