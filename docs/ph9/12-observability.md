# 12 — 可观测性

## 1. 三条通道

| 通道 | 用途 | 位置 |
|---|---|---|
| MCP tool result | 单次调用的结构化结果 | HTTP 响应 |
| Job metrics/result | 长任务进度与终态 | `job_status` |
| InfoTxt | 每轮注入的紧急提示 | 工具响应 `infoTxt` |

> `COMBAT:` 行现在保留最近最多 8 条（去重前按发生顺序），因此 self-protect 的放水、收水、举盾、逃跑等连续操作不会互相覆盖。

## 2. InfoTxt 行格式

```text
HP -2.17 (mob) 0/20
FALL: falling 2.69 blocks at y=101.31
TOOL: minecraft:diamond_pickaxe 耐久耗尽 (0/1561)
COMBAT: self-protect: creeper nearby, shield raised
MONSTERS: zombie(2,0,0), skeleton(6,-4,-6)
JOB: kill_target_close j-1-1234 done
WATCHDOG: dig_shaft j-2-5678 no move 3.2s
```

约束：

- 紧急行可重复，但只保留最近状态
- 不把 token/路径/隐私写入 InfoTxt
- 每轮拉取后清零，避免重复注入

## 2.1 工具调用统计

MCP Server 在每次 `reg()` dispatch 时计数，落盘到：

```text
<workspace>/runtime/tool-usage.json
```

查询：

```text
MCP:    tool_usage {limit?}
插件:   zff_usage {limit?}
HTTP:   GET /api/zff-mc/usage
```

返回：`totalCalls`、`uniqueTools`、`counts[{name,count,lastUsedAt}]`、`startedAt`。

## 3. Job metrics 建议

### 战斗

```json
{
  "targetId": 123,
  "weapon": "minecraft:bow",
  "phase": "charging|released|shield|chase",
  "attacks": 4,
  "hits": 3,
  "distance": 24.1,
  "shieldUses": 5
}
```

### 移动/挖掘

```json
{
  "targetY": 88,
  "mode": "shaft|stair|tunnel",
  "blocksMined": 33,
  "steps": 5,
  "stuck": 3,
  "stuckMode": "dig",
  "at": [-2690,88,287]
}
```

### 龙（拟）

```json
{
  "dragonHealth": 200,
  "phase": "perching",
  "crystals": 4,
  "arrows": 120,
  "distance": 18,
  "voidRisk": 0.2
}
```

## 4. 日志

- 客户端：`<instance>/logs/latest.log`
- MCP：`D:\MC Agent Play\logs` / 进程输出
- 插件：DSH 控制台
- 截图：`%LOCALAPPDATA%\state\headlessmc-mcp\screenshots\`

## 5. 诊断顺序

```text
1. get_state 是否有 player
2. mc_instances 是否 live/inWorld
3. jobs_list 是否有 running
4. infoTxt 是否有 HP/TOOL/COMBAT/WATCHDOG
5. 客户端 latest.log 是否有 crash/mixin/exception
6. 端口是否在监听
```

## 6. 证据截图建议

- 战斗前：装备/目标/位置
- 战斗中：盾/弓/龙/水晶
- 战斗后：死亡/掉落/结果
- 失败：InfoTxt/日志/坐标

## 7. 隐私与安全

- 不记录 token
- 不记录玩家真实身份
- 不把聊天原文无条件写入永久证据（必要时脱敏）
- 公共服务器测试需用户明确同意
