# Agent prompt — Ph6 v3：ZFF MC（服务端可选 + CR 主代理 + 插件）

你接手的是一个已完成 Ph1–Ph5、并且刚刚完成 **Ph6 设计** 的 Minecraft MCP 项目。
先读本文件，然后按顺序读：

1. `HANDOVER.md` —— 上一任 Agent 做了什么、踩了什么坑、这台机器现在是什么状态。
2. `ARCHITECTURE-PH6.md` —— 拓扑、原语、时序、状态机、数据落点。
3. `ISSUE.md` —— 真实陪玩暴露的问题（Ph6 必须修）。
4. `reference/` —— 支撑文档（原文副本，中文）。

> **测试纪律（硬性）**：全部在**本地**服务器测试，**不得连接玩家的服务器** `zmcp3.rhymc.com:1069`；
> **不要安装 vanilla server jar**。
>
> **不得推翻 Ph1–Ph5 的结论**，只允许新增/修正。

---

## 0. 一句话目标

让一个**低思考等级（reasoning=low）的 CR 主代理**稳定陪玩：**秒回聊天、看得见环境、能派出会被抢占/叫停的子代理**；
并且**只装客户端**也能跑（服务端装了且版本匹配才启用服务端增强）；最终打包成 DSH 插件 **ZFF MC**。

---

## 1. 现状

### 1.1 保留

| 层 | 内容 |
|---|---|
| 客户端 mod | 全部游戏工具（84 个）、回环 HTTP bridge、job 调度、`ZffWatchdog`（客户端 tick） |
| 服务端 mod | payload 通道、`SavedData`、`/zff`、服务端采样、`broadcastStop` —— **保留，但改为可选 + 版本协商** |
| MCP server | `mcp-server/src/server.js`（工具定义）、daemon（57611） |
| 脚本 | `start-all.ps1`、`start-mcp.ps1`、`install.ps1`、`scripts/doctor.mjs` |

### 1.2 删除（架构重构，旧运行时不再保留）

```
mcp-server/src/agent/sr.mjs          # SR 调度器（改由 DSH 子代理承担）
mcp-server/src/agent/cr.mjs          # Node Chatter（CR 改为主 Agent）
mcp-server/src/agent/ar.mjs          # Node Affecter（AR 改为子代理）
mcp-server/src/agent/sensors.mjs     # ESRS/ESRA（改名 ESRC，采样下沉到 mod）
mcp-server/src/agent/run.mjs         # 旧 CLI
mcp-server/src/agent/todolist.mjs    # 并入 CR store（M5 前用 runtime/cr-scratch.json）
mcp-server/src/agent/memory.mjs      # 同上
test/ph5-*.mjs
scripts/cr-chat.mjs
scripts/chat-listen.mjs
```

`mcp-server/src/agent/core.mjs` 里的 **daemon 客户端**仍需要：迁移到 `mcp-server/src/lib/mcp-client.mjs`，
并更新所有引用（`scripts/say.mjs`、`scripts/doctor.mjs`、`scripts/harvest*.mjs`、`scripts/audit-rows.mjs`、
`scripts/consolidate.mjs`、`scripts/sort-to-target.mjs`、`scripts/collect-deposit.mjs`、
`scripts/make-inventory-doc.mjs`、`scripts/remember-city.mjs` 等）。

---

## 2. 架构决定（v3，已与玩家商定）

### 2.1 拓扑

```
CR（主 Agent，reasoning=low，跑在 DSH 会话里）
 ├─ 原语 ① chat_turn   —— 说完再听（send → drain）
 ├─ 原语 ② env_read    —— 读 mod 常驻采样的环境 inbox
 ├─ 原语 ③ store_*     —— 双 store 读写
 ├─ 子代理 Scheduler(SR) —— 复杂问题求解（按需 spawn，返回方案）
 ├─ 子代理 AR            —— 执行任务（可被 CR 抢占中断）
 └─ 子代理 ESRC          —— 按需读 env inbox 并解释环境（不是常驻进程）

mod（客户端，唯一能真正动角色的地方）
 ├─ ChatLog      → unread 队列 + ack 游标 + self 分组 + mustReply 标记
 ├─ EnvSampler   → 环境环形缓冲（位置/血量/氧气/时间/天气/当前 job/警报）
 └─ 动作层       → mine/path/container/…（84 工具）

MCP server：工具 + CR store（workspace）+ mod store 读取
```

### 2.2 角色职责与工具面

| 角色 | 谁 | 职责 | 工具面 |
|---|---|---|---|
| **CR** | 主 Agent（LLM） | 聊天、决策、编排、抢占、汇报 | `chat_turn`、`env_read`、`store_*`、只读、动作（抢占式） |
| **SR** | 子代理 | 只解"复杂问题"：给方案/步骤，不执行 | 只读 + 计划输出 |
| **AR** | 子代理 | 执行一个任务（多步），可被 CR 中断 | 动作工具（受租约约束） |
| **ESRC** | 子代理 | 读 env inbox，解释"现在什么情况/卡在哪" | 只读（`env_read`、`scan_surface` 等） |

### 2.3 抢占（CR 优先）

- CR 发起任何**动作类**工具时：`job_cancel` 掉当前 job → `interrupt_agent(ar)`（若 AR 在跑）→ 执行 CR 的新动作。
- 抢占必须记录一条 **`preempted` 事件**（`{by:"cr", jobId, taskId, tool, at}`）写入 CR store。
- 抢占对所有动作生效，不设 `force` 参数。

### 2.4 双 store

| store | 谁写 | 谁读 | 位置 | 内容 |
|---|---|---|---|---|
| **CR store** | CR（`store_set`）+ `chat_turn` 自动写 `chat.sent` | CR、SR | `<workspace>/.mcp-agent/cr-store.json` | 记忆、待办、发出的消息、`preempted`、计划 |
| **mod store** | mod（客户端） | CR（只读） | `<gameDir>/zff/mod-store.json` | 完整聊天记录、环境采样、背包快照 |

- 两者**互不同步**，同名 key 各写各的；CR 跨读需显式 `store_get {store:"mod"}`。
- **记忆策略（schema、迁移、清理）留到 M5，先与玩家商讨**；M1–M4 期间 CR 的临时记忆放 `runtime/cr-scratch.json`。

### 2.5 自动续跑（ZFF MC 插件内置）

CR 每轮输出停止时，插件注入一条"续跑消息"。刹车规则（全部可配）：

| 条件 | 行为 |
|---|---|
| 不在 ZFF MC Mode | 不续跑 |
| 玩家说停（"停/别动/休息/别干了"） | `paused=true`，不续跑，直到玩家再次发言 |
| 游戏掉线 | 不续跑，先重连 |
| 有 job 在跑且未超时 | 不续跑（等 `ar_run` 返回） |
| 连续空转 ≥ **N**（默认 **20**，可配） | **不停止**，改为"自主找任务" |
| 连续自主 ≥ **M**（默认 **5**，可配）仍无进展 | 降为低耗待机（每 60 s 只 `chat_turn {}` 一次） |
| **管理员（ID 可配）要求下线** | **退出游戏 + 终止输出**（不再续跑） |

续跑消息模板（固定，不允许模型改写）：

```
【自动续跑】按 AIPlayer.md §0 铁律继续：先 chat_turn 看消息 → 有活就干 → 每轮必须有一句汇报。禁止空转。
```

### 2.6 管理员下线

- 插件配置 `adminPlayers: string[]`（游戏名/UUID）。
- CR 用**任意语义**判断"管理员要求下线"（不是关键词表），确认后调用插件工具 `zff_shutdown {reason}`。
- 插件用只读的 `chat_history` 复核最近发言人是否在 `adminPlayers` 里，不在则拒绝；
  在则：`disconnect` → 10 s 内未退出则杀进程 → `terminated=true`（停止续跑与一切注入）。

### 2.7 测试纪律（本地三态，不装 vanilla jar）

| 场景 | 搭法 | 验证 |
|---|---|---|
| 同版模组服 | 现有 `D:\mc\MCP Development\server`（25565、offline、flat） | `backend=server` 全功能 |
| 无模组服 | 同一服**移除我们的 jar** | `backend=local`、`zff_*` < 2 s |
| 旧版模组服 | 放旧 jar | `backend=local(mismatch)` + 双方版本号 |
| 无服务端 | 纯单机/不启动服务器 | 客户端独立可用 |

客户端：单客户端、双客户端各一轮；离线名 `DSHDeepSeek`。

### 2.8 聊天中断（Flag_User_Chat）—— 工具层通信中断（规则全在 Node 侧）

**要解决的问题**：CR 有时会卡在阻塞工具里（真实案例：昨天在服务器上 `wait for the furnace`，玩家说话它没听见）。
只靠"续跑/轮询"不够，必须在**工具层**强制把控制权抢回来。

**归属（硬性）**：正则匹配、标志、unread 队列、cursor、ack **全部在 MCP server（Node）**；
mod 只做两件事：**把聊天实时推给 Node**、**执行发送**。

1. **推送路径**：mod 在 `ClientChatReceivedEvent` 里把每条消息
   `POST http://127.0.0.1:57611/chat_event`（loopback + token），daemon 转发到 MCP server 的 ingest 端点
   （57612 `/internal/chat_event`）。server 校验后**先做正则匹配、再入 unread 队列**——这就是"缓存之前"。
2. **规则来源**：插件写 `runtime/zff-chat-rules.json`，MCP server 热加载：
   `{"enabled":true,"matcher":"^鱼","exemptTools":[]}`。默认 `^鱼`（"以『鱼』开头的句子"）。
   - 只处理 `type=="chat"`；跳过 `self` 与系统消息；匹配前剥掉 `§x`。
   - 命中 → `Flag_User_Chat = true`，消息照常入 unread 队列。
3. **守卫位置**：MCP server 的**工具调度入口**——所有工具都从这里过。标志置位时，除 `chat_turn` 与
   `exemptTools` 外，**直接返回中性异常，不转发给 daemon/mod**。
   - 这就是"CR 在思考时"的拦截：它下一次调用任何非 `chat_turn` 工具都会被拒。
4. **阻塞工具**：`wait_events` 这类长阻塞由 server **切片调用**（每 2 s 一片），片间检查标志；
   命中即返回中性异常，**不取消底层 job**。
5. **清除**：`chat_turn` 且 `drain != false` 时清除；`drain:false` 的纯发送**不清除**。不考虑线程同步。
6. **不挂起**：被打断的任务不标记挂起/失败，由 AI 在回复完玩家消息后自行决定是否继续。
7. **中性异常**：`{ok:false, error:"user_chat_pending", message:"玩家刚说话，请先 chat_turn 读取并回复"}`；
   不是命令失败，不得计入任务失败统计。
8. **续跑联动**：标志置位时，插件的自动续跑**跳过退避与低耗待机**立刻唤醒（仍遵守 `paused` /
   `terminated` / 掉线三条刹车）。
9. **例外表**：`exemptTools`（默认空）里的工具不受中断影响；mod 侧安全兜底
   （`air_critical` → `stop_all`）永远不受影响。

**数据归属变化**：unread 队列 / cursor / ack / flag 都在 MCP server 内存；mod 仍保留只读的
`chat_history` 环形缓冲（调试用）。客户端重启不影响 unread（在 Node）；
**MCP server 重启会丢 unread**（与"未读不持久化"一致）。

### 2.9 截图分析交给 SR

- `screenshot` 由 CR 或 SR 均可调用（用于取证），但**画面内容的解读一律交给 SR**：CR 不直接看截图做判断，
  而是把截图路径/需求交给 SR，SR 返回文字结论（"锅里有成品 / 门口被箱子挡住"等）。
- SR 是唯一的"视觉分析"角色；这样 CR 的上下文不被图片占满，也符合"Low 思考等级"的预算。

### 2.10 提示词三件套（Part A / B / C）+ 模式骨架

AI 玩家的提示词 = 一段**插件内置骨架** + 三段**可编辑内容**；昨天实机验证"鲸鱼娘表现很好"的就是 A 和 C 这两段。

| 段 | 内容 | 默认值 | 谁可改 |
|---|---|---|---|
| **骨架** | 五条铁律（不掉线不许停、无指令权限…）、每轮循环、被中断怎么办、分工、输出风格 | `prompts/system.md` | 插件内置，玩家**不可**改 |
| **A 工具使用** | 怎么调工具、job 模型、容器/寻路/聊天规则、常见错误 | `prompts/tools.md` | 可编辑 / 可上传 |
| **B 游戏规则** | 玩家自定义硬约束（禁止使用指令、不许拆房、必须回家…） | `prompts/rules.md`（**最小模板：无指令权限**） | 可编辑 / 可上传；清空则不注入 |
| **C 角色人设** | 鲸鱼娘女仆的身份、外形、性格、说话方式、口头禅 | `prompts/persona.md` | 可编辑 / 可上传 |

**装配顺序与优先级**

```
骨架（system.md，含五条铁律）
  → A 工具使用
  → B 游戏规则（空则跳过）
  → C 角色人设
  → 运行时事实（自动注入：账号/服务器/模式/store 路径/中断规则）
```

- 冲突时按这个顺序裁决：**安全 > B 规则 > A 工具 > C 人设**；人设的"摸鱼"永远不能压过 B 的"必须干活"。
- 每段之间加固定小标题（`## 工具使用` / `## 游戏规则` / `## 角色`）；骨架自带 `## 五条铁律` 等标题。
- **续跑消息不属于 system prompt**：它由插件作为**用户消息**注入（模板固定，见 §2.5）。

**插件侧行为**

- 设置界面分三个卡片：预览默认值、在线编辑（textarea）、上传 `.md`、恢复默认（骨架不可编辑，只在"关于"里展示）。
- 上传"需要三个"；某个槽位没有上传就用该槽位的默认值（可单槽覆盖）。
- 存储：用户版 `~/.dsh/zff-mc/prompts/{tools,rules,persona}.md`；插件内置默认在包内 `defaults/`；
  仓库里的 `prompts/` 是开发期参考与默认值源。
- 限制：每份 ≤64 KB、UTF-8；保存后**下一个会话**生效（是否支持热更新由 M0.5 结论决定）。

**与现有文档的关系（重要）**

- `AIPlayerManual.md`（23 KB，84 工具全表）**不直接注入**——每轮 7k tokens 会拖垮低思考等级；
  它保留为"按需查阅"的参考文档。Part A 默认用浓缩版 `prompts/tools.md`（约 150 行）。
- `AIPlayer.md`（面向玩家的角色卡）保留；Part C 默认用更聚焦的 `prompts/persona.md`（同一套设定，去掉给玩家的说明）。
- 若玩家坚持用完整版：把 `prompts/tools.md` 换成 `AIPlayerManual.md` 即可（配置项，不写死）。

---

## 3. 里程碑（M0–M5）

> 每个里程碑：交付物 → 验收 → `evidence/ph6-<id>.json` → `docs/ph6/REPORT-*.md`（3–10 行）。

### M0 测试地基

- 起本地三态服务器；确认客户端能连本地服；测试脚本全部指向本地地址。
- 验收：四种组合各跑通一次 `get_state` + `scan_surface`；证据 `ph6-m0-*.json`。

### M0.5 插件 API 技术验证（半天，先于插件开发）

用临时插件确认四件事：
1. 能否监听 **turn-end**（CR 输出停止）；
2. 能否以"用户消息"形式**注入续跑文本**；
3. 能否读取**当前 preset / 模式**；
4. 能否注册**插件级工具**（如 `zff_shutdown`）与**设置页**。

任一做不到 → 记录结论并改用备选（goal 自动续轮 / 定时提醒），不得在假设上开工。

### M1 原语层（mod 侧）

- `chat_turn`：send → drain；`drain:false` 只发；无 message 只读；ack 游标；self 分组；mustReply 标记；
  未读**不跨客户端重启**。
- `env_read`：环境环形缓冲 + 警报；`interrupt:true` 的警报让 mod 直接 `job_cancel`/`stop_all`（硬安全，不经过 agent）。
- `!` 恢复短语（`!zff stop` 等）。
- 验收：纯原版服下聊天秒回、`zff_*` 与 `chat_turn` 全部 < 2 s；
  **中断验收**：CR 卡在阻塞工具（`wait_events` / `ar_run` / 轮询）时，第二个本地客户端发一条 `鱼…`，
  **≤ 2 s** 内该工具返回 `user_chat_pending`（Node 切片检查）；随后 `chat_turn` drain 后 `flagUserChat=false`；
  `drain:false` 的纯发送**不**清标志；非 `chat_turn` 的工具在标志置位期间全部被拒；证据 `ph6-m1-*.json`。

### M2 角色重构

- 删旧运行时；CR 主 Agent（low）；SR/AR/ESRC 子代理；`ar_run(task, timeoutMs)` 阻塞轮询；
- CR 抢占 AR（`job_cancel` + `interrupt_agent` + `preempted` 事件）；
- 双 store 骨架 + `runtime/cr-scratch.json` 临时记忆。
- 验收：AR 长任务中 CR 一条 `path_to` 能抢占并完成；玩家说"你怎么被卡住了"一轮内响应；
  **截图分析**：CR 把截图交给 SR，SR 返回文字结论（CR 自己不做画面解读）；证据 `ph6-m2-*.json`。

### M3 服务端可选

- `hello/hello_ack` 版本协商；`backend=server|local|local(mismatch)`；本地回退；`!zff stop`。
- 验收：三态各一次，`zff_*` 全部 < 2 s，客户端不被踢；证据 `ph6-m3-*.json`。

### M4 ZFF MC 插件

- bundle（`dsh.bundle.patch` 插 `@deepseek-ai/dsh-mcp-client`）+ `ZFF MC Mode` preset + 设置页
  + 启动器（玩家给 `versions/<ver>`，上推两级得 `.minecraft`）+ store 初始化 + 续跑刹车 + 管理员下线
  + mustReply 关键词 + **提示词三件套（A/B/C 编辑器 + 上传 + 默认值）**。
- 复用（提示玩家安装）：`dsh-background-agents`（流式/中断）、`dsh-agent-persona`（人设注入）、
  `dsh-mcp-panel`（MCP 配置界面）。
- 验收：新机器选目录 → 启动 → 进服 → 人设与初始记忆生效 → CR 能聊天；
  **提示词验收**：三段分别编辑/上传后，下一会话注入生效；B 被清空时不注入；上传缺失槽位回落到默认值；
  默认 B 含"无指令权限"模板；证据 `ph6-m4-*.json`。

### M5 记忆/store 正式设计（最后做）

- 与玩家商讨 schema、迁移（`runtime/cr-scratch.json` → CR store）、清理策略、是否把 `zff_agent.dat` 导入。
- **未商讨前不得动记忆实现。**

---

## 4. 原语规格

### 4.1 `chat_turn`

```jsonc
// 请求
{ "message": "…",     // 可选；字符串或字符串数组（一次发多条，用于逐条回复）
  "drain": true,      // 默认 true；false = 只发不听（"再说话时不 drain"，且不清 Flag_User_Chat）
  "ack": 1234 }       // 可选；确认到某个 cursor
// 响应
{ "sent": [ { "seq": 1, "ts": 0, "text": "…" } ],   // message 为数组时这里是多条
  "received": [ { "seq": 2, "ts": 0, "sender": "FYNyesterday", "text": "…", "mustReply": false } ],
  "self":     [ { "seq": 3, "ts": 0, "text": "…", "delivered": true } ],
  "mustReply": [ … ],
  "cursor": 1240,
  "staleMs": 0,
  "flagUserChat": false }        // drain 后应为 false（见 §2.8）
```

- **只有 CR 能调用**（其它子代理的工具面里没有）。
- **队列在 Node**：unread / cursor / ack 都由 MCP server 维护；mod 只负责把聊天事件推上来、以及执行发送。
- ack 语义：返回的 `cursor` 即"已读"；未确认的消息保留 60 s 后删除，避免响应丢失导致消息永久丢失。
- 发送成功后，内部写 CR store 的 `chat.sent`（CR 无需手动写）。
- `self` 与 `received` 分开返回，避免 CR 把自己当玩家回。
- **多条发送**：`message` 可以是数组（建议 ≤5 条/次，条间自动 200–300 ms 间隔）；
  CR 可以对聊天列表里多条需要回复的消息**逐条回复**（哪些需要回由大模型语义判断）。
- **drain 会清除 `Flag_User_Chat`**；`drain:false` 的纯发送不清除（见 §2.8）。

### 4.2 `env_read`

```jsonc
{ "pos": [x,y,z], "hp": 20, "food": 19, "air": 300,
  "time": { "day": 495, "phase": "day", "dayTime": 0 },
  "weather": { "rain": false, "thunder": false },
  "job": { "id": "j-…", "tool": "mine_block", "state": "running", "progress": 0.4 } | null,
  "nearby": { "players": 1, "hostiles": 0, "items": 3 },
  "alerts": [ { "kind": "air_critical", "interrupt": true, "at": 0 } ],
  "staleMs": 120 }
```

- 采样在 mod 内常驻（客户端 tick），**不是子代理**；ESRC 子代理按需读同一个 inbox。
- `interrupt:true` 的警报由 mod 直接 `job_cancel`/`stop_all`（与 agent 无关的硬兜底）。

### 4.3 store 工具

| 工具 | 说明 |
|---|---|
| `store_get {store, key}` | `store = cr \| mod` |
| `store_set {store:"cr", key, value, tags?}` | 只有 CR store 可写 |
| `store_list {store, prefix?}` | CR 自主整理用 |
| `store_delete {store:"cr", key}` | 同上 |

### 4.4 聊天中断相关（全部在 Node 侧）

| 项 | 说明 |
|---|---|
| 推送端点 | mod → `POST http://127.0.0.1:57611/chat_event` → daemon 转发 → MCP server `57612 /internal/chat_event` |
| 规则文件 | `<workspace>/runtime/zff-chat-rules.json`：`{"enabled":true,"matcher":"^鱼","exemptTools":[]}`，server 热加载（插件写入） |
| `Flag_User_Chat` | MCP server 内存布尔值；入队前匹配命中即置位；**drain 时清除** |
| 队列归属 | unread 队列 / cursor / ack 均在 MCP server 内存；mod 只提供只读 `chat_history` |
| 守卫 | server 工具调度入口统一检查；非 `chat_turn` 且不在 `exemptTools` → 返回中性异常（不转发） |
| 阻塞工具 | `wait_events` 由 server 切片调用（2 s/片），片间查标志；命中提前返回，不取消 job |
| 中性异常 | `{ok:false, error:"user_chat_pending", message:"玩家刚说话，请先 chat_turn 读取并回复"}` |

- 消息流：`mod 收到聊天 → POST 57611 → server 校验 + 正则 → 入队 / 置位`；`chat_turn` 只读 Node 队列，发送才回到 mod。
- 不要在 mod 侧做正则或置位（那是旧方案，已废弃）。


---

## 5. Ph6 要修的 ISSUE（来自 `ISSUE.md`）

| ID | 级别 | 摘要 | 归属 |
|---|---|---|---|
| ISSUE-1 / 10 | 严重 | Chatter 不回复 / 只监听不回复 | **由架构解决**（CR + `chat_turn` + 续跑） |
| ISSUE-2 | 严重 | 睡觉起不来（缺 `wake`/`STOP_SLEEPING`） | mod 工具 |
| ISSUE-3 | 中 | 不能吃东西（缺 `eat`/`holdTicks`） | mod 工具 |
| ISSUE-4 | 中 | `place_block` 只认快捷栏 | 工具改进 |
| ISSUE-5 | 中 | `path_open` 顺手开箱 | 工具改进 |
| ISSUE-6 | 中 | 1 格门永远 blocked | 寻路改进 |
| ISSUE-7 | 低 | `walk` yaw 语义未文档化 | 文档 |
| ISSUE-8 | 低 | `scan_surface` 不含非整格方块 | 扫描修复 |
| ISSUE-9 | 低 | MCP client 掉线不自愈 | 宿主/插件 |
| ISSUE-11 | 中 | 营火交互 `unsupported` | 工具新增 |
| ISSUE-12 | 中 | `entity_query` 大半径静默返回空 | 工具修复 |
| ISSUE-13 | 低 | 缺 `sneak:true` | 工具新增 |
| ISSUE-14 | 高 | 农夫乐事锅成品取不出（饮品需容器） | 工具新增 |
| ISSUE-15 | 高 | 读不了书/告示牌；截图不含 GUI | 工具新增 |

每条修复必须有"复现 → 根因 → 改动 → 游戏内验证 → evidence"。

---

## 6. 交付约定

1. 只在本地测试；**禁止连接玩家服务器**。
2. 每个任务：构建 → 安装 → 游戏内验证 → `evidence/<id>.json` → `docs/ph6/REPORT-*.md`。
3. 中文文档 UTF-8 无 BOM；PowerShell 脚本 UTF-8 **带 BOM**（Windows PowerShell 5.1）。
4. 不操作人类玩家的客户端实例（gameDir 含"人类用户"）。
5. CR 默认不在普通模式运行；只有 ZFF MC Mode 开启续跑。
6. 不得回退 `optional()` + `KNOWN_CLIENTS` 门控。
7. 未商讨前不得实现 M5 记忆策略。

---

## 7. 代码与文档地图

| 路径 | 作用 |
|---|---|
| `mod/src/main/java/dev/mcp/bridge/` | 公共侧（工具/容器/任务/扫描/服务端可选） |
| `mod/src/main/java/dev/mcp/bridge/client/` | 仅客户端（bridge、watchdog、新增 chat/env inbox） |
| `mcp-server/src/server.js` | MCP 工具定义（84 + 新原语） |
| `mcp-server/src/lib/mcp-client.mjs` | 由 `agent/core.mjs` 迁移的 daemon 客户端 |
| `start-all.ps1`、`scripts/doctor.mjs` | 冷启动与自检 |
| `prompts/system.md` | ZFF MC Mode 的提示词骨架（五条铁律/循环/分工），插件内置不可改 |
| `prompts/tools.md` / `rules.md` / `persona.md` | 提示词三件套的默认值（A/B/C）；插件包内 `defaults/` 随包分发 |
| `docs/ph6/agent-prompt6.md` | 本文件 |
| `docs/ph6/HANDOVER.md` | 上一任的工作总结 |
| `docs/ph6/ARCHITECTURE-PH6.md` | 拓扑、原语、状态机 |
| `docs/ph6/ISSUE.md` | 真实陪玩问题清单 |
| `docs/ph6/reference/` | 支撑文档副本（中文原文） |

---

## 8. 待定

- **M5 记忆 schema** —— 等玩家商讨。
- **M0.5 插件 API 结论** —— 若 turn-end 钩子不可用，续跑改走 goal 自动续轮 / 定时提醒。
- 是否把旧 `zff_agent.dat` 导入 CR store（M5）。
