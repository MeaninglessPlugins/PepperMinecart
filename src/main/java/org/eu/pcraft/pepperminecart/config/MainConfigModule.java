package org.eu.pcraft.pepperminecart.config;

import lombok.Getter;
import com.google.common.collect.Maps;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.util.List;
import java.util.Map;

@ConfigSerializable
public class MainConfigModule {
    @Getter
    boolean enableCustomInteract = true;
}
