# PepperMinecart 开发文档

> 面向插件维护者/贡献者的开发说明。用户文档见根目录 `README.md`。

## 1. 项目概览

PepperMinecart 是一个 Paper 1.21+ 插件，允许玩家把方块放到矿车上，形成“移动工作站 / 移动仓库”。

核心能力：

- 放置方块到普通矿车，使用原版 `Minecart#setDisplayBlockData` 渲染；
- 潜行取下，按 `take-off-mode` / `container-pickup-policy` 分发物品；
- 工作站、铁砧、容器、特殊矿车、发射器矿车等内置类型；
- 通过 `PepperMinecartAPI` 支持第三方注册新矿车类型。

## 2. 技术栈与构建

- Java 21，Gradle Kotlin DSL；
- 依赖 `io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT`；
- 测试使用 JUnit 5 + MockBukkit；
- 构建产物由 Shadow 打包，relocate `org.bstats`。

```bash
# 注意：必须 JDK 21+。~/jdk-17 无法构建（build.gradle.kts 要求 release 21，
# 且 paper-api 1.21.4 本身要求 JVM 21）；home 下的 GraalVM JDK 21 可用：
JAVA_HOME=/home/pepper/graalvm/jdk-21 PATH=$JAVA_HOME/bin:$PATH ./gradlew clean build
```

## 3. 源码结构

| 路径 | 职责 |
|---|---|
| `com.pepperminecart.api` | 对外扩展接口：`CartTypeHandler`、`CartContext`、`PepperMinecartAPI` |
| `com.pepperminecart.engine` | 引擎：受管矿车集合、tick、事件路由、取下/销毁编排、实体替换原语 |
| `com.pepperminecart.interaction` | 玩家右键矿车交互状态机 |
| `com.pepperminecart.container` | 打开中的界面会话管理：容器实时回写、虚拟界面残留回收、铁砧耐久判定 |
| `com.pepperminecart.registry` | 类型注册表与内置处理器 |
| `com.pepperminecart.storage` | PDC 整物品持久化 |
| `com.pepperminecart.display` | 显示方块封装 |
| `com.pepperminecart.anvil` | 铁砧损坏状态机 |
| `com.pepperminecart.config` | 配置模型与冷却 |
| `com.pepperminecart.command` | `/pm reload` |

## 4. 核心数据流

### 4.1 放置

1. `InteractionListener` 判定空普通矿车 + 潜行 + 手持允许方块；
2. `CartEngine.addCart` 写入 PDC：`cart-type`、`item`、`original-material`；
3. 调用 `CartTypeHandler.onPlaced`；
4. 普通矿车设置显示方块与偏移；特殊矿车转换为原版特殊矿车实体；
5. 成功后消耗主手 1 个物品；失败回滚并保持物品不消耗。

### 4.2 取下

1. `InteractionListener` 做策略/权限/乘客/保护检查；
2. `CartEngine.takeOff` 统一编排；
3. 先 `flushCartSessions` 回写并关闭该矿车界面；
4. 调用 `onTakeOff`；非 `HANDLED` 时取得返还物品；
5. 特殊矿车还原为普通矿车；普通矿车清除显示与 PDC；
6. 按策略把物品交给玩家或落地。

### 4.3 销毁

`EntityRemoveEvent` 统一处理矿车移除：

- `UNLOAD` 只回写/关闭会话，不视为销毁；
- 原版特殊矿车走原版死亡掉落时跳过插件补掉落，避免重复；
- 其他移除路径调用 `onCartDestroyed` 并执行默认掉落。

## 5. 关键设计决策与预期行为

以下行为是**有意设计**，修改前需确认是否影响既有需求：

- 挂有方块的矿车默认禁止乘坐，由 `CartTypeHandler.allowRiding()` 控制。
- 潜影盒不受 `container-pickup-policy` 管控，始终按 `take-off-mode` 处理。
- 铁砧耐久链：`ANVIL → CHIPPED_ANVIL → DAMAGED_ANVIL → 报废`。
- **命令矿车放置/取下/销毁时保留命令**：`buildTakeOffItem` 会把实体当前命令写回方块物品（清空命令也会写回空串）；但命令方块的自定义名（`CommandBlock#getName`）与连锁/循环类型不迁移，连锁/循环一律按普通命令矿车处理。
- 容器内容以“整物品”形式存 PDC，取下时随物品 NBT 回收；特殊矿车内容在取下的瞬间组装进带库存的容器物品。
- 投掷器矿车使用世界年龄语义冷却，跨区块卸载/重载持久。

## 6. 错误处理约定

- **不针对“数据受损”场景做自愈/恢复设计**。
- 若运行中检测到异常数据（例如 PDC 物品缺失、材质无法解析、显示与存储不一致但无法安全修复），只应在后台打印日志提醒，不额外尝试猜测性恢复。
- 引擎自身的状态管理（`contexts`、会话表）**必须保持一致性**：任何提前 return / 异常路径都不能泄漏受管 context 或打开中的会话。
- 单个矿车的 `onTick` / `onMove` / `onCartDestroyed` 异常不应拖垮全局 tick 任务或事件链路，应记录日志并跳过该矿车。

## 7. 测试

```bash
JAVA_HOME=/path/to/jdk-21 PATH=$JAVA_HOME/bin:$PATH ./gradlew test
```

测试覆盖：

- 放置/取下三策略；
- 销毁不复制；
- 容器会话回写；
- 铁砧耐久状态机；
- 特殊矿车转换；
- 冷却语义；
- 配置解析。

新增功能或修复 Bug 时，应同步补充或更新 MockBukkit 测试。
