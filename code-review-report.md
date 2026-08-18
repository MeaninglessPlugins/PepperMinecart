# PepperMinecart 代码质量评审报告

> 评审日期：2025-08-17 · 依据契约 `code-review-spec.md` · 只读评审，未修改任何源码
>
> 绿门记录：评审结束后强制重跑 `./gradlew cleanTest test --rerun-tasks`（7 tasks executed，无缓存复用）→ BUILD SUCCESSFUL，130 tests / 0 failures / 0 errors / 0 skipped（33 套件）

## 状态总览

| 项 | 值 |
|---|---|
| 分支 | main，领先 origin/main **13 个提交**（含 9 个 WIP 提交） |
| 工作区 | 21 个已修改文件 + 13 个未跟踪测试文件，均未提交 |
| 构建 | `./gradlew build` **BUILD SUCCESSFUL** |
| 测试 | **130 tests, 0 failures, 0 errors, 0 skipped**（33 个测试类） |
| 产物 | `PepperMinecart-1.0.0-all.jar`（bstats 已重定位至 `com.pepperminecart.libs.bstats` ✓） |
| 规模 | main 34 文件 / 4810 行；test 33 文件 / 5055 行（测试代码多于生产代码） |

## 分维度判定

### 1. 构建与交付：绿
- 构建、shadowJar、全量测试全部通过；`BuildArtifactContractTest` 断言交付 jar 含重定位 bstats，防"瘦包误部署 NoClassDefFoundError"。
- jar 与 shadowJar 用 `-all` 分类器分离，避免同名覆盖的部署陷阱（build.gradle 注释说明了缘由）。

### 2. 功能正确性：绿（测试证据）
- 130 个测试全绿；上一轮交付有完整红绿记录（`doublecheck-report.md`：5 个新测试先红后绿，修复 #2/#3/#4/#6/#7）。
- 测试断言真实（无 `assertTrue(true)`、无 @Disabled、无 TODO 桩）。

### 3. 健壮性：优（人工评审证据）
这是本项目的最大亮点，防御性设计成体系：
- **原子性回滚链**：`ItemDelivery.dropAllTracked` 部分掉落失败回滚已生成实体；`giveOrDrop` 背包回滚检查 `removeItem` 返回值；`takeOff` 提交阶段与回滚阶段分离（`committed` 标志）。
- **失效实体守卫**：`DispenserCartHandler.shoot` 缓存 world/location、掉落完成后不再访问实体；`CartEngine.takeOff` 提交阶段对"实体已失效"分支跳过 PDC/显示清理只解除跟踪。
- **统一异常屏障**：`safeHandler`/`safeInteract`/`safeContainerControlled`/`safeCanPlace` 对第三方 handler 回调逐个兜底，异常不逃出事件链（Bukkit 调度器未捕获异常会整体取消 repeating task，tick 注释正确识别了该风险）。
- **防复制专项**：HANDLED 取下 PDC 判定、`drainOrClearVanillaInventory` 不静默清空、`SpecialCartHandler.onPlaced` 溢出掉落回收、`onCartDestroyed` 批量原子掉落。
- 静态扫描干净：无 TODO/FIXME、无 `System.out`/`printStackTrace`、无空 catch、main 源码零 `@SuppressWarnings`。

### 4. 可维护性：良
- 注释质量高：关键路径（takeOff 编排、destroy 掉落判定、PDC 损坏处理）都有"为什么这么做"的注释而非复述代码。
- 分层清晰：api / engine / registry+handler / container / delivery / storage，职责边界明确。
- 扣分项见发现清单 F3–F6。

### 5. 测试质量：良+
- 命名描述性强（`takeOff_specialCartRemovedDuringDelivery_logsInvalidEntityWarning` 等）；用动态代理模拟"实体已移除/PDC 不可读"等难以真实构造的边界。
- 扣分：无 jacoco 覆盖率指标；动态代理测试代码较重，可读性有代价。

### 6. 工程卫生：风险
- 9 个 WIP 提交混入 main 历史；21+13 个文件的正确性修复全部滞留工作区未提交；13 个提交未推送。
- 关键防复制/回滚修复处于单机丢失风险中。这是本次评审唯一的 High 级发现，且与代码质量本身无关。

## 发现清单

| # | 严重度 | 位置 | 发现 | 建议 |
|---|---|---|---|---|
| F1 | **High** | 仓库状态 | 13 提交未推送、21 修改 + 13 未跟踪未提交（含防复制核心修复），历史含 9 个 WIP 提交 | 拆分提交并推送（可按 doublecheck-report 的边界拆两轮），WIP 提交 squash |
| F2 | Medium | `README.md` 安装段 | 安装指引写 `PepperMinecart-1.0.0.jar`，实际交付物为 `PepperMinecart-1.0.0-all.jar`；瘦包部署会 NoClassDefFoundError（build.gradle 注释自己警示过该坑） | README 改为 -all 产物名 |
| F3 | Medium | `AnvilHandler:46`、`WorkstationHandler:55-61`、`EngineStateFixTest` | 9 个 deprecation 警告：`HumanEntity#openAnvil/openWorkbench/…` 在 Paper 1.21.4 已弃用 | 迁到 `openInventory` 替代 API；或至少记录升级计划 |
| F4 | Low | `CartEngine.takeOff`（287 行） | 单方法超长（放置/交付/提交/回滚/context 清理五段式） | 提取 `prepareSpecialReplacement`、`commitSpecialCart` 等私有方法 |
| F5 | Low | `AnvilDamageTracker:68`、`CartData:73`、`SpecialCartHandler:294` | 3 处静态上下文用 `Bukkit.getLogger()`，与其余 plugin logger 不一致 | 统一传 logger 或用 plugin logger |
| F6 | Info | — | 无 CI、无 jacoco/checkstyle 配置；`COMPARISON.md`/`PLAN.md` 为历史设计文档，与现状无维护关系 | 可选：GitHub Actions 跑 `./gradlew build` |
| F7 | Info | `AnvilDamageTracker.onResultTaken` 报废分支 | `cart.getWorld().playSound(...)` 在 clear/removeCart 之后（当前有 isValid 前置，风险低） | 顺手缓存 world/location |

## 总体判定

**代码质量：优良（A-）**。正确性与健壮性设计达到同类插件少见的水准——原子交付/回滚、失效实体守卫、统一异常屏障、防复制专项均有测试锚定；未发现功能性缺陷。主要失分在可维护性小项（超长方法、弃用 API、文档产物名不一致）和工程卫生（大量关键修复未提交未推送）。**在代码层面可以发布；发布前必须先处理 F1 与 F2。**
