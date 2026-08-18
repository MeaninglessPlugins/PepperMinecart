# Doublecheck spec

## Goal
实施已收敛的发射器矿车优化方案：把 shoot 改为先扣减并持久化、再掉落，掉落被取消且实体有效时补偿恢复槽位；引入统一容器访问抽象；onMove 冷却检查前置。以红→绿测试证明无复制、无丢失。

## Scope
仅改 src/main/java/com/pepperminecart/registry/handler/DispenserCartHandler.java（+ 新增同包小类 DispenserContainerAccess）与新增/调整对应测试；不改其他模块、不改配置、不改公开 API。

## Acceptance criteria
1) 红测试：shoot 在掉落前必须已扣减并持久化（模拟掉落期间移除矿车，断言掉落发生时源槽已扣空）；2) 掉落被取消时槽位恢复且持久化；3) 现有 DispenserCartShootRaceTest 保持通过；4) 全量 ./gradlew test 绿（≥130 tests，0 failures）。

## Failure modes
persist 在掉落前失败 → 中止发射、不产生掉落、源数据不变；掉落被取消且矿车已被移除（双重失败窗口）→ 不触碰失效实体 PDC，接受 1 件损失并在注释文档化；实体失效后任何路径不得访问 getWorld/getLocation/PDC；MockBukkit 无法跨 PDC 序列化容器内容时，用'掉落发生时源槽已扣空'这一不变量断言替代。

## Priorities
正确性（单故障下不复制/不丢失）> 最小改动 > 性能；保持先扣后掉新不变式清晰注释；不加配置项、不加新公开 API。

## Non-goals
不做常驻容器缓存；不做引擎级 onPoweredRail 钩子；不做 SEQUENTIAL 槽位策略；不改音效/弹射参数语义；不重构引擎其他部分。
