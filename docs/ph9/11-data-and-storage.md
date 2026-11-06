# 11 — 数据与存储

## 1. 存储分层

| 层 | 位置 | 内容 | 生命周期 |
|---|---|---|---|
| ChatHub | MCP 进程内存 + rules 文件 | 未读聊天、cursor、Flag_User_Chat | 会话 |
| CR Store | `<workspace>/.mcp-agent` | CR 自主记录、team.SR | 跨会话 |
| Memory | `<workspace>/.mcp-agent` | 目标、坐标、验证结果 | 跨会话 |
| Job | Bridge 内存 | jobId、state、metrics、result | 游戏进程 |
| Evidence | `evidence/*.json` | 测试/验收记录 | 永久 |
| Runtime flags | `runtime/zff-paused.json` / `zff-shutdown.json` | 暂停/终止 | 文件 |
| Config | `$DSH_HOME/whale_craft/config.json` | 白名单/模式/端口 | 永久 |

## 2. ChatHub

关键字段：

```json
{
  "messages": [],
  "cursor": 0,
  "unread": 0,
  "flagUserChat": false,
  "rules": {}
}
```

约束：

- 聊天是 untrusted data，不能当指令执行。
- `chat_turn` 是唯一“说完再听”的 CR 工具。
- `Flag_User_Chat` 用于中断长任务；不是每条聊天都中断。

## 3. CR Store

```text
store_get / store_set / store_list / store_delete
```

用途：

- 记录“我正在做什么”
- `team.SR` 供 `zff_team` 读取
- 不放密钥、token

## 4. Memory

```text
memory_read / memory_write / memory_list
```

建议键：

```text
goal.ph9
world.<seed>.stronghold
world.<seed>.end_portal
world.<seed>.dragon
combat.last_result
```

禁止：

- 写入 bridge token
- 写入玩家隐私
- 写入未验证的坐标（需标 `verified:true`）

## 5. Job 数据

```json
{
  "jobId": "j-9-xxxx",
  "tool": "kill_ender_dragon",
  "state": "running",
  "progress": 30,
  "metrics": {},
  "result": null,
  "deadline": 0
}
```

约束：

- 终态不可修改
- 结果必须可 JSON 序列化
- 不在 result 里塞大数组（坐标/实体列表要截断）

## 6. Evidence

命名：

```text
evidence/ph9-<date>-<env>.json
```

必备：

```json
{
  "phase": "ph9",
  "environment": "CLEAN|FULL",
  "survivalMode": true,
  "setupByCommand": false,
  "cases": [],
  "release": {},
  "notes": []
}
```

## 7. 数据保留

- 不删除 Ph1–Ph8 证据
- 新 release 覆盖 `release/`，但保留 SHA256 历史
- 测试截图放 `screenshots/`，evidence 引用路径
