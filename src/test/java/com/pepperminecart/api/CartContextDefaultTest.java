package com.pepperminecart.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** CartContext 默认方法的契约测试：空批/null 批不得 NPE，按“无需处理”返回 true。 */
class CartContextDefaultTest {

    private static class NoopContext implements CartContext {
        @Override public Minecart getMinecart() { return null; }
        @Override public CartTypeHandler getType() { return null; }
        @Override public World getWorld() { return null; }
        @Override public Location getLocation() { return null; }
        @Override public Entity getRider() { return null; }
        @Override public boolean hasRider() { return false; }
        @Override public Material getOriginalMaterial() { return null; }
        @Override public ItemStack getBlockItem() { return null; }
        @Override public void setDisplay(Material material) { }
        @Override public void setDisplay(BlockData data) { }
        @Override public Material getDisplayMaterial() { return null; }
        @Override public boolean hasDisplay() { return false; }
        @Override public void setDisplayOffset(int offset) { }
        @Override public int getDisplayOffset() { return 0; }
        @Override public void setMetaInt(NamespacedKey key, int value) { }
        @Override public int getMetaInt(NamespacedKey key, int defaultValue) { return defaultValue; }
        @Override public void setMetaString(NamespacedKey key, String value) { }
        @Override public String getMetaString(NamespacedKey key) { return null; }
        @Override public void setCooldown(NamespacedKey key, int ticks) { }
        @Override public boolean hasCooldown(NamespacedKey key) { return false; }
        @Override public void giveItem(Player player, ItemStack item) { }
        @Override public void dropItem(ItemStack item) { }
        @Override public void replaceEntity(Minecart newCart) { }
        @Override public void releaseCart() { }
        @Override public void setDropOnDestroy(boolean drop) { }
    }

    @Test
    void defaultTryDropItems_nullBatch_returnsTrueWithoutNpe() {
        CartContext ctx = new NoopContext();
        assertTrue(ctx.tryDropItems((ItemStack[]) null), "null 批应视为无需处理的空批，返回 true");
    }
}
