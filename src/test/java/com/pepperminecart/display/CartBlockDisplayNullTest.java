package com.pepperminecart.display;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.minecart.RideableMinecart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** CartBlockDisplay 入口校验：null 必须给出明确异常，而不是深层 NPE。 */
class CartBlockDisplayNullTest {

    private ServerMock server;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("test");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void setRejectsNullMaterial() {
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        assertThrows(IllegalArgumentException.class, () -> new CartBlockDisplay(cart).set((Material) null));
    }

    @Test
    void setRejectsNullBlockData() {
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        assertThrows(IllegalArgumentException.class,
                () -> new CartBlockDisplay(cart).set((org.bukkit.block.data.BlockData) null));
    }
}
