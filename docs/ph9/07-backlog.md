# 07 — Ph9 Backlog

## 1. P0（必须做，阻塞 Ph9 验收）

| ID | 任务 | 现状 | 产出 |
|---|---|---|---|
| P9-001 | 要塞定位工具 | 未实现 | `locate_stronghold` |
| P9-002 | 要塞/传送门房间导航 | 未实现 | `path_to_stronghold` |
| P9-003 | 传送门状态/激活 | 未实现 | `end_portal_status` / `activate_end_portal` |
| P9-004 | 进入末地安全检查 | 未实现 | `enter_end_portal` |
| P9-005 | 虚空/坠落保护 | **Auto MLG 已通过真机：y500→101 落水、满血、自动收回水桶** | `void_guard` / `safe_move_to` |
| P9-006 | 水晶查询/摧毁 | 未实现 | `query_crystals` / `destroy_crystal` |
| P9-007 | 龙本体/阶段查询 | 未实现 | `query_dragon` |
| P9-008 | 龙专用战斗 | 未实现 | `kill_ender_dragon` |
| P9-009 | 结构化错误码 | 部分 | 04 文档中的错误码表 |
| P9-010 | Ph9 测试证据 | 未完成 | evidence JSON |

## 2. P1（应做，显著提升稳定性）

| ID | 任务 | 原因 |
|---|---|---|
| P9-101 | `dig_tunnel` 在砂岩/结构中 stalled 修复 | 沙漠神殿实测卡住 |
| P9-102 | `walk_to` 在 1×1 竖井/头顶方块卡住修复 | 实测 `headBlocked` |
| P9-103 | 长距离 `path_to` 分段 checkpoint + 重试 | 3km 级别导航 |
| P9-104 | `kill_target_far` 对飞行目标的提前量/弹道优化 | 烈焰人测试有 miss |
| P9-105 | 弓箭与盾牌交替策略参数化 | 远程生存率 |
| P9-106 | 死亡/重生后的任务恢复 | 末地/要塞 |
| P9-107 | 低血自动吃食物 | 生存 |
| P9-108 | 末地平台边界可视化/安全检查 | void |

## 3. P2（可做，增强）

| ID | 任务 |
|---|---|
| P9-201 | 床炸龙策略（高风险，需明确开关） |
| P9-202 | 末地水晶保护结构识别优化 |
| P9-203 | 龙 breath 粒子/声音识别 |
| P9-204 | 多语言错误信息 |
| P9-205 | evidence 自动截图/日志打包 |
| P9-206 | GUI/Create/Apotheosis 扩展回归 |
| P9-207 | 服务端/多人模式权限模型 |

## 4. 已知缺陷（来自 Ph8/实测）

### BUG-1 `dig_tunnel` stalled

沙漠神殿：

```text
dig_tunnel j-4-243554 failed stalled
at [-2690,88,287] target [-2691,88,286]
```

影响：无法稳定挖进结构内部。

### BUG-2 `walk_to` headBlocked

```text
walk_to no_progress
headBlocked: [-2690,90,287]
```

影响：1×1 竖井/隧道内无法前进。

### BUG-3 End 维度不可用

某些干净存档：

```text
Unknown dimension 'minecraft:the_end'
```

影响：无法进入末地；需要 `dimension_unavailable` 明确返回，或修复存档维度。

### BUG-4 龙多部位

`entity_query` 会返回多个 `EnderDragonPart`（同 type/不同 bbox），健康值可能缺失；通用索敌可能选到 part。

### BUG-5 生存远程被龙撞

末地测试：玩家 3 HP 后被龙击杀；缺少“低血撤退/吃牛肉/保持距离”。

## 5. 任务拆分模板

每个任务实现前必须补：

```text
- 用户故事
- 工具 schema
- 返回/错误码
- 测试 case
- evidence 路径
- 兼容/降级
```

## 6. 优先级原则

1. 先让“能活着进末地”，再谈“击杀龙”。
2. 先做可验证的基础工具，再做自动化编排。
3. 不为了通过 demo 使用 `/summon`/`/kill`/`/tp` 冒充生存能力。
4. 失败要可恢复，不要无限重试。
