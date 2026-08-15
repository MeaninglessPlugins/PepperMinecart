package com.pepperminecart.container;

import org.bukkit.block.Container;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * 矿车打开中的界面会话基类（按会话类型多态化，取代旧的 Kind 枚举分派）：
 * <ul>
 *   <li>{@link ContainerCartSession}：插件容器（同引用实时库存，编辑即时生效，关闭/回写时写回 PDC）；</li>
 *   <li>{@link VanillaCartSession}：原版虚拟界面（工作台/附魔台等，关闭时回收残留物品）；</li>
 *   <li>{@link AnvilCartSession}：铁砧（回收残留 + 耐久判定）。</li>
 * </ul>
 */
public abstract class CartSession {

    protected final Minecart cart;
    protected final Inventory top;
    protected final InventoryView view;

    CartSession(Minecart cart, Inventory top, InventoryView view) {
        this.cart = cart;
        this.top = top;
        this.view = view;
    }

    public final Minecart cart() {
        return cart;
    }

    public final Inventory top() {
        return top;
    }

    /** 该矿车会话对应的实时容器引用；非容器会话返回 null。 */
    public Container liveContainer() {
        return null;
    }

    /** 回写持久化数据（容器会话写 PDC；其余无操作）。取下/销毁前由管理器统一调用。 */
    public void flush() {
    }

    /** 玩家关闭界面时的会话行为（容器写回、虚拟界面回收残留等）。 */
    public abstract void onPlayerClose(Player player);
}
