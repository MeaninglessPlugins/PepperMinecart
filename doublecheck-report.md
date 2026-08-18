# Doublecheck report

> Verdict: **green**

## Spec
- Goal: 修复评审清单中判定为必要的问题：#2 ItemDelivery 背包回滚必须检查 removeItem 返回值；#3 DispenserCartHandler.shoot 音效后移并缓存 world/location，消除扣库存前 NPE 窗口；#4 takeOff 特殊矿车提交阶段在实体已失效时安全跳过清理并告警；#6 HANDLED 取下日志不再对已正确 releaseCart 的处理器误报；#7 context 重建时迁移 lastReplacement。
- Scope: 修改 src/main/java/com/pepperminecart/delivery/ItemDelivery.java、registry/handler/DispenserCartHandler.java、engine/CartEngine.java、impl/CartContextImpl.java；新增/修改对应测试：ItemDeliveryAtomicityTest、DispenserCartShootRaceTest、TakeOffAtomicityTest。不改公开 API、plugin.yml、build 配置。
- Acceptance criteria: 新增测试先在当前实现上失败（red）；修复后各自转绿；./gradlew test 全量绿且不回归现有测试；Dispenser 发射正常路径仍保持“先掉落成功后扣库存”的原子性。
- Failure modes: #2 修复不得在 removeItem 部分失败时静默返回；#3 修复不得改变发射失败时不扣库存的语义；#4 修复不得在实体有效时跳过必要清理；#6 修复不得漏掉真正未清理 context 的告警；#7 迁移不得暴露为公开 API。
- Priorities: 正确性/防复制优先；最小改动优先；红绿证据优先；不修已验证不成立的第 1 条和低收益的第 5 条。
- Non-goals: 不修改第 1 条（普通方块矿车死亡掉落，已验证不成立）相关逻辑；不修改 AnvilCartSession 的 isSimilar 判定；不新增公开 API；不调整 plugin.yml/build 配置；不做防御性重构。

## Test evidence

### Red run（修复前，5 个新测试按预期失败）
```
./gradlew test --tests ...ItemDeliveryAtomicityTest.giveOrDrop_rollbackInventoryAddThrowsWhenRemoveItemReturnsLeftovers \
  --tests ...DispenserCartShootRaceTest \
  --tests ...TakeOffAtomicityTest.takeOff_specialCartRemovedDuringDelivery_logsInvalidEntityWarning \
  --tests ...TakeOffAtomicityTest.handledTakeOff_releaseCartDoesNotLogMissingPdcWarning \
  --tests ...TakeOffAtomicityTest.contextRebuild_migratesLastReplacement
```
- ItemDeliveryAtomicityTest：expected IllegalStateException but nothing thrown（removeItem 返回未移除项被静默吞掉）
- DispenserCartShootRaceTest：NullPointerException（shoot 在掉落完成后再次访问 cart.getWorld()）
- takeOff_specialCartRemovedDuringDelivery_logsInvalidEntityWarning：missing “已失效” 告警
- handledTakeOff_releaseCartDoesNotLogMissingPdcWarning：误报 “HANDLED 取下未写 PDC”
- contextRebuild_migratesLastReplacement：lastReplacement 未迁移

5 tests completed, 5 failed — 失败原因均命中对应缺陷。

### Green run（修复后）
```
./gradlew test
BUILD SUCCESSFUL
tests=130 failures=0 errors=0 skipped=0
```
新增 5 个测试全部转绿，既有测试无回归。

## Adversary review
- 上一轮评审清单逐条核验：第 1 条已验证不成立（Paper 1.21.4 普通矿车死亡掉 minecart 物品，不掉 display block）；第 2/3/4/6/7 条成立并已在本轮修复；第 5 条成立但低收益，按 non-goal 挂起。
- 修复采用最小改动，未改变“先掉落成功后扣库存”“交付失败保留源数据”等既有原子性语义。

## Verification
- 全量测试：`./gradlew test` → 130 tests, 0 failures, 0 errors, 0 skipped。
- 静态核验：DispenserCartHandler 已缓存 world/location 且音效后移至持久化之后；HANDLED 分支仅当 context 仍被跟踪时告警；context 重建迁移 lastReplacement。

## Delivery
- implementation edits:
  - src/main/java/com/pepperminecart/delivery/ItemDelivery.java（removeItem 返回值检查）
  - src/main/java/com/pepperminecart/registry/handler/DispenserCartHandler.java（缓存 world/location、音效后移、失效守卫）
  - src/main/java/com/pepperminecart/engine/CartEngine.java（特殊矿车提交阶段失效守卫、HANDLED 日志条件化、context 重建迁移 lastReplacement）
  - src/main/java/com/pepperminecart/impl/CartContextImpl.java（inheritReplacementFrom）
- test edits:
  - src/test/java/com/pepperminecart/delivery/ItemDeliveryAtomicityTest.java
  - src/test/java/com/pepperminecart/DispenserCartShootRaceTest.java（新增）
  - src/test/java/com/pepperminecart/TakeOffAtomicityTest.java

## Working tree note
工作区除本轮修复外，仍包含先前未提交的 takeOff“内嵌内容 + 容器 DROP spill”修复及其测试（21 个已修改文件与 12 个未跟踪测试文件中的其余部分）。本报告仅覆盖本轮 spec；全部改动均未提交，提交请将两轮改动一并纳入或按需拆分。
