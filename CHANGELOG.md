# Changelog

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循语义化版本。

## [1.0.0] - 2026-08-18

### 新增
- 方块放到矿车上：潜行 + 右键空矿车（手持方块）放置，矿车显示该方块；潜行 + 右键取下
- 工作站矿车：工作台、砂轮、织布机、制图台、锻造台、切石机、附魔台
- 铁砧矿车：铁砧界面 + 可选耐久损耗（完整 → 损坏 → 报废，概率可配置）
- 容器矿车：桶、投掷器、潜影盒及任意带库存方块，内容实时回写、随矿车持久化
- 特殊矿车：箱子/漏斗/熔炉/TNT/命令方块自动转换为原版特殊矿车，取下还原普通矿车；支持取下原版特殊矿车
- 发射器矿车：压过充能激活铁轨时随机弹出物品（间隔、偏移、速度可配置）
- 扩展 API：`CartTypeHandler` / `CartContext` / `PepperMinecartAPI`，第三方插件可注册自定义矿车类型
- 配置系统：`take-off-mode`、`allow-all-blocks`、`respect-protection`、`container-pickup-policy`、`vanilla-carts`、`anvil-damage`、`dispenser-cart` 等，`/pm reload` 热重载

### 修复
- 放置/取下/销毁的原子性与防复制：回滚链、HANDLED 取下 PDC 判定、特殊矿车死亡掉落合并
- 配置解析加固与回滚路径修正；矿车内容物丢失与冷却时钟错误
- 会话关闭单一入口、铁砧延迟任务离线守卫、销毁状态机
- API 边界守卫、注册表先校验后提交、延迟注册后重扫
- 交付背包回滚检查 `removeItem` 返回值、发射器掉落原子性、实体失效守卫

### 变更
- 构建产物使用 `-all` 分类器，`bstats` 重定位至 `com.pepperminecart.libs.bstats`
- 工程规范：MIT LICENSE、CHANGELOG、开发文档与评审报告
