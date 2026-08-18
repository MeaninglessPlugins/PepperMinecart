package com.pepperminecart.api.event;

import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 矿车语义的“放置方块”保护事件：在玩家把方块放到矿车上之前触发。
 * 保护/领地插件可监听并取消本事件以阻止放置；本插件还保留 BlockPlaceEvent 模拟以兼容旧保护插件。
 */
public class CartBlockPlaceEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Minecart cart;
    private final Material material;
    private boolean cancelled;

    public CartBlockPlaceEvent(Player player, Minecart cart, Material material) {
        this.player = player;
        this.cart = cart;
        this.material = material;
    }

    public Player getPlayer() {
        return player;
    }

    public Minecart getCart() {
        return cart;
    }

    public Material getMaterial() {
        return material;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
