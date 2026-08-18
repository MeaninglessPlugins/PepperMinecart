# Doublecheck spec

## Goal
对 PepperMinecart 上一轮审查发现的 9 项问题逐一修复并补充回归测试，最终 ./gradlew build 通过且测试 0 失败 0 错误。

## Scope
只修改与 9 项问题直接相关的主代码、测试和 README；允许在相关类内做适度重复代码重构（如 recoverLeftovers 与 closeWithoutPlayer 的公共逻辑）；不重写整体架构，不改无关文件。

## Acceptance criteria
9 项修复全部落地；新增/修改测试覆盖：受保护类型注销/覆盖被拒、离线会话兜底关闭、注册表注销原子性、null 策略与 null onTakeOff 结果、重扫清理失效 context、事件入口 PDC 异常安全、普通矿车提交段异常屏障、铁砧报废缓存 world/loc；./gradlew build 成功且全量测试 0 失败 0 错误。

## Failure modes
修复若与现有防复制/回滚语义冲突，采取保守处理保留原行为并记录；构建或测试失败时停止继续扩大改动，先修复失败；第三方 handler 异常或 null 输入不得逃出事件链；回滚/提交阶段的异常必须被隔离并清理 context。

## Priorities
正确性与测试绿优先于代码风格；可接受较大 diff 以换取清晰代码；低风险项允许简化但必须可验证；允许重构相关重复代码，但不引入新依赖或改变公开 API。

## Non-goals
不修改与 9 项无关的文件；不重写整体架构；不改变现有公开 API 契约（除非某修复不得不做，并需测试确认）。
