# MCP 工具目录（全部工具）

> 适用版本：ZFF MCP Server + ZFF Bridge（NeoForge 1.21.1）。
> 本文件由 `mcp-server/src/server.js` 的 `reg(...)` 注册表生成，共 **122 个内置工具**；另附 DSH 插件 **7 个 zff_* 工具**。Pendulum 方案已废弃，未计入工具表。

## 返回契约

| 代号 | 统一返回 |
|---|---|
| R1 | 观察快照：`{ok:true, player, world, screen, damage, bridge, infoTxt}`，不同工具只返回其中相关字段。 |
| R2 | 列表/扫描：`{ok:true, ...数组或 map, count?, truncated?, bytes?}`。 |
| R3 | Job 句柄：`{jobId, state, tool, job:{timeoutSeconds,...}, infoTxt}`；最终结果用 `job_status` 取 `result`。 |
| R4 | Job 状态：`{jobId, tool, state, progress, metrics, result?, reason?, error?, message?, infoTxt}`。 |
| R5 | 动作：`{ok:true/false, ...动作字段, infoTxt}`；失败为 `{error:{code,message}, infoTxt}`。 |
| R6 | GUI/容器：`{open, screen, widgets[], menu, slots[], changed?}`。 |
| R7 | 聊天/命令：`{ok, sent, history, messages, events, flagUserChat, drained}` 的相应子集。 |
| R8 | Store/Memory：`{ok,key,value,tags,keys,files,content}` 的相应子集。 |
| R9 | Pendulum 路由：`{ok,available,port,text,isError,health,script,error}`。 |
| R10 | DSH 插件：`{ok,mode,terminated,paused,rows,reason,...}`。 |

## 内置 MCP 工具（122）

| # | 工具 | 类别 | 功能 | 参数 | 返回 |
|---:|---|---|---|---|---|
| 1 | `get_state` | 观察 / 快照 | 读取玩家/世界/背包快照。compact=true（默认）只保留关键字段以加快响应；compact=false 返回完整数据。 | compact: boolean?<br>timeoutSeconds: number | R1 player/world/screen/damage/bridge/infoTxt |
| 2 | `scan_area` | 观察 / 快照 | 【调试/X-ray，勿用于游戏决策】按球/立方体暴力扫描方块（能看穿墙）。正常探索请用 scan_connected。radius 1..32；返回 truncated/bytes。 | center: array?<br>x: number?<br>y: number?<br>z: number?<br>radius: number(1..32)?<br>shape: enum?<br>filter: string?<br>includeAir: boolean?<br>includeEntities: boolean?<br>includeProperties: boolean?<br>includeCollision: boolean?<br>xray: boolean?<br>maxBlocks: number(1..6000)?<br>timeoutSeconds: number | R2 {blocks[],blockCounts[],entities[],truncated} |
| 3 | `scan_connected` | 观察 / 快照 | 连通空间扫描（P1-1）：从玩家所在空腔做 flood fill，只返回能走到的空腔表面方块；门/栅栏门/活板门不阻断区域，实心墙后面的东西看不见。radius 1..64。 | center: array?<br>radius: number(1..64)?<br>heightRadius: number(1..32)?<br>maxSurface: number(32..8000)?<br>maxVisitedCells: number(1000..2000000)?<br>timeBudgetMs: number(50..5000)?<br>includeEntities: boolean?<br>timeoutSeconds: number | R2 {surface,heightMap,pits,doors,containers} |
| 4 | `scan_surface` | 观察 / 快照 | 表面扫描：从眼睛对可达开放空间做 flood fill，只返回贴着开放空间的方块（surface/heightMap/pits/doors/containers）。mode=connected\|los\|fov。includeNonFull（默认 true）把门/活板门/栅栏门/床/告示牌也放进 surface（ISSUE-8）。 | center: array?<br>radius: number(1..64)?<br>mode: enum?<br>includeNonFull: boolean?<br>fovDegrees: number(10..180)?<br>faceSamples: number(1..9)?<br>maxRaySteps: number(8..256)?<br>includeEntities: boolean?<br>maxSurface: number(32..8000)?<br>maxVisitedCells: number(1000..2000000)?<br>timeBudgetMs: number(50..5000)?<br>heightRadius: number(1..32)?<br>timeoutSeconds: number | R2 {surface,heightMap,pits,doors,containers} |
| 5 | `batch` | 物品 / 方块 / 交互 | 把一串 mine/place/use 操作打包成任务顺序执行，中途不查询状态；整批完成后一次性回读校验（verifiedOk/unverified）。 | ops: array?<br>timeoutSeconds: number | R5 动作结果 |
| 6 | `watch_damage` | 观察 / 快照 | 等待下一次生命值下降，返回伤害量、原因（fall / mob:<type> / fire / drown / starve 等）和位置。 | timeoutSeconds: number(1..600)? | R1 快照/列表 |
| 7 | `damage_log` | 观察 / 快照 | 最近的受伤记录（默认 10 条，含原因、伤害量、前后血量、位置）。 | limit: number(1..40)? | R1 快照/列表 |
| 8 | `mlg_water` | 移动 / 挖掘 / 寻路 | MLG 落地水：从边缘走出去，在离地 3 格内把水放在脚下，落地后报告下落高度。需要快捷栏有水桶。 | yaw: number?<br>maxTicks: number(20..600)?<br>timeoutSeconds: number(1..600)? | R3 Job 句柄 |
| 9 | `select_slot` | 物品 / 方块 / 交互 | 选择快捷栏槽位（0-8），切换手持物品。 | slot: number(0..8) | R5 动作结果 |
| 10 | `dump_junk` | 物品 / 方块 / 交互 | 前往附近丢垃圾：整组丢出后立刻跑开，避免掉落物被吸回来。target=想要的空格数。 | target: number(1..20)?<br>run: number(3..12)?<br>all: boolean?<br>timeoutSeconds: number(1..600)? | R5 动作结果 |
| 11 | `drop_junk` | 物品 / 方块 / 交互 | [DEPRECATED since Ph4: use delete_item (server-synced, verified)] 背包满时丢弃垃圾方块（泥土/砂砾/安山岩等），直到有 target 个空位。 | target: number(1..20)? | R5 动作结果 |
| 12 | `delete_item` | 物品 / 方块 / 交互 | 服务端同步删除物品（快捷栏 SWAP + DROP_ALL_ITEMS + 校验；本地背包不被修改）。给 item 或 slot(0..35)，run 为丢完跑开距离。 | item: string?<br>slot: number(0..35)?<br>run: number(0..12)?<br>timeoutSeconds: number(1..600)? | R5 动作结果 |
| 13 | `ore_log` | 观察 / 快照 | 矿石探针流：每次挖掉一个方块都会探测它的 6 个邻居，这里返回最近发现的矿石坐标。 | limit: number(1..100)? | R1 快照/列表 |
| 14 | `ghosts` | 观察 / 快照 | 幽灵方块/客户端-服务端不同步事件（position_jump / mined_revert / invisible_wall）。check=true 时现场检测卡在空气里的情况。 | limit: number(1..100)?<br>check: boolean? | R1 快照/列表 |
| 15 | `walk_to` | 移动 / 挖掘 / 寻路 | 短距离直线行走：朝 (x,z) 移动、自动跳 1 格台阶、无进展立即报 no_progress。 | x: number<br>z: number<br>range: number?<br>maxTicks: number(10..1200)?<br>allowBreak: boolean?<br>timeoutSeconds: number(1..600)? | R3 Job 句柄 |
| 16 | `tower` | 移动 / 挖掘 / 寻路 | 跳起并在脚下放方块，连续搭高 blocks 格；执行前先检查 h+2 格竖直列。 | blocks: number(1..64)?<br>item: string?<br>timeoutSeconds: number | R3 Job 句柄 |
| 17 | `fish` | 物品 / 方块 / 交互 | 只抛一次鱼竿：找水、抛竿、等咬钩、收竿；不会自动连钓。timeoutSeconds 为等待咬钩时间。 | timeoutSeconds: number(10..180)? | R5 动作结果 |
| 18 | `combat_attack` | 战斗 | 内建 AutoAim 的单目标战斗：近战按冷却攻击，远程用弓/弩/三叉戟蓄力后释放。只打 Enemy（除非 weapon=force）。 | entityId: number<br>weapon: string?<br>timeoutSeconds: number(2..60)? | R3 Job 句柄 |
| 19 | `combat_auto` | 战斗 | 扫描最近的敌对生物，并为最近的至多 N 个目标启动 combat_attack（内建 AutoAim）。 | maxTargets: number(1..4)?<br>timeoutSeconds: number | R3 Job 句柄 |
| 20 | `kill_target_close` | 战斗 | 16 格内索敌，自动走向目标并近战击杀；有盾牌时在攻击间隙举盾。返回 health/armor/attacks/hits/outcome。 | weapon: string?<br>timeoutSeconds: number(10..600)? | R3 Job 句柄 |
| 21 | `kill_target_far` | 战斗 | 64 格内索敌，自动用弓/弩/三叉戟瞄准并射击直到击杀；返回 health/armor/attacks/hits/outcome。 | weapon: string?<br>aimOffset: number?<br>timeoutSeconds: number(10..600)? | R3 Job 句柄 |
| 22 | `escape` | 战斗 | 敌对生物超过阈值时逃跑，直到 20x20 范围安全；返回 health/armor/outcome。 | threshold: number(1..30)?<br>timeoutSeconds: number(10..600)? | R3 Job 句柄 |
| 23 | `self_protect` | 战斗 | 开启/关闭自卫：被非玩家敌对生物伤害时中断其他 job，并反击/举盾/逃跑。 | enabled: boolean<br>timeoutSeconds: number | R3 Job 句柄 |
| 24 | `bow_probe` | 观察 / 快照 | 诊断用：读取主手/副手/箭数/使用状态/服务端弹药物。 | timeoutSeconds: number | R1 快照/列表 |
| 25 | `combat_status` | 观察 / 快照 | 战斗能力：内建 AutoAim 永远启用；战斗逻辑全部内置在 Bridge 模组中。 | timeoutSeconds: number | R1 快照/列表 |
| 26 | `walk` | 移动 / 挖掘 / 寻路 | 按世界朝向走一小段（真实客户端移动），可 jump 跳下、sprint 疾跑、up 上浮/爬台阶。 | yaw: number?<br>ticks: number(1..400)?<br>jump: boolean?<br>sprint: boolean?<br>up: boolean?<br>timeoutSeconds: number | R3 Job 句柄 |
| 27 | `craft` | 物品 / 方块 / 交互 | 一键合成：默认用配方书放置（3x3 自动找附近工作台），配方没解锁时自动改用 JEI 式逐格放料；manual 可强制走 JEI 式。 | item: string<br>count: number(1..64)?<br>table: array?<br>manual: boolean?<br>timeoutSeconds: number | R5 动作结果 |
| 28 | `loot_chest` | 容器 / GUI / 村民 | 打开一个箱子/容器，把前 27 个槽的整叠物品全部 shift-click 拿走后关闭，返回拿到的物品列表。 | x: value<br>y: value<br>z: value<br>timeoutSeconds: number | R6 {taken,items[]} |
| 29 | `auto_respawn` | 物品 / 方块 / 交互 | 查看/设置死亡自动复活（默认开启）；返回死亡次数和最后一次死亡信息。 | enabled: boolean? | R5 动作结果 |
| 30 | `parallel` | 任务 / 控制 | 并行执行 2–4 个短查询（如 scan_area/get_state/entity_query），用于边走边观察；返回每个调用的结果。 | calls: array? | R2 每个子调用结果 |
| 31 | `block_query` | 观察 / 快照 | 读取指定方块的状态、碰撞箱和交互箱；positions 为 [x,y,z] 列表，最多 64 个。 | positions: array?<br>x: number?<br>y: number?<br>z: number?<br>timeoutSeconds: number | R2 {blocks[]} |
| 32 | `query_layer` | 观察 / 快照 | 以玩家为中心读取 xOz 平面 15x15x1 的全部方块；y 为绝对方块高度。 | y: value<br>timeoutSeconds: number | R2 {y,cells,legend} |
| 33 | `query_layer_foot` | 观察 / 快照 | 读取玩家脚部高度（player.blockY）的 15x15x1 平面。 | timeoutSeconds: number | R2 同上，y=feet |
| 34 | `reachable_synthese` | 观察 / 快照 | 以玩家为中心扫描 15x15x4 的可达性，返回 2D 标记网格（o/+/-/=/*/l/u/d/t/%/&）。 | timeoutSeconds: number | R2 {grid,legend} |
| 35 | `entity_query` | 观察 / 快照 | 查询客户端已跟踪的实体，含掉落物、生物和其他玩家。半径 >48 会自动改成分环扫描；空结果带 note（ISSUE-12）。 | radius: number(1..128)?<br>type: string?<br>name: string?<br>limit: number(1..200)?<br>timeoutSeconds: number | R2 {entities[],count} |
| 36 | `collision_query` | 观察 / 快照 | 读取指定方块的碰撞体积和交互体积 AABB 列表。 | positions: array?<br>x: number?<br>y: number?<br>z: number?<br>timeoutSeconds: number | R2 {positions[]...} |
| 37 | `chat` | 聊天 / 命令 / 配方 | 发送一条聊天或 /命令（只发不听）。要听玩家消息必须用 chat_turn；两者不要对同一句话重复调用。不会把 bridge token 放进聊天。 | message: string?<br>limit: number(1..200)?<br>timeoutSeconds: number | R7 {ok,sent,history} |
| 38 | `chat_history` | 聊天 / 命令 / 配方 | 只读最近聊天记录。 | limit: number(1..200)?<br>timeoutSeconds: number | R7 {messages[]} |
| 39 | `command` | 聊天 / 命令 / 配方 | 发送服务器命令（可带或不带前导斜杠）。返回 known 表示命令是否在当前客户端命令树中。 | command: string<br>timeoutSeconds: number | R7 {ok,sent,command,known,history} |
| 40 | `recipe_lookup` | 聊天 / 命令 / 配方 | 按输出/输入物品查询运行时配方（RecipeManager + JEI 虚拟配方兜底）。不硬编码配方。 | query: string<br>kind: enum?<br>limit: number(1..80)?<br>timeoutSeconds: number | R7 聊天 |
| 41 | `path_to` | 移动 / 挖掘 / 寻路 | 异步 Baritone 寻路到 (x,y,z)；range>0 时使用 GoalNear。立即返回 jobId；用 path_status/path_cancel。 | x: value<br>y: value<br>z: value<br>range: number(0..64)?<br>allowBreak: boolean?<br>allowPlace: boolean?<br>timeoutSeconds: number(1..600)? | R3 {jobId,state,tool} |
| 42 | `set_pathing_policy` | 移动 / 挖掘 / 寻路 | Set whether pathing may break/place blocks (P1-10; default both false). No args returns the current policy. | break: boolean?<br>place: boolean? | R3 Job 句柄 |
| 43 | `path_open` | 移动 / 挖掘 / 寻路 | 带开门寻路的 path_to：走到目标点的同时自动打开挡住路线的门/栅栏门/活板门（村庄农田场景）。 | x: value<br>y: value<br>z: value<br>range: number(0..64)?<br>timeoutSeconds: number(1..600)? | R3 Job 句柄 |
| 44 | `path_status` | 移动 / 挖掘 / 寻路 | 查询 path_to 任务状态；包含 distanceLeft/currentGoal/etaTicks；失败时附 layerFoot（query_layer_foot 结果）。 | jobId: string | R4 路径进度 |
| 45 | `path_cancel` | 移动 / 挖掘 / 寻路 | 取消 path_to 任务并立即停止 Baritone。 | jobId: string | R4 取消后的状态 |
| 46 | `mine_block` | 物品 / 方块 / 交互 | 异步挖掘单个方块或 positions 列表；progress 带 blocksMined/total。立即返回 jobId。 | x: number?<br>y: number?<br>z: number?<br>positions: array?<br>timeoutSeconds: number(1..600)? | R5 动作结果 |
| 47 | `place_block` | 物品 / 方块 / 交互 | 异步放置方块到 (x,y,z)；可指定 face 或 item。立即返回 jobId。 | x: value<br>y: value<br>z: value<br>face: enum?<br>item: string?<br>timeoutSeconds: number(1..600)? | R5 动作结果 |
| 48 | `use_block` | 物品 / 方块 / 交互 | 右键使用门/按钮/拉杆等方块；返回使用前后的方块状态。mode=block\|item_on\|use_item（营火/烘干架用 item_on）；sneak=true 蹲下右键（ISSUE-11/13）。 | x: value<br>y: value<br>z: value<br>face: enum?<br>mode: enum?<br>hand: enum?<br>sneak: boolean?<br>timeoutSeconds: number | R5 动作结果 |
| 49 | `entity_interact` | 容器 / GUI / 村民 | 右键/攻击实体；action=trade 打开村民交易菜单。 | entityId: number?<br>uuid: string?<br>action: enum?<br>itemSlot: number(0..8)?<br>timeoutSeconds: number | R6 GUI/容器 |
| 50 | `stop_all` | 任务 / 控制 | 全局停止：取消所有 job、停 Baritone、清空调度器、关闭界面。收到“停”先调它。 | reason: string?<br>timeoutSeconds: number | R5 {ok,stopped} |
| 51 | `cancel_active` | 任务 / 控制 | P1-11：取消由 MCP 启动且仍在运行的所有 job（用户中断/关闭会话时服务端也会自动调用）。 | — | R5 {ok,cancelled} |
| 52 | `break_entity` | 物品 / 方块 / 交互 | 破坏实体（船/矿车/展示框/画/盔甲架）直到移除并报告掉落；不要用 mine_block 处理这些。 | entityId: number?<br>uuid: string?<br>type: string?<br>radius: number(1..32)?<br>timeoutSeconds: number(1..600)? | R5 动作结果 |
| 53 | `collect_drops` | 物品 / 方块 / 交互 | 走向附近掉落物并捡起，直到半径内没有物品或超时；捡不到时用 path_to 走到掉落物所在高度再试。 | radius: number(1..16)?<br>timeoutSeconds: number(1..600)? | R5 动作结果 |
| 54 | `dig_shaft` | 移动 / 挖掘 / 寻路 | 安全向下挖：mode=stair（默认 45° 阶梯）或 shaft（1x2 竖井）；先探水/岩浆，拒绝 1x1 直下。 | level: number(-64..320)<br>mode: enum?<br>yaw: number?<br>maxBlocks: number(8..20000)?<br>timeoutSeconds: number(1..1200)? | R3 Job 句柄 |
| 55 | `dig_sealed_shaft` | 移动 / 挖掘 / 寻路 | Ph5 P5-1：密封竖井——每挖一格就用圆石/泥土把头顶上方封住，海水/含水层不会灌进来；拒绝岩浆、拒绝下方有空洞。用于海底/水下作业。 | level: number(-64..320)<br>timeoutSeconds: number(1..600)? | R3 Job 句柄 |
| 56 | `branch_mine` | 移动 / 挖掘 / 寻路 | 鱼骨挖矿：主巷 1x2 + 每 spacing 格一条支巷（挖完退回主巷），自动插火把、探水/岩浆。 | corridorLength: number(4..256)?<br>spacing: number(2..8)?<br>branchLength: number(2..128)?<br>branches: number(0..64)?<br>yaw: number?<br>side: enum?<br>maxBlocks: number(8..20000)?<br>timeoutSeconds: number(1..1200)? | R3 Job 句柄 |
| 57 | `dig_tunnel` | 移动 / 挖掘 / 寻路 | 实心岩石隧道 A->B（P2-1）：沿直线挖 1 宽 2 高走廊，每 8 格插火把，逐块验证（无幽灵方块），可自动沿隧道走回起点。from/to 为 [x,y,z]，坡度超过 45° 会拒绝。 | from: array<br>to: array<br>maxBlocks: number(8..20000)?<br>torches: boolean?<br>returnTrip: boolean?<br>timeoutSeconds: number(1..3600)? | R3 Job 句柄 |
| 58 | `throw_item` | 物品 / 方块 / 交互 | 右键投掷/使用手里的物品（如末影之眼），返回生成的投射物实体 id。 | item: string?<br>slot: number(0..8)?<br>timeoutSeconds: number | R5 动作结果 |
| 59 | `eye_throw` | 物品 / 方块 / 交互 | 末影之眼协议（P1-7）：投一颗眼，前 4 tick 抓到投射物，追踪到落点并报方位角；掉落的眼会走回去捡起来。一次只投一颗，禁止原地连投。 | timeoutSeconds: number | R5 动作结果 |
| 60 | `track_entity` | 物品 / 方块 / 交互 | 异步采样实体轨迹 N tick，返回 dx/dy/dz、水平方位 yaw 和 vertical=up/down/flat。 | entityId: number<br>ticks: number(2..200)?<br>timeoutSeconds: number(1..600)? | R5 动作结果 |
| 61 | `place_boat` | 移动 / 挖掘 / 寻路 | 在指定的水面方块上放下船，返回船实体 id（等实体真正生成后再返回）。 | x: value<br>y: value<br>z: value<br>item: string?<br>timeoutSeconds: number | R3 Job 句柄 |
| 62 | `boat_sail` | 移动 / 挖掘 / 寻路 | 上船并驶向 (x,z)，到达 range 内自动下船。 | x: number<br>z: number<br>range: number(1..16)?<br>timeoutSeconds: number(1..600)? | R3 Job 句柄 |
| 63 | `light_map` | 观察 / 快照 | 扫描脚下可行走区域的光照：返回 darkCells 数量、最暗方块光、黑暗格子列表。 | center: array?<br>y: number?<br>radius: number(1..32)?<br>timeoutSeconds: number | R1 快照/列表 |
| 64 | `torch_at` | 物品 / 方块 / 交互 | 在 (x,y,z) 放一支火把（自动找相邻实心方块吸附），需要快捷栏有火把。 | x: value<br>y: value<br>z: value<br>timeoutSeconds: number | R5 动作结果 |
| 65 | `triangulate` | 物品 / 方块 / 交互 | 纯计算：多条 (x,z,yaw) 射线求交点，用于末影之眼三角定位。射线夹角可以很小（远距离目标），质量按夹角和交点在两条射线前方判断。 | rays: array | R5 动作结果 |
| 66 | `inventory_swap` | 物品 / 方块 / 交互 | 通过玩家背包菜单把主背包槽位与快捷栏槽位交换（服务端同步），用于把镐子/方块移到快捷栏。 | fromSlot: number(0..45)<br>hotbarSlot: number(0..8)<br>timeoutSeconds: number | R5 动作结果 |
| 67 | `hotbar` | 观察 / 快照 | 读取快捷栏 9 个槽位的物品（快速检查手上的工具/方块）。 | timeoutSeconds: number | R1 快照/列表 |
| 68 | `inventory_slots` | 观察 / 快照 | List all 36 inventory slots with invSlot/menuSlot indices, item id, count and tool durability. | timeoutSeconds: number | R1 快照/列表 |
| 69 | `save_world` | 物品 / 方块 / 交互 | 保存世界（发送 /save-all）。重启客户端前必须先调用，否则强制结束进程会丢失上次自动存档之后的进度（位置/方块）。 | timeoutSeconds: number | R5 动作结果 |
| 70 | `resync` | 物品 / 方块 / 交互 | 强制清理客户端状态：关界面、取消所有 job、停 Baritone，并返回最新背包快照（真正的服务端同步需要重登）。 | timeoutSeconds: number | R5 动作结果 |
| 71 | `container_open` | 容器 / GUI / 村民 | 右键打开方块容器，并回读菜单与槽位。同步，默认 10 秒。 | x: value<br>y: value<br>z: value<br>face: enum?<br>timeoutSeconds: number | R6 GUI/容器 |
| 72 | `container_read` | 观察 / 快照 | 读取当前打开的容器菜单；默认只返回非空槽（省 token），full:true 返回全部槽位。标记哪些是玩家背包槽。 | full: boolean?<br>timeoutSeconds: number | R6 {type,slots,playerSlots} |
| 73 | `container_move` | 容器 / GUI / 村民 | 点击容器槽位并回读验证。mode=quick/take/put/split/move；fromSlot 是菜单槽索引。默认只回传变化槽（changed）与非空槽；光标持物时会先放回背包再 quick。 | fromSlot: number<br>toSlot: number?<br>mode: enum?<br>count: number(1..64)?<br>timeoutSeconds: number | R6 {changed,slots} |
| 74 | `container_close` | 容器 / GUI / 村民 | 关闭当前容器菜单。 | timeoutSeconds: number | R6 GUI/容器 |
| 75 | `villager_trades` | 观察 / 快照 | 读取当前村民菜单的报价（付款 A/B、结果、uses、maxUses、是否售罄）。 | timeoutSeconds: number | R2 {trades[]} |
| 76 | `trade_select` | 容器 / GUI / 村民 | 选择并执行第 index 个村民交易，times 为次数；返回 uses 前后对比。 | index: number<br>times: number(1..16)?<br>timeoutSeconds: number | R6 GUI/容器 |
| 77 | `screenshot` | 观察 / 快照 | 在渲染线程抓取真实画面，返回 PNG 路径；inline=true 时同时返回图片内容（供视觉模型）。 | inline: boolean?<br>timeoutSeconds: number | R5 {ok,path,width,height,bytes,takenAt} |
| 78 | `jobs_list` | 任务 / 控制 | 列出本客户端当前的 job（id/tool/state/progress）；CR 用来看"现在手里还有什么在跑"。 | — | R2 全部 Job 摘要 |
| 79 | `wait_events` | 聊天 / 命令 / 配方 | 阻塞等待游戏事件（聊天/受伤/死亡/复活），返回事件；最多 10 秒，超出按 10 秒。 | timeoutSeconds: number(0..120)?<br>limit: number(1..100)?<br>since: number? | R7 事件数组（≤10s） |
| 80 | `query_event` | 观察 / 快照 | 主动拉取 InfoTxt 并清零；等同一次空操作。没有事件时返回 infoTxt: []。 | — | R1 快照/列表 |
| 81 | `tool_usage` | 任务 / 控制 | 统计 MCP 每个工具的调用次数（持久化到 workspace/runtime/tool-usage.json）。参数 limit 控制返回条数，按次数降序。 | limit: number(1..1000)?<br>timeoutSeconds: number | R2 {ok,totalCalls,uniqueTools,counts[],startedAt} |
| 82 | `job_status` | 任务 / 控制 | 查询任意异步任务的完整状态：state/progress/metrics/reason/error/result；寻路失败时附 layerFoot。 | jobId: string | R4 单个 Job 状态/result |
| 83 | `job_cancel` | 任务 / 控制 | 取消任意异步任务；若已终止则返回当前状态。 | jobId: string | R4 取消后的状态 |
| 84 | `zff_status` | 服务/管理 | 服务端状态（来自 mod 的 SavedData）：dist、watchdog 配置、actorId、memory/todo 数量、rev、在线玩家。 | — | R10 {mode,terminated,paused,rounds,decision,prompts} |
| 85 | `zff_todo_add` | 服务/管理 | 把任务加到服务端 TodoList（所有 AI 共享同一份清单）。spec 可是字符串或 {tool,args}/{steps}。 | title: string<br>priority: number(0..9)?<br>spec: string?<br>note: string? | R5 动作结果 |
| 86 | `zff_todo_list` | 服务/管理 | 列出服务端 TodoList 的全部任务（id/status/priority/spec/jobId/actorId）。 | — | R5 动作结果 |
| 87 | `zff_todo_done` | 服务/管理 | 把服务端 TodoList 的一个任务标记为 done。 | id: string | R5 动作结果 |
| 88 | `zff_todo_update` | 服务/管理 | 更新服务端 TodoList 任务的状态和备注（例如 running / cancelled）。 | id: string<br>status: enum?<br>note: string? | R5 动作结果 |
| 89 | `zff_todo_clear` | 服务/管理 | 清空服务端 TodoList。 | — | R5 动作结果 |
| 90 | `memory_read` | 存储 / 记忆 | 读取 <workspace>/.mcp-agent 下的记忆文件（最大 30 KB 文本）。 | name: string | R8 {ok,name,content} |
| 91 | `memory_write` | 存储 / 记忆 | 原子写入/追加记忆文件；内容上限 256 KiB；用于记录目标、坐标和验证结果。 | name: string<br>content: string<br>mode: enum? | R8 {ok,name,bytes} |
| 92 | `memory_list` | 存储 / 记忆 | 列出 <workspace>/.mcp-agent 下的记忆文件和大小。 | — | R8 {files[]} |
| 93 | `mc_instances` | 观察 / 快照 | 列出实例心跳文件；默认只返回 20 秒内仍存活的实例。不会返回 token。Ph7 起每个实例带 `username`（桥接上报的游戏内名字，未进世界为 null）与 `inWorld`。 | includeExpired: boolean?<br>withUsername: boolean? | R1 快照/列表 |
| 94 | `mc_attach` | 服务/管理 | 手动按实例文件或 port+tokenFile 附加客户端；不会接受内联 token。 | instanceFile: string?<br>port: number(1..65535)?<br>tokenFile: string? | R5 动作结果 |
| 95 | `bridge_info` | 观察 / 快照 | 读取当前桥接客户端的信息（版本、加载器、MC 版本、玩家、维度）；不含 token。 | — | R1 快照/列表 |
| 96 | `chat_turn` | 聊天 / 命令 / 配方 | 说完再听：发送（可选）+ 读取未读聊天。只有 CR 调用；message 可为字符串或数组（≤5 条，逐条回复）；drain=false 只发不听且不清除 Flag_User_Chat。 | message: array?<br>drain: boolean?<br>ack: number?<br>gapMs: number(0..1000)? | R7 {sent,events,drained,flagUserChat} |
| 97 | `env_read` | 观察 / 快照 | 读取 mod 常驻采样的环境 inbox（位置/血量/氧气/时间/天气/当前 job/附近/警报/staleMs）。采样在客户端 tick 里，不在 agent 里。 | timeoutSeconds: number | R1 快照/列表 |
| 98 | `query_env_info` | 观察 / 快照 | 立即读取环境：声音字幕、游戏/现实时间、天气、季节（Serene Seasons 可选）。不创建 job。 | timeoutSeconds: number | R1 快照/列表 |
| 99 | `gui_describe` | 容器 / GUI / 村民 | 读取当前 GUI 的 Screen/widgets/menu；widget index 用于 gui_click/gui_set。 | timeoutSeconds: number | R6 {open,screen,widgets[],menu} |
| 100 | `gui_click` | 容器 / GUI / 村民 | 点击当前 GUI 的 widget（按 gui_describe 的 index）。 | index: number<br>timeoutSeconds: number | R6 新 GUI 快照 |
| 101 | `gui_set` | 容器 / GUI / 村民 | 设置当前 GUI 的 slider/editbox（按 index；slider 值 0..1）。 | index: number<br>value: number<br>timeoutSeconds: number | R6 新 GUI 快照 |
| 102 | `gui_click_at` | 容器 / GUI / 村民 | 按 GUI 缩放坐标点击当前 Screen（自绘界面的兜底）。 | x: value<br>y: value<br>timeoutSeconds: number | R6 GUI/容器 |
| 103 | `gui_scroll` | 容器 / GUI / 村民 | 在当前 Screen 的 GUI 坐标处滚动（自绘界面的兜底）。 | x: value<br>y: value<br>delta: number<br>timeoutSeconds: number | R6 GUI/容器 |
| 104 | `create_probe` | 容器 / GUI / 村民 | 读取 Create 方块实体上的 ScrollValueBehaviour（如转速控制器当前值）。 | x: value<br>y: value<br>z: value<br>timeoutSeconds: number | R6 GUI/容器 |
| 105 | `create_scroll` | 容器 / GUI / 村民 | 直接设置 Create ScrollValueBehaviour 的值（如转速控制器），不需要打开 GUI。 | x: value<br>y: value<br>z: value<br>value: number<br>timeoutSeconds: number | R6 GUI/容器 |
| 106 | `gui_close` | 容器 / GUI / 村民 | 关闭当前 GUI。 | timeoutSeconds: number | R6 GUI/容器 |
| 107 | `store_get` | 存储 / 记忆 | 读 store：store=cr\|mod。mod 只读（客户端写的 mod-store.json），cr 是 agent 自己的 store。 | store: enum<br>key: string?<br>section: enum? | R8 {ok,key,value,tags} |
| 108 | `store_set` | 存储 / 记忆 | 写 CR store（只有 cr 可写；同名 key 与 mod store 互不相干）。 | store: value?<br>key: string<br>value: value<br>tags: array? | R8 {ok,key} |
| 109 | `store_list` | 存储 / 记忆 | 列出 CR store 的 key（可按前缀）；CR 自主整理用。 | store: value?<br>prefix: string? | R8 {keys[]} |
| 110 | `store_delete` | 存储 / 记忆 | 删除 CR store 的一个 key。 | store: value?<br>key: string | R8 存储 |
| 111 | `zff_chat_stats` | 观察 / 快照 | 聊天中断诊断：unread/inflight 数量、cursor、Flag_User_Chat、规则与豁免表。不改任何状态。 | — | R1 快照/列表 |
| 112 | `zff_preempt` | 服务/管理 | CR 抢占记录（§2.3）：把 preempted 事件写进 CR store，便于复盘"为什么这个任务没做完"。 | jobId: string?<br>taskId: string?<br>tool: string<br>reason: string? | R10 {ok,record|error} |
| 113 | `zff_kill_client` | 服务/管理 | 管理员下线（§2.6）第 2 步：先 disconnect，10 s 内没退出就结束该客户端进程。只允许作用于当前已附加实例的 gameDir。 | reason: string?<br>dryRun: boolean? | R10 {ok,action|error} |
| 114 | `wake` | 物品 / 方块 / 交互 | 起床（ISSUE-2）：发 STOP_SLEEPING 并逐 tick 回读 isSleeping（最多 60 tick）。不在床上时返回 note，不报错。 | — | R5 动作结果 |
| 115 | `eat` | 物品 / 方块 / 交互 | 吃东西（ISSUE-3）：选中→按住 useItem→保持到吃完→release→回读 food/数量。物品必须带 food 组件。 | slot: number(0..35)?<br>item: string?<br>hand: enum?<br>maxTicks: number(20..400)? | R5 动作结果 |
| 116 | `use_item` | 物品 / 方块 / 交互 | 通用「按住右键」（ISSUE-3）：弓/药水/盾/食物均可，超时强制松开。不要求 food 组件。 | slot: number(0..35)?<br>item: string?<br>hand: enum?<br>maxTicks: number(20..400)? | R5 动作结果 |
| 117 | `read_book` | 观察 / 快照 | 读手上的书写成书/书与笔（ISSUE-15）：返回 title/author/pages 纯文本。不是书 → unsupported。 | slot: number(0..35)?<br>hand: enum? | R1 快照/列表 |
| 118 | `read_sign` | 观察 / 快照 | 读告示牌正反两面 4 行文字（ISSUE-15）。 | x: number?<br>y: number?<br>z: number?<br>pos: array? | R1 快照/列表 |
| 119 | `item_info` | 观察 / 快照 | 读手持/指定槽的完整 components（ISSUE-15），书面会展开成纯文本 pages。 | slot: number(0..35)?<br>hand: enum? | R1 快照/列表 |
| 120 | `screenshot_gui` | 观察 / 快照 | 含 GUI 覆盖层的截图（ISSUE-15）：先 flush GUI 再抓主渲染目标；文件名带 -gui。 | — | R5 同上，含 GUI |
| 121 | `walk_dir` | 移动 / 挖掘 / 寻路 | 语义化移动（ISSUE-7）：direction=north/south/east/west（yaw 0→+Z 南，90→-X 西，180→-Z 北，270→+X 东）。返回 jobId。 | direction: enum?<br>yaw: number?<br>ticks: number(1..400)?<br>jump: boolean?<br>sprint: boolean?<br>up: boolean?<br>timeoutSeconds: number | R3 Job 句柄 |
| 122 | `path_open_ex` | 移动 / 挖掘 / 寻路 | path_open 的显式版（ISSUE-5）：默认只开门/栅栏门/活板门；openContainers:true 恢复“顺手开箱”。返回 jobId。 | x: value<br>y: value<br>z: value<br>range: number(0..64)?<br>openContainers: boolean?<br>timeoutSeconds: number | R3 Job 句柄 |

## DSH 插件工具（7）

| 工具 | 功能 | 参数 | 返回 |
|---|---|---|---|
| `zff_status` | 查看当前 ZFF 会话状态（模式/终止/暂停/续跑轮数/最近决策/提示词来源） | — | `{ok,mode,terminated,paused,rounds,decision,prompts}` |
| `zff_usage` | 查看 MCP 每个工具的累计调用次数（按次数降序，最多1000条） | `limit?:integer` | `{ok,totalCalls,uniqueTools,counts[{name,count,lastUsedAt}],startedAt}` |
| `zff_team` | 查看 CR/SR 当前在做什么；SR 只认自己的 CR store 上报 | — | `{ok,rows[]}` |
| `zff_env` | 重新检查工作目录的 ZFF MCP Server 与游戏实例，按需解压/启动并选择实例 | — | `{ok,reason?,instance?}` |
| `zff_pause` | 暂停/恢复自动续跑，写 `runtime/zff-paused.json` | `paused:boolean`<br>`reason?:string` | `{ok,paused}` |
| `zff_shutdown` | 管理员下线：用只读 chat_history 复核 adminPlayers，通过才写 terminated | `reason?:string` | `{ok,action?|error?}` |
| `zff_prompts` | 读取/保存/重置提示词三件套 A=tools / B=rules / C=persona | `action:list\|save\|reset`<br>`slot?:tools\|rules\|persona`<br>`text?:string` | `{ok,slot,bytes,source,text}` |

## 说明

- 参数后带 `?` 为可选；`timeoutSeconds` 是大多数工具共用的可选超时。
- 所有 Job 类工具立即返回 `jobId`，不会在 HTTP 调用里阻塞到任务完成；用 `job_status` 轮询，`job_cancel` 取消。
- `infoTxt` 是服务端注入的紧急提示队列，可能包含 `COMBAT:`、`TOOL:`、`HP`、`JOB:` 等行。
- 工具名与参数以 `mcp-server/src/server.js` 为准；插件工具以 `zff-mc-plugin/index.js` 为准。
