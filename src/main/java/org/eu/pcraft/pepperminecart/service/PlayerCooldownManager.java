package org.eu.pcraft.pepperminecart.service;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家交互冷却：按玩家记录最近一次交互时间，间隔内拒绝重复交互
 */
public class PlayerCooldownManager {

    private final Map<UUID, Long> lastInteractions = new HashMap<>();

    /** 返回 true 表示允许本次交互 */
    public boolean tryConsume(Player player, long cooldownMillis) {
        if (cooldownMillis <= 0) return true;
        long now = System.currentTimeMillis();
        Long last = lastInteractions.get(player.getUniqueId());
        if (last != null && now - last < cooldownMillis) return false;
        lastInteractions.put(player.getUniqueId(), now);
        return true;
    }

    public void clear(Player player) {
        lastInteractions.remove(player.getUniqueId());
    }
}
