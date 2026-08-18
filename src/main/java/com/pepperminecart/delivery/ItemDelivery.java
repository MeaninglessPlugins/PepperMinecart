package com.pepperminecart.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 统一的物品交付/掉落原语。
 *
 * <p>Bukkit 中 {@link World#dropItemNaturally(Location, ItemStack)} 会触发可被其他插件取消的
 * {@code ItemSpawnEvent}；事件被取消时返回 null，物品实体不会生成。调用方必须依据返回值决定
 * 是否清空源槽位/源库存，否则会出现“容器已扣物品、地上却没有”的静默丢失。
 *
 * <p>本类保证“部分成功可回滚”：dropAll 中途被取消时，已经生成的掉落实体会被移除；
 * giveOrDrop 先放入背包、再掉落剩余部分时，如果掉落失败，已放入背包的部分也会被收回。
 * 调用方只有在返回 DELIVERED 时才可以清空源数据。</p>
 */
public final class ItemDelivery {

    /** 交付结果：源槽位只有在 DELIVERED 时才允许清空。 */
    public enum Result {
        /** 已全部进入玩家背包或全部成功掉落。 */
        DELIVERED,
        /** 掉落事件被其他插件取消（或部分取消），物品未被安全交付。 */
        CANCELED,
        /** 无可用掉落位置，物品未被交付。 */
        NO_LOCATION
    }

    /** dropAll 的详细结果：包含结果与成功生成的掉落实体（DELIVERED 时可用于后续回滚）。 */
    public record DropResult(Result result, List<Item> spawned) {
    }

    private ItemDelivery() {
    }

    /**
     * 先尝试放入玩家背包；放不下的部分掉落到 {@code fallbackWorld}/{@code fallbackLocation}。
     * 任一次掉落被取消或位置不可用，返回非 DELIVERED，并回滚已经放入背包的部分。
     */
    public static Result giveOrDrop(Player player, ItemStack item, World fallbackWorld, Location fallbackLocation) {
        if (item == null || item.getType().isAir()) {
            return Result.DELIVERED;
        }
        if (player != null) {
            ItemStack original = item.clone();
            Map<Integer, ItemStack> left = player.getInventory().addItem(item.clone());
            if (left.isEmpty()) {
                return Result.DELIVERED;
            }
            ItemStack[] leftovers = left.values().toArray(ItemStack[]::new);
            DropResult drop;
            try {
                drop = dropAllTracked(fallbackWorld, fallbackLocation, leftovers);
            } catch (RuntimeException ex) {
                // dropAllTracked 已在内部回滚已生成掉落；这里再收回已放入背包的部分，
                // 避免“背包多收、源未清”的复制，然后保留异常供调用方诊断。
                rollbackInventoryAdd(player, original, left);
                throw ex;
            }
            if (drop.result() == Result.DELIVERED) {
                return Result.DELIVERED;
            }
            // 回滚已经放入背包的部分，避免“背包多收、源未清”的复制。
            rollbackInventoryAdd(player, original, left);
            return drop.result();
        }
        return dropAll(fallbackWorld, fallbackLocation, item);
    }

    /** 收回 addItem 已放入背包的部分：original - leftover 之和 = 实际放入量。
     *  回滚本身失败时显式抛出，调用方必须保留源数据，不能继续清空源槽位。 */
    private static void rollbackInventoryAdd(Player player, ItemStack original, Map<Integer, ItemStack> left) {
        int added = original.getAmount();
        for (ItemStack leftover : left.values()) {
            added -= leftover.getAmount();
        }
        if (added > 0) {
            ItemStack rollback = original.clone();
            rollback.setAmount(added);
            try {
                Map<Integer, ItemStack> failed = player.getInventory().removeItem(rollback);
                if (!failed.isEmpty()) {
                    throw new IllegalStateException("回滚已放入背包的物品失败（removeItem 返回未移除项），源数据必须保留: " + failed);
                }
            } catch (RuntimeException ex) {
                throw new IllegalStateException("回滚已放入背包的物品失败，源数据必须保留", ex);
            }
        }
    }

    /**
     * 掉落一个物品；返回生成的实体。世界/位置不可用或 {@code ItemSpawnEvent} 被取消时返回 null。
     */
    public static Item dropItem(World world, Location location, ItemStack item) {
        if (world == null || location == null || item == null || item.getType().isAir()) {
            return null;
        }
        return world.dropItemNaturally(location, item.clone());
    }

    /**
     * 依次掉落全部物品：只有全部成功才返回 DELIVERED；
     * 中途有掉落被取消返回 CANCELED，并且已经生成的掉落实体会被回滚移除；位置不可用返回 NO_LOCATION。
     */
    public static Result dropAll(World world, Location location, ItemStack... items) {
        return dropAllTracked(world, location, items).result();
    }

    /** 移除已生成的全部掉落实体；单个 remove 失败不阻断其余回滚。 */
    private static void removeSpawned(List<Item> spawned) {
        for (Item spawnedItem : spawned) {
            try {
                spawnedItem.remove();
            } catch (RuntimeException ignored) {
                // 回滚尽力而为：单个实体已无法移除时继续处理其余实体
            }
        }
    }

    /**
     * 依次掉落全部物品并返回生成的实体；失败时回滚已生成实体，spawned 为空。
     * 调用方可以在后续步骤失败时使用 DELIVERED 返回的 spawned 列表再次回滚。
     */
    public static DropResult dropAllTracked(World world, Location location, ItemStack... items) {
        if (world == null || location == null || location.getWorld() == null) {
            return new DropResult(Result.NO_LOCATION, List.of());
        }
        List<Item> spawned = new ArrayList<>();
        try {
            for (ItemStack item : items) {
                if (item == null || item.getType().isAir()) {
                    continue;
                }
                Item entity = world.dropItemNaturally(location, item.clone());
                if (entity == null) {
                    removeSpawned(spawned);
                    return new DropResult(Result.CANCELED, List.of());
                }
                spawned.add(entity);
            }
        } catch (RuntimeException ex) {
            // dropItemNaturally 可能抛 IllegalArgumentException（如位置世界与传入世界不一致）。
            // 与返回 null 的取消路径一样：必须回滚已经生成的地面物品，再保留异常供诊断。
            removeSpawned(spawned);
            throw ex;
        }
        return new DropResult(Result.DELIVERED, List.copyOf(spawned));
    }
}
