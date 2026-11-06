# Ph7 ISSUE 清单

> 维护：DSH（开发侧）。所有条目来自**实际工具返回 / 插件日志 / 玩家实测**，原始文本尽量原样引用。
> 上一场（2026-11-01 15:15–15:30，`ph7-env` 新环境）的 P7-1…P7-5 保留在文末。
> 本场：**2026-11-01 22:00 – 11-02 00:05**，工作区 `D:\MC Agent Play2`，
> 服务器 `zmcp3.rhymc.com:1069`（没有装 mod），客户端 `DSHDeepSeek`（NeoForge 21.1.228 / MC 1.21.1，bridge 0.2.0）。

---

# 一、本场问题总览（按严重度）

| 编号 | 问题 | 层 | 严重度 | 状态 |
|---|---|---|---|---|
| P7-6 | 按 ▶ 后按钮闪一下 ⏸ 又立刻回 ▶（但续跑实际在跑） | 插件 UI | 🔴 高 | 已定位（待修） |
| P7-7 | `mcp__mc__*` 全部 `unknown MCP session`，只能重启 DSH | 插件/MCP | 🔴 高 | 已定位（设计缺口） |
| P7-11 | **使用物品族全部失效**（吃/挤奶/放料）——客户端重启前无法恢复 | mod | 🔴 高 | 待现场取证 |
| P7-9 | `chatPush` 连续 `ConnectException` → 聊天中断失效 | 部署 | 🔴 高 | 已定位（daemon 未起） |
| P7-8 | `backend=local` / `mcpbridge:rpc_request` 不可发送 | mod/服务器 | 🟡 预期行为 | 非 bug |
| P7-10 | `no_instance`（没有任何存活的游戏实例） | 插件 | 🟡 中 | 已定位（心跳过期） |
| P7-14 | `path_to`/`path_open` 高概率 `stalled` | mod/baritone | 🟡 中 | 待改进 |
| P7-15 | `walk_to` 91% 停住、上下台阶卡 | mod | 🟡 中 | 待改进 |
| P7-17 | `container_move` 光标持物时 quick 必失败；每次回传 90 槽 | mod | 🟡 中 | 待改进 |
| P7-19 | 聊天一条消息发 2–3 次 | mod/插件 | 🟡 中 | 待定位 |
| P7-20 | `mustReply` 对同一条旧消息反复要求回复 | MCP/插件 | 🟡 中 | 待修 |
| P7-13 | `dump_junk` 一次只腾 1 格、候选名单窄 | mod | 🟢 低 | 待改进 |
| P7-16 | FD 烹饪锅：必须先放碗，成品才到输出格 | 文档 | 🟢 低 | 已记录 |
| P7-18 | Keg 无 GUI、无反馈，无法确认奶是否进桶 | mod | 🟢 低 | 待设计 |
| P7-21 | 掉落物在 y69 台子，差 2 格高吸不到 | mod | 🟢 低 | 待改进 |
| P7-22 | `place_block` 目标格被蜂巢占位直接失败 | mod | 🟢 低 | 待改进 |
| P7-12 | "物品同步坏了"——实为背包 36/36 满 | — | — | **误判，已澄清** |

---

# 二、逐条分析

## P7-6【插件】按 ▶ 后按钮闪一下 ⏸ 又立刻回到 ▶

**玩家原话**：「我在按下开始键的时候，会短暂换到 ⏸，然后迅速暂停。有意思的是，轮询唤醒机制正常工作，AI 玩家也能进入游戏。」

**证据**

- 日志没有出现 `MCP 不可用：运行开关已自动切回 ▶`（全文 `自动切回` 计数 = 0）→ **不是** MCP 看门狗干的。
- 日志里出现**重复**的 ▶ 提示（同一秒两条）：
  ```
  14:12:55.602 ▶ 玩家点了「开始游戏」：续跑开关打开
  14:12:55.603 ▶ 已打开开关，但游戏还没进世界：进世界后再唤醒它
  14:12:55.604 ▶ 已打开开关，但游戏还没进世界：进世界后再唤醒它   ← 同一句话两次
  ```
  → 说明当时 `agents` 表里**不止一个** ZFF 会话对象。

**根因（高概率）**：运行开关是**按会话**读的（`src/run.mjs` 里的 `sessionId`）。但 HTTP 层每次都用 `pickAgent(null)`（"最近活跃的那个 agent"）来定位会话：

- `POST /api/zff-mc/run` 绑定的是 **A**；
- 800 ms 后页面 `refresh()` 再 `GET /api/zff-mc/run`，`pickAgent` 可能选了 **B**（子代理 / 另一个会话）；
- B 没按过 ▶ → 读出来是 `idle` → 按钮立刻翻回 ▶。

而插件内部 `tick` 是按**每个 agent 自己的** `st.run` 判断的，A 仍然是 `running` → 于是"UI 显示 ▶，但 AI 确实在跑"。

**影响**：玩家以为"没开始"，反复点；实际 AI 已经在跑，观感与状态不一致。

**修复方向**

1. `POST /run` 返回 `sessionId`；`GET /run` 支持 `?session=<id>`，页面把自己所属会话 id 带上，不再依赖 `pickAgent`。
2. `agents` 表按 **session id** 去重；子代理用 `isChildSession()` 排除，并补一条断言（现在只认 `header.parentSessionId`，宿主不填时会漏）。
3. ⏸/▶ 的注入循环同理，避免向同一个会话重复注入（日志里那两条重复就是证据）。

---

## P7-7【插件/MCP】`unknown MCP session`，所有游戏工具失效

**现象**：开场 `mcp__mc__*` 全部返回：
```
{"jsonrpc":"2.0","error":{"code":-32000,"message":"unknown MCP session"},"id":null}
```
重启客户端才恢复（其实真正需要重启的是 **DSH**，客户端重启只是顺手）。

**根因（已确认）**：MCP server 进程被换掉了（`关闭 MCP` / 进程崩溃 / 我这边测试杀过），而 DSH 的 `mc-control` 还拿着**上一个 server 实例**的 `mcp-session-id`。server 端返回 404 `unknown MCP session`，客户端不会自动重新 `initialize`。

**证据**：出问题时 `:57610/:57611/:57612` 三个端口监听数都是 0；重启 DSH 后立刻恢复。

**影响**：AI 只能干等，最后自己 `zff_pause`（本场 `zff-paused.json` 的 reason 就是"桥接断开…等玩家重启"）。

**修复方向**

1. 插件在 MCP 状态栏检测 `unknown MCP session` / 连接断了 → 标红 + 明确提示"**重启 DSH**"（而不是让 AI 猜）。
2. 提示词层面：`explainBlocked()` 增加该错误 → 人话映射，避免 AI 误判成"游戏桥坏了"。
3. 更根本：MCP server 生命周期应跟着 DSH 走（`--owner-heartbeat` 已在做），并且 `关闭 MCP` 后不要再被任何东西复用旧会话。

---

## P7-8【mod/服务器】`backend=local` 与 `mcpbridge:rpc_request 不可发送`

**现象**：
```json
"backend": {"backend":"local","state":"local",
 "reason":"channel not registered on this connection: Payload mcpbridge:rpc_request may not be sent to the server!"}
```

**结论：预期行为，不是 bug。** 服务器 `zmcp3.rhymc.com:1069` **没装 ZFF Bridge**，mod 的"服务端半边"（`ZffServer`，SavedData/`/zff`）不存在，所以：

- `zff_todo_*`、`/zff` 系列不可用（返回 `no_server_half`）；
- **但不影响** `get_state / walk / mine / container_*` 等客户端工具——它们走本地 HTTP 桥（60109），不经过这个通道。

**建议**：文档/提示词里明确"看到 backend=local 不要当成故障"；要让服务端共享待办可用，只能给服务器装同一个 jar。

---

## P7-9【部署】`chatPush` 连续失败 `ConnectException` → 玩家说话收不到

**现象**：`env-inbox.json`
```json
"chatPush": {"url":"http://127.0.0.1:57611/chat_event","failed":9,
             "lastError":"push failed: java.net.ConnectException"}
```

**根因（已确认）**：聊天守护进程 `mc-daemon.mjs`（57611）没在跑。它以前**没有任何人启动**；本轮已让插件在 `refreshEnv` 里 `ensureDaemon()`（`src/mcp-env.mjs`），`start-mcp.ps1` 也会拉。

**本场复发原因**：`关闭 MCP` 把 server + daemon 一起杀了，而当时 `mcpServer.autostart` 被我按"帮我杀掉"的要求设成了 `false` → 没有东西再把它们拉起来。

**修复方向**

1. `autostart` 已改回 `true`（本场已修）。
2. 「关闭 MCP」应同时把运行开关置 ▶（已做，`by:"mcp-stop"`），并在面板上把"关闭 MCP = 结束游戏"写清楚，避免误用。
3. `chatPush.failed` 连续增长时，插件应在状态栏告警（现在只在 env-inbox 里）。

---

## P7-10【插件】`no_instance`：没有任何存活的游戏实例

**现象**：日志
```
16:03:14 环境阻塞（no_instance）：没有找到要接管的游戏实例：没有任何存活的游戏实例；
         实例目录：C:\Users\ALIENWARE\AppData\Local\state\headlessmc-mcp\instances
```

**根因**：实例注册文件的**心跳有效期只有 20 s**（`EXPIRE_MS`）。客户端重启 / 退出 / 卡加载时，旧文件过期 → 就"没有任何存活的游戏实例"。这是准确的报错，不是 bug。

**但体验不好**：玩家不知道是"客户端没开"还是"客户端开着但心跳停了"。

**修复方向**：`explainBlocked('no_instance')` 里补上"最近 5 分钟见过的实例（即使已过期）+ 各自最后心跳时间"，并提示"如果是刚重启客户端，等它进世界即可"。

---

## P7-11【mod】**使用物品族整体失效**（最高优先级的功能 bug）

**现象**（Agent 实测 6+ 次）：

| 动作 | 工具 | 返回 | 实际 |
|---|---|---|---|
| 吃牛肉/柿子 | `eat` | `SUCCESS`，`consumed:false` | 进不了"进食中"，饱食度不涨 |
| 挤奶 | `use_block`/`entity_interact` | `SUCCESS` | 不产出奶桶 |
| 给 Keg 放料 | `use_block mode=item_on` | `SUCCESS` | 料不消耗 |

同时：**放置方块、挖掘、开箱子、合成、开门全部正常**。
唯一恢复方式：**重启客户端**。

**关键区分**：坏的是 `useItem`（右键用物品）这条路径；`useItemOn`（对方块/实体右键）、`startDestroyBlock` 都正常。

**根因候选（按可能性排序，需一次现场取证确认）**

1. **服务端拒绝了 `ServerboundUseItemPacket`**。`mc.gameMode.useItem()` 只做客户端预测，返回 `SUCCESS` 不代表服务器接受；1.21 的 use-item 包带 `sequence`，与 block-change ack 有耦合。若客户端 `sequence` 与服务器不同步，服务器会**静默丢弃** → 与"本地 SUCCESS、无效果、重启才好"完全吻合。
   - 验证：`eat` 返回里看 `using` / `useItemRemaining`。若 `using:false` 且 `useItemRemaining:0`，说明客户端都没进入 using（更偏 2/3）；若 `using:true` 但 `foodAfter==foodBefore`，就是服务器丢弃。
2. **挤奶用错了 API**：挤奶是"对**实体**右键"（`useOn` 牛），当前实现走的是对空气 `useItem` → 必然拿不到奶。这是**确定的工具缺陷**（可独立修）。
3. **界面遮挡**：背包/容器 GUI 开着时，原版 `Minecraft.startUseItem` 会直接返回；我们的实现绕过了它，行为和服务端预期不一致。
4. **客户端 `itemUseTimer`/`rightClickDelayTimer` 卡住**：只有重启能清 —— 与"重启才恢复"吻合，但需要代码里找是否有未复位的地方。

**建议修复（分批）**

- **立刻**：`eat`/`use_item` 增加**后置校验**：`countAfter`/`foodAfter` 没变 → 返回 `incomplete`（已有）+ `hint:"服务器未接受，请检查 sequence / 界面"`；挤奶改成"先 `entity_query` 找牛 → `entity_interact use`"。
- **下一步**：所有 `useItem` 前先 `if (mc.screen != null) mc.setScreen(null)`；并在 `useItem` 后**再发一次**（间隔 250 ms）作为补偿重试。
- **根治**：改用 `Minecraft.startUseItem()` / `startUseItemOn()` 而不是直接 `gameMode.*`，让原版把 `rightClickDelayTimer`、`sequence`、`ensureHasSentCarriedItem` 都走一遍。

---

## P7-12【非 bug】"物品同步坏了" —— 实为背包 36/36 满

**玩家自己纠正**：捡不起蜂蜡是因为背包满了；腾出空间后立刻进包。**记录在案，避免下次误判**。

**建议**：`collect_drops` / `inventory_slots` 在背包满时明确返回 `inventory_full:true` + 建议"先 dump_junk"，而不是让 AI 以为掉落物不存在。

---

## P7-13【mod】`dump_junk` 一次只腾 1 格、候选名单窄

**现象**：反复执行很多次才腾出空间。

**证据**（`DumpJob.java`）：候选是**硬编码白名单**，只有 18 种：
```
dirt, coarse_dirt, gravel, andesite, diorite, granite, tuff, snowball,
wheat_seeds, rooted_dirt, mud, clay_ball, cobblestone, cobbled_deepslate,
deepslate, stone, calcite, dripstone_block
```

**问题**：① 一次调用只丢到 `targetFree`（默认 1）；② 白名单外的垃圾（腐肉、树叶、多余种子、空瓶）永远丢不掉。

**建议**：`targetFree` 默认提高到 6；白名单补 `rotten_flesh / leaves / sapling / flint / stick / bowl（多余时）/ bucket（空）/ egg / string`；并支持 `dryRun:true` 先返回"打算丢什么"。

---

## P7-14【mod/baritone】`path_to` / `path_open` 高概率 `stalled`

**现象**：卡在 `(-4800, 1155)`、`z=1162` 的墙、栅栏柱；`allowedBreak/allowedPlace` 默认 false。

**根因**：`BaritoneControl` 明确把 `allowBreak/allowPlace` 设为 false（怕 baritone 乱挖），所以遇到墙/栅栏/需要垫脚的落差就无解 → `stalled`。

**建议**

1. `path_to` 增加参数 `allowBreak`/`allowPlace`（默认仍 false，由 AI 在"确认安全"时显式打开）。
2. `stalled` 时返回**卡住的原因**（例如 `blocked_by: minecraft:oak_fence`、`needs_jump`），AI 才能换方案（先 `mine_block` 拆栅栏，再走）。
3. 对"1 格高差"做**自动垫脚**：`place_block` 失败时（P7-22）改用 `walk + jump` 组合。

---

## P7-15【mod】`walk_to` 91% 停住、上下台阶卡

**现象**：`walk_to` 常在 91% 停住；`y67 → y73` 换层几乎必卡。

**根因（高概率）**：`WalkToJob` 的进度看门狗按"距离是否变小"判定，台阶/跳跃期间 60 tick 内距离可能不变 → 直接 `stalled`；跳跃只有 `jumpCooldown` 的单一策略，没处理"上 2 格以上需要先垫脚"。

**建议**

1. 进度判据改成"**最短距离**是否改善"（记录历史最小值），而不是"当前位置是否更近"。
2. 卡住时先试 `jump`，再试 `place_block`(脚下)，最后才 `stalled`，并把原因返回。
3. 文档里写明：`walk_to` 只适合**同层**短距离；跨层用 `path_to` 或显式 `dig`/`place`。

---

## P7-16【文档/工具】FD 烹饪锅：必须先放碗

**结论（Agent 实测，值得写进工具说明）**：

1. 结果格点击会被拒；
2. **必须先往第 7 格放一个碗（容器槽）**，成品才出现在第 8 格；
3. 再 `container_move mode=quick` 才能取出。

**建议**：把这条写进 `container_open`/`container_move` 的 tool description（或 `recipe_lookup` 的提示），并在容器里检测到 `farmersdelight:cooking_pot` 时直接返回 `hint`。

---

## P7-17【mod】容器工具两个问题

**(a) 光标上拿着物品时 `quick` 必失败**
根因：quick 走的是 shift-click（`QUICK_MOVE`），原版在"光标有物品"时会把它当作"放下"而不是"移动"。当前实现没有先清空光标。
**建议**：`container_move` 前检查 `menu.getCarried()`，非空先 `container_close`/`container_open` 或执行一次 `PICKUP` 放回；失败时返回 `carried:<item>` 供 AI 判断。

**(b) 每次回传整 90 格 + 背包，token 开销极大**
**建议**：`container_read` / `container_move` 默认只返回**非空槽 + 变化**（`changed:[{slot,from,to}]`），需要全量时用 `full:true`。粗略估算可省 80% token。

---

## P7-18【mod】Keg：放料/倒奶/取奶全部无反馈

**现象**：三种操作都"无反应"，也没有 GUI 可读，无法确认奶是否进桶。

**根因**：`use_block` 只回 `result`（客户端预测），Keg（`brewinandchewin:keg`）的液体状态在 **BlockEntity** 里，我们没有任何工具能读它。

**建议**：新增 `block_entity_read {x,y,z}`（只读 NBT 摘要，白名单字段），至少能读 `FluidTank`/`Tank` 类组件的液量与种类；顺带解决"放料到底进没进"的问题。

---

## P7-19【mod/插件】聊天一条消息被发 2–3 次

**现象**：玩家看到刷屏（敌人看不到，说明是客户端重复发送）。

**候选原因**

1. `chat_turn` 的 `message` 支持数组 → AI 一次传了 3 条相同内容（提示词没禁止重复）；
2. `chat` 工具与 `chat_turn` 都被调用（AI 先用 `chat` 再说一遍）；
3. mod 的 `ChatPush` 把 `self` 消息也推回队列 → 造成"自己看到自己说了两遍"（`self:11` 就是这个计数）。

**建议**：① 服务端对同文本做 2 s 去重（`recordSent` 里比对最近 5 条）；② `chat` 与 `chat_turn` 的 description 明确"只用一个"；③ `ChatPush` 不推自己发的行（或推了也标 `self:true` 且不显示）。

---

## P7-20【MCP】`mustReply` 对同一条旧消息反复要求回复

**现象**：同一条旧消息反复 `repeat:true` 要求回复。

**根因**：`chat-hub.mjs` 的 `unread` 条目有 `repeats` 字段，`mustReplyKeywords` 默认含 `鱼/?/？`；只要 `lastAck < seq` 且含关键词，就会被反复标记。

**建议**：`repeats` 上限 2；`chat_turn` 成功 drain 后**按 seq 记 ack**（不只是清 flag）；对 `mustReply` 的消息，AI 回复过一次就写 `repliedAt`，不再要求。

---

## P7-21【mod】掉落物在 y69 台子上，差 2 格吸不到

**现象**：玩家在 y67，物品在 y69 的平台，走不过去也吸不到（吸收半径 1 格）。

**建议**：`collect_drops` 支持"先 `tower`/`place_block` 垫高一格，再走"；或返回 `verticalGap` 提示 AI。当前工具没有垂直方向的处理。

---

## P7-22【mod】`place_block` 目标格被蜂巢占位直接失败

**现象**：想垫脚，目标格被蜂巢占用 → 直接失败，没有替代位置。

**建议**：`place_block` 失败时自动尝试"同高度的相邻 4 格"，返回 `tried:[...]`；实在不行返回 `blocked_by:<block id>`。

---

# 三、优先级与建议动作

**P0（先修，影响能不能玩）**

1. **P7-11 使用物品族**：先加后置校验 + 挤奶改走 `entity_interact`；再改成 `startUseItem` 路径。
2. **P7-7 `unknown MCP session`**：状态栏标红 + 明确提示"重启 DSH"；`explainBlocked` 映射人话。
3. **P7-6 ▶ 闪烁**：`POST /run` 返回 `sessionId`，`GET /run` 用它；`agents` 按 session 去重。

**P1（体验/成本）**

4. P7-9 daemon 存活告警；P7-10 过期实例提示；P7-19 聊天去重；P7-20 mustReply 限次；P7-17 容器返回瘦身。

**P2（策略/工具增强）**

5. P7-14/P7-15 寻路参数与失败原因；P7-13 dump_junk；P7-18 block_entity_read；P7-21/P7-22 垂直与占位处理。

---

# 四、需要一次现场取证才能定论的

| 问题 | 需要的证据 |
|---|---|
| P7-11 是否服务器丢弃 | 一次 `eat` 的完整 JSON（`using/useItemRemaining/before/after`）+ 同时刻服务器日志 |
| P7-19 聊天重复 | 同一分钟的 `chat.sent` 记录 + mod 日志 `ChatPush` 行数 |
| P7-6 会话错位 | 一次点击前后的 `/api/zff-mc/run` 返回（含 `sessionId`） |

---

# 五、本场**正常**的部分（避免重复排查）

- 桥接本体：`/instances` → `port 60109, DSHDeepSeek, inWorld:true, NeoForge 1.21.1, bridge 0.2.0`。
- 方块类工具：放置、挖掘、开箱、合成、开门全部正常。
- 已完成物资：番茄酱 1、南瓜籽 8、糖 2、蜂蜡 2、蘑菇 16、碗 50、麦子 13、铁 62。
- 蜂房橡木门 + 8 个活板门已全部关闭（Keg 在 `(-4756,67,1111)`）。

---

# 附：上一场（15:15–15:30，`ph7-env`）的问题，保留归档

## P7-1【环境】单机世界默认没有作弊，`/give`、`/setblock`、`/tp` 全部静默失败

- **现象**：`command` 工具返回 `{"ok":true,"sent":true}`，但 `chat_history` 里是
  `Unknown or incomplete command`，`block_query` 仍是 `minecraft:air`。
- **原因**：世界来自专用服务器（`level.dat` 的 `allowCommands=0`），单机里没有 op 权限。
- **绕法**（已验证）：`Esc → Open to LAN → Allow Commands: ON`。
- **建议**：`command` 返回里带 `executed:false` 之类的信号，或在 `get_state` 里暴露 `allowCommands`。

## P7-2【整合包】`kaleidoscope_tavern:bar_stool` / `sofa` 这类"颜色变体"没有无前缀 id

- **建议**：提供 `block_exists(id)` / `block_variants(name)`。

## P7-3【游戏/整合包】启动期 mixin 警告与慢启动

- 493 条 warning/exception + `Game took 73.9 seconds to start`，属整合包噪音，**不影响**进世界。

## P7-4【世界】复制专用服务器存档后首次 `--quickPlaySingleplayer` 停在主菜单

- **原因**：首次启动的 Narrator/Accessibility 引导屏挡住了 quickPlay。
- **建议**：预写 `options.txt` 的 `narrator:0` 并在自动化里先点掉引导。

## P7-5【工具】`container_move` 的参数名与文档直觉不一致

- **现象**：按 `{from:{…},to:{…}}` 传参 → `MCP error -32602: Required at fromSlot`。
- **正确用法**：`{fromSlot:<index>, toSlot:<index>, count:<n>}`。
- **建议**：schema 同时接受两种形式，或改名 `fromIndex/toIndex` 并在描述里写清楚。
