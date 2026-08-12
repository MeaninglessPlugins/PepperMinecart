package org.eu.pcraft.pepperminecart.feature;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.eu.pcraft.pepperminecart.feature.anvil.AnvilFeature;
import org.eu.pcraft.pepperminecart.feature.container.BarrelFeature;
import org.eu.pcraft.pepperminecart.feature.container.ContainerFeature;
import org.eu.pcraft.pepperminecart.feature.container.DropperFeature;
import org.eu.pcraft.pepperminecart.feature.vanilla.ChestCartFeature;
import org.eu.pcraft.pepperminecart.feature.vanilla.FurnaceCartFeature;
import org.eu.pcraft.pepperminecart.feature.vanilla.HopperCartFeature;
import org.eu.pcraft.pepperminecart.feature.vanilla.VanillaCartFeature;
import org.eu.pcraft.pepperminecart.feature.workstation.WorkstationFeature;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 特性注册表：Material -> CartFeature 负责交互分发，名称 -> CartFeature 负责配置解析。
 * 由配置文件驱动重建；新矿车 = 新增一个 CartFeature 实现并在此注册。
 */
public class FeatureRegistry {

    private final Map<String, CartFeature> byName = new HashMap<>();
    private final Map<Material, CartFeature> byMaterial = new EnumMap<>(Material.class);

    public FeatureRegistry(Map<String, String> blockInteractions, Map<String, String> entityTransformations) {
        registerBuiltins();
        registerConfig(blockInteractions);
        registerVanilla(entityTransformations);
    }

    private void registerBuiltins() {
        // 工作站（纯开界面）
        for (Map.Entry<String, Consumer<Player>> entry : WORKSTATION_OPENERS.entrySet()) {
            register(new WorkstationFeature(entry.getKey(), entry.getValue()));
        }
        // 铁砧（开界面 + 耐久损耗）
        register(new AnvilFeature());
        // 容器
        ContainerFeature container = new ContainerFeature();
        register(container);
        BarrelFeature barrel = new BarrelFeature();
        register(barrel);
        DropperFeature dropper = new DropperFeature();
        register(dropper);

        // 潜影盒（各颜色）自动挂到通用容器特性
        for (Material material : Material.values()) {
            if (material.name().endsWith("_SHULKER_BOX")) {
                byMaterial.put(material, container);
            }
        }
        // 容器方块默认注册（配置可覆盖）
        byMaterial.put(Material.BARREL, barrel);
        byMaterial.put(Material.DROPPER, dropper);
    }

    private void registerConfig(Map<String, String> config) {
        if (config == null) return;
        for (Map.Entry<String, String> entry : config.entrySet()) {
            Material material = parseMaterial(entry.getKey());
            CartFeature feature = entry.getValue() == null ? null : byName.get(normalize(entry.getValue()));
            if (material == null || feature == null) {
                Bukkit.getLogger().warning("[PepperMinecart] 配置 block-interactions 中存在无效项: '"
                        + entry.getKey() + "=" + entry.getValue() + "'，已跳过");
                continue;
            }
            byMaterial.put(material, feature);
        }
    }

    public CartFeature get(Material material) {
        return byMaterial.get(material);
    }

    public CartFeature get(String name) {
        return byName.get(name);
    }

    /**
     * 注册原版特殊矿车特性（由 entity-transformations 配置驱动，优先级高于 block-interactions，
     * 保证方块放置/取下始终走原版矿车形态）
     */
    private void registerVanilla(Map<String, String> transformations) {
        if (transformations == null) return;
        Set<EntityType> registeredTypes = EnumSet.noneOf(EntityType.class);
        for (Map.Entry<String, String> entry : transformations.entrySet()) {
            Material material = parseMaterial(entry.getKey());
            EntityType type = parseEntityType(entry.getValue());
            if (material == null || type == null) {
                Bukkit.getLogger().warning("[PepperMinecart] 配置 entity-transformations 中存在无效项: '"
                        + entry.getKey() + "=" + entry.getValue() + "'，已跳过");
                continue;
            }
            if (!registeredTypes.add(type)) {
                Bukkit.getLogger().warning("[PepperMinecart] 配置 entity-transformations 中存在重复实体类型: '"
                        + entry.getKey() + "=" + entry.getValue() + "'，已跳过");
                continue;
            }
            CartFeature feature = createVanillaFeature(material, type);
            byName.put(feature.getName(), feature);
            byMaterial.put(material, feature);
        }
    }

    private static CartFeature createVanillaFeature(Material material, EntityType type) {
        if (material == Material.CHEST) return new ChestCartFeature(material, type);
        if (material == Material.HOPPER) return new HopperCartFeature(material, type);
        if (material == Material.FURNACE) return new FurnaceCartFeature(material, type);
        // TNT/命令方块等无库存类型
        return new VanillaCartFeature(material, type);
    }

    private static EntityType parseEntityType(String name) {
        if (name == null) return null;
        try {
            return EntityType.valueOf(normalize(name));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 注册一个新的特性实现（新矿车扩展入口） */
    public void register(String name, CartFeature feature) {
        byName.put(name, feature);
    }

    public void register(CartFeature feature) {
        byName.put(feature.getName(), feature);
    }

    public void register(Material material, CartFeature feature) {
        byMaterial.put(material, feature);
    }

    private static Material parseMaterial(String name) {
        if (name == null) return null;
        return Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }

    private static String normalize(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    /**
     * 工作站配置词汇 -> 打开对应界面的动作（原 InteractionType 枚举吸收至此）
     */
    private static final Map<String, Consumer<Player>> WORKSTATION_OPENERS = Map.of(
            "WORKBENCH", p -> p.openWorkbench(null, true),
            "GRINDSTONE", p -> p.openGrindstone(null, true),
            "LOOM", p -> p.openLoom(null, true),
            "CARTOGRAPHY_TABLE", p -> p.openCartographyTable(null, true),
            "SMITHING_TABLE", p -> p.openSmithingTable(null, true),
            "STONECUTTER", p -> p.openStonecutter(null, true),
            "ENCHANTING_TABLE", p -> p.openEnchanting(null, true)
    );
}
