# ZFF MC（`@local/zff-mc`）—— DSH 的 Minecraft 陪玩插件

**装法**：`dsh plugin --profile desktop add link:D:/mc/zff-mc`（本机已装，见 profile 的 `dsh.profile.bundles`）。
**开法**：新会话模式选「ZFF MC Mode」；游戏工具以 `mcp__mc__*` 出现（MCP server 先起：`start-mcp.ps1`）。
**插件不启动游戏**：你自己开好 Minecraft 并进服，然后跟 AI 说一句「我进游戏了」，
AI 用 `mcp__mc__hello` / `get_state` 确认在线后直接接手（这是 2026-11-01 按玩家要求去掉启动器后的形态）。
**自动续跑**：每轮输出停止后注入续跑文案；刹车 = **▶（玩家没点开始）** / 不在 ZFF MC Mode / `paused` / 掉线 / job 未超时 / `terminated`。

## ▶ / ⏸ 运行开关（Ph7.2，玩法核心）

「开始游戏」按钮就是开关本身：

| 按钮 | 含义 | 行为 |
|---|---|---|
| **▶ 开始游戏** | 已结束（默认；DSH 重启后也回到这里） | **不注入任何续跑**；游戏工具**只读**放行（还能问"我在哪/几点"），会改变世界的工具一律拒绝 |
| **⏸ 进行中** | 游戏进行中 | 满足其它条件时自动续跑；点它一下 = 结束游戏：立刻停止续跑，并给当前这一轮注入"停手"提示 |

- 状态落盘 `<workspace>/runtime/zff-run.json`（`{state, at, by, bootId}`）。
- **关掉 DSH 自动复位**：文件里的 `bootId` 与本次启动不符 → 当 ▶ 处理并重写。
- 游戏没开就点 ▶：允许"等待客户端进世界"（按钮旁会写出来），实例一进世界就开始续跑。
- 本会话**一个 `mcp__mc__*` 都没注册**时（mc-control 启动时没连上 57610）：插件自动把开关置回 ▶，并提示玩家"完全退出 DSH 再打开"。

**叫停**：玩家说「停/别动/休息/别干了」→ 暂停；再说一句 → 解除。也可调 `zff_pause {paused}`。
**下线**：`zff_shutdown {reason}` 用只读 `chat_history` 复核 `adminPlayers`（最近 60 s），不在名单一律拒绝。

## 团队面板（Ph7.4）

标题条 **「团队」** 按钮 → 浮层，3 s 刷新，一行一个角色：

| 角色 | 数据来源 |
|---|---|
| CR（主代理） | 插件直读：`agent.status` + 最近续跑决策 + `runtime/cr-active-job.json` |
| ESRA（mod 看门狗） | `runtime/env-inbox.json` 的 `alerts`（60 s 内） |
| SR / AR / ESRC（子代理） | **自报**：`store_set {store:"cr", key:"team.SR", value:{task,state,at}}` |

没自报就显示「未上报」；超 180 s 仍 `running` 标 `stale`（可能已结束）。同一份数据也有工具 `zff_team`，AI 自己也能查。

> 记忆只有两种：`store_*`（CR store，`<ws>/.mcp-agent/cr-store.json`）与 `memory_*`（工作区文件）。
> **服务端共享记忆（`zff_memory_*`）已于 2026-11-01 取消**（MCP server 99 个工具）。

## 设置页（5 个标签，保存即生效）

| 标签 | 内容 | 落盘 |
|---|---|---|
| **提示词 A/B/C** | 三段分别「预览默认值 / 在线编辑 / 上传 `.md` / 恢复默认」；B 清空则该段不注入；每份 ≤64 KB | `~/.dsh/zff-mc/prompts/{tools,rules,persona}.md`（缺则用包内 `defaults/`） |
| **游戏** | 你的游戏名、服务器地址（只是"事实"，写进提示词，不用于启动） | `~/.dsh/zff-mc/settings.json` |
| **续跑** | 总开关、从聊天读"叫停"、空转 N（默认 20）、自主 M（默认 5）、低耗间隔、最小间隔、掉线阈值、轮询间隔 | 同上 |
| **管理员** | `adminPlayers`（每行一个；留空 = 谁都不能让 AI 下线） | 同上 |
| **聊天规则** | 中断正则（默认 `^鱼`）、必须回复的关键词、豁免工具 | `<工作区>/runtime/zff-chat-rules.json`（**MCP server 热加载**） |

生效优先级：`DEFAULT_CONFIG` ← profile 的 `cordis.patch.yml`（`id: zff-mc` 的 `config`）← `settings.json`（文件优先，改完立即生效）。
提示词是「**下一个会话**生效」，其余立即生效。

**入口**：新会话页与已有会话标题条都是「**开始游戏** / **设置**」· 插件详情页「提示词设置」。
「开始游戏」是个**说明弹窗**（四步：自己开游戏 → 进服 → 回来说一句 → AI 确认接手），不启动任何进程。
**落盘**：日志 `.dsh\zff-mc\logs\zff-mc.log`；运行时在工作区 `<工作区>\runtime\`：`env-inbox.json`（读）、
`cr-active-job.json`（读）、`zff-chat-flag.json`（读）、`zff-paused.json`（读写）、`zff-shutdown.json`（写）。
**工具**：`zff_shutdown` · `zff_pause` · `zff_status` · `zff_prompts`。
**自检**：`node D:\mc\zff-mc\selfcheck.mjs`（离线，148 条）。

## 已知限制

1. 宿主没有 turn-end 钩子，一轮结束靠 2 s 轮询 `agent.status` 推断（≤2 s 误差）。
2. 客户端 bundle 必须在**包名**下注册（`id: '@local/zff-mc'`）——写成短名会让宿主判 entry 未激活、
   整次启动失败（2026-11-01 三次事故的根因，见 `docs/ph6/INCIDENT-2026-11-01-PLUGIN-BOOT.md`）。
3. 设置页的「聊天规则」需要工作区可解析（本机钉在 `D:\mc\MCP Development`）。
4. 启动器已移除：不再需要版本目录/java/内存这些配置，也不需要 `src/launcher.mjs` 参与运行。
