# Ph6 ISSUE 记录

> **状态（2026-11-01，Ph6 收尾）**：ISSUE-2 / 3 / 4 / 5 / 8 / 11 / 12 / 13 / 15 已修并做游戏内验证，
> ISSUE-1 / 10 由架构解决（CR + `chat_turn` + 续跑），ISSUE-9 靠插件/宿主重连，ISSUE-6 只做了保守重试。
> 逐条结论与验证见 [`REPORT-ISSUES.md`](REPORT-ISSUES.md)，真机证据 `evidence/ph6-m2-issues-live.json`。
> 下面的原文保持不动，作为"当时发生了什么"的第一手记录。

> 记录人：DSHDeepSeek（游戏内 AI 玩家）· 2026-10-31 · 依据：本场游戏实测 + 玩家 FYNyesterday 的聊天反馈
> 服务器：`zmcp3.rhymc.com:1069`（3 人在线：`DSHDeepSeek` / `FYNyesterday` / `ItaIicHarbor737`（西瓜））
> 会话时间线：day 496 世界出生点 → 主人 TP 回主城 → 分到小屋并设出生点 → 农活 / 咖啡农场 → 夜里全员睡觉卡住

## 玩家反馈原文（按时间）

| 时间 | 玩家（FYNyesterday）原话 | 指向的问题 |
|---|---|---|
| 21:29 | 「继续游戏，不要停下来，有什么问题再聊天窗报告」+「Chatter子代理反应不及时」 | Chatter 不回复 / 不主动说话 |
| 21:52 | 「你先去看，我也帮你看看」 | 主代理在游戏里做决定太慢 |
| 22:11 | 「西瓜在外面碰不到床怎么办？」 | 3 人服夜晚跳过需要全员睡，但西瓜没有床 |
| 22:14 | 「没事，我们等你起来」 | 睡觉后主代理卡住，只能等天亮 |
| 22:14 | 「在D:/mc/MCP development/docs/ph6目录下创建一个ISSUE.md用来记录各种错误」 | 本文档 |
| 22:0x | 「在睡觉期间应该保持监听Chatter」 | 睡觉也要保持聊天监听 |

---

## ISSUE-1（严重）Chatter 子代理不会自动回复玩家的消息

### 现象
- 玩家在游戏里发消息，AI 玩家没有任何自动回应；玩家原话「Chatter子代理反应不及时」。
- 只有主代理（DSH 模型）手动轮询 `chat_history` 才会回话，一旦主代理在跑长任务/睡觉，聊天就断了。

### 证据
1. `runtime/agent/events.jsonl` **从未出现** `chat_smalltalk` / `chat_task` 事件；只有 SR 空闲时的
   `task_request` → CR 回的 `chat_reply {"reason":"idle_waiting","text":"waiting for instructions"}`。
   也就是说 CR 从未真正处理过任何一条玩家消息。
2. `runtime/agent-chat.out.log` 最后一行停在 `20:45`，之后再没有任何 CR 进程在跑（`run.mjs chat` 已退出）。
3. `zff_status` 返回 `"crEnabled": false`——但这是 **mod 侧 SavedData 的开关**（`ZffConfig.java:23`），
   跟 Node 侧 CR 的 `ZFF_CR` 环境变量是两套东西，互不影响。

### 根因（4 条，逐层）
1. **根本没启动**：`cr.mjs:72` 里 `if (!this.enabled) { ...; return this; }` —— 只有 `ZFF_CR=1`
   （或 `run.mjs chat` 的 `crReply:true`）才会执行到 `cr.mjs:76` 的 `setInterval(poll)`。
   本场会话从头到尾没有这个进程，所以"没有反应"不是慢，是**没人在听**。
2. **就算启动也不是大模型**：`cr.mjs:96-111` 的 `handleMessage` 只有两条路：
   - 命中 `TASK_WORDS`（`cr.mjs:14`，含单字「去/挖/回/来/打…」）→ 回一句固定文案
     `收到：<原文>（已记进任务清单，等主代理来执行）`；
   - 否则 → `smallTalk()`（`cr.mjs:127-132`）把 `AIPlayer.md`「性格」一节的**第一行原样**回出去。
   所以即使开着，它也只是复读，无法理解"早上好""这集神了"这类消息。
3. **事件没接上**：`sr.mjs:61` 定义了 `onChatReply()`，但 `Scheduler.start()`（`sr.mjs:21-30`）里
   **没有** `bus.on('chat_reply', ...)`，`chat_task` 也没人订阅。→ CR 判出来的任务进不了 TodoList，
   SR 永远收不到。
4. **两套嘴**：主代理（DSH）自己在聊天；再起 `run.mjs chat` 会出现第二套 CR 同时发言，
   还会往 TodoList 里塞任务，和主代理直接调工具打架。设计上必须二选一，或让 CR 只做"待回复队列"。

### 影响
- 玩家对着 AI 说话没人应；主代理一忙（挖矿/寻路/睡觉）就掉线式沉默。
- 玩家说的「在睡觉期间应该保持监听Chatter」正是这条的后果：睡觉期间主代理无法行动，但**必须继续听聊天**。

### 修复建议
1. **主代理侧兜底**：把 `chat_history` 轮询做成常驻循环（2 s/次，参考 `Chatter.poll`），
   睡觉、卡界面、长任务期间都不停。
2. **CR 接真模型**：把 CR 改成"把新消息丢给 LLM（或用主代理的模型）生成一句 ≤50 字回复"，
   而不是复读性格行；最小改动是让 CR 把消息发到事件总线，由主代理回复。
3. **修订阅**：`sr.start()` 加 `this.bus.on('chat_reply', (e) => this.onChatReply(e))`（以及 `chat_task`）。
4. **`lastSeenTs` 初始化**：`cr.mjs:42` 现在从 0 开始，第一次 poll 会把 buffer 里 20 条旧消息
   全部重放一遍（可能对旧消息乱回复）；应初始化为启动时刻。
5. **任务识别**：`TASK_WORDS` 里的单字「回/来/去/打」误判率极高（「我们先回家睡觉吧」会被当成任务）；
   建议改成整词/意图判断，或交给 LLM。
6. **两个开关统一**：`zff_status.crEnabled` 应该反映 Node CR 的真实状态（或至少文档写清楚是 mod 侧）。

---

## ISSUE-2（严重）睡觉后无法起床，主代理整夜空转

### 现象
- `use_block` 点床 → 成功入睡（`screen = InBedChatScreen`），但之后**没有任何工具能让人起来**。
- `stop_all` 返回 `screenClosed:false`；`disconnect` 无效；`walk` / `jump` 也不唤醒。
- 只能等天亮，或等**所有**玩家都睡（本服 3 人：`1/3 → 2/3 joueurs endormis`，西瓜在野外没有床，凑不齐 3/3）。

### 证据
- 玩家原话：「西瓜在外面碰不到床怎么办？」「没事，我们等你起来」。
- 代码检索：`mod/src/main/java/dev/mcp/bridge/*.java` 里**没有** `stopSleeping` / `InBed` 相关实现。
- 时间线：22:11 躺下 → 22:14 起不来 → 22:19 仍在床上（服务端时间 day 499 夜）。

### 根因
- bridge 只实现了"上床"（右键床的方块交互），没有实现"下床"（`ServerboundPlayerCommandPacket(STOP_SLEEPING)`）。
- 睡觉状态是**服务端**状态，不是客户端 screen，所以 `mc.setScreen(null)` 关不掉。

### 修复建议
1. bridge 加 `wake` 工具：发 `ServerboundPlayerCommandPacket(ACTION_STOP_SLEEPING)`，并回读 `isSleeping`。
2. 或者加 `sleep` 工具带"睡 N tick 后自动起床"的语义。
3. 服务器侧：给每个玩家一张床（西瓜没有床 → 晚上跳不过去）；或允许 `sleep.skipping` 插件式 `/time set day`。
4. **睡觉期间主代理仍要监听聊天**（见 ISSUE-1 建议 1），别整夜静默。

---

## ISSUE-3（中）无法吃东西，饱食度只掉不涨

### 现象
- `throw_item {item:"minecraft:bread"}` 返回 `SUCCESS`，但面包不减少、food 不变（12/20 → 12/20）。
- `kaleidoscope_cookery:cooked_rice` 同样无效。
- 只有**蛋糕方块**能吃：`use_block` 右键蛋糕 → `bites+1`、food +2。

### 根因
- `ItemActions.throwItem()` 只调一次 `mc.gameMode.useItem(player, MAIN_HAND)`，
  等于"按下右键"的瞬间；进食需要客户端持续 `isUsingItem` 32 tick，bridge 没有保持这个状态，
  也没有 `releaseUsingItem` 的收尾。
- 蛋糕走的是方块交互（`useBlock`），所以不受影响。

### 追加实测（2026-10-31 22:24）
- 连发两次 `throw_item`（间隔 700 ms）**也不行**：`salmon` 数量不变、food 5 不变。
- `minecraft:salmon`（原版可生吃）、`minecraft:bread`、`kaleidoscope_cookery:cooked_rice` 全部无效。
- 结论：不是"某个食物的问题"，是桥接层没有实现完整的"使用物品"状态机。
- 现场对策：**只吃蛋糕方块**；没蛋糕就找 op `/give cake`；饱食度低时少走动（走动消耗饱食度）。

### 修复建议
- 加 `eat` 工具：`useItem` 后连续 tick/保持到 `getUseDuration` 结束再 `releaseUsingItem`，回读 food 变化。
- 或 `throw_item` 支持 `holdTicks` 参数。

---

## ISSUE-4（中）`place_block` 只认快捷栏

### 现象
- 放木桶报错：`unsupported: no matching block item in the hotbar`（物品在主背包 35 格）。
- 修法：`inventory_slots` 找 `invSlot` → `inventory_swap {fromSlot, hotbarSlot}` → 再 `place_block`。

### 建议
- `place_block` 内部自动做 hotbar 交换（找物品 → swap → 放回），而不是要求调用方手动搬。

## ISSUE-5（中）`path_open` 会顺手开箱子

### 现象
- 寻路到点后，`get_state` 里常常挂着 `ContainerScreen (ChestMenu)`：`path_open` 沿路右键开箱/木桶。
- 本场 `doorsOpened` 最高 17；每次都得补一个 `container_close`。

### 建议
- `path_open` 只对 `_door`/`fence_gate` 发交互，或加 `openContainers: false` 开关。
- 寻路结束后自动 `container_close`。

## ISSUE-6（中）主城小屋北门永远 blocked

### 现象
- 小屋门 `/setblock` 在 `-4809,73,1157`（`spruce_door open=true`），但 `path_to`/`walk` 西向永远 `blocked`。
- 南门 `-4809,73,1165` 正常。判断为门打开后的碰撞板 + 门框 1 格通道，0.3 格对不齐就卡住。

### 建议
- `walk` 支持"对齐到方块中心"或加 `sneak`；寻路把 1 格门当成 2 格宽处理。

## ISSUE-7（低）`walk` 的 yaw 是"世界朝向"，不是玩家 yaw

- 实测映射：`0`→+Z（南）、`90`→-X（西）、`180`→-Z（北）、`270`→+X（东）。
- 速度：走 ≈0.07 格/tick，疾跑 ≈0.28 格/tick。
- 建议：文档写清楚（手册里没写），或直接改成语义化参数 `north/south/east/west`。

## ISSUE-8（低）`scan_surface` 不含非整格方块

- 门、活板门、甜浆果/咖啡丛都不在 `surface` 里，`doors` 字段恒为空（实测 25 次扫描全空）。
- 找门/找作物只能用 `block_query` 网格 + `properties.age`。
- 建议：`doors` 字段修好；或加 `includeNonFull:true`。

## ISSUE-9（低）`mcp__mc__*` 原生工具掉线后无法自愈

- DSH 的 mc MCP client 20:04 与服务端断开，`reconnect.maxAttempts:10` 用完后不再尝试；
  本次只能改用 `scripts/tool.mjs` 走 daemon CLI（功能等价，但输出是文本、截图要落盘再读）。
- 建议：给 MCP client 加"无限重连+指数退避"，或在 DSH 侧提供手动重连入口（热改 patch 也行）。

## ISSUE-10（严重）"KeepListening" 只监听不回复 —— 缺回复闭环

### 现象
- 玩家 22:18 连发两条：「明天你想是什么」「明天你想吃什么」，代理在监听循环里**看到了却没有回**。
- 玩家原话：「你一直KeepListening但是你不回消息呀」。

### 根因
- `scripts/watch.mjs`（本次临时写的监听脚本）只把新消息 `console.log` 出来，**没有调用 `chat` 回话**；
  而主代理在那一轮里把注意力放在"等天亮"，没有把"新消息→回复"接成一个动作。
- 本质上还是 ISSUE-1 的第 2 条：CR 不会生成回复；主代理又没被强制要求"看到就回"。

### 影响
- 玩家觉得 AI 不理人；监听越多越显得"dead inside"。

### 修复建议
1. 监听循环内部直接接回复：新玩家消息 → 生成一句（CR/主代理）→ `chat` 发出去 → 记录已回。
2. 加"未回复消息"计数：`seen - replied > 0` 且超过 N 秒就强制回一轮（看门狗）。
3. 主代理行为准则（已写进 `AIPlayer.md` 与 `zff_memory: rules.sleep`）：**听到玩家说话，必须在一轮内回话**。

## ISSUE-11（中）篝火/营火交互 `unsupported`

- 现象：把 `extradelight:green_coffee` 放到营火（campfire）上烘烤，`use_block` 对营火返回
  `unsupported`；贴到 1.9 格、换正面/顶面都一样。
- 影响：咖啡馆没法用营火做"咖啡烘焙台"，只能跑回厨房用烟熏炉。
- 推测：`Actions.useBlock` 只处理"方块状态交互"（门/按钮），营火的
  `useItemOn`（把食物插到营火上）走的是另一条逻辑，bridge 没实现。
- 建议：`use_block` 增加 `mode:"item_on"`，直接调 `gameMode.useItemOn` + `InteractionHand`，
  或给 bridge 加一个 `cook_on` 工具。

## ISSUE-12（中）`entity_query` 大半径静默返回空

- 现象：找牛时 `entity_query {radius:64}` 和 `{radius:128}` 都返回 `entities: []`，
  换成 `{radius:32}` 立刻能查到 3 头牛。多次复现（不只是牛，羊/鸡也一样）。
- 影响：找动物、找实体多花了很多时间；自动化脚本会被误导以为"没有"。
- 推测：大半径查询超时 → 上层 catch 后返回空数组而不是报错；或者服务端区块没加载。
- 建议：超时时返回 `error:"timeout"` 而不是空列表；或分块扫描（32 格为一圈）。

## ISSUE-13（低）营火/可堆叠方块放置需先切空手

- 烘干架（`extradelight:drying_rack`）取货必须**空手 + 下蹲右键**（玩家实测；见玩家提示
  「要下蹲右键取下来」）。bridge 没有 sneak 参数，`use_block` 只能"放"，取不下来。
- 建议：`use_block` 增加 `sneak:true` 参数（发 `ServerboundPlayerCommandPacket` 的 START_SNEAKING
  + `useItemOn` + STOP_SNEAKING）。

## ISSUE-14（高）农夫乐事锅（cooking_pot）成品取不出来

- 复现：营火 + `farmersdelight:cooking_pot` 煮 `extradelight:ground_coffee`，
  3~4 秒后成品 `extradelight:coffee` 出现在锅的成品槽（GUI `slots[6]`，count=4），
  但 `container_move {fromSlot:6, mode:"quick"}` / `mode:"take"` 都返回
  `ok:true, verified:false`，成品**纹丝不动**；空手右键锅只会打开 GUI。
- 影响：咖啡做出来了但拿不到，整条咖啡线卡在最后 1 厘米。
- 推测：`vectorwing.farmersdelight` 的 `CookingPotMenu.quickMoveStack` 对成品槽有自定义
  逻辑（要配合"盛菜容器"），bridge 的 quickMove/drag 模拟不触发它。
- 建议：`container_move` 支持"点击槽位 + 点击目标槽"的真实鼠标模拟（PICKUP 两次），
  或对 `CookingPotMenu` 特判：`slots[6]` 直接 `PICKUP_ALL` + 放进玩家背包空格。
- **玩家给的正确答案（2026-10-31 15:48）**：`extradelight:coffee` 是**饮品**，要从锅里取必须
  用**玻璃瓶**（空瓶 + 右键锅 → 变成咖啡瓶），shift 点击成品槽对饮品无效。
  所以这不是 bridge 的 bug，是"饮品需要容器"的机制；bridge 缺的是
  "手上容器 + 右键方块槽位"这条交互路径。
- 用户侧临时解法：玩家自己 shift 点击成品槽拿出来给我（对**非饮品**成品有效）。

## ISSUE-15（高）没有"阅读"能力：书面/告示牌的书、牌子读不了

- 复现：玩家 `FYNyesterday` 给了一本 `minecraft:written_book`（材料清单）。
  `throw_item` 能打开（screen = `BookViewScreen`），但：
  - `screenshot` 只截 3D 世界画面，**不含 GUI 覆盖层**，所以书本文字截不到；
  - `get_screen` 在这个 daemon 里不存在（`Tool get_screen not found`）；
  - `container_read` / `inventory_slots` 只返回物品 id/count，**没有 NBT/文本**。
- 影响：玩家用书面方式给任务/清单时，agent 完全读不到，只能请玩家改用聊天口述。
- 建议（按优先级）：
  1. `inventory_slots`/新增 `item_nbt` 工具返回手持物品的 `components`（written_book 的
     `minecraft:written_book_content`：title/author/pages 纯文本）；
  2. 新增 `read_sign`（告示牌 front/back text）与 `read_book {slot}`；
  3. `screenshot {withGui:true}` 让 GUI 层进入截图，视觉模型就能直接读字。
- 用户侧临时方案（2026-11-01 00:12 玩家指示）：把书先还给他，改聊天口述。

## 附：睡觉策略（玩家 FYNyesterday 2026-10-31 明确要求）

> 「只有在服务器只剩下你没睡叫的时候你才躺到床上」

- 本服 3 人，夜晚跳过需要 **3/3 都睡**；谁在外头没有床（本场的西瓜）就永远凑不齐。
- 所以：**别人醒着时不许睡**；夜里有事做事（巡逻/整理/装修/聊天），等系统提示 `2/3 joueurs endormis`
  且只剩自己没睡时才上床。
- 配合 ISSUE-2：上床后没有 `wake`，一旦先睡就只能干等天亮，代价很大。

---

## 附：本次为绕过问题新增的工具（可直接复用）

| 脚本 | 用途 |
|---|---|
| `scripts/tool.mjs <tool> [payload.json] [timeout]` | 走 daemon 调任意 bridge 工具；UTF-8 安全、输出转义 |
| `scripts/waitjob.mjs <jobId>` | 轮询 job 到终态，打印进度/结果 |
| `scripts/me.mjs` | 一行摘要：坐标/血量/饱食/界面/时间/背包 |
| `scripts/bridges.mjs` | 列出所有 live bridge 实例（含 gameDir，不打印 token） |
| `scripts/coffee.mjs` | 收成熟咖啡丛（网格 block_query + use_block + 拾取） |
| `scripts/houses.mjs` / `scripts/map.mjs` / `scripts/findmat.mjs` | 城市测绘 / 材料检索（基于 `runtime/storage-inventory.json`） |

## 附：本次会话里观察到、但还没定位的行为

- `path_open` 偶尔把终点判成 `distance ~3` 就返回 `done`，但角色实际停在 1~2 格外（`left=0.7`），
  需要补一次短 `walk` 才能贴到目标。
- 吃蛋糕时 `bites` 会从 4 → 5 跳变（一次 `use_block` 有时只加 1 口，有时 2 口）。
- 睡觉后 `get_state` 的 `position.y` 仍是站立高度（73.56），但 `screen` 是 `InBedChatScreen`——
  说明 bridge 对"睡觉"状态缺少明确字段（建议 `player.sleeping: true/false`）。

---

# Ph7 追加（2026-11-01 15:15–15:30）：本轮 scope 内、但**未完善**的地方

> 按玩家要求：这里只做**分析**，本轮不修。环境 = `D:\mc\MCP Development\ph7-env`（从 release 重建）。
> 证据：`evidence/ph7-blocks.json`（24 个方块）、`evidence/ph7-freezer.json`（冰柜 GUI + 塞物品）、
> `runtime/shots/ph7-*.png`。

## P6-16【插件·环境自举】端口被别的会话占用时，只能"报告"，不能"自动换端口"

- **现状**：`mcpServer.port` 固定 57610；插件发现 `GET /instances` 的 `workspace` 与本会话工作目录不同时，
  按新加的守卫报 `server_workspace_mismatch` 并停止续跑（**行为正确**，比拿错目录的 runtime 强）。
- **未完善**：玩家此时没有"一键解决"的路径——要么手动杀 server，要么手动改端口。
- **可能的做法**：按 workspace 的短哈希派生端口（57610 + hash%50），并让 profile 里的 `mc-control`
  用 `!!js` 表达式算同一个端口；或插件自动 `kill` 自己 spawn 的旧 server（只能杀自己起的，别人的不能动）。
- **风险**：端口漂移会让"手动起 server"的用户再次对不上；需要先定"谁拥有 server"的策略。

## P6-17【插件·环境自举】解压后没有校验 zip 完整性，也不支持升级

- **现状**：只要 `<工作目录>` 里有 `ZFF MCP Server.zip` 就解压；已有 `zff-mcp-server/` 时**不再看 zip**。
- **未完善**：玩家换了新版 zip（比如 0.2.0）却还留着旧目录 → 插件继续用旧目录，不会提示。
- **可能的做法**：解压后在 `zff-mcp-server/.zff-version.json` 写 `{sha256, at}`；每次 tick 比对 zip 的 sha256，
  不一致时提示"检测到新 zip，是否覆盖解压"（覆盖需要先停 server）。

## P6-18【插件·实例选择】`username` 依赖"已进世界"，菜单态实例会被判成 `unknown_username`

- **现状**：`/info` 的 `player` 只在 `BridgeRuntime.playerName != null` 时出现（进世界后才有）；
  菜单里的实例 → `username: null` → 选择逻辑报 `unknown_username` 并停。
- **影响**：玩家先开 DSH 再开游戏（或重登）时，会看到"无法确认身份"的提示，得等进了世界才好。
- **可能的做法**：mod 在 `ClientPlayerNetworkEvent.LoggingIn` 之前就把 `Minecraft.getInstance().getUser().getName()`
  写进注册文件（离线名在登录前就已知）。**这是 mod 改动**，需要重新构建 jar（本轮没有 gradle 工程，见 P6-19）。

## P6-19【工程】mod 的构建工程已丢失，`release/ZFF Bridge.jar` 只能沿用旧产物

- **现状**：`D:\mc\MCP Development\mod` 下只有 `src/`，没有 `build.gradle`/`gradlew`；
  全盘（`D:\mc` 深度 3）也找不到 gradle 工程。`mod\build\libs` 不存在。
- **影响**：任何 mod 侧改动（比如 P6-18）都无法重新产出 jar；本次 release 用的是
  `client-test/mods/mcpbridge-0.1.0.jar`（sha256 `1d2531af…`，比源码新 5 秒，确认是最新产物）。
- **建议**：把 NeoForge MDK 工程补回仓库（`mod/build.gradle` + `gradle/wrapper`），
  并在 `scripts/build-release.mjs` 里改成"先 `gradlew build`，再拷 jar"，否则开源后别人无法复现。

## P6-20【桥】`use_block` 的"成功"只代表**发包成功**，不代表方块真的响应了

- **现状**：`use_block` 返回 `attempts: ["use_item_on(north):PASS","use_item:PASS"]` — 这是**客户端发包结果**。
  24 个方块里只有冰柜真的开了 GUI（`FreezerMenu`），其余都是"点了但本轮没观察到状态变化"。
- **未完善**：缺少"这次交互到底产生了什么"的判定（GUI 打开 / 方块属性变化 / 方块实体 NBT 变化 / 聊天栏提示）。
- **可能的做法**：`use_block` 返回里带上 `screenBefore/screenAfter`、`blockBefore/blockAfter`，
  并给一个 `effective: true|false`（GUI 打开或属性变化才 true）。对 LLM 来说，"点了没反应"与"点失败"必须能区分。

## P6-21【桥】容器类方块与普通方块的入口不统一

- **现状**：冰柜走 `use_block` 打开 GUI 后 `container_read` 可用；但 `container_open` 是另一条路
  （`container_read` 在没开菜单时直接报 `no container menu is open`）。
- **未完善**：同一个"打开容器"动作有两套入口，LLM 容易选错（本轮 P7-5 的 `container_move` 参数名也是同类问题）。
- **可能的做法**：`container_open {x,y,z}` 内部先 `use_block`，失败再报错；`container_read` 在未开菜单时
  自动尝试打开最近的容器。
