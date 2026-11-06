# Ph5 - MCP tool reference (generated)

Generated from `mcp-server/src/server.js` (84 tools). Regenerate with `node scripts/gen-tools-doc.mjs`.

Conventions:
- sync tools take `timeoutSeconds` and answer immediately;
- job tools return `{jobId, tool, state}`; poll with `job_status {jobId}`, cancel with `job_cancel {jobId}`;
- coordinates are integer block coordinates unless stated otherwise;
- every job reports `metrics` (tool specific) and `reason`/`error` on failure.

| # | Tool | Purpose |
|---|---|---|
| 1 | `get_state` | 读取玩家/世界/背包快照。compact=true（默认）只保留关键字段以加快响应；compact=false 返回完整数据。 |
| 2 | `scan_area` | 【调试/X-ray，勿用于游戏决策】按球/立方体暴力扫描方块（能看穿墙）。正常探索请用 scan_connected。radius 1..32；返回 truncated/bytes。 |
| 3 | `scan_connected` | 连通空间扫描（P1-1）：从玩家所在空腔做 flood fill，只返回能走到的空腔表面方块；门/栅栏门/活板门不阻断区域，实心墙后面的东西看不见。radius 1..64。 |
| 4 | `scan_surface` | 表面扫描：从眼睛对可达开放空间做 flood fill，只返回贴着开放空间的方块（surface/heightMap/pits/doors/containers）。mode=connected\|los\|fov。 |
| 5 | `batch` | 把一串 mine/place/use 操作打包成任务顺序执行，中途不查询状态；整批完成后一次性回读校验（verifiedOk/unverified）。 |
| 6 | `watch_damage` | 等待下一次生命值下降，返回伤害量、原因（fall / mob:<type> / fire / drown / starve 等）和位置。 |
| 7 | `damage_log` | 最近的受伤记录（默认 10 条，含原因、伤害量、前后血量、位置）。 |
| 8 | `mlg_water` | MLG 落地水：从边缘走出去，在离地 3 格内把水放在脚下，落地后报告下落高度。需要快捷栏有水桶。 |
| 9 | `select_slot` | 选择快捷栏槽位（0-8），切换手持物品。 |
| 10 | `dump_junk` | 前往附近丢垃圾：整组丢出后立刻跑开，避免掉落物被吸回来。target=想要的空格数。 |
| 11 | `drop_junk` | [DEPRECATED since Ph4: use delete_item (server-synced, verified)] 背包满时丢弃垃圾方块（泥土/砂砾/安山岩等），直到有 target 个空位。 |
| 12 | `delete_item` | 服务端同步删除物品（快捷栏 SWAP + DROP_ALL_ITEMS + 校验；本地背包不被修改）。给 item 或 slot(0..35)，run 为丢完跑开距离。 |
| 13 | `ore_log` | 矿石探针流：每次挖掉一个方块都会探测它的 6 个邻居，这里返回最近发现的矿石坐标。 |
| 14 | `ghosts` | 幽灵方块/客户端-服务端不同步事件（position_jump / mined_revert / invisible_wall）。check=true 时现场检测卡在空气里的情况。 |
| 15 | `walk_to` | 短距离直线行走：朝 (x,z) 移动、自动跳 1 格台阶、无进展立即报 no_progress。 |
| 16 | `tower` | 跳起并在脚下放方块，连续搭高 blocks 格；自动选择快捷栏里的方块。 |
| 17 | `walk` | 按世界朝向走一小段（真实客户端移动），可 jump 跳下、sprint 疾跑、up 上浮/爬台阶。 |
| 18 | `craft` | 一键合成：默认用配方书放置（3x3 自动找附近工作台），配方没解锁时自动改用 JEI 式逐格放料；manual 可强制走 JEI 式。 |
| 19 | `wait_events` | 阻塞等待游戏事件（聊天/受伤/死亡/复活），返回自上次调用以来的事件和 cursor；不用轮询。 |
| 20 | `loot_chest` | 打开一个箱子/容器，把前 27 个槽的整叠物品全部 shift-click 拿走后关闭，返回拿到的物品列表。 |
| 21 | `auto_respawn` | 查看/设置死亡自动复活（默认开启）；返回死亡次数和最后一次死亡信息。 |
| 22 | `parallel` | 并行执行 2–4 个短查询（如 scan_area/get_state/entity_query），用于边走边观察；返回每个调用的结果。 |
| 23 | `block_query` | 读取指定方块的状态、碰撞箱和交互箱；positions 为 [x,y,z] 列表，最多 64 个。 |
| 24 | `entity_query` | 查询客户端已跟踪的实体，含掉落物、生物和其他玩家。 |
| 25 | `collision_query` | 读取指定方块的碰撞体积和交互体积 AABB 列表。 |
| 26 | `chat` | 读取最近聊天；给出 message 时发送聊天或 /命令。不会把 bridge token 放进聊天。 |
| 27 | `chat_history` | 只读最近聊天记录。 |
| 28 | `command` | 发送服务器命令（可带或不带前导斜杠）。返回 known 表示命令是否在当前客户端命令树中。 |
| 29 | `recipe_lookup` | 按输出/输入物品查询运行时配方（RecipeManager + JEI 虚拟配方兜底）。不硬编码配方。 |
| 30 | `path_to` | 异步 Baritone 寻路到 (x,y,z)；range>0 时使用 GoalNear。立即返回 jobId；用 path_status/path_cancel。 |
| 31 | `set_pathing_policy` | Set whether pathing may break/place blocks (P1-10; default both false). No args returns the current policy. |
| 32 | `path_open` | 带开门寻路的 path_to：走到目标点的同时自动打开挡住路线的门/栅栏门/活板门（村庄农田场景）。 |
| 33 | `path_status` | 查询 path_to 任务状态；包含 distanceLeft/currentGoal/etaTicks。 |
| 34 | `path_cancel` | 取消 path_to 任务并立即停止 Baritone。 |
| 35 | `mine_block` | 异步挖掘单个方块或 positions 列表；progress 带 blocksMined/total。立即返回 jobId。 |
| 36 | `place_block` | 异步放置方块到 (x,y,z)；可指定 face 或 item。立即返回 jobId。 |
| 37 | `use_block` | 右键使用门/按钮/拉杆等方块；返回使用前后的方块状态。 |
| 38 | `entity_interact` | 右键/攻击实体；action=trade 打开村民交易菜单。 |
| 39 | `stop_all` | 全局停止：取消所有 job、停 Baritone、清空调度器、关闭界面。收到“停”先调它。 |
| 40 | `cancel_active` | P1-11：取消由 MCP 启动且仍在运行的所有 job（用户中断/关闭会话时服务端也会自动调用）。 |
| 41 | `break_entity` | 破坏实体（船/矿车/展示框/画/盔甲架）直到移除并报告掉落；不要用 mine_block 处理这些。 |
| 42 | `collect_drops` | 走向附近掉落物并捡起，直到半径内没有物品或超时；捡不到时用 path_to 走到掉落物所在高度再试。 |
| 43 | `dig_shaft` | 安全向下挖：mode=stair（默认 45° 阶梯）或 shaft（1x2 竖井）；先探水/岩浆，拒绝 1x1 直下。 |
| 44 | `dig_sealed_shaft` | Ph5 P5-1：密封竖井——每挖一格就用圆石/泥土把头顶上方封住，海水/含水层不会灌进来；拒绝岩浆、拒绝下方有空洞。用于海底/水下作业。 |
| 45 | `branch_mine` | 鱼骨挖矿：主巷 1x2 + 每 spacing 格一条支巷（挖完退回主巷），自动插火把、探水/岩浆。 |
| 46 | `dig_tunnel` | 实心岩石隧道 A->B（P2-1）：沿直线挖 1 宽 2 高走廊，每 8 格插火把，逐块验证（无幽灵方块），可自动沿隧道走回起点。from/to 为 [x,y,z]，坡度超过 45° 会拒绝。 |
| 47 | `throw_item` | 右键投掷/使用手里的物品（如末影之眼），返回生成的投射物实体 id。 |
| 48 | `eye_throw` | 末影之眼协议（P1-7）：投一颗眼，前 4 tick 抓到投射物，追踪到落点并报方位角；掉落的眼会走回去捡起来。一次只投一颗，禁止原地连投。 |
| 49 | `track_entity` | 异步采样实体轨迹 N tick，返回 dx/dy/dz、水平方位 yaw 和 vertical=up/down/flat。 |
| 50 | `place_boat` | 在指定的水面方块上放下船，返回船实体 id（等实体真正生成后再返回）。 |
| 51 | `boat_sail` | 上船并驶向 (x,z)，到达 range 内自动下船。 |
| 52 | `light_map` | 扫描脚下可行走区域的光照：返回 darkCells 数量、最暗方块光、黑暗格子列表。 |
| 53 | `torch_at` | 在 (x,y,z) 放一支火把（自动找相邻实心方块吸附），需要快捷栏有火把。 |
| 54 | `triangulate` | 纯计算：多条 (x,z,yaw) 射线求交点，用于末影之眼三角定位。射线夹角可以很小（远距离目标），质量按夹角和交点在两条射线前方判断。 |
| 55 | `inventory_swap` | 通过玩家背包菜单把主背包槽位与快捷栏槽位交换（服务端同步），用于把镐子/方块移到快捷栏。 |
| 56 | `hotbar` | 读取快捷栏 9 个槽位的物品（快速检查手上的工具/方块）。 |
| 57 | `inventory_slots` | List all 36 inventory slots with invSlot/menuSlot indices, item id, count and tool durability. |
| 58 | `save_world` | 保存世界（发送 /save-all）。重启客户端前必须先调用，否则强制结束进程会丢失上次自动存档之后的进度（位置/方块）。 |
| 59 | `resync` | 强制清理客户端状态：关界面、取消所有 job、停 Baritone，并返回最新背包快照（真正的服务端同步需要重登）。 |
| 60 | `container_open` | 右键打开方块容器，并回读菜单与槽位。同步，默认 10 秒。 |
| 61 | `container_read` | 读取当前打开的容器菜单所有槽位；标记哪些是玩家背包槽。 |
| 62 | `container_move` | 点击容器槽位并回读验证。mode=quick/take/put/split/move；fromSlot 是菜单槽索引。 |
| 63 | `container_close` | 关闭当前容器菜单。 |
| 64 | `villager_trades` | 读取当前村民菜单的报价（付款 A/B、结果、uses、maxUses、是否售罄）。 |
| 65 | `trade_select` | 选择并执行第 index 个村民交易，times 为次数；返回 uses 前后对比。 |
| 66 | `screenshot` | 在渲染线程抓取真实画面，返回 PNG 路径；inline=true 时同时返回图片内容（供视觉模型）。 |
| 67 | `job_status` | 查询任意异步任务的完整状态：state/progress/metrics/reason/error/result。 |
| 68 | `job_cancel` | 取消任意异步任务；若已终止则返回当前状态。 |
| 69 | `zff_status` | 服务端状态（来自 mod 的 SavedData）：dist、watchdog 配置、actorId、memory/todo 数量、rev、在线玩家。 |
| 70 | `zff_memory_set` | 写入服务端共享记忆（键值，值可放 JSON 字符串）；跨客户端/跨会话保留。 |
| 71 | `zff_memory_get` | 读取服务端共享记忆的一个键。 |
| 72 | `zff_memory_list` | 列出服务端共享记忆（可按 tag 过滤）。 |
| 73 | `zff_memory_forget` | 删除服务端共享记忆的一个键。 |
| 74 | `zff_todo_add` | 把任务加到服务端 TodoList（所有 AI 共享同一份清单）。spec 可是字符串或 {tool,args}/{steps}。 |
| 75 | `zff_todo_list` | 列出服务端 TodoList 的全部任务（id/status/priority/spec/jobId/actorId）。 |
| 76 | `zff_todo_done` | 把服务端 TodoList 的一个任务标记为 done。 |
| 77 | `zff_todo_update` | 更新服务端 TodoList 任务的状态和备注（例如 running / cancelled）。 |
| 78 | `zff_todo_clear` | 清空服务端 TodoList。 |
| 79 | `memory_read` | 读取 <workspace>/.mcp-agent 下的记忆文件（最大 30 KB 文本）。 |
| 80 | `memory_write` | 原子写入/追加记忆文件；内容上限 256 KiB；用于记录目标、坐标和验证结果。 |
| 81 | `memory_list` | 列出 <workspace>/.mcp-agent 下的记忆文件和大小。 |
| 82 | `mc_instances` | 列出实例心跳文件；默认只返回 20 秒内仍存活的实例。不会返回 token。 |
| 83 | `mc_attach` | 手动按实例文件或 port+tokenFile 附加客户端；不会接受内联 token。 |
| 84 | `bridge_info` | 读取当前桥接客户端的信息（版本、加载器、MC 版本、玩家、维度）；不含 token。 |

## Schemas

### `get_state`

读取玩家/世界/背包快照。compact=true（默认）只保留关键字段以加快响应；compact=false 返回完整数据。

```js
{ compact: z.boolean().optional(), timeoutSeconds: t, }
```

### `scan_area`

【调试/X-ray，勿用于游戏决策】按球/立方体暴力扫描方块（能看穿墙）。正常探索请用 scan_connected。radius 1..32；返回 truncated/bytes。

```js
{ center: z.array(z.number().int()).length(3).optional(), x: optCoord, y: optCoord, z: optCoord, radius: z.number().int().min(1).max(32).optional(), shape: z.enum(['sphere', 'cube']).optional(), filter: z.string().optional(), includeAir: z.boolean().optional(), includeEntities: z.boolean().optional(), includePropert...
```

### `scan_connected`

连通空间扫描（P1-1）：从玩家所在空腔做 flood fill，只返回能走到的空腔表面方块；门/栅栏门/活板门不阻断区域，实心墙后面的东西看不见。radius 1..64。

```js
{ center: z.array(z.number().int()).length(3).optional(), radius: z.number().int().min(1).max(64).optional(), heightRadius: z.number().int().min(1).max(32).optional(), maxSurface: z.number().int().min(32).max(8000).optional(), maxVisitedCells: z.number().int().min(1000).max(2000000).optional(), timeBudgetMs: z.numbe...
```

### `scan_surface`

表面扫描：从眼睛对可达开放空间做 flood fill，只返回贴着开放空间的方块（surface/heightMap/pits/doors/containers）。mode=connected|los|fov。

```js
{ center: z.array(z.number().int()).length(3).optional(), radius: z.number().int().min(1).max(64).optional(), mode: z.enum(['connected', 'los', 'fov']).optional(), fovDegrees: z.number().int().min(10).max(180).optional(), faceSamples: z.number().int().min(1).max(9).optional(), maxRaySteps: z.number().int().min(8).ma...
```

### `batch`

把一串 mine/place/use 操作打包成任务顺序执行，中途不查询状态；整批完成后一次性回读校验（verifiedOk/unverified）。

```js
{ ops: z.array(z.object({ op: z.enum(['place', 'mine', 'use']), x: z.number().int(), y: z.number().int(), z: z.number().int(), face: z.string().optional(), item: z.string().optional(), })).min(1).max(2048), timeoutSeconds: t, }
```

### `watch_damage`

等待下一次生命值下降，返回伤害量、原因（fall / mob:<type> / fire / drown / starve 等）和位置。

```js
{ timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `damage_log`

最近的受伤记录（默认 10 条，含原因、伤害量、前后血量、位置）。

```js
{ limit: z.number().int().min(1).max(40).optional(), }
```

### `mlg_water`

MLG 落地水：从边缘走出去，在离地 3 格内把水放在脚下，落地后报告下落高度。需要快捷栏有水桶。

```js
{ yaw: z.number().optional(), maxTicks: z.number().int().min(20).max(600).optional(), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `select_slot`

选择快捷栏槽位（0-8），切换手持物品。

```js
{ slot: z.number().int().min(0).max(8), }
```

### `dump_junk`

前往附近丢垃圾：整组丢出后立刻跑开，避免掉落物被吸回来。target=想要的空格数。

```js
{ target: z.number().int().min(1).max(20).optional(), run: z.number().int().min(3).max(12).optional(), all: z.boolean().optional(), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `drop_junk`

[DEPRECATED since Ph4: use delete_item (server-synced, verified)] 背包满时丢弃垃圾方块（泥土/砂砾/安山岩等），直到有 target 个空位。

```js
{ target: z.number().int().min(1).max(20).optional(), }
```

### `delete_item`

服务端同步删除物品（快捷栏 SWAP + DROP_ALL_ITEMS + 校验；本地背包不被修改）。给 item 或 slot(0..35)，run 为丢完跑开距离。

```js
{ item: z.string().optional(), slot: z.number().int().min(0).max(35).optional(), run: z.number().int().min(0).max(12).optional(), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `ore_log`

矿石探针流：每次挖掉一个方块都会探测它的 6 个邻居，这里返回最近发现的矿石坐标。

```js
{ limit: z.number().int().min(1).max(100).optional(), }
```

### `ghosts`

幽灵方块/客户端-服务端不同步事件（position_jump / mined_revert / invisible_wall）。check=true 时现场检测卡在空气里的情况。

```js
{ limit: z.number().int().min(1).max(100).optional(), check: z.boolean().optional(), }
```

### `walk_to`

短距离直线行走：朝 (x,z) 移动、自动跳 1 格台阶、无进展立即报 no_progress。

```js
{ x: z.number(), z: z.number(), range: z.number().min(0.5).max(16).optional(), maxTicks: z.number().int().min(10).max(1200).optional(), allowBreak: z.boolean().optional(), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `tower`

跳起并在脚下放方块，连续搭高 blocks 格；自动选择快捷栏里的方块。

```js
{ blocks: z.number().int().min(1).max(64).optional(), item: z.string().optional(), timeoutSeconds: t, }
```

### `walk`

按世界朝向走一小段（真实客户端移动），可 jump 跳下、sprint 疾跑、up 上浮/爬台阶。

```js
{ yaw: z.number().optional(), ticks: z.number().int().min(1).max(400).optional(), jump: z.boolean().optional(), sprint: z.boolean().optional(), up: z.boolean().optional(), timeoutSeconds: t, }
```

### `craft`

一键合成：默认用配方书放置（3x3 自动找附近工作台），配方没解锁时自动改用 JEI 式逐格放料；manual 可强制走 JEI 式。

```js
{ item: z.string(), count: z.number().int().min(1).max(64).optional(), table: z.array(z.number().int()).length(3).optional(), manual: z.boolean().optional(), timeoutSeconds: t, }
```

### `wait_events`

阻塞等待游戏事件（聊天/受伤/死亡/复活），返回自上次调用以来的事件和 cursor；不用轮询。

```js
{ timeoutSeconds: z.number().int().min(0).max(120).optional(), limit: z.number().int().min(1).max(100).optional(), since: z.number().int().optional(), }
```

### `loot_chest`

打开一个箱子/容器，把前 27 个槽的整叠物品全部 shift-click 拿走后关闭，返回拿到的物品列表。

```js
{ x: coord, y: coord, z: coord, timeoutSeconds: t, }
```

### `auto_respawn`

查看/设置死亡自动复活（默认开启）；返回死亡次数和最后一次死亡信息。

```js
{ enabled: z.boolean().optional(), }
```

### `parallel`

并行执行 2–4 个短查询（如 scan_area/get_state/entity_query），用于边走边观察；返回每个调用的结果。

```js
{ calls: z.array(z.object({ tool: z.string(), args: z.record(z.any()).optional() })).min(2).max(4), }
```

### `block_query`

读取指定方块的状态、碰撞箱和交互箱；positions 为 [x,y,z] 列表，最多 64 个。

```js
{ positions: z.array(z.array(z.number().int()).length(3)).max(64).optional(), x: optCoord, y: optCoord, z: optCoord, timeoutSeconds: t, }
```

### `entity_query`

查询客户端已跟踪的实体，含掉落物、生物和其他玩家。

```js
{ radius: z.number().int().min(1).max(64).optional(), type: z.string().optional(), name: z.string().optional(), limit: z.number().int().min(1).max(200).optional(), timeoutSeconds: t, }
```

### `collision_query`

读取指定方块的碰撞体积和交互体积 AABB 列表。

```js
{ positions: z.array(z.array(z.number().int()).length(3)).max(64).optional(), x: optCoord, y: optCoord, z: optCoord, timeoutSeconds: t, }
```

### `chat`

读取最近聊天；给出 message 时发送聊天或 /命令。不会把 bridge token 放进聊天。

```js
{ message: z.string().max(256).optional(), limit: z.number().int().min(1).max(200).optional(), timeoutSeconds: t, }
```

### `chat_history`

只读最近聊天记录。

```js
{ limit: z.number().int().min(1).max(200).optional(), timeoutSeconds: t, }
```

### `command`

发送服务器命令（可带或不带前导斜杠）。返回 known 表示命令是否在当前客户端命令树中。

```js
{ command: z.string().min(1).max(256), timeoutSeconds: t, }
```

### `recipe_lookup`

按输出/输入物品查询运行时配方（RecipeManager + JEI 虚拟配方兜底）。不硬编码配方。

```js
{ query: z.string().min(1).max(200), kind: z.enum(['output', 'input', 'any']).optional(), limit: z.number().int().min(1).max(80).optional(), timeoutSeconds: t, }
```

### `path_to`

异步 Baritone 寻路到 (x,y,z)；range>0 时使用 GoalNear。立即返回 jobId；用 path_status/path_cancel。

```js
{ x: coord, y: coord, z: coord, range: z.number().int().min(0).max(64).optional(), allowBreak: z.boolean().optional(), allowPlace: z.boolean().optional(), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `set_pathing_policy`

Set whether pathing may break/place blocks (P1-10; default both false). No args returns the current policy.

```js
{ break: z.boolean().optional(), place: z.boolean().optional(), }
```

### `path_open`

带开门寻路的 path_to：走到目标点的同时自动打开挡住路线的门/栅栏门/活板门（村庄农田场景）。

```js
{ x: coord, y: coord, z: coord, range: z.number().int().min(0).max(64).optional(), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `path_status`

查询 path_to 任务状态；包含 distanceLeft/currentGoal/etaTicks。

```js
{ jobId: z.string().min(1), }
```

### `path_cancel`

取消 path_to 任务并立即停止 Baritone。

```js
{ jobId: z.string().min(1), }
```

### `mine_block`

异步挖掘单个方块或 positions 列表；progress 带 blocksMined/total。立即返回 jobId。

```js
{ x: optCoord, y: optCoord, z: optCoord, positions: z.array(z.array(z.number().int()).length(3)).max(64).optional(), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `place_block`

异步放置方块到 (x,y,z)；可指定 face 或 item。立即返回 jobId。

```js
{ x: coord, y: coord, z: coord, face: z.enum(['up', 'down', 'north', 'south', 'east', 'west']).optional(), item: z.string().optional(), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `use_block`

右键使用门/按钮/拉杆等方块；返回使用前后的方块状态。

```js
{ x: coord, y: coord, z: coord, face: z.enum(['up', 'down', 'north', 'south', 'east', 'west']).optional(), timeoutSeconds: t, }
```

### `entity_interact`

右键/攻击实体；action=trade 打开村民交易菜单。

```js
{ entityId: z.number().int().optional(), uuid: z.string().optional(), action: z.enum(['use', 'attack', 'trade']).optional(), itemSlot: z.number().int().min(0).max(8).optional(), timeoutSeconds: t, }
```

### `stop_all`

全局停止：取消所有 job、停 Baritone、清空调度器、关闭界面。收到“停”先调它。

```js
{ reason: z.string().max(120).optional(), timeoutSeconds: t, }
```

### `cancel_active`

P1-11：取消由 MCP 启动且仍在运行的所有 job（用户中断/关闭会话时服务端也会自动调用）。

```js
{}
```

### `break_entity`

破坏实体（船/矿车/展示框/画/盔甲架）直到移除并报告掉落；不要用 mine_block 处理这些。

```js
{ entityId: z.number().int().optional(), uuid: z.string().optional(), type: z.string().optional(), radius: z.number().min(1).max(32).optional(), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `collect_drops`

走向附近掉落物并捡起，直到半径内没有物品或超时；捡不到时用 path_to 走到掉落物所在高度再试。

```js
{ radius: z.number().min(1).max(16).optional(), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `dig_shaft`

安全向下挖：mode=stair（默认 45° 阶梯）或 shaft（1x2 竖井）；先探水/岩浆，拒绝 1x1 直下。

```js
{ level: z.number().int().min(-64).max(320), mode: z.enum(['stair', 'shaft', 'up', 'down']).optional(), yaw: z.number().optional(), maxBlocks: z.number().int().min(8).max(20000).optional(), timeoutSeconds: z.number().int().min(1).max(1200).optional(), }
```

### `dig_sealed_shaft`

Ph5 P5-1：密封竖井——每挖一格就用圆石/泥土把头顶上方封住，海水/含水层不会灌进来；拒绝岩浆、拒绝下方有空洞。用于海底/水下作业。

```js
{ level: z.number().int().min(-64).max(320), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `branch_mine`

鱼骨挖矿：主巷 1x2 + 每 spacing 格一条支巷（挖完退回主巷），自动插火把、探水/岩浆。

```js
{ corridorLength: z.number().int().min(4).max(256).optional(), spacing: z.number().int().min(2).max(8).optional(), branchLength: z.number().int().min(2).max(128).optional(), branches: z.number().int().min(0).max(64).optional(), yaw: z.number().optional(), side: z.enum(['left', 'right']).optional(), maxBlocks: z.numb...
```

### `dig_tunnel`

实心岩石隧道 A->B（P2-1）：沿直线挖 1 宽 2 高走廊，每 8 格插火把，逐块验证（无幽灵方块），可自动沿隧道走回起点。from/to 为 [x,y,z]，坡度超过 45° 会拒绝。

```js
{ from: z.array(z.number().int()).length(3), to: z.array(z.number().int()).length(3), maxBlocks: z.number().int().min(8).max(20000).optional(), torches: z.boolean().optional(), returnTrip: z.boolean().optional(), timeoutSeconds: z.number().int().min(1).max(3600).optional(), }
```

### `throw_item`

右键投掷/使用手里的物品（如末影之眼），返回生成的投射物实体 id。

```js
{ item: z.string().optional(), slot: z.number().int().min(0).max(8).optional(), timeoutSeconds: t, }
```

### `eye_throw`

末影之眼协议（P1-7）：投一颗眼，前 4 tick 抓到投射物，追踪到落点并报方位角；掉落的眼会走回去捡起来。一次只投一颗，禁止原地连投。

```js
{ timeoutSeconds: t, }
```

### `track_entity`

异步采样实体轨迹 N tick，返回 dx/dy/dz、水平方位 yaw 和 vertical=up/down/flat。

```js
{ entityId: z.number().int(), ticks: z.number().int().min(2).max(200).optional(), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `place_boat`

在指定的水面方块上放下船，返回船实体 id（等实体真正生成后再返回）。

```js
{ x: coord, y: coord, z: coord, item: z.string().optional(), timeoutSeconds: t, }
```

### `boat_sail`

上船并驶向 (x,z)，到达 range 内自动下船。

```js
{ x: z.number(), z: z.number(), range: z.number().min(1).max(16).optional(), timeoutSeconds: z.number().int().min(1).max(600).optional(), }
```

### `light_map`

扫描脚下可行走区域的光照：返回 darkCells 数量、最暗方块光、黑暗格子列表。

```js
{ center: z.array(z.number().int()).length(3).optional(), y: z.number().int().optional(), radius: z.number().int().min(1).max(32).optional(), timeoutSeconds: t, }
```

### `torch_at`

在 (x,y,z) 放一支火把（自动找相邻实心方块吸附），需要快捷栏有火把。

```js
{ x: coord, y: coord, z: coord, timeoutSeconds: t, }
```

### `triangulate`

纯计算：多条 (x,z,yaw) 射线求交点，用于末影之眼三角定位。射线夹角可以很小（远距离目标），质量按夹角和交点在两条射线前方判断。

```js
{ rays: z.array(z.object({ x: z.number(), z: z.number(), yaw: z.number() })).min(2).max(6), }
```

### `inventory_swap`

通过玩家背包菜单把主背包槽位与快捷栏槽位交换（服务端同步），用于把镐子/方块移到快捷栏。

```js
{ fromSlot: z.number().int().min(0).max(45), hotbarSlot: z.number().int().min(0).max(8), timeoutSeconds: t, }
```

### `hotbar`

读取快捷栏 9 个槽位的物品（快速检查手上的工具/方块）。

```js
{ timeoutSeconds: t, }
```

### `inventory_slots`

List all 36 inventory slots with invSlot/menuSlot indices, item id, count and tool durability.

```js
{ timeoutSeconds: t, }
```

### `save_world`

保存世界（发送 /save-all）。重启客户端前必须先调用，否则强制结束进程会丢失上次自动存档之后的进度（位置/方块）。

```js
{ timeoutSeconds: t, }
```

### `resync`

强制清理客户端状态：关界面、取消所有 job、停 Baritone，并返回最新背包快照（真正的服务端同步需要重登）。

```js
{ timeoutSeconds: t, }
```

### `container_open`

右键打开方块容器，并回读菜单与槽位。同步，默认 10 秒。

```js
{ x: coord, y: coord, z: coord, face: z.enum(['up', 'down', 'north', 'south', 'east', 'west']).optional(), timeoutSeconds: t, }
```

### `container_read`

读取当前打开的容器菜单所有槽位；标记哪些是玩家背包槽。

```js
{ timeoutSeconds: t, }
```

### `container_move`

点击容器槽位并回读验证。mode=quick/take/put/split/move；fromSlot 是菜单槽索引。

```js
{ fromSlot: z.number().int().min(0), toSlot: z.number().int().min(0).optional(), mode: z.enum(['quick', 'take', 'put', 'split', 'move']).optional(), count: z.number().int().min(1).max(64).optional(), timeoutSeconds: t, }
```

### `container_close`

关闭当前容器菜单。

```js
{ timeoutSeconds: t, }
```

### `villager_trades`

读取当前村民菜单的报价（付款 A/B、结果、uses、maxUses、是否售罄）。

```js
{ timeoutSeconds: t, }
```

### `trade_select`

选择并执行第 index 个村民交易，times 为次数；返回 uses 前后对比。

```js
{ index: z.number().int().min(0), times: z.number().int().min(1).max(16).optional(), timeoutSeconds: t, }
```

### `screenshot`

在渲染线程抓取真实画面，返回 PNG 路径；inline=true 时同时返回图片内容（供视觉模型）。

```js
{ inline: z.boolean().optional(), timeoutSeconds: t, }
```

### `job_status`

查询任意异步任务的完整状态：state/progress/metrics/reason/error/result。

```js
{ jobId: z.string().min(1), }
```

### `job_cancel`

取消任意异步任务；若已终止则返回当前状态。

```js
{ jobId: z.string().min(1), }
```

### `zff_status`

服务端状态（来自 mod 的 SavedData）：dist、watchdog 配置、actorId、memory/todo 数量、rev、在线玩家。

```js
{}
```

### `zff_memory_set`

写入服务端共享记忆（键值，值可放 JSON 字符串）；跨客户端/跨会话保留。

```js
{ key: z.string().min(1).max(200), value: z.string().max(64 * 1024), role: z.string().max(32).optional(), tags: z.array(z.string().max(64)).max(16).optional(), }
```

### `zff_memory_get`

读取服务端共享记忆的一个键。

```js
{ key: z.string().min(1).max(200), }
```

### `zff_memory_list`

列出服务端共享记忆（可按 tag 过滤）。

```js
{ tag: z.string().max(64).optional(), }
```

### `zff_memory_forget`

删除服务端共享记忆的一个键。

```js
{ key: z.string().min(1).max(200), }
```

### `zff_todo_add`

把任务加到服务端 TodoList（所有 AI 共享同一份清单）。spec 可是字符串或 {tool,args}/{steps}。

```js
{ title: z.string().min(1).max(300), priority: z.number().int().min(0).max(9).optional(), spec: z.union([z.string().max(8192), z.record(z.any())]).optional(), note: z.string().max(2000).optional(), }
```

### `zff_todo_list`

列出服务端 TodoList 的全部任务（id/status/priority/spec/jobId/actorId）。

```js
{}
```

### `zff_todo_done`

把服务端 TodoList 的一个任务标记为 done。

```js
{ id: z.string().min(1).max(80), }
```

### `zff_todo_update`

更新服务端 TodoList 任务的状态和备注（例如 running / cancelled）。

```js
{ id: z.string().min(1).max(80), status: z.enum(['pending', 'running', 'done', 'failed', 'cancelled']).optional(), note: z.string().max(2000).optional(), }
```

### `zff_todo_clear`

清空服务端 TodoList。

```js
{}
```

### `memory_read`

读取 <workspace>/.mcp-agent 下的记忆文件（最大 30 KB 文本）。

```js
{ name: z.string().min(1).max(200), }
```

### `memory_write`

原子写入/追加记忆文件；内容上限 256 KiB；用于记录目标、坐标和验证结果。

```js
{ name: z.string().min(1).max(200), content: z.string().max(256 * 1024), mode: z.enum(['replace', 'append']).optional(), }
```

### `memory_list`

列出 <workspace>/.mcp-agent 下的记忆文件和大小。

```js
{}
```

### `mc_instances`

列出实例心跳文件；默认只返回 20 秒内仍存活的实例。不会返回 token。

```js
{ includeExpired: z.boolean().optional(), }
```

### `mc_attach`

手动按实例文件或 port+tokenFile 附加客户端；不会接受内联 token。

```js
{ instanceFile: z.string().optional(), port: z.number().int().min(1).max(65535).optional(), tokenFile: z.string().optional(), }
```

### `bridge_info`

读取当前桥接客户端的信息（版本、加载器、MC 版本、玩家、维度）；不含 token。

```js
{}
```

