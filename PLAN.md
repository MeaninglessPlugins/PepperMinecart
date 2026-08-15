# PepperMinecart 需求审查与实现计划

> 版本：v1.1（扩展模型修订：统一 CartTypeHandler + CartEngine）　|　状态：待确认后实施　|　目标平台：Paper 1.21+（Java 21）

> **实施状态（2026-08）：已全部落地。** 主源码编译通过；8 个单元测试通过；本地 Paper 1.21.4 真实服务器冒烟通过（插件加载、config.yml 自动生成、`/pm reload` 热重载、正常卸载、日志无异常）；产物 `build/libs/PepperMinecart-1.0.0.jar`（Shadow 打包 bStats）。**已按参考项目（F:\PepperMinecart）完成第二轮重构**：VehicleMoveEvent 事件驱动投掷器、EntityRemoveEvent 统一销毁清理、铁砧 PrepareAnvil 标记 + 延迟确认、放置失败回滚、实体替换迁移名与乘客、getTicksLived 冷却、整物品持久化（PDC 单键存完整 ItemStack，容器内容随物品回收，特殊矿车按实体类型反查材质并组装"带库存的容器物品"）——详见 `COMPARISON.md` §5"已合入"。未覆盖项：Paper test framework 集成测试（paper-test-lib 构件在当前网络不可获取，可后续补充）；`display-block-offset` 默认值 6 需目测定标。

> **第三轮重构（长期维护精修，已完成）**：① 引入 MockBukkit（`org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.45.0`，对齐 paper-api 1.21.4）集成测试 12 例覆盖放置/取下三策略/销毁不复制/容器回写/铁砧状态机/特殊矿车转换/冷却语义，修复 `CartData.clear()` 在活视图上边遍历边删除的 ConcurrentModificationException 隐患；② 冷却统一为世界年龄语义（`CartContext.setCooldown/hasCooldown` 与投掷器共用一套，删除引擎 `consumeCooldown` 与系统时钟路径）；③ 取下编排从 `InteractionListener` 移入 `CartEngine.takeOff`，`onTakeOff` 改为返回 `TakeOffOutcome`（DEFAULT/HANDLED），删除 `replaced` 标记协议；新增 `MinecartSwap` 公共原语统一特殊矿车转换与取下还原的 spawn-and-swap；④ 引擎不再感知具体处理器：新增 `VanillaCartSupport` 能力接口识别原版特殊矿车，删除 `SpecialCartHandler.ORIGINAL_MATERIAL` 冗余 PDC 键（统一用 `CartData.ORIGINAL`）；⑤ `ContainerSession` 拆分为 `CartSessionManager` + `CartSession` 多态层次（`ContainerCartSession`/`VanillaCartSession`/`AnvilCartSession`），Kind 枚举分派删除，会话查找改按 UUID；主类去 `final`（MockBukkit 代理加载要求）。全部 27 项测试通过，`./gradlew build` 产出正常。

---

## 1. 需求审查结论

对需求文档逐条审查，结论：**需求整体清晰、可行，全部可用纯 Bukkit/Paper API 实现，无需 NMS 与 paperweight 补丁**。下表为逐条映射与注意点。

| 需求 | 实现方式（API 映射） | 审查注意点 |
|---|---|---|
| 2.1 放置方块 | `PlayerInteractEntityEvent`（Paper 提供 `getHand()`）+ `Player#isSneaking()`；空普通矿车 = `RideableMinecart` 且无乘客、无显示方块 | 需同时取消 `PlayerInteractAtEntityEvent` 防重复处理；"有人乘坐"指真实乘客（显示方块是矿车自身属性，不占乘客位） |
| 2.1 特殊矿车转换 | 箱子/漏斗/熔炉/TNT/命令方块 → 移除普通矿车，`world.spawn(loc, ChestMinecart/HopperMinecart/FurnaceMinecart/ExplosiveMinecart/CommandMinecart.class)`，复制位置与速度 | CommandMinecart 命令存原版 NBT，自动持久化 |
| 2.2 取下 | 潜行+右键有方块矿车；策略 `INVENTORY \| DROP \| DISABLED`；特殊矿车还原为普通矿车 | 特殊矿车库存去向需定义（见决策 D6）；潜影盒始终进背包且内容写入物品 NBT |
| 2.3 方块显示 | **原版显示方块 API**：`Minecart#setDisplayBlockData(BlockData)` + `setDisplayBlockOffset(int)`，方块作为实体自身 `DisplayState` NBT 渲染 | 已对 1.21/1.21.1/1.21.3/1.21.4 的 paper-api jar 做 javap 验证，全部可用；无额外实体，无幽灵/同步问题；清除用 AIR（API 无 hasDisplayBlock） |
| 2.3 工作站 | `openWorkbench/openGrindstone/openLoom/openCartographyTable/openSmithingTable/openStonecutter/openEnchanting(location, force=true)` 原生虚拟界面 | 关闭时残留物品（合成格等）会被原版丢弃到地面，需在 `InventoryCloseEvent` 回收进玩家背包 |
| 2.3 铁砧 | `openAnvil(location, true)` | 同上回收逻辑；耐久状态存 PDC |
| 2.3 容器 | 自定义 `Bukkit.createInventory`，尺寸取自 `Material.createBlockData().createBlockState()` 的 `InventoryHolder`；内容序列化存 PDC | 点击即回写 PDC（实时回写）；木桶动画见决策 D1 |
| 2.4 铁砧耐久 | 铁砧视图结果格（slot 2）被取走时按概率推进状态机：正常→损坏→报废；显示方块（`setDisplayBlockData`）同步切换为 chipped/damaged 铁砧 | 报废含义见决策 D7；该机制同时给"显示方块"提供了状态可视化 |
| 2.5 发射器矿车 | 普通矿车挂 DISPENSER（不转换）；Paper `VehicleMoveEvent` 检测压过充能 ACTIVATOR_RAIL（`Rail#isPowered()`），冷却后随机弹出 1 物品 | 发射间隔、偏移全部可配置；库存走 PDC |
| 2.6 冷却/音效 | 玩家 UUID → 时间戳 Map；`Sound` 播放（放置/取下可分别配置开关） | 内存态即可，无需持久化 |
| 2.7 数据安全 | 方块类型随实体 **NBT（DisplayState）** 自动保存，容器等业务数据存 PDC；`EntityDeathEvent` 掉落方块+内容；`ChunkLoadEvent` 校验显示状态与 PDC 一致，异常时修复 | 方块显示是实体自身属性，死亡/卸载无残留，天然防复制；崩溃时最多丢失一次自动保存间隔内的改动（与原版容器同级） |
| 2.8 配置/命令 | 带注释的 `config.yml` 随 jar 发布 + `saveDefaultConfig()`；`/pepperminecart reload`（别名 `pm`） | 重载需重建注册表/冷却等运行时状态 |
| 2.9 权限 | plugin.yml 声明 `pepperminecart.use`（default: true）、`pepperminecart.reload`（default: op） | — |
| 2.10 统计 | bStats（`org.bstats:bstats-bukkit`，Shadow 打包） | 插件 ID 需在 bstats.org 注册后填入（先用占位） |
| 3 兼容性 | `api-version: '1.21'`；只用 1.19.4 及更早已存在的 API（避免 1.21.2+ 新 API，如 EntityLoadEvent） | 编译用 1.21.4 API，运行兼容全部 1.21+ |
| 3 可扩展性 | `CartTypeHandler` 统一扩展接口 + `CartTypeRegistry` + `CartEngine`（tick/乘坐/移动事件路由）；`PepperMinecartAPI` 通过 Bukkit `ServicesManager` 注册为服务 | 新"矿车类型"（含行为型，如钟乳石扣血矿车）= 实现接口 + 注册，零核心改动（见 D11 与 §3 示例） |
| 3 易用性 | 交互逻辑单一入口、配置全注释 | — |

### 1.1 审查发现的问题与歧义（已给出决策，见 §2 决策记录）

1. **木桶开合动画**：原生 API 无法对虚拟容器触发方块动画。决策：以开合音效实现（D1）。
2. **特殊矿车取下时库存去向**：需求未明确。决策：背包优先、溢出落地（D6）。
3. **铁砧"报废消失"**：是否掉落物品未明确。决策：直接消失，可配置（D7）。
4. **领地保护协作**：实体交互不触发 BlockPlaceEvent。决策：可选模拟 BlockPlaceEvent 校验（D8）。
5. **Folia**：需求未要求。决策：本期仅支持 Paper（D9）。
6. **bStats 插件 ID**：需注册（D10）。
7. **"挂块矿车禁止乘坐"（2.3）与自定义类型可能需要允许乘坐**：乘坐策略下放给扩展类型，`allowRiding()` 默认 false，行为型类型可开启（D11）。

---

## 2. 实现方式决定（技术选型与关键决策）

### D1 技术栈
- **构建**：Gradle（Kotlin DSL）+ Wrapper。本机无 Gradle，先从 services.gradle.org 下载发行版引导生成 wrapper（Gradle 9.x，兼容本机 JDK 25 运行）。
- **依赖**：`io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT`（PaperMC Maven 仓库，已验证可达）+ `org.bstats:bstats-bukkit`（Shadow 插件打包进 jar）。
- **Java**：目标 21（toolchain 21，本机 JDK 25 以 `--release 21` 编译，必要时用 foojay 插件自动下载 JDK 21）。
- **不引入**：NMS、paperweight、ProtocolLib —— 全部功能纯 API 可完成，最大程度保证 1.21+ 全版本兼容与升级简单。
- **包名**：`com.pepperminecart`（可改）。

### D2 方块显示方案：原版显示方块 API（`Minecart#setDisplayBlockData`）
- 使用 Bukkit 原生 `setDisplayBlockData(BlockData)` + `setDisplayBlockOffset(int)`——已通过对 paper-api **1.21 / 1.21.1 / 1.21.3 / 1.21.4** 四个版本 jar 的 javap 验证，**全部 1.21.x 均可用，无版本门槛**。
- 方块是矿车实体自身的 `DisplayState` NBT 属性：随矿车渲染与移动、随实体保存（区块卸载/关服自动持久化）、矿车死亡随之消失——无需额外实体，无幽灵/泄漏/同步问题。
- 判定"有方块"：`getDisplayBlockData()` 非空且非 AIR（API 无 hasDisplayBlock）；清除：`setDisplayBlockData(AIR 方块数据)`。
- 铁砧耐久时切换显示为 chipped/damaged 变体，实现视觉状态。
- 显示高度用 `setDisplayBlockOffset`（1/16 格）微调；默认值在本地服务器目测定标，并做成配置项。

### D3 数据持久化：实体 NBT + PDC 分层
- **方块类型**：权威来源为实体 NBT（`DisplayState`），随矿车自动保存/恢复，无需插件干预。
- **业务数据**（PDC `NamespacedKey`）：`cart-type`（矿车类型 id，扩展路由主键）、`container`（容器内容 `ItemStack[]` 经 `serializeAsBytes()`/`deserializeBytes()` 存 `BYTE_ARRAY`）、`anvil-state`（铁砧耐久）、`last-shot`（投掷冷却时间戳）、`block`（方块类型冗余备份，供校验/恢复显示用）。
- 特殊矿车内容走原版 NBT（ChestMinecart 库存等），自动持久化。
- 实时回写：容器视图每次 `InventoryClickEvent` 即写 PDC，`InventoryCloseEvent` 兜底再写一次。

### D4 交互事件设计（单一入口）
- 统一在 `PlayerInteractEntityEvent` 处理（Paper 有 `getHand()`），命中矿车时按状态机分流：
  - 空普通矿车 + 潜行 + 手持允许方块 → 放置（取消原版上车）
  - 有方块矿车 + 潜行 → 取下（按策略）
  - 有方块矿车 + 非潜行 → 打开对应界面
  - 其余（空车非潜行等）→ 放行原版
- 同时取消 `PlayerInteractAtEntityEvent` 防止双重处理；放置/取下仅在主手交互处理，避免副手歧义。
- 乘坐拦截：`EntityMountEvent` 路由到 `CartEngine`，由矿车类型 handler 的 `allowRiding()` 决策——内置类型默认 false（满足 2.3 禁止乘坐），自定义行为型类型（如钟乳石矿车）可返回 true 并接收 `onMount`/`onDismount`。显示方块是矿车自身属性、不占乘客位。

### D5 界面方案：原生虚拟界面 + 关闭回收
- 工作站/铁砧/附魔台用 Bukkit 原生 `openXxx(location, force=true)`，矿车位置即虚拟方块位置。
- 容器用自定义 Inventory（尺寸按方块类型），点击即回写。
- **关闭回收**：`InventoryCloseEvent` 时把视图内残留物品（合成格、铁砧输入/输出等）并入玩家背包，溢出落地 —— 防止原版在虚拟位置把物品丢地上导致"复制/丢失"。
- 木桶开合：打开/关闭时播放 `BLOCK_BARREL_OPEN/CLOSE` 音效（配置可关）。完整方块动画需发包伪造方块，列为后续可选增强，不在本期。

### D6 特殊矿车取下时内容处理
- 取下箱子/漏斗/熔炉矿车：还原普通矿车；内容物**优先并入玩家背包，放不下落地**（与原版破坏矿车"全掉落"相比，背包优先更符合"移动仓库"体验）。
- 潜影盒：内容物写入潜影盒物品 NBT（`BlockStateMeta`）后整体进背包，**不受取下策略限制**。

### D7 铁砧报废
- 报废 = 方块从矿车消失（移除显示、清 PDC），**默认无掉落**（与"消失"字面一致）；提供 `anvil-damage.drop-on-break` 配置可改为掉落损坏铁砧物品。
- 损坏概率默认 0.12，可配置。

### D8 领地保护协作
- 提供 `respect-protection: true`（默认开）：放置时于矿车位置模拟 `BlockPlaceEvent`（以放置的方块数据构造），被其他插件取消则禁止放置；取下时模拟 `BlockBreakEvent` 同理。关闭后则为纯实体交互，不受领地限制。

### D9 范围界定
- 仅支持 Paper（非 Folia）；`folia-supported: false`。Folia 兼容列为远期事项。
- 特殊矿车（箱子/漏斗/熔炉）沿用原版界面交互，不重复造轮子；TNT/命令矿车无界面。

### D10 bStats
- 集成 `Metrics` 类，插件 ID 先用占位（0），发布前在 bstats.org 注册后替换。统计：放置/取下次数、活跃服务器等。

### D11 扩展模型：统一 CartTypeHandler + 引擎路由
- **核心概念**："矿车类型"（cart type）= 注册在 `CartTypeRegistry` 的一个 `CartTypeHandler`。受管矿车的类型判定：PDC `cart-type` id 优先，显示方块 material 兜底（兼容老数据/第三方显示）。
- **统一而非分叉**：现有"挂方块矿车"只是类型的一种形态——玩家放置方块时，引擎按 material 查注册表、写入类型 id 并接管。内置 5 类 handler 全部实现同一接口（工作站=交互、容器=交互+库存、转换=实体替换、投掷器=移动+库存、铁砧=交互+状态），新增类型与内置类型能力对等。
- **引擎职责**（`CartEngine`，核心新增组件）：维护受管矿车集合（放置/区块加载加入，死亡/卸载移除）；每 tick 调度 `onTick`；路由 `EntityMountEvent`/`EntityDismountEvent`（按 `allowRiding()` 决策）、`VehicleMoveEvent`（→ `onMove`）；破坏掉落分发前回调 `onCartDestroyed`；实体替换（特殊矿车转换）自动重绑定跟踪。
- **扩展运行时**（`CartContext`）：显示控制、PDC 元数据、冷却（PDC 时间戳）、骑乘者访问、物品给予/掉落、`replaceEntity`——handler 不触碰引擎内部。
- **可行性边界**：Paper 无官方"自定义实体类型"注册 API，新矿车类型统一以"原版矿车实体 + 类型 id（PDC）+ 行为钩子"实现（业界标准做法），不引入 NMS。
- **示例**：钟乳石矿车（乘坐扣血）见 §3 代码骨架。

---

## 3. 项目结构

```
E:\PMC\
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradlew(.bat) + gradle/wrapper/          # Gradle Wrapper
├── src/main/
│   ├── java/com/pepperminecart/
│   │   ├── PepperMinecartPlugin.java        # 主类：加载/重载/服务注册
│   │   ├── command/PepperMinecartCommand.java   # /pm reload
│   │   ├── config/PluginConfig.java         # config.yml 模型（全字段 + 默认值）
│   │   ├── api/
│   │   │   ├── PepperMinecartAPI.java       # 对外服务接口（注册/查询/上下文）
│   │   │   ├── CartTypeHandler.java         # 扩展点接口（见下）
│   │   │   ├── CartContext.java             # 扩展运行时（显示/元数据/冷却/物品）
│   │   │   └── TakeOffResult.java           # 取下结果枚举
│   │   ├── engine/CartEngine.java           # 受管矿车跟踪/tick 调度/事件路由/重绑定
│   │   ├── registry/
│   │   │   ├── CartTypeRegistry.java        # id / Material → Handler 注册表
│   │   │   └── handler/                     # 内置类型（均实现 CartTypeHandler）：
│   │   │       ├── WorkstationHandler.java  # 工作台/砂轮/织布机/制图台/锻造台/切石机/附魔台
│   │   │       ├── AnvilHandler.java        # 铁砧 + 耐久
│   │   │       ├── ContainerHandler.java    # 桶/投掷器/潜影盒/通用容器
│   │   │       ├── SpecialCartHandler.java  # 箱子/漏斗/熔炉/TNT/命令方块（转换）
│   │   │       └── DispenserCartHandler.java# 投掷器（容器+铁轨发射）
│   │   ├── interaction/InteractionListener.java  # 右键分流/上车拦截/破坏掉落
│   │   ├── display/CartBlockDisplay.java         # setDisplayBlockData 封装（设置/清除/状态切换/offset）
│   │   ├── storage/CartData.java            # PDC 读写/序列化
│   │   ├── container/CartSessionManager.java  # 界面会话管理器（第三轮重构后；原 ContainerSession 拆分为
│   │   │                                       #   CartSession 多态层次：容器/虚拟界面/铁砧三类会话）
│   │   ├── anvil/AnvilDamageTracker.java    # 耐久状态机
│   │   ├── dispenser/DispenserCartShooter.java  # 激活铁轨检测+弹出
│   │   └── cooldown/InteractionCooldown.java
│   └── resources/
│       ├── plugin.yml
│       ├── config.yml                       # 带注释默认配置
│       └── bstats 集成类（或独立包）
└── src/test/java/...                        # JUnit + Paper test framework
```

**扩展点接口**（`CartTypeHandler`，全部方法有默认实现，新增类型只需覆盖需要的钩子）：
```java
public interface CartTypeHandler {
    NamespacedKey getId();                                   // 唯一类型 id（如 "dripstone:cart"，防跨插件冲突）
    Set<Material> handledMaterials();                        // 放置这些方块触发本类型（可空 = 仅 API 创建）

    default boolean canPlace(Player p, Minecart cart) { return true; }
    default boolean allowRiding() { return false; }          // 默认禁止乘坐（需求 2.3）
    default void onPlaced(Player p, CartContext ctx) {}      // 放置后（默认：显示方块 + 写类型 id）
    default boolean onInteract(Player p, CartContext ctx) { return false; } // 打开界面，返回 true = 已处理
    default void onTakeOff(Player p, CartContext ctx, TakeOffResult result) {} // 默认：还原普通矿车 + 返还物品
    default void onTick(CartContext ctx) {}                  // 引擎每 tick 调度（仅受管矿车）
    default void onMove(CartContext ctx) {}                  // 矿车移动时（投掷器铁轨检测）
    default void onMount(Entity rider, CartContext ctx) {}
    default void onDismount(Entity rider, CartContext ctx) {}
    default void onCartDestroyed(CartContext ctx) {}         // 破坏掉落分发前
}
```
**扩展运行时**（`CartContext`）：`getMinecart/getRider/getLocation/getWorld`、`setDisplay(Material)`、`setMetaInt/setMetaString`（PDC 持久化）、`setCooldown/hasCooldown`（PDC 时间戳）、`giveItem/dropItem`、`replaceEntity(Minecart)`（特殊矿车转换时重绑定）——handler 全程不触碰引擎内部。

**新增矿车类型三步走**（以"钟乳石矿车——乘坐扣血"为例，第三方插件零核心改动）：
```java
// 1) 实现类型
public class DripstoneCartHandler implements CartTypeHandler {
    private static final NamespacedKey INTERVAL = NamespacedKey.fromString("dripstone:interval");
    private static final NamespacedKey COOLDOWN = NamespacedKey.fromString("dripstone:cooldown");

    @Override public NamespacedKey getId() { return NamespacedKey.fromString("dripstone:cart"); }
    @Override public Set<Material> handledMaterials() { return Set.of(Material.POINTED_DRIPSTONE); }
    @Override public boolean allowRiding() { return true; }   // 钟乳石矿车允许乘坐（2.3 的例外由类型自己决定）

    @Override public void onPlaced(Player p, CartContext ctx) {
        ctx.setDisplay(Material.POINTED_DRIPSTONE);
        ctx.setMetaInt(INTERVAL, 20);                        // 每 20 tick 结算一次
    }
    @Override public void onTick(CartContext ctx) {
        if (ctx.getRider() == null || ctx.hasCooldown(COOLDOWN)) return;
        ctx.setCooldown(COOLDOWN, ctx.getMetaInt(INTERVAL, 20));
        ((LivingEntity) ctx.getRider()).damage(1.0, ctx.getMinecart());  // 每次半颗心
        ctx.getWorld().playSound(ctx.getLocation(), Sound.BLOCK_POINTED_DRIPSTONE_DRIP, 1f, 1f);
    }
}

// 2) 注册（本插件或第三方插件均可）
PepperMinecartAPI api = Bukkit.getServicesManager().load(PepperMinecartAPI.class);
api.registerCartType(new DripstoneCartHandler());

// 3) 完成。玩家体验：潜行+右键空矿车放钟乳石 → 生成钟乳石矿车（显示方块自动出现）；
//    上车持续扣血；潜行+右键取下 → 还原普通矿车 + 钟乳石物品；破坏矿车 → 掉落钟乳石。
```
新增"仅行为、无方块"的类型（如纯 API 召唤的幽灵矿车）同样支持：`handledMaterials()` 返回空集，由创建方写 `cart-type` PDC 并交给引擎跟踪。

---

## 4. 配置设计（config.yml 草案）

```yaml
# 方块放置
take-off-mode: INVENTORY          # INVENTORY 进背包(默认) | DROP 落地 | DISABLED 禁止取下
allow-all-blocks: true            # true=除黑名单外全部允许；false=仅允许白名单
disabled-blocks: []               # 禁止放置的方块（如: [BEDROCK, BARRIER]）
enabled-blocks: []                # allow-all-blocks=false 时生效
respect-protection: true          # 模拟 BlockPlaceEvent/BreakEvent 与领地插件协作

# 交互
interaction-cooldown-ms: 250      # 防连点冷却
sounds:
  place: true
  take-off: true

# 铁砧耐久（2.4）
anvil-damage:
  enabled: true
  chance-per-use: 0.12
  drop-on-break: false            # 报废时是否掉落损坏铁砧物品

# 发射器矿车（2.5）
dispenser-cart:
  cooldown-ticks: 40              # 两次弹出最小间隔
  eject-offset: 0.6               # 弹出偏移量
  eject-speed: 0.5                # 弹出初速度
```

所有项均有注释说明；`/pm reload` 热重载全部生效。

---

## 5. 命令 / 权限 / 统计

- `plugin.yml`：`name: PepperMinecart`，`api-version: '1.21'`，`main: com.pepperminecart.PepperMinecartPlugin`。
- 命令 `/pepperminecart`（别名 `pm`），子命令 `reload`，需 `pepperminecart.reload`（默认 op）。
- 权限：`pepperminecart.use`（default: true）、`pepperminecart.reload`（default: op）。
- bStats：主类构造 `Metrics`，插件 ID 占位待注册。

---

## 6. 分阶段实施计划

### 阶段 0 — 工程环境（0.5 天）
- [ ] 下载 Gradle 9.x 发行版 → 初始化 wrapper（gradlew）提交进仓库
- [ ] 搭建 Gradle Kotlin DSL 构建：paper-api 1.21.4、Java toolchain 21、Shadow 插件（bStats）
- [ ] 空插件骨架：plugin.yml、主类、权限/命令声明、`saveDefaultConfig()` 生成带注释 config.yml、`/pm reload` 空实现
- [ ] bStats 集成（占位 ID）
- **验收**：本地 Paper 服务器（下载 1.21.4 Paper jar）能加载插件、生成配置、`pm reload` 正常输出。

### 阶段 1 — 数据层与显示层（1 天）
- [ ] `CartData`：PDC 读写（方块类型、容器字节、铁砧状态、投掷时间戳），含序列化工具
- [ ] `CartBlockDisplay`：封装 `setDisplayBlockData` 设置/清除/AIR 判定/铁砧状态切换/offset 微调
- [ ] 本地服务器实测：验证渲染效果与清除行为，定标默认 `display-block-offset`（写入配置）
- [ ] `CartTypeRegistry` + `CartTypeHandler`/`CartContext` 接口 + `PepperMinecartAPI`（ServicesManager 注册）
- [ ] `CartEngine`：受管矿车跟踪集合（放置/区块加载加入，死亡/卸载移除）、tick 调度、mount/move/死亡事件路由、实体替换重绑定
- [ ] `PluginConfig` 完整配置模型与校验
- **验收**：单元测试覆盖 PDC 序列化往返、注册表增删查、引擎路由；集成测试注册一个测试类型验证 tick 与挂载路由。

### 阶段 2 — 放置/取下核心交互（1.5 天）
- [ ] `InteractionListener` 右键分流状态机（放置 / 取下 / 界面 / 放行原版）
- [ ] 放置：允许性校验（配置黑白名单、乘客、冷却）、消耗物品、挂显示、音效
- [ ] 取下：三种策略、潜影盒特例（NBT 回收）、音效
- [ ] `SpecialCartHandler`：5 类特殊矿车双向转换（含内容处理 D6）
- [ ] 乘坐路由（`EntityMountEvent` → `allowRiding()` 决策 + `onMount/onDismount`）、`respect-protection` 模拟事件
- [ ] `InteractionCooldown`
- **验收**：Paper test framework 集成测试：放置→显示出现、取下→物品返还、转换→实体类型正确且内容不丢。

### 阶段 3 — 界面层（1.5 天）
- [ ] `WorkstationHandler`：7 种工作站原生界面 + 关闭回收
- [ ] `AnvilHandler`：铁砧界面 + 关闭回收 + `AnvilDamageTracker`（取结果判定、状态机、显示切换）
- [ ] `ContainerHandler`：桶/投掷器/潜影盒/通用容器虚拟库存、实时回写、木桶音效
- [ ] `DispenserCartHandler`：投掷器作为容器的接入
- **验收**：集成测试：容器存取回写 PDC、关闭回收无物品丢失/复制、铁砧耐久三态推进、木桶开合音效播放。

### 阶段 4 — 投掷器发射与数据安全加固（1 天）
- [ ] `DispenserCartShooter`：`VehicleMoveEvent` + 充能激活铁轨检测、冷却、随机物品、偏移弹出、音效
- [ ] 矿车死亡（`EntityDeathEvent`）：从 displayState 掉落方块物品 + PDC 内容（显示随实体消亡，无残留）
- [ ] `ChunkLoadEvent`/启动扫描：校验 displayState 与 PDC 冗余 `block` 键一致，异常时修复（防第三方清显示等边界）
- [ ] 乘坐路由补漏（生物自动上车路径 → allowRiding 决策 + onMount）
- **验收**：集成测试：压轨弹出（物品数/冷却生效）、破坏矿车掉落完整、卸载重载数据不丢不复制。

### 阶段 5 — 打磨与文档（0.5 天）
- [ ] 扩展 API 文档（javadoc 注释 + README 教程，**以"钟乳石矿车"为完整示例**，第三方插件可照抄接入）
- [ ] `pm reload` 全量重建运行时状态验证（含注册表与引擎）
- [ ] README：功能、配置、权限、FAQ（含与领地插件协作说明）
- **验收**：README 完整、扩展示例可复现、reload 无泄漏。

### 阶段 6 — 测试与发布（1 天）
- [ ] JUnit 单测：配置解析、冷却、铁砧状态机、序列化、投掷随机逻辑
- [ ] Paper test framework 集成测试全绿（含跨区块卸载/重载场景）
- [ ] 扩展 API 集成测试：模拟第三方插件注册钟乳石类型，验证 onTick 扣血 / allowRiding / 取下还原
- [ ] 本地 Paper 服务器冒烟：控制台启动无异常、`pm reload`、手动清单核对（放置/取下/界面/转换/发射/破坏）
- [ ] 打包产物验证（`build/libs/*.jar` 含 bStats 且无冗余依赖）、bStats ID 替换提醒
- **验收**：`gradle build` + `paper test plugin` 全绿，产出可分发 jar。

---

## 7. 测试策略

| 层级 | 工具 | 覆盖 |
|---|---|---|
| 单元 | JUnit 5 | 配置/序列化/状态机/冷却/纯逻辑 |
| 集成 | Paper test framework（`paper test plugin` 在真实服务器内跑 `@PaperTest`，可模拟玩家右键实体） | 放置/取下/转换/界面/发射/破坏/重载/扩展类型路由 |
| 冒烟 | 本地 Paper 1.21.4 服务器 + 控制台 | 加载、配置生成、reload、人工清单 |

---

## 8. 风险与备选方案

| 风险 | 影响 | 对策/备选 |
|---|---|---|
| 显示偏移/渲染效果需目测调优 | 方块在矿车上的位置不理想 | 用 `setDisplayBlockOffset` 微调并做成配置项；本地服务器实测定标（阶段 1 内完成） |
| `VehicleMoveEvent` 在个别 1.21 小版本行为差异 | 投掷器不触发 | 备选：EntityMoveEvent / 定时扫描矿车下方铁轨；发射判定抽象成独立接口便于切换 |
| `PlayerInteractEntityEvent#getHand()` 版本差异 | 编译/运行错误 | 实现时对 API jar 核对；如缺失则回退主手判定 |
| 原生虚拟界面关闭时物品掉落位置异常 | 物品丢失/复制 | 关闭回收逻辑统一处理 + 集成测试覆盖 |
| 崩溃丢失最近一次自动保存间隔的数据 | 少量数据回滚 | 与原版容器同级，接受并写入 README |
| bStats 未注册 ID | 统计不生效 | 不影响功能；发布前替换 ID |
| Gradle/网络下载失败 | 无法构建 | 已验证仓库可达；wrapper 固定版本，离线后可复用已下载发行版 |

---

## 9. 待确认事项（默认值已按上述决策执行，可随时调整）

1. 包名/组织：`com.pepperminecart`（可改，如 `dev.xxx.pepperminecart`）。
2. 木桶"开合动画"以音效实现是否可接受（完整动画需发包，列为后续增强）。
3. 铁砧报废默认无掉落，是否认可。
4. 特殊矿车取下时内容"背包优先、溢出落地"是否认可。
5. 是否需要在取下时也校验领地（默认开）。

---

*下一步：确认本计划后，从阶段 0 开始实施。*
