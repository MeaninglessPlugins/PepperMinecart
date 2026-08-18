package com.pepperminecart.cooldown;

import com.pepperminecart.config.PluginConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** 玩家交互冷却，防止快速连点。 */
public class InteractionCooldown implements Listener {

    private final PluginConfig config;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public InteractionCooldown(PluginConfig config) {
        this.config = config;
    }

    /** @return true 表示本次交互允许执行 */
    public boolean tryUse(Player player) {
        // 单调时钟：currentTimeMillis 会随系统/NTP 校时跳变（回拨冻结全部交互、前拨瞬间全部失效）
        long now = System.nanoTime();
        Long last = lastUse.get(player.getUniqueId());
        long cooldownNs = config.interactionCooldownMs() * 1_000_000L;
        if (last != null && now - last < cooldownNs) {
            return false;
        }
        lastUse.put(player.getUniqueId(), now);
        return true;
    }

    /** 玩家退出时清理冷却记录，防止离线玩家条目永久泄漏。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    /** 清空全部冷却记录（/pm reload 时调用，避免旧冷却残留影响新配置语义）。 */
    public void clear() {
        lastUse.clear();
    }
}
