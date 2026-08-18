package com.pepperminecart;

import com.pepperminecart.anvil.AnvilDamageTracker;
import com.pepperminecart.api.PepperMinecartAPI;
import com.pepperminecart.command.PepperMinecartCommand;
import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.container.CartSessionManager;
import com.pepperminecart.cooldown.InteractionCooldown;
import com.pepperminecart.engine.CartEngine;
import com.pepperminecart.impl.PepperMinecartAPIImpl;
import com.pepperminecart.interaction.InteractionListener;
import com.pepperminecart.registry.CartTypeRegistry;
import com.pepperminecart.registry.handler.AnvilHandler;
import com.pepperminecart.registry.handler.ContainerHandler;
import com.pepperminecart.registry.handler.DispenserCartHandler;
import com.pepperminecart.registry.handler.GenericBlockHandler;
import com.pepperminecart.registry.handler.SpecialCartHandler;
import com.pepperminecart.registry.handler.WorkstationHandler;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** PepperMinecart 主类：加载配置、注册内置矿车类型、启动引擎与监听器、注册对外服务。
 *  注意：类不可声明为 final —— MockBukkit 以 ByteBuddy 子类化主类加载插件。 */
public class PepperMinecartPlugin extends JavaPlugin {

    /** bStats 插件 ID：在 https://bstats.org 注册后替换（0 = 暂不启用统计）。 */
    private static final int BSTATS_PLUGIN_ID = 0;

    private PluginConfig config;
    private CartEngine engine;
    private CartSessionManager sessions;
    private AnvilDamageTracker anvilTracker;

    // ---- 内部访问器（测试与引擎内部装配用，非公开 API） ----

    PluginConfig pluginConfig() {
        return config;
    }

    CartEngine engine() {
        return engine;
    }

    CartSessionManager sessions() {
        return sessions;
    }

    AnvilDamageTracker anvilTracker() {
        return anvilTracker;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.config = new PluginConfig(this);
        config.reload();

        CartTypeRegistry registry = new CartTypeRegistry(getLogger());

        // 按序构建：引擎 → 铁砧追踪 → 会话管理 → 引擎注入会话（销毁时需回写容器）
        this.engine = new CartEngine(this, registry, config);
        this.anvilTracker = new AnvilDamageTracker(config, engine);
        this.sessions = new CartSessionManager(this, config, anvilTracker);
        engine.setSessionManager(sessions);

        // 内置矿车类型（全部实现 CartTypeHandler）
        registry.register(GenericBlockHandler.INSTANCE);
        registry.register(new SpecialCartHandler());
        // 内置特殊矿车类型支撑原版特殊矿车实体的识别；被注销/覆盖后死亡掉落会与
        // 原版掉落重复，因此注册后立即标记为受保护（API 注销/覆盖会被拒绝）。
        registry.protect(SpecialCartHandler.ID);
        registry.register(new ContainerHandler(sessions));
        registry.register(new WorkstationHandler(sessions));
        registry.register(new AnvilHandler(sessions));
        registry.register(new DispenserCartHandler(config, sessions));

        // 对外服务（供其他插件扩展矿车类型）：先于存量扫描注册服务。
        // 依赖本插件的扩展插件在本插件 onEnable 返回后才执行自己的 onEnable/registerCartType，
        // 因此存量扫描延迟到下一个 tick：让所有依赖插件先完成类型注册，避免扩展矿车被误解析为通用处理器。
        getServer().getServicesManager().register(PepperMinecartAPI.class,
                new PepperMinecartAPIImpl(registry, engine), this, ServicePriority.Normal);

        // 存量受管矿车：插件启用前已加载的区块不会再触发 ChunkLoadEvent，需主动扫描
        getServer().getScheduler().runTask(this, engine::registerLoadedCarts);

        // 引擎与监听器
        engine.start();
        getServer().getPluginManager().registerEvents(engine, this);
        getServer().getPluginManager().registerEvents(sessions, this);
        InteractionCooldown interactionCooldown = new InteractionCooldown(config);
        getServer().getPluginManager().registerEvents(
                new InteractionListener(this, engine, registry, config, interactionCooldown), this);
        // 冷却记录需监听玩家退出做清理（防离线条目泄漏）
        getServer().getPluginManager().registerEvents(interactionCooldown, this);

        // 命令与权限
        var cmd = getCommand("pepperminecart");
        if (cmd != null) {
            cmd.setExecutor(new PepperMinecartCommand(config, interactionCooldown));
        } else {
            // 判空后必须喊出来：命令名与 plugin.yml 不一致时静默启动会让命令永远"未知命令"，极难排查
            getLogger().severe("未找到命令 'pepperminecart'，请检查 plugin.yml 的 commands 段");
        }

        // 统计
        if (BSTATS_PLUGIN_ID > 0) {
            new Metrics(this, BSTATS_PLUGIN_ID);
        }

        getLogger().info("PepperMinecart v" + getPluginMeta().getVersion() + " 已启用");
    }

    @Override
    public void onDisable() {
        // 兜底保存打开的容器会话，防止虚拟界面物品丢失
        if (sessions != null) {
            sessions.closeAll();
        }
        if (engine != null) {
            engine.shutdown();
        }
        getLogger().info("PepperMinecart 已禁用");
    }
}
