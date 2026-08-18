# Doublecheck spec

## Goal
修复已确证 Bug #2：CartContextImpl.deliverItem 在 TakeOffResult.DISABLED 下必须返回 false 并记录告警，物品不得交付；调用方按契约保留源数据，关闭静默丢物入口。

## Scope
仅改 src/main/java/com/pepperminecart/impl/CartContextImpl.java（DISABLED 分支返回值 + 告警日志）与新增一个红绿测试；不改引擎/接口/配置，不改其他模块。

## Acceptance criteria
1) 红测试：deliverItem(player, item, DISABLED) 返回 false；2) 同一测试断言背包与地面均无该物品；3) 现有 EngineStateFixTest.giveItemDisabledModeDeliversNothing 保持通过；4) 全量 ./gradlew test 绿（≥137 tests，0 failures）。

## Failure modes
DISABLED 下 deliverItem 不得产生任何物品交付（背包/地面）；返回 false 后调用方保留源数据；告警日志只记录一次（在 deliverItem 的 DISABLED 分支统一输出，避免 giveItem 双入口重复刷屏）。

## Priorities
正确性（不丢物、契约一致）> 最小改动；不新增公开 API、不改配置语义。

## Non-goals
不修改 CartContext 接口默认实现；不动 takeOff 前置拦截逻辑；不处理 takeOff 交付期间被第三方移除的同族竞态（另行决策）。
