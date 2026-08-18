# 修改意见（对应 code-review-report.md 的 F1–F7）

> 只给方案，未执行任何修改。验证手段统一：改动后 `./gradlew build` 全绿（130 测试）为最低门槛。

## F1 · High · 工程卫生：提交与推送

**方案 A（推荐，按语义拆 2 组提交）**

- 组 A「防复制/交付原子性修复」（对应 doublecheck-report 的 #2/#3/#4/#6/#7）：
  - main：`delivery/ItemDelivery.java`、`registry/handler/DispenserCartHandler.java`、`engine/CartEngine.java`、`impl/CartContextImpl.java`
  - test：`delivery/ItemDeliveryAtomicityTest.java`、`DispenserCartShootRaceTest.java`、`TakeOffAtomicityTest.java`
  - 注意 `CartEngine.java` 混有两轮改动，用 `git add -p` 按 hunk 挑选（spill/内嵌内容相关 hunk 归组 B）
- 组 B「takeOff 内嵌内容 + 容器 DROP spill 与剩余健壮性」：其余 12 个 main 修改 + 其余 10 个新测试
- 提交信息建议：`fix: 取下交付失败时回滚已放入背包/已生成掉落，消除复制` 与 `fix: 特殊矿车取下内嵌内容与容器 DROP spill 的原子处置`

**方案 B（省事版）**：拆不动时退化为 2 个提交——「fix: 交付与取下原子性/健壮性修复（含测试）」+「docs: doublecheck 记录」，风险可控。

**WIP 提交整理**（9 个 WIP 混在 main 历史）：
```bash
git branch backup/before-rebase   # 保底
git rebase -i d767ddb             # origin/main 之后重写，把 WIP 合并为语义化提交
```
然后 `git push origin main`。推送前最后跑一次全量构建。

## F2 · Medium · README 产物名

两处（README.md:24 安装段、README.md:153 构建说明）`PepperMinecart-1.0.0.jar` 改为 `PepperMinecart-1.0.0-all.jar`，并在安装段补一句「不含 -all 的是瘦包（无内置 bstats），部署会 NoClassDefFoundError」。不要反向改 build.gradle——-all 分类器是有意设计。

## F3 · Medium · 9+2 个弃用 API

**main 8 处**（AnvilHandler:46 openAnvil；WorkstationHandler:55–61 七处 open*）：
已从 paper-api 1.21.4 字节码确认这些方法 `@Deprecated(since="1.21.4")`（未标 forRemoval，短期安全），替代为 `openInventory(Inventory)`（需要先用 `Bukkit.createInventory(null, InventoryType.X)` 构造虚拟库存）。

WorkstationHandler.onInteract 迁移草图（行为等价，原 force=true 本就无距离检查）：
```java
InventoryType type = switch (m) {
    case CRAFTING_TABLE -> InventoryType.WORKBENCH;
    case GRINDSTONE -> InventoryType.GRINDSTONE;
    case LOOM -> InventoryType.LOOM;
    case CARTOGRAPHY_TABLE -> InventoryType.CARTOGRAPHY;
    case SMITHING_TABLE -> InventoryType.SMITHING;
    case STONECUTTER -> InventoryType.STONECUTTER;
    case ENCHANTING_TABLE -> InventoryType.ENCHANTING;
    default -> null;
};
if (type == null) return false;
InventoryView view = player.openInventory(Bukkit.createInventory(null, type));
if (view == null) return false;
sessions.openVanillaView(player, cart, view);
return true;
```
AnvilHandler:46 同理（`InventoryType.ANVIL`）。迁移后必须跑 `MinecartFlowTest`、`AnvilCartSessionOfflineTest` 等界面相关测试确认 MockBukkit 虚拟视图行为不变；若 MockBukkit 对某 InventoryType 支持不完整，就按类型逐个迁移而不是一把梭。

**test 2 处**：
- `EngineStateFixTest:343`：`ctx.giveItem(player, item, TakeOffResult.DISABLED)` → `ctx.deliverItem(player, item, TakeOffResult.DISABLED)`（新 API 返回 boolean，断言不变）。本插件自己的旧签名 @Deprecated 方法建议保留（公开 API 兼容），仅测试换新调用。
- `ContainerHandlerTest:32`：`LEGACY_WHITE_SHULKER_BOX` 被标记 for removal。该测试意图就是验证 isLegacy 防护，加 `@SuppressWarnings("removal")` + 一行注释「刻意使用 legacy 常量验证防护，官方移除前保留」即可，无需改断言。

## F4 · Low · takeOff 287 行拆分

零风险拆法（只提取、不改控制流，测试全绿即通过）：
1. `captureVanillaContents(InventoryHolder)` — 提取非空内容列表（现在内联在 takeOff 的两个分支里，提取后两处复用）
2. `migrateCustomName(Minecart from, Minecart to)` / `migratePassengers(Minecart from, Minecart to)` — 后者与 CartContextImpl.replaceEntity 里的乘客迁移对齐
3. `rollbackReplacementAndDrops(Minecart, List<Item>)` — finally 回滚体
4. `cleanupContextIfUnmanaged(CartContextImpl)` — finally 尾段

不建议引入大结构（record TakeOffPreparation 之类）——takeOff 的五段式控制流是防复制语义的骨架，大改得不偿失。

## F5 · Low · logger 混用 3 处

- 推荐：`AnvilDamageTracker` 构造器加第三参 `Logger`，`PepperMinecartPlugin.onEnable` 传 `getLogger()`（改 2 处 + 测试构造点同步或加重载兼容）。
- `CartData`（静态工具类）与 `SpecialCartHandler.onCartDestroyed`：保持 `Bukkit.getLogger()` 并加一行注释「静态上下文，约定使用 Bukkit logger（消息自带 [PepperMinecart] 前缀）」，或为 CartData 加一次性 `setLogger` 注入。两者任选，不必为此大动。

## F6 · Info · CI 与覆盖率（可选）

GitHub Actions（.github/workflows/build.yml）：checkout → setup-java 21 (temurin) → gradle/actions/setup-gradle → `./gradlew build` → upload `build/libs/*-all.jar`。jacoco 对 MockBukkit 动态代理场景意义有限，可只挂报告不设阈值（gradle 加 `id("jacoco")` + `jacocoTestReport`，需 0.8.12+ 兼容 Java 21）。

## F7 · Info · AnvilDamageTracker 报废音效顺序

最小 diff：在 `onResultTaken` 交付前已取得的 `world`/`loc` 复用，报废分支的音效行 `cart.getWorld().playSound(cart.getLocation(), ...)` 改为 `world.playSound(loc, ...)`（这两变量在方法前段已捕获且交付成功才走到这里）。一行改动。

## 建议执行顺序

F1（备份+提交+推送，防丢失）→ F2（一行文档）→ F7+F5（各一两行）→ F3（弃用迁移，配回归测试）→ F4（纯提取重构）→ F6（可选）。每步独立跑全量测试。
