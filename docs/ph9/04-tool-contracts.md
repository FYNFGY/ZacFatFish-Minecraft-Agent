# 04 — Ph9 工具契约

> 现有 122 个内置工具 + 7 个插件工具的完整描述、参数、返回见根目录 [MCP-TOOLS.md](../MCP-TOOLS.md)。
>
> 本文件只定义 Ph9 **新增/修改** 的契约。实现必须与此一致，并同步更新 `MCP-TOOLS.md`。

## 1. 通用约定

### 1.1 成功

```json
{
  "ok": true,
  "...": "工具特定字段",
  "infoTxt": []
}
```

### 1.2 失败

```json
{
  "error": {
    "code": "string",
    "message": "human readable"
  },
  "infoTxt": []
}
```

### 1.3 Job 类工具

创建：

```json
{"jobId":"j-9-xxxxx","state":"queued","tool":"kill_ender_dragon","infoTxt":[]}
```

查询：

```json
{
  "jobId": "j-9-xxxxx",
  "tool": "kill_ender_dragon",
  "state": "done|failed|cancelled|running|queued",
  "progress": 100,
  "metrics": {},
  "result": {},
  "reason": "optional",
  "error": "optional",
  "message": "optional",
  "infoTxt": []
}
```

### 1.4 证据标记

所有 Ph9 测试结果必须带：

```json
{
  "setupByCommand": true,
  "survivalMode": true,
  "difficulty": "easy|normal|...",
  "evidencePath": "evidence/ph9-....json"
}
```

## 2. 新工具

### 2.1 `locate_stronghold`

| 项 | 内容 |
|---|---|
| 功能 | 定位最近要塞 |
| 参数 | `dimension?: "overworld"`<br>`timeoutSeconds?: number` |
| 返回 | `{ok:true, structure:"minecraft:stronghold", x,y,z, distance, source:"scan"\|"command"}` |
| 失败 | `structure_not_found`, `dimension_unavailable`, `timeout` |
| 备注 | 生存模式优先真实搜索；测试可用 `locate` 但标注 |

### 2.2 `path_to_stronghold`

| 项 | 内容 |
|---|---|
| 功能 | 从当前位置到要塞/传送门房间 |
| 参数 | `x:number, y:number, z:number`<br>`safeOnly?: boolean`<br>`timeoutSeconds?: number` |
| 返回 Job | `{jobId,...}`；最终 `result:{outcome:"reached"\|...,finalPos,distanceLeft}` |
| 失败 | `no_path`, `stalled`, `lava_risk`, `player_died` |

### 2.3 `end_portal_status`

| 项 | 内容 |
|---|---|
| 功能 | 检查传送门框架与眼睛 |
| 参数 | `x?:number,y?:number,z?:number,radius?:number` |
| 返回 | `{ok,frames:12,eyes:12,activated:boolean,missing:[],portalBlock:boolean}` |
| 失败 | `no_portal`, `too_far` |

### 2.4 `activate_end_portal`

| 项 | 内容 |
|---|---|
| 功能 | 用末影之眼补全并激活传送门 |
| 参数 | `x?:number,y?:number,z?:number`<br>`maxTravel?:number` |
| 返回 | `{ok,placed:number,eyes:12,activated:true}` |
| 失败 | `missing_eyes`, `no_portal`, `blocked`, `player_died` |

### 2.5 `enter_end_portal`

| 项 | 内容 |
|---|---|
| 功能 | 进入末地并确认安全落点 |
| 参数 | `returnWhenSafe?:boolean` |
| 返回 | `{ok,dimension:"minecraft:the_end",x,y,z,safe:boolean,voidRisk:number}` |
| 失败 | `portal_not_found`, `dimension_unavailable`, `void_risk`, `player_died` |
| 备注 | 进入后立即 `VoidGuard`；黑曜石平台外不得自动走 |

### 2.6 `void_guard`

| 项 | 内容 |
|---|---|
| 功能 | 判断当前位置/目标点是否接近虚空，必要时停步/退回 |
| 参数 | `x?:number,z?:number,radius?:number` |
| 返回 | `{ok,safe:boolean,distanceToVoid:number,nearestSafe:[x,y,z]}` |
| 失败 | `not_in_end`, `no_safe_ground` |

### 2.7 `safe_move_to`

| 项 | 内容 |
|---|---|
| 功能 | 带虚空/坠落/岩浆检查的 3D 移动 |
| 参数 | `x:number,y:number,z:number`<br>`allowBreak?:boolean,allowPlace?:boolean`<br>`maxFall?:number,timeoutSeconds?:number` |
| 返回 Job | `{jobId,...}`；结果 `outcome:"reached"\|"aborted"` + `safe` |
| 失败 | `void_risk`, `no_path`, `stalled`, `player_died` |

### 2.8 `query_crystals`

| 项 | 内容 |
|---|---|
| 功能 | 查询末地水晶 |
| 参数 | `radius?:number` |
| 返回 | `{ok,count,crystals:[{id,x,y,z,protected,alive,healing}]}` |
| 失败 | `not_in_end`, `no_crystals` |

### 2.9 `destroy_crystal`

| 项 | 内容 |
|---|---|
| 功能 | 从安全距离摧毁一个水晶 |
| 参数 | `id?:number`<br>`x?:number,y?:number,z?:number`<br>`weapon?:string` |
| 返回 | `{ok,destroyed:boolean,remaining:number,arrowsUsed:number,damageTaken:number}` |
| 失败 | `out_of_range`, `no_line_of_sight`, `weapon_unusable`, `ammo_empty`, `player_died` |
| 备注 | 优先在爆炸半径外射击；无箭时可用床/近战但必须风险评估 |

### 2.10 `query_dragon`

| 项 | 内容 |
|---|---|
| 功能 | 查询龙本体、阶段、头部/身体、perch 状态、水晶 |
| 参数 | `timeoutSeconds?:number` |
| 返回 | `{ok,dragonId,health,maxHealth,phase,head:[x,y,z],body:[x,y,z],perch:[x,y,z]\|null,crystals:number}` |
| 失败 | `dragon_not_found`, `not_in_end` |

### 2.11 `kill_ender_dragon`

| 项 | 内容 |
|---|---|
| 功能 | 末影龙专用多阶段战斗 |
| 参数 | `weapon?:string`<br>`destroyCrystalsFirst?:boolean`<br>`timeoutSeconds?:number` |
| 返回 Job | `result:{outcome,health,armor,arrowsBefore,arrowsAfter,attacks,hits,dragonHealth,phase,crystals}` |
| 失败 | `player_died`, `arrows_empty`, `no_damage_timeout`, `crystals_remaining`, `no_perch_window` |
| 退出条件 | 玩家死亡 / 龙死亡 / 箭耗尽 / 30s 无有效伤害 / 时间上限 |

## 3. 修改现有工具

### 3.1 `kill_target_far`

新增：

```text
aimOffset?: number
shieldBetweenShots?: boolean (default true)
```

返回新增：

```json
{"shieldUses": 12, "arrowsBefore": 192, "arrowsAfter": 160}
```

### 3.2 `combat_attack`

- 对 `EnderDragon` 目标时，禁止把 `EnderDragonPart` 当作独立目标。
- 返回 `targetPart` 字段。

### 3.3 `entity_query`

新增：

```text
filterType?: "dragon"|"crystal"|...
excludeParts?: boolean (default true)
```

返回：

```json
{"parts":[...],"dragonId":123}
```

## 4. 错误码总表

| code | 含义 |
|---|---|
| `not_in_end` | 当前维度不是末地 |
| `dimension_unavailable` | 世界无该维度 |
| `portal_not_found` | 找不到传送门 |
| `missing_eyes` | 缺少末影之眼 |
| `no_path` | 无地面路径 |
| `stalled` | 有路径但执行无进展 |
| `void_risk` | 虚空风险 |
| `lava_risk` | 岩浆风险 |
| `out_of_range` | 超出交互/射击范围 |
| `no_line_of_sight` | 无视线 |
| `weapon_unusable` | 武器损坏/不可用 |
| `ammo_empty` | 箭耗尽 |
| `no_food` | 无可用食物 |
| `crystals_remaining` | 水晶未清 |
| `no_perch_window` | 未等到 perch |
| `no_damage_timeout` | 长时间无有效伤害 |
| `player_died` | 玩家死亡 |
| `dimension_unavailable` | 维度不可用 |
| `timeout` | 任务超时 |

## 5. 验收

- 新增工具必须先在 `MCP-TOOLS.md` 出现；
- 每个错误码至少一个测试；
- 每个 Job 结果必须有 `outcome/health/armor`；
- 远程必须有 `arrowsBefore/After`；
- 末地必须有 `dimension/safe/voidRisk`。
