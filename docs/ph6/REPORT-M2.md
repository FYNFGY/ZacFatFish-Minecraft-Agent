# Ph6 M2 报告 —— 角色重构：CR 主代理 + 子代理 + 双 store

> 这一层里，**能落在 MCP/仓库里的都落了**；落在 DSH 会话里的部分（子代理、抢占、每轮汇报）以
> **契约 + 提示词 + 工具面**的形式交付，并标出宿主能力缺口。

---

## 1. 删掉的旧运行时（已完成）

| 删除 | 说明 |
|---|---|
| `mcp-server/src/agent/{sr,cr,ar,sensors,run,todolist,memory,server-backend,core}.mjs` | Ph5 的 SR/CR/AR/ESRS/ESRA Node 调度器全部下线 |
| `test/ph5-*.mjs`、`test/cr-chat.mjs`、`scripts/chat-listen.mjs` | 对应的旧测试与监听脚本 |
| `scripts/*.mjs` 里的 `agent/core.mjs` 引用 | **22 个脚本**改成 `mcp-server/src/lib/mcp-client.mjs`（只保留 daemon 客户端 `call/tool`，不再有任何 agent 概念） |
| `start-all.ps1` 结尾的 `agent/run.mjs chat` 提示 | 改成"用 ZFF MC 插件开 ZFF MC Mode 会话" |

`node --check` 全部通过；`scripts/doctor.mjs` / `say.mjs` / `tool.mjs` 等仍可用（它们的 `call` 语义没变）。

## 2. 双 store（已完成）

| store | 实现 | 验证 |
|---|---|---|
| CR store | `mcp-server/src/lib/store.mjs`（原子写 + 串行化 read-modify-write）；`chat_turn` 自动追加 `chat.sent`，`zff_preempt` 追加 `preempted`，两者都只留最近 200/100 条 | 真机 `chat_turn` 后 `.mcp-agent/cr-store.json → entries["chat.sent"]` 有内容 |
| mod store | `ModStore.java` 每 5 s 写 `<gameDir>/zff/mod-store.json`（2000 条聊天环形 + 环境 + 背包摘要） | 真机 `store_get {store:"mod"}` 读到文件，`ageMs` < 5 s |
| CR 临时记忆 | `runtime/cr-scratch.json`（M5 前唯一允许的临时记忆） | 已生成骨架 |
| 两者关系 | **互不同步**：同名 key 各写各的；跨读必须显式 `store_get {store:"mod"}` | 代码注释 + `prompts/tools.md` 写明 |

## 3. 角色与工具面（契约）

`docs/ph6/ARCHITECTURE-PH6.md` 的表格是最终契约，落到实处的部分：

| 角色 | 谁 | 落地方式 |
|---|---|---|
| **CR** | DSH 主 Agent（ZFF MC Mode 会话） | 独占 `chat_turn`；`env_read`/`store_*`/只读/动作全都可用；动作类调用前先 `zff_preempt` 记一笔 |
| **SR** | `subagent` 子代理 | 只读 + 计划输出；**截图解读只由 SR 做**（`prompts/tools.md` §3「截图」一行 + §8 最后一行） |
| **AR** | `subagent` 子代理 | 动作工具；被 CR 抢占（`job_cancel` → `interrupt_agent` → `zff_preempt`） |
| **ESRC** | `subagent`（按需） | 只读 `env_read` + `scan_surface`，回答"现在什么情况/卡在哪" |

### 3.1 抢占（已实现的部分）

- `job_cancel`（已有）+ `zff_preempt {jobId,taskId,tool,reason}`（新增）→ 把 `{by:"cr",jobId,taskId,tool,at}`
  写进 CR store 的 `preempted`，`store_get {store:"cr",key:"preempted"}` 可复盘。
- `interrupt_agent(ar)` 是 DSH 的工具，不在 MCP 侧；`prompts/tools.md` 与 ARCHITECTURE 写明顺序。
- **没有做** `ar_run(task, timeoutMs)` 这个"阻塞轮询"工具：它的语义是"主 Agent 等子代理"，那是 DSH 的事
  （`subagent` + 轮询 `job_status` / 子代理回报），在 MCP server 里再造一个会与宿主重复。已在契约里改成
  "派单 → 轮询/等回报 → 决定继续/抢占"。

### 3.2 宿主能力缺口（要玩家知道）

- **per-child 工具收窄需要 `dsh-background-agents`**（插件 README 里列为推荐安装）。没装时，SR/AR/ESRC 的
  工具面只能靠**提示词**约束（子代理在正文里被明确告知"你只能只读"），不是硬隔离。
- `reasoning=low` 是**会话/模型配置**，不是插件能改的：本机 profile 的 `agent-default-model.reasoningEffort`
  目前是 `max`。要让 CR 以 low 跑，在 ZFF MC Mode 的会话里把推理等级调低（或给该 preset 单独配模型），
  插件不改玩家的全局默认。
- 续跑没有 turn-end 钩子（M0.5 结论），只能轮询 `agent.status` 的 `running→idle` 跳变，误差 ≤2 s。

## 4. 截图政策（已完成）

- `screenshot_gui` 落地且真机验证：854×480 PNG，**包含聊天栏、快捷栏、准星、手持物**
  （`evidence/shots/ph6-screenshot-gui.png`）。这意味着"玩家用书面/告示牌给任务"的读图链路成立。
- 政策写进 `prompts/tools.md`：截图谁都能调，**画面解读只交给 SR**，CR 拿文字结论。

## 5. 验收对照

| 要求 | 状态 |
|---|---|
| 删旧运行时、`mcp-client.mjs` 迁移、所有引用更新 | ✅ 19 个文件删除、22 个脚本改写、`node --check` 全绿 |
| CR 主 Agent（low） | ⚠️ 会话侧配置（见 §3.2），插件 preset 已提供 ZFF MC Mode |
| SR/AR/ESRC 子代理 | ⚠️ 契约 + 提示词完成，硬隔离依赖 `dsh-background-agents` |
| `ar_run` 阻塞轮询 | ❌ 改为 DSH 原生"派单 + 等回报"，理由见 §3.1 |
| CR 抢占 AR（含 `preempted` 事件） | ✅ `zff_preempt` + `job_cancel`；`interrupt_agent` 由宿主执行 |
| 双 store 骨架 + `runtime/cr-scratch.json` | ✅ |
| 截图分析交给 SR | ✅ 工具 + 政策 + 真机 GUI 截图 |
