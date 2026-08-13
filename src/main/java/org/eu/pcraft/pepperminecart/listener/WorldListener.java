package org.eu.pcraft.pepperminecart.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.eu.pcraft.pepperminecart.service.MinecartService;

/**
 * 世界事件：区块卸载时清理矿车会话，防止内存泄漏
 */
public class WorldListener implements Listener {

    private final MinecartService service;

    public WorldListener(MinecartService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        // 仅在卸载确认不会被其他插件取消时清理会话，避免误清正在编辑的容器界面
        service.handleChunkUnload(event.getChunk());
    }
}
