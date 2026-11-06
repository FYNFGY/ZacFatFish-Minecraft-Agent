# Ph6 架构：CR 主代理 + 子代理 + 双 store + 可选服务端

> 配套 `agent-prompt6.md`（任务与验收）。本文件只讲结构、协议、时序、状态机与数据落点。

---

## 1. 分层

```
┌──────────────────────────── DSH 会话 ────────────────────────────┐
│ CR（主 Agent，reasoning=low）                                     │
│   ├─ 原语 chat_turn / env_read / store_*                          │
│   ├─ 子代理 SR     （复杂问题 → 方案 + 截图解读）                   │
│   ├─ 子代理 AR     （执行任务，可被 CR 抢占）                       │
│   └─ 子代理 ESRC   （按需读 env，解释现场）                        │
│ 插件 ZFF MC：preset / 启动器 / 续跑 / 管理员下线 / 关键词           │
└───────────────┬──────────────────────────────────────────────────┘
                │ MCP（streamable-http 127.0.0.1:57610）
┌───────────────▼──────────────────────────────────────────────────┐
│ MCP server：工具路由 + CR store + 聊天队列/Flag_User_Chat（Node）   │
│             ingest 57612 /internal/chat_event                     │
└───────────────▲──────────────────────┬───────────────────────────┘
                │ chat_event（推送）    │ daemon 127.0.0.1:57611
                │                      ▼
┌───────────────┴──────────────────────────────────────────────────┐
│ 客户端 mod：ChatLog 环形(只读) · ChatEvent 推送 · EnvSampler       │
│             BridgeRuntime（回环 HTTP）· ZffWatchdog · 动作层(84)   │
└───────────────┬──────────────────────────────────────────────────┘
                │ Minecraft 连接（可选 payload 通道）
┌───────────────▼──────────────────────────────────────────────────┐
│ 服务端 mod（可选）：版本协商 · SavedData · /zff · 采样 · 广播      │
└──────────────────────────────────────────────────────────────────┘
```

---

## 2. 工具面（按角色收窄）

| 工具组 | CR | SR | AR | ESRC |
|---|---|---|---|---|
| `chat_turn` | ✅（独占） | ❌ | ❌ | ❌ |
| `env_read` | ✅ | ✅ | ✅ | ✅ |
| `store_*`（cr 写） | ✅ | 读 | 读 | 读 |
| 动作（mine/path/place/container…） | ✅（抢占式） | ❌ | ✅ | ❌ |
| 只读（get_state/scan/entity/inventory…） | ✅ | ✅ | ✅ | ✅ |
| `job_*` | ✅ | ❌ | ✅ | ❌ |
| 子代理 spawn | ✅ | ❌ | ❌ | ❌ |

- CR 独占 `chat_turn`，保证"只有一个人读聊天"。
- AR 的工具面靠 per-child tool scoping（装 `dsh-background-agents` 时用它，否则用 preset 白名单）。
- `screenshot` 谁都能调（取证），但**画面解读只交给 SR**：CR 把截图交给 SR，拿回文字结论（见 §3.7）。
- 每个工具入口（**MCP server 的调度层**）都有 **`Flag_User_Chat` 守卫**：置位时除 `chat_turn` 与
  `exemptTools` 外一律返回中性异常（见 §3.6）。

---

## 3. 关键时序

### 3.1 聊天（说完再听）

```
CR: chat_turn {message:"我来了"}            # send → drain
     ↳ {sent, received:[…], self:[…], cursor}
CR: 处理 received（回/不回/记 todo）
CR: chat_turn {message:"…", drain:false}    # 再说话时不 drain
```

- 纯听：`chat_turn {}`。
- ack：返回 cursor；未确认消息保留 60 s，确认或超时后删除。
- `self` 与 `received` 分开，防止 CR 回自己。

### 3.2 任务（CR → AR）

```
CR: ar_run {task:{steps:[…]}, timeoutMs: 30000}
     ↳ 阻塞到完成或超时，返回 {state, progress, metrics, partial}
     超时 → CR 决定：继续等 / 抢占 / 换方案
```

- 轮询靠"超时返回"实现，不依赖宿主定时器。
- AR 的每次工具调用都会注册看门狗（mod 侧 `ZffWatchdog`）。

### 3.3 抢占（CR 优先）

```
AR: 正在 mine_block (jobId=j1)
CR: path_to(…)                     # 动作类调用
  → job_cancel(j1) → interrupt_agent(ar) → 执行 path_to
  → CR store 记 preempted{by:"cr", jobId:j1, tool:"path_to"}
```

- 记录必须落 CR store，便于复盘"为什么这个任务没做完"。

### 3.4 掉线与恢复

```
env_read.staleMs > 阈值  →  CR 判定掉线
→ chat_turn {message:"我掉线了，正在重连"}（可选）
→ 触发启动器重连（插件）→ get_state 校验 → 继续
续跑刹车：掉线期间不续跑
```

### 3.5 管理员下线

```
CR 从 received 里看到管理员说“先下线吧”
→ CR 判断（任意语义）→ zff_shutdown {reason, by}
→ 插件校验 adminPlayers（用只读 chat_history 复核最近发言人）
→ disconnect → 10 s 未退出则杀进程 → terminated=true（停止续跑）
```

### 3.6 聊天中断（Flag_User_Chat）—— 把控制权从阻塞工具里抢回来（规则全在 Node）

真实案例：CR 卡在"等熔炉"这类阻塞调用里，玩家说话它没听见。解决方式是**工具层的通信中断**。
正则、标志、队列全部在 **MCP server（Node）**；mod 只负责推送聊天事件与执行发送。

```
玩家发消息 "鱼，回来吃饭"                    （type=chat，非 self）
  → mod 收到 → POST http://127.0.0.1:57611/chat_event（loopback + token）
  → daemon 转发 → MCP server 57612 /internal/chat_event
  → server 校验 → 剥 §x → 正则 runtime/zff-chat-rules.json（默认 ^鱼）命中
  → 置位 Flag_User_Chat=1 → 消息入 Node 侧 unread 队列

情况 A：CR 正在跑 MCP 工具（非 chat_turn）
  → server 工具调度入口发现标志 → 直接返回中性异常（不转发 daemon）
    {ok:false, error:"user_chat_pending", message:"玩家刚说话，请先 chat_turn 读取并回复"}
  → 不取消底层 job，不标记任务挂起/失败

情况 B：CR 正在思考（没有工具在飞）
  → 思考结束、调用任意非 chat_turn 工具 → 同样被入口守卫拒绝

情况 C：server 侧阻塞工具（wait_events / ar_run / 轮询）
  → server 把长阻塞切片（2 s/片），片间查标志 → 命中即返回中性异常（底层 job 继续跑）

CR：chat_turn {} 或 chat_turn {message}（drain）
  → drain 清除 Flag_User_Chat（drain:false 的纯发送不清除）
  → 返回 received，CR 逐条回复（可一次发多条）
  → CR 自行决定是否继续刚才被打断的任务（不自动恢复、不标记挂起）
```

**规则补充**

- 只匹配玩家聊天；系统消息、命令反馈、自己发的（`self`）都不算。
- 规则文件由插件写入 `runtime/zff-chat-rules.json`，server 热加载：`{"enabled":true,"matcher":"^鱼","exemptTools":[]}`。
- 标志置位时，插件的自动续跑**跳过退避与低耗待机**立即唤醒（仍遵守 paused / terminated / 掉线三条刹车）。
- 安全兜底（`air_critical` → `stop_all`）不受该标志影响。
- 队列在 Node：客户端重启不影响 unread；**MCP server 重启会丢 unread**（符合"未读不持久化"）。

### 3.7 截图分析交给 SR

```
CR: 需要一个画面结论（“锅里有成品吗 / 门口被什么挡住”）
  → CR 先 screenshot（取证）或直接请 SR 截图
  → SR 读图并返回文字结论（不含图片，保持 CR 上下文轻）
  → CR 依据文字结论决定动作
```

- CR **不做**画面解读；SR 是唯一的视觉分析角色。


---

## 4. 数据布局

| 数据 | 位置 | 生命周期 |
|---|---|---|
| 聊天未读 + cursor + `Flag_User_Chat` | **MCP server 内存** | MCP server 重启即丢（**不持久化**）；客户端重启不影响 |
| 完整聊天记录（只读） | mod store `<gameDir>/zff/mod-store.json` | 持久（环形 2000 条） |
| 聊天规则 | `<workspace>/runtime/zff-chat-rules.json` | 持久（插件写，server 热加载） |
| 环境采样 | mod 内存环形 | 易失 |
| 背包/位置快照 | mod store | 持久（按需） |
| CR 记忆/待办/chat.sent/preempted | `<workspace>/.mcp-agent/cr-store.json` | 持久 |
| M1–M4 临时记忆 | `<workspace>/runtime/cr-scratch.json` | 持久，M5 迁移 |
| 服务端 SavedData（可选） | `<world>/data/zff_agent.dat` | 跟随世界存档 |

---

## 5. CR 状态机

```
        ┌────────────┐  续跑/玩家消息   ┌───────────┐
        │ TERMINATED │◀── 管理员下线 ──│  ACTIVE   │
        └────────────┘                 └─────┬─────┘
                                             │ 玩家说停
                                       ┌─────▼─────┐
                                       │  PAUSED   │
                                       └─────┬─────┘
                                             │ 玩家再发言
                                       ┌─────▼─────┐
                                       │  ACTIVE   │
                                       └───────────┘
```

- `ACTIVE`：每轮 = `chat_turn` → `env_read` → 决策（回/忽略/派任务）→ 汇报。
- 空转计数：连续 N 次（默认 20）无进展 → `SELF_DIRECTED`（自主找任务：只读/探索/整理自己背包/回常驻点）。
- 自主 M 轮（默认 5）无进展 → `LOW_POWER`（每 60 s 只 `chat_turn {}`）。

**`Flag_User_Chat` 对状态机的影响**（正交，不新增状态）：

| 当前状态 | 标志置位后 |
|---|---|
| `ACTIVE`（工具在跑） | 工具抛中性异常 → CR 进入一轮"读取并回复" → drain 清标志 → 回到 `ACTIVE` 自选下一步 |
| `ACTIVE`（思考中） | 下一次非 `chat_turn` 调用被拒 → 同上 |
| `SELF_DIRECTED` / `LOW_POWER` | 插件跳过退避**立即唤醒**（仍遵守 paused / terminated / 掉线） |
| `PAUSED` / `TERMINATED` | 不唤醒（标志保留，直到玩家恢复或重新上线） |


---

## 6. 与 Ph5 的差异

| 维度 | Ph5 | Ph6 |
|---|---|---|
| 顶层 | Node `SR` 调度器 | **CR 主 Agent**（LLM） |
| 聊天 | Node `Chatter` 轮询 + 复读 | `chat_turn` 原语 + CR 理解 |
| 执行 | Node `AR` 进程内 | **AR 子代理**（可中断） |
| 感知 | Node `ESRS/ESRA` 轮询 | mod 常驻采样 + **ESRC 子代理**按需读 |
| 存储 | `SavedData`（服务端必需） | 双 store（CR/mod）+ 可选服务端 |
| 唤醒 | 无（人不说话就停） | **自动续跑**（插件，带刹车） |
| 抢占 | 无 | CR 优先，记 `preempted` |

---

## 7. 关键技术点

1. **版本协商**：`hello{modVersion,protocol}` ↔ `hello_ack{modVersion,protocol,serverId}`；不匹配 → `backend=local(mismatch)`，不发 RPC。
2. **ack 语义**：`chat_turn` 返回 `cursor`；确认/60 s 超时才删，避免响应丢失导致消息永久丢失。
3. **self 去重**：本地发送记 `self:true`；服务器回声按 `(sender==自己 && 文本同 && 2 s 内)` 合并。
4. **mustReply**：插件配置关键词表；命中的消息在 `received` 里带 `mustReply:true`。
5. **硬安全兜底**：`env_read.alerts.interrupt=true` 时，mod 直接 `job_cancel/stop_all`，不经过任何 agent。
6. **抢占记录**：`preempted` 事件写 CR store，复盘用。
7. **未读不持久化**：聊天未读只在内存，客户端重启清空（玩家明确要求）。
8. **工具层中断（Node 侧）**：mod 把每条聊天 `POST 57611/chat_event` → daemon → server `57612/internal/chat_event`；
   server 在**入队前**用 `runtime/zff-chat-rules.json` 的正则（默认 `^鱼`）匹配并置位 `Flag_User_Chat`；
   守卫在 server 的**工具调度入口**统一执行（不转发 daemon）；阻塞工具由 server 切片（2 s）检查标志。
   中性异常码 `user_chat_pending`，不取消 job、不计失败。
9. **标志清除**：只由 drain 清除（`chat_turn` 且 `drain!=false`）；`drain:false` 的纯发送不清除。
10. **截图政策**：`screenshot` 谁都能调，画面解读只由 SR 做，SR 返回文字结论。
11. **提示词装配**：会话启动时把三段拼进 system prompt ——
    `prompts/system.md`（骨架，含五条铁律）→ `## 工具使用`（`prompts/tools.md`，默认）→ `## 游戏规则`（`prompts/rules.md`，默认"无指令权限"最小模板，清空则不注入）→
    `## 角色`（`prompts/persona.md`，默认鲸鱼娘女仆），最后附运行时事实（账号/服务器/模式/store 路径）。
    优先级：安全 > 规则 > 工具 > 人设。用户版在 `~/.dsh/zff-mc/prompts/`，缺哪个槽位就回落到插件内置 `defaults/`。
