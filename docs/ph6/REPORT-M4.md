# Ph6 M4 报告 —— ZFF MC 插件

> 插件本体在 `D:\mc\zff-mc\`（本地 link 包，包名 `@local/zff-mc`），离线自检 **163 通过 / 0 失败**。
> **它没有在真宿主里加载过**：本场的硬约束是不重启 DSH（重启会杀掉当前会话），所以 `apply()`、
> 事件监听、工具注册、设置页路由全部只有静态与离线证据。**这是 M4 最大的未验证项，必须诚实对待。**

---

## 1. M0.5 技术验证结论（详见 `PH6-M0.5-PLUGIN-API.md`）

| 能力 | 结论 | 怎么做的 |
|---|---|---|
| 监听 turn-end | **不能**（宿主没有该事件；`agent/pre-step` 是每步**之前**） | 退到**轮询 `agent.status` 的 `running→idle` 跳变**（≤2 s 误差）；`agent.steer()` 在空闲时会直接起一轮，用作唤醒 |
| 以用户消息注入 | **能** | `agent.steer(message)`；消息形状 `{role,content,source,id}`，`source.kind` 必须是 `plugin:zff-mc`；拿不到宿主实现时用自带等价实现（`src/tool-def.mjs` 不 import 任何 `@deepseek-ai/*`） |
| 读 preset / 模式 | **能** | `agentPresets.composedPreset(agent.ctx)` + 配置里的 `mcModePresets` 名单 |
| 插件工具 + 设置页 | **能** | `ctx.tools.register(defineTool({name,description,parameters,output,execute}))`；设置页 = `exports["./client"]` + `dsh.client.platform:"web"` + `ctx.webServer.register({kind:"prefix",...})` |

推论（已按此实现）：**续跑不能依赖 turn-end**。

## 2. 交付物

| 文件 | 内容 |
|---|---|
| `package.json` | `@local/zff-mc`；`dsh.bundle.patch` = `cordis.patch.yml` + `presets/zff-mc.patch.yml`；`dsh.client.platform = "web"` |
| `cordis.patch.yml` | `insert: [{id: zff-mc}]`；**mcp-client 条目默认注释**（本机 profile 已有 `mc-control`，同 `serverName` 会重复注册，README 写明换机器时取消注释） |
| `presets/zff-mc.patch.yml` | **`ZFF MC Mode`**：persona + 文件工具 + todo + compaction；不挂 shell/子代理/联网 |
| `index.js` | 续跑 / 提示词注入 / `zff_shutdown` / 启动器 / `/api/zff-mc` 路由 / `tools.guard` |
| `client.js` | 设置页：A/B/C 三张卡片（预览默认值 / 在线编辑 / 上传 `.md` / 恢复默认） |
| `src/brakes.mjs` | 6 条刹车纯函数（离线可断言） |
| `src/prompts.mjs` | 三件套读写 + 装配（安全兜底 > B > A > C > 运行时事实；B 空不注入；≤64 KB） |
| `src/runtime.mjs` | `runtime/*.json` 原子读写（永不抛） |
| `src/chat.mjs` | 走 daemon `57611/call` 只读 `chat_history` + 叫停判定 |
| `src/shutdown.mjs` | 管理员授权（拒绝优先） |
| `src/launcher.mjs` | `versions/<ver>` 上推两级得 `.minecraft`，拼 java 命令（`spawn` + `stdio:'ignore'`） |
| `defaults/{tools,rules,persona}.md` | 与仓库 `prompts/` 同步（本次已重新拷贝，`tools.md` 补了 Ph6 新工具） |
| `README.md` / `locale/{zh,en}.json` / `selfcheck.mjs` | 说明、界面文案、自检 |

## 3. 续跑与刹车（`src/brakes.mjs` + `index.js`）

固定模板（不允许模型改写）：

```
【自动续跑】按 AIPlayer.md §0 铁律继续：先 chat_turn 看消息 → 有活就干 → 每轮必须有一句汇报。禁止空转。
```

| 条件 | 行为 | 自检 |
|---|---|---|
| 不在 ZFF MC Mode | 不续跑 | ✅ |
| `paused=true`（玩家说停） | 不续跑，直到玩家再次发言 | ✅ |
| 掉线（`runtime/env-inbox.json` 的 `staleMs` 过旧 / `offline=false`） | 不续跑 | ✅（无信号时 fail-open，不误刹） |
| 有 job 在跑且未超时（`runtime/cr-active-job.json`） | 不续跑 | ✅ |
| 连续空转 ≥ N（默认 20） | **不停止**，改注入"自主找任务" | ✅ |
| 连续自主 ≥ M（默认 5）仍无进展 | 降为低耗待机（每 60 s 一次 `chat_turn {}`） | ✅ |
| `Flag_User_Chat` 置位（`runtime/zff-chat-flag.json`） | **跳过退避与低耗待机立刻唤醒**（仍守 paused/terminated/掉线） | ✅ |
| 管理员要求下线 | `zff_shutdown` → `disconnect` → 10 s 未退就杀进程 → `terminated=true`，停止一切注入 | ✅（授权部分） |

**`zff_shutdown` 的两次校验**：插件用只读 `chat_history` 复核最近 60 s 发言人是否在 `adminPlayers`；
不在 → `{ok:false,error:"not_admin"}` 且**不写**标记；在 → 写 `runtime/zff-shutdown.json (terminated:true)` 并返回
`{ok:true,action:"disconnect_then_kill"}`，真正的断连/杀进程由 MCP 工具 `zff_kill_client` 执行（它只认当前
已附加实例的 gameDir）。

## 4. 提示词三件套

- 路径：用户版 `$DSH_HOME/zff-mc/prompts/{tools,rules,persona}.md`，缺哪个槽位就回落包内 `defaults/`。
- 装配顺序（`src/prompts.mjs`）：安全兜底（写死在代码里）→ `## 游戏规则`(B) → `## 工具使用`(A) → `## 角色`(C) → 运行时事实。
- B 为空（只剩注释/空白）→ **整段不注入**；每份 ≤64 KB（65536 通过 / 65537 拒绝）。
- **落地位置与设计不同**：`system-prompt/assemble` 的载荷形状没有证据，插件**刻意没挂**它；实际是"每轮请求的
  第一条消息（plugin notice）+ preset 的 persona"。要真 system prompt 前缀，需要拿到宿主载荷证据后再改。

## 5. 验收对照

| 要求 | 状态 |
|---|---|
| bundle（`dsh.bundle.patch` + `@deepseek-ai/dsh-mcp-client`） | ✅ 结构照 whale_craft；mcp-client 条目按本机现状注释 |
| `ZFF MC Mode` preset | ✅ |
| 设置页（A/B/C 编辑 + 上传 + 恢复默认） | ✅ 代码完成，**未在真宿主打开过** |
| 启动器（versions → .minecraft → 命令） | ✅ 离线用真实版本 JSON 校验（mainClass=BootstrapLauncher） |
| store 初始化 | ✅ `runtime/*.json` 原子读写；**文件由 M1 的 MCP server/插件各自写入** |
| 续跑刹车 + 管理员下线 + mustReply 关键词 | ✅ 离线 163 断言全绿 |
| 新机器选目录 → 启动 → 进服 → 人设生效 → 能聊天 | ❌ **未做端到端**（需要重启 DSH 装插件） |

## 6. 下一步（给下一位 Agent，按顺序）

1. **装插件**：`dsh plugin --profile desktop add link:D:\mc\zff-mc`（或改成 `@local/zff-mc` link），**完全重启 DSH**。
2. 开一个新会话，选 **ZFF MC Mode**，看设置页三张卡片是否能打开、能否保存/上传/恢复默认。
3. 用 `scripts/ph6-connect-all.mjs` 或插件启动器把客户端拉进本地服，确认续跑真的会注入（`agent.status` 跳变那一刻）。
4. 排错重点：`apply()` 是否被调用、`inject` 的服务是否就绪、`agent.steer` 是否在空闲时会起一轮、
   `restrict({deny})` 是否真的隐藏了工具（不确定就靠 `tools.guard` 硬拒）。
5. 若 `agent.status` 没有 `running` 以外的取值，把轮询判定换成"最后一条 assistant 消息时间 + 空闲阈值"。
