package org.eu.pcraft.pepperminecart.config;

import lombok.Getter;
import com.google.common.collect.Maps;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.util.List;
import java.util.Map;

@ConfigSerializable
@Getter
public class MainConfigModule {
    public boolean enableCustomInteract = true;
}
