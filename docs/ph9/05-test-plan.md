# 05 — Ph9 测试计划

> 原则：**真机、生存、可复现、有证据**。
>
> 每个 case 必须写：前置、命令 setup、执行、期望、证据字段。
> 允许命令 setup 的 case 必须标 `setupByCommand: true`，不能算“纯生存通过”。

## 1. 测试环境矩阵

| 环境 | 内容 | 用途 |
|---|---|---|
| CLEAN | NeoForge 1.21.1 + Baritone 1.11.3 + Pendulum/Jupiter/Rhino + ZFF Bridge | 验证无内容模组干扰 |
| FULL | 当前 253 mod 整合包 | 验证真实整合包兼容 |
| VANILLA | 仅 NeoForge + ZFF Bridge（无 Baritone/Pendulum） | 验证可选依赖降级 |

## 2. 用例

### P9-T01 要塞定位

| 项 | 内容 |
|---|---|
| 前置 | 生存 overworld，未定位 |
| 执行 | `locate_stronghold`；或命令 `locate structure minecraft:stronghold` 仅作对照 |
| 期望 | 返回坐标/距离；`source` 标注 |
| 证据 | `structure,x,y,z,distance,source,setupByCommand` |
| 失败 | `structure_not_found` |

### P9-T02 要塞导航

| 项 | 内容 |
|---|---|
| 前置 | 已知要塞坐标，生存 |
| 执行 | `path_to_stronghold`，每次最多 5 分钟 |
| 期望 | 到达传送门房间；无岩浆/窒息死亡 |
| 证据 | `outcome,finalPos,distanceLeft,damageTaken` |
| 失败 | `no_path/stalled/lava_risk/player_died` |

### P9-T03 传送门状态

| 项 | 内容 |
|---|---|
| 前置 | 找到传送门房间 |
| 执行 | `end_portal_status` |
| 期望 | `frames=12`，正确统计 eyes/activated |
| 证据 | 截图 + frames/eyes |

### P9-T04 激活传送门

| 项 | 内容 |
|---|---|
| 前置 | 0–11 眼，生存，有末影之眼 |
| 执行 | `activate_end_portal` |
| 期望 | placed + existing = 12；activated=true |
| 证据 | 前后 eyes 数量、截图 |
| 失败 | `missing_eyes/no_portal/blocked` |

### P9-T05 进入末地

| 项 | 内容 |
|---|---|
| 前置 | 已激活传送门，装备齐全 |
| 执行 | `enter_end_portal` |
| 期望 | dimension=the_end；落在黑曜石平台；safe=true |
| 证据 | `dimension,x,y,z,safe,voidRisk` |
| 失败 | `dimension_unavailable/void_risk/player_died` |

### P9-T06 虚空保护

| 项 | 内容 |
|---|---|
| 前置 | 末地平台边缘 |
| 执行 | 向边缘移动 / 被龙击退 |
| 期望 | `void_guard` 阻止继续前进或退回安全点 |
| 证据 | 10 次边缘测试，0 次坠落 |
| 失败 | `void_risk` 仍坠落 |

### P9-T17 self-protect 自动落地水

| 项 | 内容 |
|---|---|
| 前置 | self-protect 开启，背包有 1 个水桶，站上 y=95 平台 |
| 执行 | 走出平台边缘自由落体 |
| 期望 | 落地前自动放水；落地后把水收回水桶；health 不下降；水面消失；水桶恢复为满水 |
| 证据 | `healthBefore/After`、`waterBlockAfter`、`bucketAfter`、`infoTxt`、截图 |
| 实测 | y=500 → y=101，health 20，水已收回，水桶为满水，`infoTxt` 同时含 placed/recovered |

### P9-T18 Tower 看门狗阈值

| 项 | 内容 |
|---|---|
| 前置 | `zff_bridge.config` 中 `towerDeltaY=1.5` |
| 执行 | `tower up` 时观察 watchdog 的 `minDistance` |
| 期望 | tower 模式阈值 1.5 格；低于 1.5 才计 stuck |
| 证据 | `zff_status` / watchdog JSON |

### P9-T19 寻路失败返回脚部平面

| 项 | 内容 |
|---|---|
| 前置 | 目标被墙挡住 |
| 执行 | `walk_to` 到墙后 → 等 failed |
| 期望 | `job_status` 带 `layerFoot`，含 15×15 平面/y/center |
| 实测 | `walk_to` failed no_progress，`layerFoot.y=101`，`rows=15 cols=15` |

### P9-T07 水晶查询

| 项 | 内容 |
|---|---|
| 前置 | 末地，水晶存在 |
| 执行 | `query_crystals` |
| 期望 | count 正确；protected 标记正确 |
| 证据 | `crystals[]` + 截图 |

### P9-T08 水晶摧毁

| 项 | 内容 |
|---|---|
| 前置 | 至少 1 暴露 + 1 铁栏杆水晶 |
| 执行 | `destroy_crystal` |
| 期望 | 水晶消失；玩家不掉虚空/不被炸死 |
| 证据 | `destroyed,remaining,arrowsUsed,damageTaken` |
| 失败 | `out_of_range/no_line_of_sight/player_died` |

### P9-T09 龙查询

| 项 | 内容 |
|---|---|
| 前置 | 末地有龙 |
| 执行 | `query_dragon` |
| 期望 | health/phase/head/perch 正确；不返回龙翼作为本体 |
| 证据 | JSON + 截图 |

### P9-T10 龙战斗

| 项 | 内容 |
|---|---|
| 前置 | 水晶清完，192 箭，弓/盾/牛肉 |
| 执行 | `kill_ender_dragon` |
| 期望 | 至少一次 perch 伤害；结果结构化 |
| 证据 | `outcome,health,armor,arrowsBefore/After,attacks,hits,dragonHealth,phase` |
| 失败 | `player_died/arrows_empty/no_damage_timeout/crystals_remaining` |

### P9-T11 战斗资源校验

| 子项 | 期望 |
|---|---|
| 无箭 | `ammo_empty` + InfoTxt |
| 弓损坏 | `weapon_unusable` |
| 无盾 | 苦力怕 -> escape，普通怪 -> 近战 |
| 无食物低血 | 退出/撤退 |

### P9-T12 路径索敌回归

| 项 | 内容 |
|---|---|
| 场景 | 墙后 6 格僵尸 |
| 期望 | `entity_not_found` |
| 场景 | 同侧僵尸 |
| 期望 | `target_killed` |

### P9-T13 盾牌真实生效

| 项 | 内容 |
|---|---|
| 场景 | 生存基岩平台，点燃苦力怕 2 格 |
| 无盾 | 爆炸伤害 > 0 |
| 有盾 | 爆炸伤害 0；盾牌 damage 增加 |
| 证据 | `healthBefore/After,shieldDamage,using` |

### P9-T14 远程真实放箭

| 项 | 内容 |
|---|---|
| 场景 | 60 格目标，弓 64 箭 |
| 期望 | arrows 减少；目标掉血/死亡；结束 `using=false` |
| 证据 | arrowsBefore/After, hits, health |

### P9-T15 Pendulum MCP 生命周期

| 项 | 内容 |
|---|---|
| 前置 | `mcp_enabled=true` |
| 执行 | `pendulum_mcp_status`；`pendulum_eval mc.log(...)` |
| 期望 | available=true；script/eval 返回 |
| 关闭游戏 | 25566 关闭；status available=false |
| 证据 | port/health/afterClose |

### P9-T16 重启恢复

| 项 | 内容 |
|---|---|
| 前置 | 正在执行 Job |
| 执行 | 关闭游戏 / 重启 MCP / 重进世界 |
| 期望 | 不残留进程；旧 Job 不悬挂；新实例注册成功 |
| 证据 | process/ports/job states |

## 3. 证据格式

每个 case 使用 `16-evidence-template.md`，至少包含：

```json
{
  "id": "P9-Txx",
  "result": "pass|fail|blocked",
  "environment": "CLEAN|FULL|VANILLA",
  "survivalMode": true,
  "difficulty": "normal",
  "setupByCommand": false,
  "observed": {},
  "screenshot": "path or null",
  "notes": ""
}
```

## 4. 测试顺序

```text
T12/T13/T14 回归（避免新代码破坏旧战斗）
→ T01/T02 要塞
→ T03/T04 传送门
→ T05/T06 进末地/虚空
→ T07/T08 水晶
→ T09/T10 龙
→ T11 资源失败
→ T15/T16 生命周期/恢复
```

## 5. 失败处理

- 同一 case 连续 2 次失败 -> 记录 `blocked`，不得继续无限重试。
- 失败必须保留：日志、Job JSON、截图、坐标、装备、难度。
- 由“命令 setup”导致的失败，单独标记，不混入纯生存结论。
