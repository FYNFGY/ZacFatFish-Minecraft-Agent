# Ph9 开发文档包

> 目的：让下一轮接手者在不依赖当前会话上下文的情况下，理解系统、复现环境、继续开发并验收。
>
> **2026-11-06 更新：Pendulum 方案已废弃。** 不再安装 Pendulum/Jupiter/Rhino，不再提供 `pendulum_*` 工具；战斗全部由 Bridge 原生实现。本文中历史 Pendulum 描述仅用于对照。
>
> 本目录是 Ph9 的**唯一入口**。先读本文件，再按顺序读 01–19。

## 1. Ph9 一句话目标

把当前“能在平地上打怪、能在命令传送下进末地”的能力，升级为：

```text
原版生存可到达要塞
→ 激活并进入末地
→ 清除水晶
→ 击败末影龙
→ 全程有 safety / evidence / 可恢复
```

## 2. 文档索引

| 编号 | 文档 | 作用 |
|---|---|---|
| 00 | `README.md`（本文件） | 索引、阅读顺序、门禁 |
| 01 | `01-scope-and-goals.md` | Ph9 范围、非目标、里程碑 |
| 02 | `02-requirements.md` | 功能/非功能需求，FR/NFR 编号 |
| 03 | `03-architecture.md` | 组件、端口、部署、时序、数据边界 |
| 04 | `04-tool-contracts.md` | 新工具 schema/返回/错误码；引用根 `MCP-TOOLS.md` |
| 05 | `05-test-plan.md` | 真机测试矩阵、命令、证据要求 |
| 06 | `06-roadmap.md` | 9.1–9.5 阶段、依赖、退出条件 |
| 07 | `07-backlog.md` | P0/P1/P2 任务与已知缺陷 |
| 08 | `08-runbook.md` | 安装、启停、端口、常见故障 |
| 09 | `09-release-checklist.md` | release 构建与发布前检查 |
| 10 | `10-risk-register.md` | 风险、触发条件、缓解 |
| 11 | `11-data-and-storage.md` | ChatHub、Store、Memory、Job、证据 |
| 12 | `12-observability.md` | InfoTxt、日志、Job metrics、截图 |
| 13 | `13-acceptance.md` | Ph9 验收门禁与签署条件 |
| 14 | `14-glossary.md` | 术语表 |
| 15 | `15-handoff-prompt.md` | 下一轮 AI 的启动提示词 |
| 16 | `16-evidence-template.md` | 证据 JSON 模板 |
| 17 | `17-open-questions.md` | 待用户确认的范围/验收问题 |
| 18 | `18-compatibility.md` | 版本/可选依赖矩阵 |
| 19 | `19-source-map.md` | 源码与关键类定位 |

## 3. 根目录必读图

- [SADT](../SADT.md) — 系统上下文与分解
- [FAST](../FAST.md) — 功能树与端到端链
- [MCP-TOOLS](../MCP-TOOLS.md) — 122 个内置工具 + 7 个插件工具

## 4. 阅读顺序

### 第一次接手

```text
README（本文件）
→ 01 scope
→ 03 architecture
→ 04 tool contracts
→ 08 runbook
→ 05 test plan
→ 07 backlog
```

### 只想跑测试

```text
08 runbook
→ 05 test plan
→ 13 acceptance
```

### 只想改代码

```text
03 architecture
→ 04 tool contracts
→ 07 backlog
→ 09 release checklist
```

## 5. Ph9 全局门禁

Ph9 不以“代码写完”为完成，而以以下全部成立为完成：

```text
[ ] 原版生存可定位要塞
[ ] 可激活 12 眼传送门
[ ] 可进入末地且不因虚空/坠落死亡
[ ] 可清除至少 1 组水晶（含铁栏杆保护）
[ ] 可在 perch 窗口对龙造成有效伤害
[ ] 击杀末影龙（或明确记录未完成原因）
[ ] 全过程有 evidence JSON + 截图
[ ] 相关工具在 MCP-TOOLS.md 有 schema/return
[ ] release 可复现，测试套件全绿
```

## 6. 当前基线与已知缺口

### 已通过（Ph8 证据）

- 近战 `kill_target_close`
- 远程 `kill_target_far`（真实弓 release、箭数下降）
- `escape`
- `self_protect`（远程可被打断、苦力怕盾挡）
- 路径可达索敌（墙后目标不索敌）
- Pendulum 自带 MCP 路由与关闭生命周期
- 生存模式基岩平台苦力怕 0 伤害 + 盾牌耐久下降

### 未完成

- 要塞定位/传送门激活/进入末地的完整生存链
- 末地虚空与平台安全移动
- 水晶查询/清除
- 末影龙多阶段战斗
- 复杂结构内长距离 `dig_tunnel` / `walk_to` 的稳定性
- 部分存档 `minecraft:the_end` 维度不可用（“Unknown dimension”）

## 7. 重要约定

1. 不把 Pendulum / Baritone / Create / Apotheosis / Serene Seasons 变成硬依赖。
2. 不把 `/summon`、`/kill`、`/tp` 当作“生存能力”通过验收；只能作为测试 setup，必须在 evidence 标注 `setupByCommand: true`。
3. 任何“战斗通过”必须返回 `outcome / health / armor / attacks / hits`，远程还要 `arrowsBefore/After`。
4. 任何 Job 必须有 deadline 与 no-progress 退出。
5. 先更新文档与契约，再改实现。
