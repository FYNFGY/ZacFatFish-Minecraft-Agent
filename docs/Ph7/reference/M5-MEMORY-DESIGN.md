# Ph6 M5 —— 记忆 / store 正式设计（**提案，未实现**）

> 状态：**只讨论，不动代码**。按 `agent-prompt6.md` 的硬性规定，M5 的 schema、迁移、清理策略
> 必须先和玩家商定，商定前不得实现。本文件列出**需要玩家拍板的选项**与我的建议，供下一次对话直接过一遍。

---

## 1. 现在有什么（M1–M4 结束时的真实状态）

| 数据 | 位置 | 谁写 | 谁读 | 现状 |
|---|---|---|---|---|
| CR store | `<workspace>/.mcp-agent/cr-store.json` | CR（`store_set`）、`chat_turn` 写 `chat.sent`、`zff_preempt` 写 `preempted` | CR / SR | 已实现（M2 骨架） |
| mod store | `<gameDir>/zff/mod-store.json` | mod（客户端 tick，5 s 一次） | CR（`store_get {store:"mod"}` 只读） | 已实现（M1） |
| CR 临时记忆 | `<workspace>/runtime/cr-scratch.json` | CR 手工写 | CR | 已实现（M2 占位，M5 要迁移） |
| 聊天未读 / cursor / `Flag_User_Chat` | MCP server 内存 | Node | Node | 已实现（M1），**不持久化**（玩家明确要求） |
| 服务端 SavedData | `<world>/data/zff_agent.dat` | 服务端 mod | 通过 `zff_*` 工具 | Ph5 遗产，可选（`backend=server` 时才有） |
| 旧 `.mcp-agent/*.md` 记忆 | `<workspace>/.mcp-agent/` | Ph1–Ph5 的 agent | `memory_read` | 还在，两套并存 |

---

## 2. 必须先拍板的 6 个问题

### Q1. CR store 的 schema：扁平 KV 还是分层？
现在 `store_set {key, value, tags}` 是**扁平 KV**（`key` 是任意字符串），适合 `store_list {prefix}` 按前缀整理。
- **建议 A（推荐）**：保持扁平 KV，用**命名约定**分层：`mem.city.home`、`todo.mine.1`、`plan.farm.2026-11-01`。
  理由：`store_get/store_list` 已经是这么设计的，前缀即"目录"；不需要额外迁移代码。
- **B**：改成真正的树（`{type:"dir", children:{}}`）。更整齐，但要重写工具、写迁移器，收益主要是"好看"。

### Q2. `cr-scratch.json` 怎么并进 CR store？
- **建议**：一次性迁移脚本（`scripts/m5-migrate-scratch.mjs`）把 `facts` 写成 `mem.*`、`notes` 追加成
  `log.scratch.<ts>`，迁完把原文件改名 `cr-scratch.migrated.json` 留档。迁移前后各做一次 `store_list` 对比。

### Q3. 旧 `zff_agent.dat`（服务端 SavedData）要不要导入？
- 现状：本地测试服上有 3 条 memory、4 条 todo（Ph5 留下的）。
- **建议**：**不自动导入**。做一个只读导出工具 `zff_memory_export`，让玩家看过再决定要不要 `store_set` 进 CR store。
  理由：那是"上一个角色"的记忆，混进新角色的记忆里会污染人设。

### Q4. 清理策略：谁删、删什么、什么时候删？
- **建议**：CR 自己按 `tags` 清理 + 一个硬上限：
  - `chat.sent` 只留最近 200 条（已经这么做了）；
  - `preempted` 只留最近 100 条（已经这么做了）；
  - `log.*` 前缀超过 30 天自动删（需要 M5 实现一个 `store_prune`）；
  - `mem.*` **永不自动删**（那是"人设的记忆"，删了就是失忆）。
- 需要玩家确认：**是否允许 CR 自己删自己的记忆**？建议：只允许删 `log.*`，`mem.*` 只能人删。

### Q5. 记忆的"权重 / 衰减"？
- **建议（推荐）**：不做衰减。低思考等级的 CR 算不动权重；改成"**写的时候带 tags，读的时候按 tag 取**"。
- 备选：`mem.*` 每条带 `touchedAt`，`store_list` 默认按 `touchedAt` 倒序，让 CR 自己看到"最近用过的"。

### Q6. 记忆文件的权限（谁能看 / 谁能写）
| 角色 | CR store | mod store | `cr-scratch` |
|---|---|---|---|
| CR | 读写 | 只读 | 读写 |
| SR 子代理 | 只读 | 只读 | 只读 |
| AR 子代理 | 只读 | 只读 | 只读 |
| ESRC 子代理 | 只读 | 只读 | 只读 |

- **建议**：按上表硬性收窄（MCP server 侧可以做，但 DSH 的 per-child 工具面是更自然的位置）。
  需要玩家确认"子代理要不要能写记忆"——写会带来并发覆盖。

---

## 3. 迁移计划（商定后照此执行）

1. `M5.1` 工具补齐：`store_prune {store:"cr", prefix, olderThanDays}`、`zff_memory_export`。
2. `M5.2` 迁移脚本 + 迁移前后快照（写成 `evidence/ph6-m5-migration.json`）。
3. `M5.3` CR 的提示词（`prompts/tools.md`）里补一节"什么时候写记忆、写在哪"。
4. `M5.4` 删掉 `runtime/cr-scratch.json` 的读写代码，文档改为"已迁移"。

---

## 4. 明确不做的事（除非玩家改主意）

- 不把聊天记录当长期记忆（那是 mod store 的只读环形缓冲，重启客户端就没了）。
- 不在 `mod-store.json` 里存任何 agent 写的内容（**两个 store 互不同步**，这是架构决定）。
- 不让记忆跨玩家服务器自动同步（`zff_agent.dat` 跟着世界存档走，我们不复制它）。
