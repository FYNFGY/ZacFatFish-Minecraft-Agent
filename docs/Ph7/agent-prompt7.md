# Ph7 任务书与交接文档（`agent-prompt7.md`）

> 生成时间：2026-11-02 01:10（Asia/Shanghai）
> 维护者：DSH（开发侧）→ **交给下一个 AI 接手**。读完本文 = 拿到全部上下文。
> 玩家（唯一验收人）：**不允许替他做决定**；本文里标 **【待确认】** 的才需要问他，其余都已定稿。

---

## 0. 交接卡片（先读这一段）

| 项 | 值 |
|---|---|
| 本轮代号 | **M0**（修 ISSUE）+ **M1**（提示词 / 设置页 / 中断系统） |
| 当前状态 | **M0 未开始**；M1-1/1-2/1-3 **未开始**；基础设施（插件/MCP/测试套件）已就绪 |
| 你的第一个动作 | 按 §9「开工步骤」跑 M0-1（只取证、不改代码） |
| 硬约束 | ① 不许动 PCL 启动器里的游戏实例；② DSH 插件 UI 由玩家亲自验收；③ 每次改完必须跑 `node test/run-all.mjs --slow` 全绿才交 |
| 当前运行态 | MCP server `pid 9224`（57610/57612）、daemon `pid 29128`（57611），工作区 `D:\MC Agent Play2` |
| 关键文档 | `docs/Ph7/ISSUE.md`（22 条问题）、`docs/Ph7/REPORT.md`（Ph7.0–7.8 历史）、`docs/Ph7/reference/`（17 份参考） |

**一句话目标**：让 AI 玩家在真实客户端里**连续、可靠**地完成玩家派的任务；中断要"立刻生效、只打断 CR、可配置、可维护"。

---

## 1. 环境与边界（血泪教训）

### 1.1 三套东西必须分清

| 名字 | 路径 / 端口 | 谁能动 |
|---|---|---|
| **PCL 里的游戏**（玩家自己的实例） | `D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft\versions\*` | **禁止**启动/重启/杀进程/改配置 |
| **本工作区的测试客户端** | `D:\mc\MCP Development\ph7-env\client`（253 mods，含 bridge） | 允许：启动、建世界、`/give /setblock /tp /damage /effect /clear`、命令方块 |
| **MCP server / daemon** | 57610（MCP）、57611（聊天/事件守护）、57612（内部 ingest） | 允许启停（插件面板或 `start-mcp.ps1`） |

- 测试客户端用**独立 gameDir**（`ph7-env\client`），JVM 用 `javaw` + `--quickPlaySingleplayer m0test`。
- 实例靠**端口**区分，不要靠窗口标题；玩家实例的端口会在 `%LOCALAPPDATA%\state\headlessmc-mcp\instances\*.json` 里。
- 需要截图确认时，`scripts/mc-gui.ps1 -TargetPid <pid>` 只对**我们自己的**窗口操作。

### 1.2 测试世界（M0 用）

新建超平坦：**允许作弊 / 和平 / 生存**（`ph7-env/client/saves/m0test`）。
需要的场景物品/方块（**id 已查证，来自测试客户端的 mods 目录**）：

| 用例 | id | 说明 |
|---|---|---|
| 牛 | `minecraft:cow` | `/summon cow`，用 `entity_query type=cow` 找 |
| 食物 | `minecraft:bread` / `minecraft:cooked_beef` / `minecraft:beef` | `/give` 给 |
| 蛋糕 | `minecraft:cake` | 是**方块**，放地上右键吃（`use_block`） |
| 雕纹书架 | `minecraft:chiseled_bookshelf` | 1.21 原版方块，6 个 book slot |
| 附魔书 | `minecraft:enchanted_book` | `/give @p enchanted_book`（可带 `[enchantments={…}]`） |
| 咖啡浆果植株 | **`coffee_delight:coffee_bush`** | 模组：`[咖啡乐事] coffee_delight-1.4.1.jar`；blockstate `age=0..3`，**成熟 = `age=3`** |
| 小麦种子 | `minecraft:wheat_seeds` | |
| 油锅 | **`kaleidoscope_cookery:oil_pot`** | 模组：`kaleidoscopecookery-1.4.1`；流程"放种子 → 出油 → 空桶装" |
| 空桶 | `minecraft:bucket` | |

> 参考文档里有 `TOOLS.md` / `AIPlayerManual.md`；模组操作查不到就**上网查证**（玩家明确要求）。

---

## 2. 代码地图（改之前先看这里）

### 2.1 插件 `D:\mc\zff-mc`（DSH 宿主插件）

| 文件 | 作用 | 本轮要动 |
|---|---|---|
| `index.js`（~1600 行） | 工具注册、`/api/zff-mc/*` 路由、刹车、环境自举、▶/⏸、团队面板、心跳 | M1 中断状态展示（可选）、P7-6 修复 |
| `client.js` | 浏览器端：▶/⏸、MCP 状态栏、团队面板、**设置页（提示词三件套）** | **M1-2**：默认提示词预填/可复制/恢复默认 |
| `src/run.mjs` | ▶/⏸ 落盘（`runtime/zff-run.json`），**按会话**绑定 `sessionId` | P7-6 |
| `src/brakes.mjs` | 续跑判定（run_idle / paused / env_blocked / job / flag / 冷却） | 中断落地后可加"中断优先" |
| `src/mcp-env.mjs` | 环境自举：找/解压 zip、`ensureServer`、`ensureDaemon`、`chooseInstance`、`explainBlocked` | P7-7/P7-9/P7-10 |
| `src/mcp-control.mjs` | 面板启停：`mcpStatus/startMcp/stopMcp/sendHeartbeat` | P7-7 会话失效提示 |
| `src/team.mjs` | 团队面板数据（CR 插件直读；SR/AR/ESRC 自报 `team.<role>`） | — |
| `src/prompts.mjs` | Part A/B/C 合成、默认值读取、`composePrompt` | **M1-1** 配合 |
| `defaults/tools.md` | **Part A**（工具规则 + 团队上报 + 派活） | **M1-1：协作模型写这里** |
| `defaults/rules.md` | **Part B**（硬约束 + 连续作业纪律，2026-11-01 已重写） | — |
| `defaults/persona.md` | **Part C**（角色性格） | **M1-1**：System Prompt 重写配合 |
| `presets/zff-mc.patch.yml` | ZFF MC Mode 的 preset：persona prefix/suffix + 工具插件 + 子代理插件 | **M1-1：System Prompt 主战场** |

### 2.2 MCP server `D:\mc\MCP Development\mcp-server`

| 文件 | 作用 | 本轮要动 |
|---|---|---|
| `src/server.js`（~1800 行） | 99 个工具、`waitJob`、`/health`、`/instances`、chat 守卫、`--owner-heartbeat`、状态文件 | **M1-3：中断引擎接入 `waitJob` + 每个工具调用开始清标志** |
| `src/lib/chat-hub.mjs` | unread 队列、cursor、`Flag_User_Chat`、`guardError`、`stats` | **M1-3**：扩展成中断管理器（或新模块） |
| `src/mc-daemon.mjs` | mod → server 中转（`/chat_event`、`/chat_state`、`/call`、看门狗） | **M1-3**：新增 `/event`（damage/fall/hunger/inventory_full） |
| `src/lib/store.mjs` | BridgeStore（实例/环境/任务）、CrStore | 取消 job 用 `job_cancel` |

### 2.3 mod `D:\mc\MCP Development\mod-mdk`（NeoForge 21.1.228 / MC 1.21.1）

| 文件 | 作用 | 本轮要动 |
|---|---|---|
| `IssueTools.java` | `eat` / `use_item` / `wake` / `screenshot` 等 | **P7-11：使用物品族** |
| `Actions.java` | `use_block`（含床另一半重试）、`walk` 等 | P7-11 / P7-22 |
| `ChatPush.java` | 把聊天推给 daemon（57611） | **M1-3：泛化成 EventPush** |
| `Tools.java` | 工具调度表（99 个里的 mod 部分） | M1-3 事件 |
| `BridgeRuntime.java` | 客户端生命周期、`playerName`、tick | M1-3：检测 fall/health/food/slots |
| `ZffServer/ZffRemote/Errors` | 服务端半边（服务器没装 mod 时 `no_server_half`） | — |

**构建**：`cd mod-mdk; $env:JAVA_HOME="D:\java\jdk-21_windows-x64_bin\jdk-21.0.8"; .\gradlew.bat build`（约 12–60 s）。
**装到测试客户端**：`Copy-Item mod-mdk\build\libs\mcpbridge-0.2.0.jar ph7-env\client\mods\mcpbridge-0.2.0.jar -Force`，**必须重启客户端**。

---

## 3. M0：修 ISSUE + 6 个验收用例

### 3.1 范围（玩家 Q1 已确认）

**M0 必做**：P7-11（使用物品族）、P7-6（▶ 闪烁）、P7-7（unknown MCP session 提示）、P7-9（daemon 告警）、P7-10（实例提示）、P7-19（聊天重复）、P7-20（mustReply 限次）、**P7-17（容器 token/光标）**。
**M0b 留后**：P7-13、P7-14、P7-15、P7-18、P7-21、P7-22。

### 3.2 六个验收用例（必须真机跑通）

| # | 用例 | 关键点 | 失败时先查 |
|---|---|---|---|
| 1 | 挤牛奶 | 必须对**牛实体**右键（`entity_interact use`），不是对空气 `useItem` | 牛在不在 4 格内；`entity_query cow` |
| 2 | 吃面包 / 熟肉排 / 生肉排 | `eat` 要能进入 using 并**真的回血/回饱食** | `eat` 返回的 `using/useItemRemaining/foodAfter` |
| 3 | 吃蛋糕 | 蛋糕是**方块**（`useItemOn`），不是物品 | 站在蛋糕旁，`use_block` |
| 4 | 附魔书 → 雕纹书架 → 取出 | `minecraft:chiseled_bookshelf` 有 6 个槽，右键放/取 | `block_query` 的 `chiseled_bookshelf` 属性；空槽先放 |
| 5 | 收获成熟咖啡浆果植株 | `coffee_delight:coffee_bush`，**`age=3` 才是成熟**；右键收获后 `age` 回 0 | `block_query` 看 `age`；`scan_surface` 找植株 |
| 6 | 小麦种子榨油 + 桶装 | `kaleidoscope_cookery:oil_pot`：放 `minecraft:wheat_seeds` → 出油 → 空桶装 | 油锅的容器槽规则（同 FD 锅，可能要先把桶放进去） |

**取证要求**：每个用例保存 `evidence/m0-<case>.json`（工具请求 + 返回 + 前后 `get_state`/`inventory_slots`），失败的要带 `screenshot`。

### 3.3 P7-11 的取证（决定怎么修）

跑一次 `eat {item:"minecraft:cooked_beef", maxTicks:64}`，看：

- `using:false` + `useItemRemaining:0` → 客户端没进入 using（界面遮挡 / 物品不可食 / 代码路径错）
- `using:true` 但 `foodAfter==foodBefore` → **服务器丢弃了 use-item 包**（改走 `Minecraft.startUseItem`，别只调 `gameMode.useItem`）

> 现阶段只做了"后置校验"（`consumed:false` 会返回 `incomplete`），**根因未修**。

---

## 4. M1-1：System Prompt 重写 + 子代理协作模型

### 4.1 玩家要求（原话拆解）

1. **游戏状态下不要暂停任何一轮对话输出** → 定稿理解（玩家 Q6"统一"已确认）：
   **▶ 状态下，禁止以「请示 / 等待指令 / 汇报完就停」结束回合；每一轮必须有实际推进。**
   （点进对话**仍然不应该被唤醒**——唤醒只由 ▶ 决定，这是 11-01 定下的。）
2. **强制采用玩家设计的子代理协作模型**，模型细节**写进默认 Part A**（`defaults/tools.md`）。
3. 子代理**不受**"禁止停下/必须推进"约束（Q7）。

### 4.2 协作模型（写进 Part A 的骨架，可再扩）

| 角色 | 职责 | 工具面 | 约束 |
|---|---|---|---|
| **CR**（主） | 聊天、决策、编排、抢占、对玩家汇报 | 全部 | 必须推进；不许空转；不许请示式收尾 |
| **SR** | 只读分析：方案 / 截图解读 / 路线 | 只读（scan/get_state/env_read） | 不改世界；跑完交回 |
| **AR** | 执行：走/挖/放/用/容器 | 动作类 | 一次只做被指派的那件事 |
| **ESRC** | 现场解释"为什么卡/现在什么情况" | 只读 | 一句话结论 + 证据 |
| **ESRA** | mod 侧看门狗（3 s 任务异常） | 无（mod 内部） | 只上报，不决策 |

规则：派活前先 `todo_write`；子代理开工/收工写 `team.<角色>`（团队面板读它）；CR 用 `interrupt_agent` 抢占；子代理之间不互相抢。

#### 4.2.1 汇报对象（2026-11-02 玩家澄清）

> 玩家原话：「阶段汇报是**给子代理看的**，不是给 CR 看的。」

- **子代理（SR/AR/ESRC）**：按阶段向 **CR** 汇报（做了什么 → 结果 → 下一步），这是给 CR 做决策用的，**不要直接对玩家播报**。
- **CR**：对玩家**只在三个时机**说话——任务完成 / 被挡住需要玩家 / 玩家问话。**不做"过程播报"，也不做给子代理看的阶段汇报**。
- 因此 Part B §2「只在三个阶段汇报」是**给 CR 的**；子代理侧的"阶段汇报"写在 **Part A 的协作模型**里，两处不要混。
- persona 里原来的"每轮必须有一句汇报"要改掉（与上述冲突）：改成 **"每轮必须有实质推进；汇报只发生在完成/被挡/被问"**。

**System Prompt 分工**：`presets/zff-mc.patch.yml` 的 persona（prefix/suffix）只放"身份 + 必须遵守 Part A 协作模型 + 游戏进行中不许停"；细节全在 Part A。

---

## 5. M1-2：设置页默认提示词（UI，玩家亲自验收）

- 打开「设置 → 提示词」三个 tab（A/B/C）时，输入框**预填默认文本**（可编辑的初始值）。
- 文本**可全选复制**（不要只读遮罩；不要禁用选择）。
- 「恢复默认」按钮：把输入框恢复为默认值（未保存前可撤销）。
- 保存 = 写 `~/.dsh/zff-mc/prompts/<slot>.md`（覆盖默认）。
- 改动文件：`client.js` 的 `SettingsModal` + `src/prompts.mjs` 的默认值读取。

---

## 6. M1-3：中断系统（本轮最重要，严格要求）

### 6.1 现状（为什么玩家从没看到中断生效）

- 只有 `wait_events` 做了 2 s 切片检查；**其余长任务走 `waitJob()`，完全不看中断标志** → `collect_drops` 会死等到 60 s timeout。
- 所以"正则中断后工具还在等 timeout"是**必然**，不是偶发。

### 6.2 五类中断（定稿）

| id | 触发 | 默认冷却 | 附带信息 |
|---|---|---|---|
| `fall` | `fallDistance > 2.5` 且未着地 | **30 s** | 当前 y、下落距离、建议 `mlg_water` |
| `damage` | 生命值下降（边沿） | **5 s** | `damage_log` 最新：来源/伤害/位置 |
| `chat` | 玩家消息命中 `^鱼` | **10 s** | 消息文本 + 发送者 |
| `hunger` | 饱食度**首次** ≤ 6 | **30 s**（30 s 后仍 ≤6 可再触发） | 当前饱食度/饱和度 |
| `inventory_full` | 背包 **36 格全满**（边沿） | **30 s** | 已用格数 / 最后物品 |

**优先级（只返回一个异常）**：`fall > damage > chat > hunger > inventory_full`。

### 6.3 不可中断清单（可配置）

`runtime/zff-interrupts.json`：

```jsonc
{
  "cooldowns": { "chat": 10, "damage": 5, "hunger": 30, "fall": 30, "inventory_full": 30 },
  "neverInterrupt": {
    "chat": ["chat_turn"],
    "damage": ["chat_turn", "walk_to"],
    "hunger": ["chat_turn"],
    "fall": ["chat_turn"],
    "inventory_full": ["chat_turn"]
  },
  "priority": ["fall", "damage", "chat", "hunger", "inventory_full"]
}
```

规则：工具名命中 `neverInterrupt[type]` → 该中断**不打断这次调用**（标志仍挂起，等下一次工具调用开始时清）。

### 6.4 清除语义（玩家定稿）

- **任何** MCP 工具调用**开始时**：清空所有挂起标志；**该次调用照常执行、不被阻断**。
- 被清掉的信息**附在这次调用的结果里**（`cleared:[{type,detail}]`），AI 不丢信息。
- 冷却从**触发时刻**算，清除不影响冷却。

### 6.5 取消范围（玩家强调）

- 中断只取消 **CR 当前这个工具调用对应的 job**；**AR / SR 的调用与 job 不受影响**。
- **例外：`fall` → `stop_all`**（取消**所有** job + 停 baritone），因为要立刻自救。

### 6.6 早退机制（修 P7-11 之外的第二根支柱）

- `waitJob()` 改成 250 ms 轮询：每轮检查 `interrupts.take({ tool })`；命中 → `job_cancel` 当前 job → 立刻返回。
- 返回统一：

```json
{"ok":false,"error":"interrupted",
 "interrupt":{"type":"damage","detail":"被苦力怕炸了 7 点","at":1793...,"sinceMs":320},
 "jobId":"j-2z-836a9d","cancelled":true}
```

- 聊天中断不再用 `user_chat_pending` 当"拒绝"；`wait_events` 保留切片，但同样读新引擎。

### 6.7 事件来源（要改 mod）

```
mod（tick 里检测边沿） → POST 127.0.0.1:57611/event（Bearer） → daemon → /internal/event → server
kind: chat | damage | fall | hunger | inventory_full
```

- damage：`LivingEntity` 受伤事件拿 `DamageSource`（能写"被苦力怕炸了"）
- fall：`fallDistance > 2.5 && !onGround`
- hunger：`foodLevel` 首次 ≤6
- inventory_full：36 格全满首次

### 6.8 测试（玩家要求：用命令方块 + 长任务）

| 中断 | 命令方块 | 断言 |
|---|---|---|
| damage | `/damage @p 4 minecraft:generic` | ≤1.5 s 返回、`type=damage`、job cancelled |
| hunger | `/effect give @p minecraft:hunger 20 4` | 跌破 6 触发一次；30 s 内不再触发 |
| fall | `/tp @p ~ ~25 ~` + 清脚下 | **先** `stop_all`，`type=fall` |
| inventory_full | `/clear` 后 `/give dirt 64` ×36 | 第 36 格触发 |
| chat | 玩家说「鱼…」 | `type=chat`，消息在 `interrupt.detail` |
| 不可中断 | 对 `chat_turn` 触发任意中断 | 调用**正常完成**，结果带 `cleared` |

长任务：`mine_block` 64 格 或 `path_to` 60 格（>10 s），期间触发命令方块。
测试脚本：`test/zff-interrupts.mjs`（服务端单测）+ `evidence/m1-interrupts-*.json`（真机）。

### 6.9 ⚠️ 风险 R1（**留给下一个 AI 做，必须先验证，否则"只中断 CR"实现不了**）

#### 6.9.1 为什么这是个问题

玩家的要求是：**中断只取消 CR 的工具调用，AR / SR 的调用与 job 不受影响**（Fall 例外）。

但 MCP server 的世界里**只有 MCP 会话**，它看不到 DSH 的"CR / 子代理"身份：

```
DSH: CR ──┐
          ├─ mc-control（MCP client）──► MCP server: 99 个工具
DSH: AR ──┘                                 ↑
                              这里只认识 mcp-session-id
```

所以"取消谁的 job"必须先回答：**CR 和子代理，在 MCP server 看来是不是两个不同的 session？**

#### 6.9.2 验证方法（30 分钟，按顺序做）

**Step 1 — 看有没有多个 MCP session**

1. 起 MCP server（`start-mcp.ps1 -Workspace <ws>`，或插件面板「启动 MCP」），确保日志在 `runtime/zff-mcp-server.err.log`。
2. 让 **CR** 调一次 `get_state`；再让 CR 用 `subagent` 起一个 **SR**，让 SR 也调一次 `get_state`。
3. 看 server 日志里的这一行（每次 POST /mcp 都会打）：

```
[http] POST /mcp session=<uuid> known=true|false
```

- 出现**两个不同的 `<uuid>`** → 子代理有独立 MCP 会话 → 走 **方案 A**（推荐）。
- **只有同一个 `<uuid>`** → 走 **方案 B**（退化）或 **方案 C**。

**Step 2 — 看宿主能不能带 per-agent 头**

解包检查 `mc-control`（`@deepseek-ai/dsh-mcp-client`）的配置 schema，看有没有 `headers` / `requestHeaders` 之类：

```powershell
node scripts\asar-read.mjs --asar "C:\Users\ALIENWARE\AppData\Local\Programs\DeepSeek Harness\resources\app.asar" --find "requestHeaders"
```

- 有，且能按 agent 作用域生效 → **方案 C**（最干净）。
- 只有全局头 → 回到 A/B。

**Step 3 — 看工具调用能不能带"发起者"信息**

检查 `mc-control` 调 `tools/call` 时是否透传 `_meta` / 自定义字段（`tools/call` 的 `params._meta`）。若支持，让插件在每次工具调用里塞 `actor:"CR"|"SR"`，服务端据此判断 → **方案 D**。

#### 6.9.3 三条实现路径（按验证结果选）

| 方案 | 前提 | 实现 | 风险 |
|---|---|---|---|
| **A. 按 MCP session 区分** | Step 1 出现多个 uuid | 服务端记录 `jobId → mcpSessionId`；插件通过 `/internal/actor` 声明"当前 CR 的 session"；中断时只取消匹配的 job | 需要插件知道 CR 的 MCP session id（可从 server 的 `/internal/sessions` 与"最近一次 CR 工具调用时间"对上） |
| **B. 只取消"当前正在等待的那个 job"** | 无法区分 | `waitJob` 只取消**自己正在等**的那个 jobId；其它 job 不动 | 子代理同时跑长任务时会被它自己那次调用打断（概率低）；**必须写进文档告诉玩家** |
| **C. per-agent header** | Step 2 可行 | 每个 agent 的 MCP 客户端带 `X-ZFF-Actor`，服务端据此判断 | 依赖宿主能力；可能不可行 |
| **D. tools/call `_meta`** | Step 3 可行 | 插件/宿主在调用里带 actor | 同样依赖宿主 |

**默认选择**：A（若可行）→ C → D → B。
**如果只能选 B**：在 `docs/Ph7/ISSUE.md` 新增 **P7-23**，写明"子代理并发长任务时会被误伤"，并在交付时**明确告诉玩家**。

#### 6.9.4 验证结果写回哪里

1. 本文 **§6.9.5**（新增一节"R1 实测结论"）：写清 session 数量、选了哪个方案、为什么。
2. `docs/Ph7/ISSUE.md`：若出现新约束（如"宿主不支持 per-agent 头"），记一条 P7-23。
3. `evidence/m1-r1-*.json|log`：贴 `[http] POST /mcp` 的原始日志行。

#### 6.9.5 R1 实测结论（2026-11-02 13:20 填）

```
- 观测到的 MCP session 数量：1 个活跃 transport（daemon 的 abc3a263-…）；没有出现第二个 uuid。
- CR 的 session：与 daemon 同一个 MCP 会话（本次测试会话的工具面里没有 mcp__mc__*，
  CR / SR 的区分无法在本机直接观察；宿主是一个 mc-control 连接供所有 agent 复用）。
- SR/AR 的 session：没有独立 session；子代理继承宿主的 mc-control 连接。
- 选用的方案：**B**（只取消"本次调用正在等/正在查"的那个 jobId）。
  · waitJob() 每 250 ms 检查中断；命中时只 cancel 它自己在等的 job；
  · job_status / path_status 命中时只 cancel 参数里的那个 jobId；
  · fall 例外：stop_all + cancelActiveJobs（取消所有 job）。
- 未解决的风险：若 CR 恰好轮询的是 AR 启动的 jobId，中断会取消 AR 那个 job；
  AR/SR 正在跑但没人轮询的 job 不受影响。已记入 ISSUE.md 的 P7-23。
- 证据：evidence/m1-r1-sessions.log、evidence/m1-interrupts-live.json。
```

> 时间盒：**30 分钟**。30 分钟没结论就先按方案 B 实现（能立刻满足"不再等 timeout"的主诉求），把 R1 记进 ISSUE，别卡住 M0。

---

## 7. 测试与交付标准

```powershell
cd "D:\mc\MCP Development"
node test\run-all.mjs          # 快：6 套件
node test\run-all.mjs --slow   # 加真实进程（解压→起 server+daemon→stop 全杀）
```

- 每个 M 任务完成 → 更新本文 §8 进度板 + 跑全绿 + 贴结果。
- DSH 插件 UI（M1-2）由玩家验收，我们只保证 `selfcheck` + `zff-api` 绿。
- 证据落 `evidence/`：`m0-<case>.json`、`m1-interrupts-*.json`、截图。

---

## 8. 进度看板（2026-11-02 13:20 更新）

| 项 | 状态 | 备注 |
|---|---|---|
| Ph7.8 插件/MCP 基座（autostart、daemon 自启、心跳、团队面板、▶ 门禁、状态栏） | ✅ 完成 | 已交付，玩家已实测 |
| 测试套件（`run-all` 7+1 套件） | ✅ 完成 | 新增 `zff-interrupts` 单测；最终 `--slow` 结果见交付说明 |
| `docs/Ph7/ISSUE.md`（P7-6…P7-22 全量分析） | ✅ 完成 | 已补 P7-11 根因 / P7-23 |
| `docs/Ph7/reference/`（17 份 AI 参考文档） | ✅ 完成 | 本轮复制 |
| **M0-1** 测试客户端 + 6 用例取证 | ✅ 完成 | `evidence/m0-*.json`；用 `ph7test` 世界（非新超平坦，场景用命令搭） |
| **M0-2** 修 P7-11/6/7/9/10/17/19/20 | ✅ 完成 | P7-11 根因=右键状态没保持；P7-17 已改 brief+changed；P7-7 为提示/状态缓解 |
| **M0-3** 6 用例复测 + 证据 | ✅ 完成 | 6/6 真机通过；油锅实际流程与任务书不同，见下 |
| **M1-1** System Prompt + Part A 协作模型 | ✅ 完成 | `defaults/tools.md` §9/§10、`persona.md`、`presets/zff-mc.patch.yml` |
| **M1-2** 设置页默认提示词 | ✅ 完成（待玩家验收） | 预填默认文本 / 可编辑复制 / 恢复默认 / 删除自定义 |
| **M1-3** 中断系统 | ✅ 完成 | 引擎 + waitJob 早退 + job_status 取消 + mod 四类事件 + 真机测试 |
| M0b（P7-13/14/15/18/21/22） | ⬜ 留后 | |

**M0 用例结果（真机）**

| # | 用例 | 结果 | 证据 |
|---|---|---|---|
| 1 | 挤牛奶 | ✅ `entity_interact use`，bucket 2→1、milk_bucket +1 | `m0-milk.json` |
| 2 | 吃面包/熟/生牛肉 | ✅ `consumed:true`、food 上升；根因是客户端 keyUse 未保持 | `m0-eat-cooked-beef.json`、`m0-eat-raw-beef.json` |
| 3 | 吃蛋糕 | ✅ bites 1→2（方块 useItemOn） | `m0-cake.json` |
| 4 | 附魔书→雕纹书架→取出 | ✅ slot_1 occupied true→false | `m0-bookshelf-in.json`、`m0-bookshelf-out.json` |
| 5 | 收获咖啡 | ✅ age 3→1（不是 0，模组实际行为） | `m0-coffee.json` |
| 6 | 油锅 | ✅ 油入锅 `has_oil:true` → 空手取出 2 油；**种子不是直接放油锅** | `m0-oil-insert.json`、`m0-oil-take.json` |

> 油锅修正：`kaleidoscope_cookery:oil_pot` 只接受 `kaleidoscope_cookery:oil`（右键放入/空手取出）；
> 种子→油是 **millstone（3×3 多方块，需要 Mob 站上去磨）**，没有"空桶装"这一步。任务书 §1.2 的描述与本版模组不符。

**最终测试（2026-11-02 13:21）**

```
node test/run-all.mjs --slow
8/8 suites passed  (--slow)
```

- 新增 `test/zff-interrupts.mjs`（9 组断言：冷却/优先级/neverInterrupt/清除/热加载/fall 回调）。
- 真机：`evidence/m1-interrupts-live.json`（wait_events 早退 / job_status 取消 / fall stop_all 全 PASS）。
- 真机：`evidence/m1-chat-live.json`（玩家"鱼…"→ chat 中断进入引擎）。
- 真机：`evidence/m1-mod-events-live.json`（mod EventPush 真实 damage/hunger/fall；fall 触发 stop_all）。
- 真机：`evidence/m0-*.json` 共 10 份（含 bread/cooked/raw、milk、cake、bookshelf×2、coffee、oil×2）。
- 插件 UI（M1-2）仍需玩家亲自验收；代码侧 `selfcheck` / `zff-api` / `zff-settings` 全绿。



---

## 9. 开工步骤（照着做）

1. **读** `docs/Ph7/ISSUE.md` 的 P7-6…P7-11 与本文 §6。
2. **验 R1**：起测试客户端 → 让 CR 调一次 `get_state`，再让 SR 调一次 `get_state`，看 server 日志里 `session=` 是否不同。把结论写进 §6.9。
3. **建世界**：`ph7-env\client` 启动新超平坦（允许作弊/和平/生存），建好 §1.2 的场景。
4. **M0-1 取证**：6 个用例逐个跑，存 `evidence/m0-*.json`；P7-11 额外抓 `eat` 全返回。
5. **M0-2 修复**：先修 P7-11（按取证结论选"改 `startUseItem`"还是"服务器丢弃补偿"），再修 P7-6/7/9/10/17/19/20。
6. **M0-3 复测** + `run-all --slow`。
7. **M1-1/2/3** 按 §4/§5/§6 实现；M1-3 先写 `runtime/zff-interrupts.json` + 服务端引擎，再改 mod 推事件，最后命令方块测试。
8. 每步更新 §8；交付时把 `agent-prompt7.md` 的进度板改成实际状态。

---

## 10. 参考文档索引（`docs/Ph7/reference/`）

| 文件 | 用途 |
|---|---|
| `architechture.md` | 玩家原始架构设想（SR/AR/CR/ESRA 的由来） |
| `AGENT-ARCHITECTURE-ph5.md` | 子代理协作模型（Ph5 定稿） |
| `ARCHITECTURE-PH6.md` | Ph6 架构（CR store / chat hub / 团队） |
| `AIPlayer.md` / `AIPlayerManual.md` | 最早期 System Prompt 与手册（M1-1 参考） |
| `ZFF-MC-SYSTEM-PROMPT.md` | 当前 System Prompt 设计与约束（**M1-1 必读**） |
| `TOOLS.md` | 工具手册（99 个工具的语义/参数） |
| `agent-prompt6.md` | 上一轮任务书（本文的结构参照） |
| `HANDOVER-ph6.md` | 上一轮交接 |
| `INSTALL.md` | 安装/部署（含插件、zip、启动器） |
| `ISSUE-ph6.md` / `ISSUE-TOOLS-ph6.md` | 历史问题（别重复踩） |
| `ISSUE-ph7.md`（= `docs/Ph7/ISSUE.md`） | 本轮要修的 22 条 |
| `REPORT-ph7.md` | Ph7 全部实现记录（含 Ph7.8） |
| `PH6-PLUGIN-API.md` | DSH 插件 API 用法（路由/工具/槽位） |
| `M5-MEMORY-DESIGN.md` | 记忆设计（CR store vs 服务端记忆，后者已取消） |
| `CLIENT-ONLY-ANALYSIS.md` | 只装客户端方案的分析 |

---

## 11. 坑与约定（能省你半天）

1. **PowerShell 传含反引号/引号的字符串给 `node -e` 会被吃掉** → 复杂补丁一律写成 `.mjs` 文件再执行（本仓库 `scripts/` 里有大量例子）。
2. 文件是 **CRLF 还是 LF** 会影响字符串匹配：读文件时先 `.replace(/\r\n/g, '\n')`。
3. `zip` 里存了每个文件的 **mtime** → 内容不变、重建 hash 也会变；对比"是否真的改了"要比较**内容**（如 `SHA256SUMS` + 解压后 diff）。
4. `ZFF Bridge.jar` 只在**改了 Java** 后才会变；`build-release.mjs` 只复制 `mod-mdk/build/libs/*.jar`。
5. 改完 jar 必须**重启客户端**；改完插件必须**重启 DSH**；改 `client.js` 刷新页面即可。
6. `mcp__mc__*` 报 `unknown MCP session` = MCP server 被换过，**只能重启 DSH**；不是桥坏了。
7. `backend=local` 是 zmcp3 没装 mod 的**预期**，不要当 bug 查。
8. `关闭 MCP` = 结束游戏（运行开关置 ▶）；只想停手点 ⏸。
9. 测试客户端启动命令（参考）：
   `ph7-env\client` + `launch-ph7-client-clean.bat`（javaw、无 pause），或直接拼 jvm 参数；**不要**用 `start` 启动（长命令行会静默失败）。

---

## 12. 已确认的设计细节（2026-11-02 玩家回答）

| 项 | 结论 |
|---|---|
| 咖啡浆果 | 模组 **咖啡乐事** `coffee_delight`，方块 **`coffee_delight:coffee_bush`**，`age=0..3`，**成熟 = age 3** |
| 榨油 | 实测：**种子→油要 millstone（3×3 多方块，需 Mob 站上去磨）**；`oil_pot` 只接受 `kaleidoscope_cookery:oil`（右键放入、空手取出），没有"空桶装" |
| 阶段汇报 | **给子代理看的，不是给 CR 的**；CR 只对玩家在「完成 / 被挡 / 被问」时说话（详见 §4.2.1） |
| 其余 | Q1–Q9 已在 §3–§6 定稿，无需再问 |

> 唯一还要**实测**的是 §6.9 的 R1（CR 与子代理能否在 MCP 层区分）。

---

## 13. 交付清单（2026-11-02）

**改动的代码**

- 插件：`D:\mc\zff-mc\{index.js,client.js}`、`src/{mcp-control,mcp-env}.mjs`、`defaults/{tools,persona}.md`、`presets/zff-mc.patch.yml`
- MCP：`mcp-server/src/{server.js,mc-daemon.mjs}`、`src/lib/{chat-hub.mjs,interrupts.mjs(新)}`
- mod：`IssueTools.java`（P7-11）、`Containers.java`（P7-17）、`EventPush.java`（新）、`BridgeRuntime/BridgeConfig/Tools.java`
- 测试：`test/{zff-interrupts.mjs(新),m1-interrupt-live.mjs(新),run-all.mjs}`、`scripts/evidence-call.mjs(新)`

**证据**

- `evidence/m0-*.json` 10 份（6 个验收用例，含挤奶/三种吃/蛋糕/书架/咖啡/油锅）
- `evidence/m1-interrupts-live.json`（PASS：wait_events 早退、job 取消、fall stop_all）
- `evidence/m1-chat-live.json`、`evidence/m1-chat-dedupe.json`、`evidence/m1-r1-sessions.log`

**验证**

- `node test/run-all.mjs --slow` → `8/8 suites passed`
- 已知未做：M0b（P7-13/14/15/18/21/22）；M1-2 的 UI 视觉/交互由玩家亲自验收。


