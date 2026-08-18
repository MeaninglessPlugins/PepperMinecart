package com.pepperminecart.impl;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.PepperMinecartAPI;
import com.pepperminecart.engine.CartEngine;
import com.pepperminecart.registry.CartTypeRegistry;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;

/** PepperMinecartAPI 服务实现。 */
public class PepperMinecartAPIImpl implements PepperMinecartAPI {

    private final CartTypeRegistry registry;
    private final CartEngine engine;

    public PepperMinecartAPIImpl(CartTypeRegistry registry, CartEngine engine) {
        this.registry = registry;
        this.engine = engine;
    }

    @Override
    public void registerCartType(CartTypeHandler handler) {
        // 入口前置校验：null handler/id 若放进注册表，会在更远的地方（addCart 的
        // type.getId().toString()）才炸，排查成本高
        if (handler == null) {
            throw new IllegalArgumentException("handler 不能为 null");
        }
        if (handler.getId() == null) {
            throw new IllegalArgumentException("handler.getId() 不能为 null");
        }
        registry.register(handler);
        // 类型表变化后重扫：已跟踪 context 按新注册表刷新；已加载区块的存量矿车也补扫
        if (engine != null) {
            engine.rescanManagedContexts();
            engine.registerLoadedCarts();
        }
    }

    @Override
    public void unregisterCartType(NamespacedKey id) {
        registry.unregister(id);
        // 注销后立即重扫已跟踪 context，避免旧 handler 继续 tick/交互
        if (engine != null) {
            engine.rescanManagedContexts();
        }
    }

    @Override
    public CartTypeHandler getCartType(NamespacedKey id) {
        return registry.byId(id);
    }

    @Override
    public boolean isManagedCart(Minecart cart) {
        if (cart == null) {
            throw new IllegalArgumentException("cart 不能为 null");
        }
        return engine.isManaged(cart);
    }

    @Override
    public CartContext getContext(Minecart cart) {
        if (cart == null) {
            throw new IllegalArgumentException("cart 不能为 null");
        }
        return engine.contextOrNull(cart);
    }
}
