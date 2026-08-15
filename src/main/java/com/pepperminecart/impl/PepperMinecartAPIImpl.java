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
        registry.register(handler);
    }

    @Override
    public void unregisterCartType(NamespacedKey id) {
        registry.unregister(id);
    }

    @Override
    public CartTypeHandler getCartType(NamespacedKey id) {
        return registry.byId(id);
    }

    @Override
    public boolean isManagedCart(Minecart cart) {
        return engine.isManaged(cart);
    }

    @Override
    public CartContext getContext(Minecart cart) {
        return engine.contextOrNull(cart);
    }
}
