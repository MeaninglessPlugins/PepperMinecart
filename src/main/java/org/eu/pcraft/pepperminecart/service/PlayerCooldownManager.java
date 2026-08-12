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

    /** 是否处于冷却中（仅查询，不记录时间） */
    public boolean isCoolingDown(Player player, long cooldownMillis) {
        if (cooldownMillis <= 0) return false;
        Long last = lastInteractions.get(player.getUniqueId());
        return last != null && System.currentTimeMillis() - last < cooldownMillis;
    }

    /** 记录一次成功的交互时间 */
    public void markInteraction(Player player) {
        lastInteractions.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void clear(Player player) {
        lastInteractions.remove(player.getUniqueId());
    }
}
