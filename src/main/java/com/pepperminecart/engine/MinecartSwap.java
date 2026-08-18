package com.pepperminecart.engine;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Minecart;
import org.bukkit.util.Vector;

/**
 * 矿车实体替换公共原语：捕获运动状态（位置/速度/朝向）→ 在相同位置生成替代矿车 → 应用运动状态。
 *
 * <p>供两处使用：特殊矿车转换（SpecialCartHandler#onPlaced 的 spawn-and-swap）与
 * 取下还原（CartEngine#takeOff 特殊矿车 → 普通矿车）。PDC/自定义名/乘客迁移属于上层职责
 * （CartContext#replaceEntity），不在本工具范围内。</p>
 */
public final class MinecartSwap {

    private MinecartSwap() {
    }

    /** 矿车运动状态快照。 */
    public record Motion(Location location, Vector velocity, float yaw, float pitch) {
    }

    /** 捕获矿车当前运动状态。 */
    public static Motion capture(Minecart cart) {
        Location loc = cart.getLocation();
        if (loc == null) {
            throw new IllegalStateException("矿车不在世界中，无法捕获运动状态: " + cart.getUniqueId());
        }
        return new Motion(loc.clone(), cart.getVelocity(), loc.getYaw(), loc.getPitch());
    }

    /** 把运动状态应用到目标矿车。 */
    public static void apply(Minecart cart, Motion motion) {
        cart.setVelocity(motion.velocity());
        cart.setRotation(motion.yaw(), motion.pitch());
    }

    /**
     * 在快照位置（同世界）生成替代矿车并应用运动状态。
     *
     * @throws RuntimeException 生成被取消/失败时（由调用方决定回滚策略）
     */
    public static <T extends Minecart> T spawnReplacement(Motion motion, Class<T> type) {
        World world = motion.location().getWorld();
        if (world == null) {
            throw new IllegalStateException("运动快照中的世界不可用，无法生成替代矿车");
        }
        T replacement = world.spawn(motion.location().clone(), type);
        if (replacement == null) {
            throw new IllegalStateException("生成替代矿车失败: " + type.getSimpleName());
        }
        apply(replacement, motion);
        return replacement;
    }
}
