# Ph5 需求规格（细化版）

来源：`requirement.original.md`（原始需求，30 行）+ Ph4 实现现状。本文把每一条需求变成**可测的接口与验收**，
标注当前状态：`[已实现]` / `[部分]` / `[待实现]` / `[不优先(*) ]`。工具名以 `mcp-server/src/server.js`
注册的 74 个 MCP 工具为准。

---

## A. MCP 基础（原始需求 MCP 1-3）

### A1 非阻塞工具与超时
每条工具调用必须有超时，长任务返回 `jobId` 而不是阻塞等待。

- 约定：
  - 同步工具（`get_state`、`block_query`、`scan_*`、`container_*`、`use_block`、`chat` 等）在 schema 里带
    `timeoutSeconds`（默认 5-10 s）。
  - 异步工具（`mine_block`、`dig_*`、`branch_mine`、`walk_to`、`path_to`、`tower`、`craft`、`collect_drops`、
    `delete_item`、`eye_throw`、`boat_sail`、`dig_sealed_shaft` …）立即返回
    `{jobId, tool, state}`；用 `job_status {jobId}` 轮询，`job_cancel {jobId}` 取消。
  - job 对象字段：`jobId/tool/state(queued|running|done|failed|cancelled)/progress/metrics/reason/error/result/
    createdAt/updatedAt/deadline/timeoutSeconds`。
- 验收：任一异步工具在 200 ms 内返回 jobId；`job_status` 能给出 `state` 与 `metrics`；超时后必须是
  `failed` + `timeout`，不能静默挂住。
- 状态：`[已实现]`（Jobs.java + server.js）。注意：`job_status` 轮询间隔建议 300-500 ms。

### A2 用户中断 = 取消任务
用户停止助手时，正在运行的游戏任务必须停止。

- 触发：MCP 请求被取消（`notifications/cancelled`）、MCP 会话 SSE 流关闭、或 daemon 的 watch TTL 到期。
- 验收：见 `evidence/p1-11-abort.json`、`evidence/daemon-abort.json`。
- 状态：`[已实现]`（MCP 层 + daemon `/watch`）。

### A3 记忆文件
用文件记录目标/坐标/结论，跨会话可读。

- 工具：`memory_write {name, content, mode}`、`memory_read {name}`、`memory_list`；目录 `<workspace>/.mcp-agent`
  （当前 workspace = `D:\mc`）。
- 验收：写入后 `memory_list` 可见；重启 MCP 服务器后仍可读。
- 状态：`[已实现]`。

### A4 桥接到有头客户端
MCP 客户端连到一个**正在运行的、有窗口的**整合包客户端，不额外启动无头客户端。

- 机制：mod 在客户端内开 loopback HTTP（随机端口 + Bearer token），写实例文件
  `%LOCALAPPDATA%\state\headlessmc-mcp\instances\<uuid>.json`（port/token/heartbeatAt）；MCP 服务器发现实例并固定它。
- 心跳：约 5 s 一次；超过 45 s 视为失效。
- 验收：客户端重启后 MCP 工具仍可用（自动重连）；`mc_instances` 能看到实例。
- 状态：`[已实现]`。

---

## B. 寻路（原始需求：寻路模块）

### B1 Baritone 寻路
- 工具：`path_to {x,y,z,range,allowBreak,allowPlace,timeoutSeconds}` → job；`path_status`、`path_cancel`；
  `set_pathing_policy {break,place}` 设置默认策略（默认都不允许）。
- 行为要求：
  - 到达 `range` 内算成功并立即 `BaritoneControl.cancel()`；
  - 60 tick 内位移 < 1 格 → `failed` + `stalled`（进度看门狗）；
  - 卡住且前方是植物（见 D2）时，用 BlockMiner 清掉植物后重新寻路；
  - 不破坏建筑方块，不放置方块（除非策略允许）。
- 验收：`ph4-p0-stalled.json`（3.7 s 内 stalled）+ 长距离寻路（TestA 中 1000+ 格可达）。
- 状态：`[已实现]`。

### B2 短距直线走路
- 工具：`walk_to {x,z,range,maxTicks,allowBreak}`：朝目标直线走、自动跳 1 格台阶、卡住 4 tick 清植物、
  60 tick 无进展 `no_progress`；`walk {yaw,ticks,jump,sprint,up}` 用于精确移动（跳出、上浮）。
- 状态：`[已实现]`。

---

## C. 交互逻辑（原始需求 交互 1-7）

### C1 容器交互（全部容器类型）
- 工具：`container_open {x,y,z}` → 菜单信息；`container_read`；`container_move {fromSlot,mode}`；
  `container_close`；`loot_chest {x,y,z,slots?}`（自动取走全部/指定槽）；`villager_trades`、`trade_select`。
- 要求：
  - 支持原版箱子/木桶/末影箱/熔炉/漏斗/发射器，以及模组容器（Create 的库存、抽屉等只要走 vanilla Menu 即可）；
  - **绝不本地改 `ItemStack`**（会 desync）；所有搬运走菜单点击；
  - 读回验证：`container_move` 后重新 `container_read` 确认槽位变化。
- 验收：开箱→取走一组物品→复查槽位为空；放入同理；末影箱跨维度同一背包。
- 状态：`[已实现]`（container_* / loot_chest / villager_*）。

### C2 破坏/放置方块 + 工具/武器
- 工具：`mine_block {positions,timeoutSeconds}`、`place_block {x,y,z,item}`、`use_block {x,y,z,face}`、
  `break_entity {entityId|uuid|type}`。
- 要求：
  - 只走预测路径（`startDestroyBlock/continueDestroyBlock`），**禁止** `gameMode.destroyBlock`（幽灵方块）；
  - 方块算"已破坏"必须满足：状态改变且保持 3 tick（`Mining.CONFIRM_TICKS`）；
  - 自动选最优工具（含耐久检查：<10 且方块有价值 → `tool_low`；有备用健康工具就换）；
  - 无工具可挖的方块（不需要正确工具）允许空手；
  - 武器：`entity_interact {action:attack}` 攻击实体。
- 验收：`ph4-p0-tool-low.json`、`ph4-p0-tunnel.json`（0 幽灵）。
- 状态：`[已实现]`。

### C3 工作台 + JEI 配方
- 工具：`craft {item,count,table?:[x,y,z],timeoutSeconds}`、`recipe_lookup {output|input}`。
- 要求：
  - 3x3 配方必须靠近工作台（4 格内）；2x2 走背包网格；
  - 配方查询优先 `RecipeManager`，找不到再查 JEI 虚拟配方（整合包自带 JEI）；
  - 缺料返回 `unsupported` + "missing ingredient for the recipe, made N"；
  - 合成后校验产物数量。
- 验收：无工作台时合成木板/木棍；有工作台时合成镐/船；`recipe_lookup` 能列出某物品的配方。
- 状态：`[已实现]`。

### C4 生物交互
- 工具：`entity_query {radius,type}`、`entity_interact {entityId|uuid,action:use|attack|trade,itemSlot}`、
  `villager_trades`、`trade_select`、`break_entity`。
- 要求：村民交易（列出交易→选择→确认）、喂食动物（右键手持食物）、攻击、上船/上矿车等。
- 验收：与村民完成一次交易；对牛右键喂小麦。
- 状态：`[部分]`：交易已实现，喂食走 `entity_interact use`（未专门验收）。

### C5 门/按钮/拉杆
- 工具：`use_block {x,y,z,face}`（右键方块）。
- 要求：门、活板门、栅栏门开/关；按钮按下；拉杆切换；`scan_connected` 把门/活板门当可通行。
- 验收：`ph4-p1-scan-connected.json`（门连通两室）。
- 状态：`[已实现]`。

### C6 (*) Carry On 抱起
- 状态：`[待实现(*) ]`。需要配置文件白名单 + `entity_interact` 特殊模式。

### C7 (*) 机械动力交互
- 状态：`[部分(*) ]`：机械方块可用 `use_block` 右键；缺"机械状态查询"（转速/应力/物品流）。

---

## D. 游戏环境感知（原始需求 感知 1-5）

### D1 聊天列表 / 指令 / 发消息
- 工具：`chat {message}`（以 `/` 开头自动当指令）、`chat_history {limit}`、`command {command}`。
- 验收：发送消息出现在 `chat_history`；执行 `/time set day` 生效。
- 状态：`[已实现]`。

### D2 周围/面前方块（含状态）
- 工具：`block_query {positions|center+radius}`（每次 ≤64 个位置）、`collision_query`、
  `scan_connected {radius}`（连通空间表面，不透视）、`scan_surface {radius}`、`scan_area`（调试/X-ray，勿用于决策）。
- 要求：
  - 返回 id、air、replaceable、requiresTool、hardness、collision（AABB 列表）、interaction、fluid、光照、方块状态 properties；
  - `scan_connected`：从眼睛 flood fill 可通行格（空气/植物/水/门/栅栏门/活板门均视为可通行），返回可达空气格 +
    相邻实心方块表面；**墙后的房间不可见**（不做 X 光）。
- 验收：`ph4-p1-scan-connected.json`（有门→两室可见；无门→墙后不可见）。
- 状态：`[已实现]`。

### D3 实体类型/位置
- 工具：`entity_query {radius,type}`：返回 type/id/uuid/name/坐标/距离/AABB/alive，掉落物附 item{id,count}。
- 验收：能列出附近掉落物、友好/敌对生物、船/矿车/展示框。
- 状态：`[已实现]`。

### D4 碰撞箱与交互体积
- 工具：`collision_query {positions|entityId}`：方块碰撞 AABB、实体 AABB、交互体积（方块 interaction shape）。
- 验收：对门/台阶/栅栏返回非满方块 AABB；对实体返回其 AABB。
- 状态：`[已实现]`（`block_query` 也内嵌 collision/interaction）。

### D5 时间感知
- 现状：`get_state` 只给 position/health/food 等，**没有时间/天气**。
- 要求：`get_state` 增加 `world.time`（dayTime、day、isDay、moonPhase）、`weather`（rain/thunder）、`dimension`；
  或新增 `time_status`。
- 验收：`/time set day` 后 `isDay=true`；下雨时 `weather.rain=true`。
- 状态：`[待实现]`。

---

## E. 水下/海底作业（Ph5 新增，源于 TestA）

背景：TestA 的强堡在 (0,0)，位于海底下方（0,0 处 y=62→34 全是水，32 沙，30 以下石头）。当前工具集无法水下作业。

### E1 密封竖井
- 工具：`dig_sealed_shaft {level,timeoutSeconds}` → job：每挖一格就用圆石/泥土把头顶上方封住，防止水灌入；
  拒绝岩浆、拒绝下方是空洞。
- 验收：从海底 y=33 挖到 y=20（13 格），全程 `air > 150`、无水进入、`dry=true`。
- 状态：`[已实现，未验收]`（`SealShaftJob.java` + 工具已注册）。

### E2 水呼吸 / 上浮策略
- 要求：任务检测 `air < 100` 时自动上浮换气；有水下呼吸药水/导管时自动使用；metrics 报 `air` 曲线。
- 状态：`[待实现]`。

### E3 排水工作区
- 要求：海绵/沙封住 3x3 区域后抽干，5x5x3 空间变干可走。
- 状态：`[待实现]`。

### E4 水下挖掘/寻路
- 要求：`dig_tunnel` 允许两端密封的水下隧道；Baritone 水中段上报 `swim` 段。
- 状态：`[待实现]`。

---

## F. 非功能要求

### F1 稳定性
- 重启客户端前必须先 `save_world`（`clean-start.ps1` / `stop-all.ps1` 已内置），否则世界回档到上次自动存档。
- 重启后玩家位置/背包保持。
- 验收：`evidence/fix-state3.json`。
- 状态：`[已实现]`。

### F2 开发环境
- NeoForge 1.21.1 重度机械症整合包（源码可能被魔改）；工作目录 `D:\mc\MCP Development`。
- Java 源码 **UTF-8 无 BOM**；PowerShell 写中文路径的脚本必须带 BOM。
- 客户端：`D:\SoftWare\PCL 正式版 2.12.2\PCL\DeepSeekSmoke.bat`（世界 "Smoke Test"，存档 `Nouveau monde (1)`）。
- 状态：`[已实现]`。

---

## G. 工具清单（当前 74 个，见 `TOOLS.md`）

分组：状态/扫描（get_state, block_query, scan_area, scan_connected, scan_surface, collision_query, light_map, ore_log,
ghosts, chat_history, memory_*）｜移动（walk_to, walk, path_to, path_status, path_cancel, set_pathing_policy, path_open）｜
挖掘（mine_block, dig_shaft, dig_sealed_shaft, dig_tunnel, branch_mine, tower, mlg_water）｜交互（place_block, use_block,
container_*, entity_interact, villager_trades, trade_select, break_entity, collect_drops, delete_item, dump_junk/drop_junk）｜
物品（craft, recipe_lookup, inventory_*, hotbar, select_slot）｜特殊（eye_throw, throw_item, track_entity, place_boat,
boat_sail, triangulate, save_world, screenshot, batch, parallel, wait_events, watch_damage, damage_log, auto_respawn）｜
管理（stop_all, cancel_active, job_status, job_cancel, resync, mc_instances, mc_attach, bridge_info）。

验收：每个工具在 `TOOLS.md` 里有一行说明 + 参数 + 返回摘要 + 超时。
