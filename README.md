# PepperMinecart

Minecraft **Paper 1.21+** 服务端插件：把方块放到矿车上，实现**移动工作站 / 移动仓库**。

- **潜行 + 右键空矿车**（手持方块）：消耗 1 个方块放到矿车上，矿车显示该方块
- **潜行 + 右键有方块的矿车**：把方块取下（进背包 / 落地 / 禁止，可配置）
- **右键有方块的矿车**：打开方块对应的功能界面（工作台、铁砧、容器等）

## 功能特性

| 类别 | 支持 |
|---|---|
| 工作站 | 工作台、砂轮、织布机、制图台、锻造台、切石机、附魔台 |
| 铁砧 | 铁砧界面 + 可选耐久损耗（完整 → 损坏 → 报废，可配置概率） |
| 容器 | 桶、投掷器、潜影盒及**任意带库存的方块**（通用容器），内容实时回写、随矿车持久化 |
| 特殊矿车 | 箱子/漏斗/熔炉/TNT/命令方块 放置后自动转换为原版特殊矿车实体；取下时还原普通矿车，箱子/漏斗内容组装进"带库存的容器物品"随物品回收；世界上已存在的原版特殊矿车同样支持取下（`vanilla-carts.allow-pickup` 可关） |
| 发射器矿车 | 压过充能激活铁轨时随机弹出 1 个物品（间隔、偏移、速度可配置） |
| 数据安全 | 方块显示随实体 NBT 自动持久化；容器内容存实体 PDC；矿车被破坏时掉落方块与内容，不丢失、不复制 |
| 交互体验 | 交互冷却防连点、放置/取下音效开关、容器类矿车独立取下策略（PICKUP/DROP/FORBIDDEN） |
| 扩展性 | 统一 `CartTypeHandler` 扩展接口，第三方插件可注册全新矿车类型（见下文"扩展指南"） |

## 安装

1. 将 `PepperMinecart-1.0.0.jar` 放入服务器 `plugins/` 目录
2. 重启服务器（或 `/reload`）
3. 首次启动自动生成带注释的 `plugins/PepperMinecart/config.yml`

> 依赖：Paper 1.21+（非 Folia），Java 21+。

## 配置

所有配置项在 `config.yml` 中均有中文注释，修改后执行 `/pm reload` 热重载，无需重启。

```yaml
take-off-mode: INVENTORY      # 取下策略（所有方块统一，无例外）：INVENTORY 进背包 | DROP 落地 | DISABLED 禁止取下
allow-all-blocks: true        # true=除黑名单外全部允许；false=仅允许白名单
disabled-blocks: [BEDROCK, BARRIER]
enabled-blocks: []
respect-protection: true      # 与领地插件协作（模拟方块放置/破坏事件）
display-block-offset: 6       # 显示方块高度（1/16 格），按视觉效果调整
interaction-cooldown-ms: 250  # 交互冷却
sounds: { place: true, take-off: true }
container-pickup-policy: PICKUP  # 容器类矿车（木桶/投掷器/通用容器，不含潜影盒）：
                                 # PICKUP 带NBT取下 | DROP 内容物落地、容器按 take-off-mode | FORBIDDEN 禁止取下
vanilla-carts:
  allow-pickup: true          # 是否允许取下原版特殊矿车（箱子/漏斗/熔炉/TNT/命令）上的物品
anvil-damage:                 # 铁砧耐久（可选）
  enabled: true
  chance-per-use: 0.12
  drop-on-break: false
dispenser-cart:               # 发射器矿车
  cooldown-ticks: 40
  eject-offset: 0.6
  eject-speed: 0.5
```

## 命令与权限

| 命令 | 权限 | 默认 |
|---|---|---|
| `/pepperminecart reload`（别名 `/pm`） | `pepperminecart.reload` | op |
| 放置/取下/打开界面 | `pepperminecart.use` | 所有玩家 |

## 数据与兼容

- 矿车方块显示使用原版实体 NBT（`DisplayState`），随区块保存/关服重启自动持久化。
- 容器内容与业务数据存实体 PDC（`pepperminecart:*`），点击界面实时回写。
- 矿车被破坏：方块物品与容器内容掉落（TNT 矿车爆炸同理），不丢失。
- 挂有方块的矿车禁止玩家/生物乘坐（扩展类型可通过 `allowRiding()` 开放，见下）。
- 与领地插件协作：开启 `respect-protection` 后，放置/取下会模拟 `BlockPlaceEvent`/`BlockBreakEvent`，被其他插件取消则禁止操作。

## 扩展指南：新增矿车类型

Paper 无官方"自定义实体类型"注册 API，本插件以**原版矿车 + 类型 id（PDC）+ 行为钩子**提供统一扩展模型。
内置类型（工作站/铁砧/容器/特殊矿车/投掷器）全部是 `CartTypeHandler` 的实现，第三方插件能力对等。

**三步接入，零核心改动** —— 以"钟乳石矿车（乘坐扣血）"为例：

```java
// 1) 实现类型（只覆盖需要的钩子）
public class DripstoneCartHandler implements CartTypeHandler {
    private static final NamespacedKey INTERVAL = NamespacedKey.fromString("dripstone:interval");
    private static final NamespacedKey COOLDOWN = NamespacedKey.fromString("dripstone:cooldown");

    @Override public NamespacedKey getId() { return NamespacedKey.fromString("dripstone:cart"); }
    @Override public Set<Material> handledMaterials() { return Set.of(Material.POINTED_DRIPSTONE); }
    @Override public boolean allowRiding() { return true; }   // 允许乘坐（默认禁止）

    @Override public void onPlaced(Player player, CartContext ctx) {
        ctx.setDisplay(Material.POINTED_DRIPSTONE);           // 显示钟乳石
        ctx.setMetaInt(INTERVAL, 20);                         // 每 20 tick 结算一次
    }
    @Override public void onTick(CartContext ctx) {
        if (ctx.getRider() == null || ctx.hasCooldown(COOLDOWN)) return;
        ctx.setCooldown(COOLDOWN, ctx.getMetaInt(INTERVAL, 20));
        ((LivingEntity) ctx.getRider()).damage(1.0, ctx.getMinecart());  // 每次半颗心
        ctx.getWorld().playSound(ctx.getLocation(), Sound.BLOCK_POINTED_DRIPSTONE_DRIP, 1f, 1f);
    }
}

// 2) 注册（你的插件 onEnable 中）
PepperMinecartAPI api = Bukkit.getServicesManager().load(PepperMinecartAPI.class);
api.registerCartType(new DripstoneCartHandler());
```

完成。玩家把钟乳石放到空矿车上即生成"钟乳石矿车"：显示方块自动出现、上车持续扣血、
潜行右键取下还原普通矿车并返还钟乳石、破坏矿车掉落钟乳石——全部由引擎默认行为处理。

### 钩子一览（全部有默认实现）

| 钩子 | 说明 |
|---|---|
| `canPlace` | 是否允许放置 |
| `allowRiding` | 是否允许乘坐（默认 false，对应"挂块矿车禁止乘坐"） |
| `onPlaced` | 放置后（引擎默认：显示方块 + 写类型数据） |
| `onInteract` | 非潜行右键，打开界面（返回 true = 已处理） |
| `onTakeOff` | 潜行右键取下。返回 `TakeOffOutcome.DEFAULT` 由引擎执行默认流程（还原普通矿车 + 返还物品）；返回 `HANDLED` 表示处理器已完整处理 |
| `getTakeOffItem` | 取下返还的物品（默认：放置时的方块） |
| `onTick` | 引擎每 tick 调度（仅受管矿车） |
| `onMove` | 矿车移动时（投掷器铁轨检测同款钩子） |
| `onMount` / `onDismount` | 上下车事件（仅 `allowRiding()` 为 true 时触发） |
| `onCartDestroyed` | 矿车被破坏（引擎掉落方块与内容之前） |

运行时上下文 `CartContext` 提供：显示控制（`setDisplay`）、持久化元数据（`setMetaInt`/`setMetaString`）、
冷却（`setCooldown`/`hasCooldown`，世界年龄语义：单调递增、跨卸载/重启持久、不受 `/time set` 与系统时钟影响）、
物品分发（`giveItem`/`dropItem`）、
实体替换（`replaceEntity`，特殊矿车转换场景，自动复制数据并重新跟踪）等能力。

## FAQ

**Q：木桶有开合动画吗？**
A：播放开合音效；完整的方块动画需要发包伪造方块，列为后续增强。

**Q：取下容器矿车（木桶等）时里面的东西怎么办？**
A：由 `container-pickup-policy` 控制——`PICKUP` 内容物随容器物品（NBT）带走；`DROP` 内容物全部掉落到地面、
容器物品本身按 `take-off-mode` 处理；`FORBIDDEN` 禁止取下容器类方块。潜影盒不参与该策略，与其他方块一样按 `take-off-mode` 处理。

**Q：铁砧"报废"了掉落什么？**
A：默认直接消失；可配置 `anvil-damage.drop-on-break: true` 掉落损坏铁砧物品。

**Q：支持 Folia 吗？**
A：本期仅支持 Paper（`folia-supported: false`），Folia 兼容列为远期事项。

**Q：矿车被炸了数据会丢吗？**
A：不会。方块物品与容器内容会在矿车死亡位置掉落；显示方块随实体消亡，无残留。

**Q：为什么会"复制"方块？**
A：插件在矿车死亡时清空实体数据并移除显示，所有路径统一走引擎默认行为，避免复制。

## 构建

```bash
./gradlew build        # 产物: build/libs/PepperMinecart-1.0.0.jar
./gradlew test         # 单元测试 + MockBukkit 集成测试（放置/取下/销毁/容器/铁砧核心路径）
```

## 待办与已知限制

- bStats 插件 ID 占位（0 = 关闭），发布前在 [bstats.org](https://bstats.org) 注册后替换 `PepperMinecartPlugin.BSTATS_PLUGIN_ID`。
- 熔炉矿车取下与原版一致，为普通熔炉物品（不携带燃料）。
- 命令方块矿车放置/取下时保留已设置的命令（原版只有普通命令矿车一种实体，连锁/循环类型按普通命令矿车处理）。
- 木桶开合动画（完整版）需发包伪造方块，暂以音效替代。
- 监听 `EntityRemoveEvent`（Paper 标记弃用但功能正常，所有 1.21.x 均触发）以覆盖插件 `remove()` 等全部矿车移除路径；启动日志会出现一条该事件的弃用 WARN，属预期。
- 箱子/漏斗矿车取下时返还"带库存的容器物品"（内容组装进物品 NBT 随物品回收）；矿车被破坏时方块本体与内容均由原版掉落（插件不再重复掉落，防止复制）。
