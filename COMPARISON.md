# PepperMinecart 设计对比：E:\PMC（我的实现） vs F:\PepperMinecart（参考项目 v1.5）

> 对比日期：2026-08　|　参考项目：org.eu.pcraft.pepperminecart，编译目标 paper-api 1.21.11，apiVersion 1.21

## 0. 结论先行

两个实现**同源设计**（同一份需求文档），核心决策高度一致：都用 `setDisplayBlockData` 显示方块、
`PlayerInteractEntityEvent` 单入口、同一张特殊矿车转换表、同一套会话管理思路。
差异集中在**工程稳健性**（参考项目更成熟）与**扩展模型**（我的实现更完整）。

- 参考项目 v1.5 在以下方面更扎实：销毁路径覆盖（`EntityRemoveEvent`）、投掷器事件驱动（`VehicleMoveEvent`）、
  铁砧判定严谨性（`PrepareAnvilEvent` + 延迟确认）、物品安全（放置失败回滚）、热路径优化（负缓存）、
  容器"同一引用"回写（杜绝拷贝）。
- 我的实现独有：**公开第三方扩展 API**（ServicesManager + `CartTypeHandler`/`CartContext`，含 `allowRiding`/`onTick`/`onMount` 钩子）、
  界面残留物品回收、ChunkLoad 显示校验恢复、零外部运行时依赖。

---

## 1. 共同设计决策（双方不谋而合）

| 决策 | 双方做法 |
|---|---|
| 方块显示 | `Minecart#setDisplayBlockData(BlockData)`（原版 DisplayState NBT），随实体持久化 |
| 交互入口 | `PlayerInteractEntityEvent` + `getHand()==HAND` + `HIGHEST` + `ignoreCancelled=true` + `pepperminecart.use` |
| 交互模型 | 潜行 = 放置/取下；非潜行 = 打开界面；空车非潜行放行原版乘坐 |
| 特殊矿车 | 同一张转换表：CHEST/HOPPER/FURNACE/TNT/COMMAND_BLOCK → 原版实体，取下还原普通矿车 |
| 潜影盒 | 不受容器取下策略限制，始终进背包 |
| 铁砧耐久 | 三态链：ANVIL → CHIPPED_ANVIL → DAMAGED_ANVIL → 报废消失；结果槽 rawSlot 2 判定 |
| 投掷器 | 压过充能激活铁轨（`Powerable.isPowered()`）+ 冷却 + 随机弹出 1 物品 |
| 会话管理 | 打开中的容器界面关闭时回写；区块卸载/插件停用清理会话，防幽灵界面与内存泄漏 |
| 冷却 | 玩家级交互冷却，仅取消插件路径的交互（放行原版） |

---

## 2. 参考项目更优之处（值得合入我的实现）

| # | 优点 | 参考实现位置 | 我的现状 |
|---|---|---|---|
| 1 | **`VehicleMoveEvent` 事件驱动投掷器**（`org.bukkit.event.vehicle.VehicleMoveEvent`，Bukkit 经典事件，1.21.4 API 存在） | `listener/VehicleListener.java:38-43` | 我用引擎 tick 位置差分触发 `onMove`——功能等价但每 tick 开销大、不优雅 |
| 2 | **`EntityRemoveEvent`（排除 UNLOAD）统一销毁清理**：覆盖破坏、`/kill`、插件 `remove()` 等全部移除路径；区块卸载由 WorldListener 单独处理会话（明确"卸载≠销毁"，不重复掉落） | `VehicleListener.java:30-36` | 我只监听 `EntityDeathEvent`：其他插件直接 `remove()` 矿车时方块物品会凭空消失（数据丢失缺口） |
| 3 | **持久化存"整个物品"**（NBT-API `BlockInfo` 键）：容器内容天然在物品 `BlockStateMeta` 里，取下原样归还带 NBT 的物品；潜影盒零特判；箱子矿车取下还能得到"带库存的箱子物品" | `repository/MinecartDataRepository.java`、`feature/vanilla/ChestCartFeature.java` | 我用自研 `ItemStack[]` 字节序列化 + 潜影盒特判 + 内容逐格回背包——功能等价但复杂得多、保真度低（物品其他 NBT 标签丢失） |
| 4 | **容器"同一引用"回写**：打开界面时持有物品 BlockStateMeta 中 Container 的同一个引用（LiveContainer），编辑/发射/关闭都写同一对象，杜绝拷贝与刷物品 | `feature/CartSessions.java:54-57` | 我用独立虚拟 Inventory + 每次点击字节回写——安全但代码多 |
| 5 | **铁砧判定严谨**：`PrepareAnvilEvent` 登记"有效修复结果"标记（WeakHashMap）→ 点击结果槽后延迟 1 tick 对比确认"真的取走"（shift 进满背包不算；同一次修复只判定一次） | `listener/InventoryListener.java:26-105` | 我 rawSlot==2 点击即判定：光标非空点击（no-op）、重复点击会误判多次 |
| 6 | **投掷器冷却用 `getTicksLived()`**：单调递增、跨区块重载持久、不受 `/time set` 影响 | `feature/CartSessions.java:161-171` | 我用 `System.currentTimeMillis()` 存 PDC——功能等价但受系统时钟影响 |
| 7 | **负缓存 noBlockCart**（上限 1 万整体清空）：热路径（VehicleMoveEvent/VehicleEnterEvent）短路 NBT 读取 | `feature/CartSessions.java:32-52` | 我无此优化（引擎只遍历受管集合，等价但实现不同） |
| 8 | **放置失败回滚**：放置抛异常不消耗物品；物品消耗后显式写回主手（不依赖活引用实现细节）——防吞物品/刷物品 | `service/MinecartService.java:204-214` | 我先消耗后放置：放置异常会吞物品（真实缺陷） |
| 9 | **实体替换迁移自定义名与乘客**；`removePassenger` 被 `VehicleExitEvent` 取消时优雅处理 | `feature/CartPersistence.java:42-63` | 转换不迁移自定义名（小缺陷） |
| 10 | **界面用 `MenuType.create(player)`**（1.21.4+ 新 API）：玩家绑定菜单，无虚拟方块位置，关闭残留物品由原版处理；先开界面再登记会话（防误清） | `AnvilFeature.java:24-30`、`FeatureRegistry.java:134-142` | 我用 `openXxx(location, force)` 位置绑定菜单 + 自研关闭回收——正确但绕 |
| 11 | **配置细粒度**：`block-interactions` 方块→特性全配置化、`vanilla-carts.conversions` 每材质启用开关、`vanilla-carts.pickup` 每材质取下策略、`container-pickup-policy` 独立于全局取下策略、`enable-custom-interact` 总开关 | `resources/config.yml` | 我只有全局 `take-off-mode` + 黑/白名单 |
| 12 | 成熟工程：Lombok、Configurate、run-paper（`./gradlew runServer` 一键起服）、真实 bStats ID 21763、GitHub Actions | `build.gradle` | 无 lombok/configurate；bStats ID 占位 0 |

---

## 3. 我的实现更优之处（参考项目可借鉴）

| # | 优点 | 说明 |
|---|---|---|
| 1 | **公开第三方扩展 API** | `PepperMinecartAPI`（ServicesManager）+ `CartTypeHandler` + `CartContext`：其他插件运行时注册新矿车类型，含 `allowRiding`/`onTick`/`onMount`/`onMove`/`onInteract` 等钩子。参考项目的 `CartFeature` 是**内部接口**（注释写明"新增矿车 = 新增实现并在此注册"），第三方要改源码重编译 |
| 2 | **`allowRiding()` 乘坐策略可配置** | 行为型类型（如钟乳石扣血矿车）可开放乘坐；参考项目 `VehicleEnterEvent` 一律禁止 |
| 3 | **界面残留物品回收** | 关闭工作台/铁砧视图时把残留物品（合成格、输入槽）并入背包，溢出落地——位置绑定菜单的防丢失保障（参考项目未显式处理，依赖玩家绑定菜单的原版行为） |
| 4 | **ChunkLoad 显示校验与恢复** | PDC 备份 `block` 键与 displayState 一致性检查，第三方清除显示时可恢复；参考项目无备份 |
| 5 | **零外部运行时依赖** | 仅 Paper API + bStats（shadow 打包）；参考项目需 shade + relocate NBT-API/Configurate |
| 6 | **铁砧/容器状态显式持久化** | `anvil-state` PDC 键显式状态机；参考项目从物品类型（ANVIL→CHIPPED→DAMAGED）隐式推断 |
| 7 | 测试验证 | 8 个单元测试 + 本地 Paper 1.21.4 真实服务器冒烟（加载/配置生成/reload/卸载零异常） |

---

## 4. 关键差异对照表

| 维度 | E:\PMC（我的实现） | F:\PepperMinecart（参考 v1.5） |
|---|---|---|
| 编译目标 | paper-api 1.21.4（`--release 21`） | paper-api 1.21.11（toolchain 21） |
| 实际运行时下限 | 1.21.0+（全 API 1.21.0 前已存在） | 用了 1.21.2+ 的 `EntityRemoveEvent`、1.21.4+ 的 `MenuType`，实际 ≥1.21.4 |
| 持久化 | Bukkit PDC：`cart-type`/`block`/`container`(byte[])/`anvil-state`/`last-shot` | NBT-API：`BlockInfo` 整物品键 |
| 投掷器触发 | 引擎 tick 位置差分 | `VehicleMoveEvent` |
| 销毁清理 | `EntityDeathEvent` + `ChunkUnloadEvent` | `EntityRemoveEvent`（排除 UNLOAD）+ WorldListener 会话清理 |
| 容器回写 | 虚拟 Inventory + 字节序列化，点击即写 | 物品 BlockStateMeta 同一 Container 引用（LiveContainer） |
| 铁砧判定 | rawSlot 2 点击即判定 | PrepareAnvilEvent 标记 + 延迟确认真取走 |
| 界面打开 | `openXxx(location, force)` + 关闭回收 | `MenuType.create(player)` |
| 取下策略 | 全局 `take-off-mode`（INVENTORY/DROP/DISABLED） | 容器 `container-pickup-policy` + 原版矿车 per-material `pickup` |
| 扩展 API | **公开**（ServicesManager 服务） | 内部接口（源码级扩展） |
| 乘坐策略 | `allowRiding()` 钩子 | 一律禁止 |
| 依赖 | Paper API + bStats | + NBT-API、Configurate、Lombok |
| 转换细节 | 不迁移自定义名/乘客 | 迁移名与乘客 |
| 放置安全 | 先消耗后放置（异常吞物品） | 先放置成功再消耗（回滚） |

---

## 5. 建议的融合改进清单（针对我的实现 E:\PMC）

> **已合入（2026-08）：** P0 全部四项（VehicleMoveEvent 驱动投掷器、EntityRemoveEvent 统一销毁、
> 铁砧 PrepareAnvil 标记 + 延迟确认、放置失败回滚 + 显式写回主手）、P1 两项（实体替换迁移自定义名与乘客、
> 投掷器冷却改用 getTicksLived）、P2 第 7 项（整物品持久化：PDC 单键存完整 ItemStack，容器内容随物品回收，
> 同时删除了潜影盒特判与 anvil-state 键，特殊矿车改为按实体类型反查材质并组装"带库存的容器物品"）。
> 未合入：MenuType 玩家绑定菜单（会放弃 1.21.0–1.21.3 运行时兼容，当前 openXxx + 关闭回收功能等价）、
> 配置细粒度化（block-interactions 等，列为后续增强）。

### P0 立即修复（正确性/数据安全）
1. **`VehicleMoveEvent` 驱动投掷器**：删除引擎位置差分 Map，`CartEngine` 监听
   `org.bukkit.event.vehicle.VehicleMoveEvent`（1.21.4 API 已有）→ 调 `handler.onMove(ctx)`。
2. **`EntityRemoveEvent`（排除 `Cause.UNLOAD`）统一销毁清理**：替换/补充 `EntityDeathEvent`，
   覆盖第三方 `remove()` 路径，堵住"方块凭空消失"缺口；卸载路径保持现有会话清理。
3. **铁砧判定升级**：`PrepareAnvilEvent` 登记有效结果标记 + 点击后延迟 1 tick 对比结果槽确认真取走。
4. **放置失败回滚**：先执行放置逻辑，异常时不消耗物品；消耗后显式 `setItemInMainHand` 写回。

### P1 体验改进
5. 实体替换（特殊矿车转换）迁移自定义名；乘客尽力转移（`removePassenger` 失败则放行弹出）。
6. 投掷器冷却改用 `getTicksLived()`（实体年龄）替代系统时钟。

### P2 可选重构（改动面大，需确认）
7. **持久化改为"整物品"方案**：用现有 PDC + `ItemStack.serializeAsBytes()` 存**整个物品**到一个键
   （不引入 NBT-API 依赖），容器内容天然在 BlockStateMeta 内：
   - `ContainerHandler` 删除潜影盒特判与逐格回收，取下原样归还带 NBT 物品；
   - `SpecialCartHandler` 支持"带库存的箱子物品"双向转移（参考 `ChestCartFeature`）；
   - `CartData` 简化为单一物品键。
8. **界面打开改用 `MenuType.create(player)`**：放弃 1.21.0–1.21.3 运行时兼容（实际运行时下限提到 1.21.4），
   换取玩家绑定菜单（无虚拟位置、无需自研关闭回收）。**需决策：是否接受运行时下限 1.21.4。**
9. **配置细化**：`block-interactions` 方块→特性映射配置化、`vanilla-carts.conversions` 每材质开关、
   `vanilla-carts.pickup` 每材质取下策略（PICKUP/DROP/FORBIDDEN）。

### 参考项目可反向借鉴我的
- 公开扩展 API（`CartFeature` 注册表对外暴露 + ServicesManager）
- 界面残留回收（若继续用位置绑定菜单）
- ChunkLoad 显示校验恢复（PDC 备份键）

---

## 7. 设计架构对比（补充）

### 7.1 分层与依赖方向

**参考项目（F:\PepperMinecart）——"薄监听器 + 单一应用服务"分层**：

```
listener/（4 个薄适配器，零业务逻辑）
   ↓ 委托
service/MinecartService（唯一编排者：放置/取下/交互分发/会话生命周期/销毁/铁砧，430 行）
   ↓ 解析与分发
feature/CartFeature（领域行为，无状态）──→ FeatureContext（门面 Facade）
                                              ├── CartPersistence（持久化+实体替换）
                                              ├── CartSessions（会话状态）
                                              └── Audio（音效）
registry/MinecartRegistry（实体类型↔方块材质转换表）
repository/MinecartDataRepository（NBT 底层读写）
```

依赖严格单向：监听器 → 服务 → 特性/注册表；特性只依赖 `FeatureContext` 门面，**不反向依赖服务**（无环）。
所有可变状态集中在 `FeatureContext`/`CartSessions` 一个地方。

**我的实现（E:\PMC）——"扩展点内核 + 多中心组件"**：

```
主类装配（PepperMinecartPlugin）
├── engine/CartEngine（生命周期中枢：受管矿车集合、tick 循环、事件路由、破坏掉落）
├── interaction/InteractionListener（交互状态机：放置/取下/开界面，业务逻辑在此）
├── container/ContainerSession（库存会话：回写/关闭回收）
└── registry/CartTypeRegistry + registry/handler/*（内置类型）
        api/（公开接口）←── impl/（实现）── 经 ServicesManager 暴露给第三方
```

没有单一编排者：引擎、交互监听器、会话管理器是三个平级组件，各自承担一块职责，
由主类装配；`CartContext`（每矿车上下文）是引擎向 handler 注入的运行句柄。

### 7.2 行为单元：内部领域对象 vs 公开扩展点

| | 参考项目 | 我的实现 |
|---|---|---|
| 行为接口 | `CartFeature`（**内部**接口，无插件边界） | `CartTypeHandler`（**即公开 API**，api/ 包） |
| 扩展方式 | 源码级：新增实现类 + 在 `FeatureRegistry.registerBuiltins()` 注册 | 运行时：第三方 `Bukkit.getServicesManager().load(PepperMinecartAPI.class).registerCartType(...)` |
| 标识 | 配置字符串名（`getName()`，如 "ANVIL"） | `NamespacedKey`（如 `dripstone:cart`，防跨插件冲突） |
| 配置映射 | `block-interactions` 配置把方块指到特性名（配置驱动） | handler 自声明 `handledMaterials()`（代码驱动） |

架构含义：参考项目把"扩展"当作内部可维护性手段；我把"扩展"当作**插件对外产品能力**
（api/impl 分离 + ServicesManager 服务注册 = 标准的 Bukkit 插件服务模式）。

### 7.3 状态归属：无状态特性 + 共享门面 vs 无状态 handler + 每矿车上下文

| | 参考项目 | 我的实现 |
|---|---|---|
| 特性对象 | 必须**无状态**（javadoc 明确要求）：注册表重载时整体重建，而会话跨重载存活 | handler 无状态；引擎/注册表**重载时保持稳定**，只刷配置 |
| 运行状态 | 集中在 `FeatureContext`（门面）→ `CartSessions`：铁砧会话(player→cart)、工作站会话、LiveContainer、投掷冷却(uuid→ticks)、负缓存集合 | 分散三处：引擎 `contexts`(uuid→CartContextImpl) + `lastLocations`；ContainerSession `open`(player→session)；**持久状态存 PDC**（铁砧状态、投掷冷却） |
| 重载语义 | 重建注册表 + setter 注入服务（Lombok @Setter 换引用） | 引擎/注册表不动，仅 `PluginConfig.reload()` 刷新参数 |

架构含义：参考是"单一可变状态对象 + 不可变行为对象"的经典组合（便于重载与推理）；
我是"状态分片 + PDC 持久化优先"（内存态轻、跨重启存活，但状态点分散）。

### 7.4 解析策略：单维材质解析 vs 双级 id/材质解析

- **参考**：**单一解析键 = "矿车代表的方块材质"**（`resolveMaterial`）——自定义矿车读 BlockInfo 物品的材质，
  原版特殊矿车按实体类型反查转换表。所有分发（特性、取下、销毁）共用这一个键；
  事件触发时**按需解析** + 负缓存（noBlockCart 集合）短路热路径。
- **我**：**两级解析**——PDC `cart-type` id 优先（注册表 byId），显示方块材质兜底（byMaterial）；
  放置时一次性登记进引擎**受管集合**（UUID → CartContext），之后事件/tick 直接查集合，
  外来矿车（第三方 NBT 显示）按需解析。

架构含义：参考模型下**每种矿车都必然代表一个方块**（含原版转换矿车）；我的模型允许
**纯行为类型**（`handledMaterials()` 为空、无显示方块、仅 API 创建）——这是两套架构
能力维度的根本差异。

### 7.5 驱动模型：纯事件驱动 vs 事件 + 引擎 tick

- **参考**：**纯事件驱动**。钩子清单全部由事件触发：`VehicleMoveEvent`（投掷器）、
  `EntityRemoveEvent`（销毁）、`InventoryClose/PrepareAnvil/Click`（铁砧/容器）、
  `PlayerInteractEntityEvent`（交互）。**没有 tick 循环、没有连续行为钩子、没有乘坐策略**。
- **我**：**事件 + 引擎 tick 循环**。`CartEngine` 每 tick 遍历受管矿车：位置差分触发 `onMove`、
  每 tick 调 `onTick`；另有 `onMount/onDismount/allowRiding` 生命周期钩子。

架构含义：参考架构表达"离散交互"，我的架构额外表达"**持续行为**"（钟乳石扣血、铁轨发射、
状态机演进），这是"矿车类型"（行为实体）与"矿车方块"（交互对象）两种心智模型的差异。

### 7.6 持久化模型：单对象身份语义 vs 结构化键值

| | 参考项目 | 我的实现 |
|---|---|---|
| 载体 | NBT-API，**单键 `BlockInfo` 存整个 ItemStack** | Bukkit PDC，多键：`cart-type`/`block`/`container`(byte[])/`anvil-state`/`last-shot` |
| 容器语义 | 物品 `BlockStateMeta` 内的 Container 即容器本体，**同一引用**（LiveContainer）编辑/回写——身份语义 | 独立虚拟 Inventory + `ItemStack[]` 字节序列化回写——值语义 |
| 保真度 | 物品全部 NBT 标签保留（含容器内容、任意自定义标签） | 保留方块类型 + 容器内容数组，其余标签丢弃 |
| 依赖 | 外部 NBT-API（shadow + relocate） | 零依赖（PDC + `serializeAsBytes`） |

### 7.7 生命周期管理：事件驱动清理 vs 显式受管集合

- **参考**：**无受管集合**。清理全部由事件驱动：`EntityRemoveEvent`（排除 UNLOAD）统一销毁、
  `ChunkUnloadEvent` 按坐标清会话、`PlayerQuitEvent` 清玩家会话、`InventoryCloseEvent` 清容器。
- **我**：**显式生命周期**：引擎受管集合——放置/区块加载时加入，死亡/卸载/取下时移除；
  附带 ChunkLoad 显示校验恢复（PDC 备份 vs displayState 一致性）与 tick 失效清理。

### 7.8 构建与工程架构

| | 参考项目 | 我的实现 |
|---|---|---|
| 编译目标 | paper-api 1.21.11（实际需 ≥1.21.4：EntityRemoveEvent/MenuType） | paper-api 1.21.4（运行时 1.21.0+ 兼容） |
| 代码生成/配置库 | Lombok + Configurate（类型安全配置模块） | 纯 Java + YamlConfiguration（`apply(FileConfiguration)` 可单测） |
| 开发工具链 | run-paper 一键起服、GH Actions、bStats 真实 ID | 自建真实服务器冒烟流程（paperclip + RCON） |
| 测试 | 配置解析单测 ×2 | 配置/冷却单测 ×8 + 真实服务器冒烟 |

### 7.9 架构风格定性

- **参考项目 = "应用服务 + 特性 + 门面上下文"**：经典插件领域分层，三个关键词——**无状态**（特性）、
  **配置驱动**（注册表随配置重建）、**事件驱动**（无 tick）。代价：单一编排者与门面偏大
  （服务 430 行、门面 30+ 方法），扩展仅限源码级。
- **我的实现 = "扩展点内核 + 引擎"**：以公开扩展接口为产品边界，三个关键词——**运行时扩展**
  （ServicesManager 服务）、**显式生命周期**（受管集合 + tick 引擎）、**持久优先**（状态存 PDC）。
  代价：组件数量多（引擎/交互/会话三中心），tick 循环有恒定开销，扩展 API 需维护兼容承诺。

两者可互相演进：参考的 `CartFeature` 包一层公开注册表即可获得我的运行时扩展能力；
我的引擎若去掉 tick 与受管集合、改为纯事件分发，即退化为参考的架构。

---

## 8. 可维护性与功能差异分析

### 8.1 可维护性

#### 参考项目（应用服务 + 特性 + 门面）

**更优处：**

| 维度 | 说明 |
|---|---|
| 控制流可预测 | 所有业务经 MinecartService 单入口，`listener → service → feature` 线性可读；新开发者只需理解一条链 |
| 门面封装 | FeatureContext 隐藏 NBT 键/会话结构/音效细节，特性实现者面对小接口面 |
| 无状态契约 | "特性必须无状态"是显式 javadoc 不变量，降低实例状态推理成本 |
| 配置驱动 | 管理员不改代码即可把"任意方块 → 已有特性"（block-interactions）；"已有行为配新方块"零编译 |
| 无 tick | 纯事件驱动，无定时任务/重入问题，主线程顺序执行心智负担小 |

**风险/代价：**

| 维度 | 说明 |
|---|---|
| 上帝对象倾向 | MinecartService（430 行）同时是"编排者"和"默认行为实现"（placeOnEmpty/genericPickup 都在服务里）；FeatureContext 30+ 方法。新功能基本都要改这两个类——开闭原则差，随功能增多持续膨胀 |
| 重载隐藏不变量 | 注册表重载重建 + 会话跨重载存活 → 特性必须无状态。这是容易踩的坑（参考项目自己在 javadoc 里反复提醒），新加字段进特性 = 重载后错配 |
| 扩展 = 改核心 | 新矿车类型要改 FeatureRegistry.registerBuiltins() 并重新编译，没有二进制兼容承诺 |
| 优化与业务交织 | 负缓存（noBlockCart）逻辑散在 CartSessions 与 MinecartService.hasBlockOnCart 两处，热路径优化代码混在业务判断里 |
| 可测性 | 单测仅覆盖配置解析（2 个）；MinecartService 强依赖 Bukkit 实体/事件，纯 JVM 无法测试核心业务 |
| 工具链依赖 | Lombok + Configurate + NBT-API 三件套（shadow relocate、dev 开关），升级 Paper 时第三方库可能滞后 |

#### 我的实现（扩展点内核 + 引擎）

**更优处：**

| 维度 | 说明 |
|---|---|
| 开闭原则 | 新矿车类型 = 实现 CartTypeHandler + 注册，**不碰任何现有类**；内置与第三方同构，核心稳定 |
| 类小职清 | 引擎/交互/会话/各 handler 单类 ≤200 行，职责单一 |
| API 边界显式 | api/ 与 impl/ 分离，内部重构不破坏第三方；接口 javadoc 即契约 |
| 持久优先 | 铁砧状态/冷却存 PDC，内存态少；崩溃重启行为一致，引擎上下文可从 PDC 重建（ChunkLoad 校验恢复） |
| 零依赖 | 升级 Paper 无第三方库滞后问题 |

**风险/代价：**

| 维度 | 说明 |
|---|---|
| 多中心控制流 | 引擎 + 交互监听器 + 会话管理器平级：一条交互链路跨多个类（放置 = InteractionListener → engine.addCart → handler.onPlaced → 引擎 tick…），无单一业务入口，上手慢 |
| 引擎隐式契约 | 受管集合增删时机散落在多个事件处理器（放置/加载/死亡/卸载/取下），漏一个路径 = 内存泄漏或僵尸上下文；生命周期规则未像参考那样集中文档化 |
| API 兼容负担 | 公开接口一旦发布，签名变更即破坏性变更，需要语义化版本管理；钩子设计错误长期困扰 |
| tick 恒定开销 | 每 tick 遍历受管集合 + 位置差分轮询（对比参考的 VehicleMoveEvent 事件驱动，已列入 P0 修复） |
| 数据访问不统一 | CartData 静态工具被引擎/监听器/handler 多处直接调用，门面不彻底 |

#### 维护性小结

- **参考 = 初期好、长期膨胀**：单入口好读，但服务/门面随功能持续变大（上帝对象化），扩展必须改核心，重载有隐藏不变量。适合"功能集合收敛"的成熟插件。
- **我 = 初期分散、长期稳**：多中心上手慢，但核心不随类型增多而膨胀，扩展走公开 API；代价是维护 API 兼容承诺与引擎生命周期契约。

### 8.2 功能（架构表达能力上限）

#### 参考项目的事件交互模型

**能干净表达**：一切"离散事件触发的方块交互"——工作站/铁砧/容器/投掷器（VehicleMoveEvent）/原版转换/取下策略，且配置化映射（管理员可配）。

**表达不了（架构上限，需旁路 hack）**：

| 功能 | 为何表达不了 |
|---|---|
| 连续/周期行为（每 N tick 扣血、持续喷射） | 无 tick 钩子；CartFeature 无生命周期钩子管理自建任务 |
| 乘坐策略（某类型允许乘坐） | VehicleEnterEvent 硬编码"有方块一律禁止" |
| 上车/下车钩子（上车即传送、下车触发） | 无 onMount/onDismount；旁路监听器无法按类型分发 |
| 纯行为类型（无方块但有行为的矿车） | 解析模型强制"矿车必然代表一个方块"（BlockInfo 或实体类型反查） |
| 第三方运行时扩展 | 无公开 API |

**能但绕**：铁砧耐久（会话 + PrepareAnvilEvent 标记，实现严谨性反而优于我方 P0 前版本）。

#### 我的行为实体模型

**能干净表达**：连续行为（onTick）、移动触发（onMove）、上下车（onMount/onDismount）、乘坐策略（allowRiding）、纯行为类型（handledMaterials 空集）、第三方运行时注册（ServicesManager）、显示校验恢复。

**弱/缺（架构或现状）**：

| 功能 | 说明 |
|---|---|
| 配置化行为映射 | handler 代码声明材料集；管理员不能像参考那样用配置把"任意方块 → 任意特性" |
| 整物品保真 | 容器内容数组独立存 PDC，取下时"带库存的箱子物品/潜影盒"需特判（P2 重构项）；参考模型原生支持 |
| 铁砧判定严谨性 | 现状 rawSlot 2 点击即判定（P0 修复项），参考用 PrepareAnvil 标记 + 延迟确认 |
| 销毁路径覆盖 | 现状 EntityDeathEvent（P0 修复项：EntityRemoveEvent），第三方 remove() 会丢方块 |
| MenuType 玩家绑定菜单 | 现状 location 绑定 + 自研回收，功能等价但实现绕 |

#### 功能上限对照（实用视角）

| 需求 | 参考架构 | 我的架构 |
|---|---|---|
| 新增"工作台同款"方块（如 CONDUIT → 工作台） | 配置加一行 | 改 handler 或等插件更新 |
| 钟乳石矿车（乘坐扣血） | **做不到**（禁乘坐 + 无 tick） | 原生支持（allowRiding + onTick） |
| 传送门矿车（上车即传送） | 需外部监听器旁路 | onMount 原生 |
| 箱子矿车取下"带走库存" | 原生（物品 BlockStateMeta） | 需 P2 重构 |
| 管理员自定义方块行为 | 配置驱动、零代码 | 代码驱动、需开发 |
| 第三方插件贡献新矿车类型 | 改源码/合 PR | 运行时注册 |

### 8.3 结论

- **可维护性**：参考"单入口、好读、但随功能膨胀且扩展改核心"；我"多中心、上手慢、但核心稳定、扩展不碰核心"。
- **功能**：参考是**事件交互模型**——离散交互功能完备（铁砧判定更严谨、整物品语义、配置化），
  连续行为/乘坐策略/上下车钩子/第三方扩展表达不了；我是**行为实体模型**——连续行为/生命周期/
  运行时扩展完备，但配置化表达与整物品保真弱，且 P0 四项正确性修复尚未合入。
- **一句话**：参考架构适合"方块交互插件"的终态（管理员友好、交互完备）；我的架构适合
  "矿车类型平台"的演进（开发者友好、行为丰富）。交集（事件交互）两者差距不大，
  差距在交集之外：参考赢在配置化与物品语义，我赢在行为钩子与第三方扩展。

---

## 9. 关于 VehicleMoveEvent 与 ChestMinecart 的澄清记录

- **`VehicleMoveEvent`**：存在，包名是 `org.bukkit.event.vehicle.VehicleMoveEvent`（Bukkit 经典事件，
  非 `com.destroystokyo.paper.event.entity`）。1.21.0–1.21.4 的 paper-api 中均存在，参考项目正在使用。
  我方此前误搜包名导致误判，已确认可用，列入 P0 修复。
- **`ChestMinecart` / `FurnaceMinecart`**：在任何 Paper 版本（1.21.0–1.21.11、26.2）与 Spigot javadocs 中
  均不存在（jd.papermc.io 逐版本 404）。"箱子矿车"在参考项目中的对应物是 `ChestCartFeature` 类名与
  `StorageMinecart` 接口（箱子矿车 = `StorageMinecart`、熔炉矿车 = `PoweredMinecart`）。
