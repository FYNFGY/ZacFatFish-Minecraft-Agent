# MCP 工具参考

工具名在 DSH 中为 `mcp__mc__<name>`。所有坐标默认是**整数方块坐标**；`get_state.player.x/y/z` 是精确双精度位置，`blockX/blockY/blockZ` 是整数方块坐标。

## 同步 / 感知

| 工具 | 参数 | 说明 |
|---|---|---|
| `get_state` | — | 玩家、世界、屏幕、背包、菜单、bridge/Baritone 状态；默认 2s |
| `scan_area` | `center?[x,y,z]`, `radius` 1..32, `shape` sphere/cube, `filter`, `includeAir`, `includeEntities`, `includeProperties`, `includeCollision`, `maxBlocks` | 方块以 `[x,y,z,id]` 紧凑数组返回；`blockCounts`；超过 30KB 自动截断并标记 `truncated` |
| `scan_surface` | `center?`, `radius` 1..64, `mode` connected/los/fov, `fovDegrees`, `faceSamples`, `maxRaySteps`, `maxSurface`, `maxVisitedCells`, `timeBudgetMs`, `heightRadius`, `includeEntities` | 从眼睛 flood fill 可达开放空间，只返回贴着它的表层方块；`surface` 带面位掩码（1=up,2=down,4=north,8=south,16=west,32=east）；附 `heightMap`/`pits`/`doors`/`containers`；`los`/`fov` 是二次可见性过滤；响应内部裁到约 24KB，超限置 `truncated/truncatedReason`。详见 `SCAN_SURFACE.md` |
| `parallel` | `calls` 2..4 个 `{tool,args}` | 并行执行短查询（scan_area/get_state 等），边走边观察 |
| `block_query` | `positions?[[x,y,z]]` (≤64) 或 `x/y/z` | 方块 id/属性/硬度/碰撞箱/交互箱 |
| `entity_query` | `radius` 1..64, `type?`, `name?`, `limit` | 已跟踪实体、掉落物、玩家；含 bbox/距离/生命 |
| `collision_query` | `positions?` 或 `x/y/z` | 只返回碰撞/交互 AABB |
| `chat` | `message?`, `limit?` | 有 message 时发送（`/` 开头走命令），始终返回近期聊天 |
| `chat_history` | `limit?` | 只读 ring buffer（300 条） |
| `command` | `command` | 发送服务器命令；返回 `known`（客户端命令树是否认识） |
| `recipe_lookup` | `query`, `kind` output/input/any, `limit` | 运行时 `RecipeManager`；JEI 反射兜底虚拟配方；不硬编码 |
| `screenshot` | `inline?` | 渲染线程抓取，返回 PNG 路径；`inline=true` 时 MCP 返回 image content |
| `use_block` | `x,y,z`, `face?` | 右键门/按钮/拉杆等；返回前后状态 |
| `entity_interact` | `entityId?`/`uuid?`, `action` use/attack/trade, `itemSlot?` | 右键/攻击实体；`trade` 打开村民菜单 |
| `container_open` | `x,y,z`, `face?` | 右键方块容器，等待菜单出现，返回槽位 |
| `container_read` | — | 读当前菜单所有槽位，标记玩家背包槽 |
| `container_move` | `fromSlot`, `toSlot?`, `mode` quick/take/put/split/move/take_one, `count?` | 执行点击后等待 4 tick 回读，返回 `verified`/`moved`；`take_one` 只取一次合成结果（PICKUP + 收进背包），不会把材料全部做成成品 |
| `batch` | `ops` 1..2048 个 `{op:place\|mine\|use, x,y,z, face?, item?}`, `timeoutSeconds?` | 把一串挖掘/放置/交互打包成 job，中途不查询；整批结束后一次性回读校验（`verifiedOk`/`unverified`/`failed`）；启动时会取消 Baritone |
| `tower` | `blocks` 1..64, `item?`, `timeoutSeconds?` | 跳起并在脚下放方块连续搭高；只支持站在**完整方块**上（楼梯/半砖会快速返回 `unsupported` 并说明原因） |
| `walk` | `yaw?`, `ticks` 1..400, `jump?`, `sprint?`, `up?` | 真实客户端移动一小段；`sprint` 疾跑（0.30/tick），`up` 在水中上浮/遇台阶起跳；启动时取消 Baritone |
| `loot_chest` | `x,y,z`, `timeoutSeconds?` | 打开容器 → 前 27 槽整叠 shift-click 取出 → 关闭，返回物品列表；实测 24 组/1.66s、4 组/0.69s |
| `watch_damage` | `timeoutSeconds?` | 等到下一次掉血，返回伤害量、原因（`fall` / `mob:<type>` / `hotFloor` / `fire` / `drown` / `starve`）、前后血量、位置 |
| `damage_log` | `limit?` 1..40 | 最近的受伤记录（含原因和上下文） |
| `auto_respawn` | `enabled?` | 死亡自动复活（默认开启），返回死亡次数与最后一次死亡详情 |
| `container_close` | — | 关闭菜单 |
| `villager_trades` | — | 当前 MerchantMenu 的报价 |
| `trade_select` | `index`, `times?` | 选择并执行交易，返回 uses 前后 |
| `bridge_info` | — | 桥版本/玩家/维度/端口；无 token |
| `mc_instances` | `includeExpired?` | 实例心跳列表；显式白名单字段，无 token |
| `mc_attach` | `instanceFile?` 或 `port` + `tokenFile` | 手动附加；不接受内联 token |

## 异步 / 任务

| 工具 | 参数 | 说明 |
|---|---|---|
| `path_to` | `x,y,z`, `range?` 0..64, `timeoutSeconds?` 默认 300 | Baritone `GoalBlock`/`GoalNear`；立即返回 `jobId` |
| `path_status` | `jobId` | `state/progress/metrics.distanceLeft/currentGoal/etaTicks` |
| `path_cancel` | `jobId` | 取消并 `cancelEverything()` |
| `mine_block` | `x/y/z` 或 `positions[]`, `timeoutSeconds?` 默认 60 | 逐 tick `start/continueDestroyBlock`；`blocksMined/total` |
| `place_block` | `x,y,z`, `face?`, `item?`, `timeoutSeconds?` 默认 60 | 自动选 hotbar 物品；放置后 3 tick 回读 |
| `job_status` | `jobId` | 任意 job 的完整状态 |
| `job_cancel` | `jobId` | 任意 job 取消 |
| `craft` | `item`, `count?` 1..64, `table?` [x,y,z], `manual?`, `timeoutSeconds?` | 一键合成：默认配方书放置（3x3 自动找 5 格内工作台并等 CraftingMenu），失败 3 次后自动转 JEI 式逐格放料；`manual:true` 强制 JEI 式（不需要配方解锁）；按库存增量计数 |
| `wait_events` | `timeoutSeconds?` 0..120, `limit?`, `since?` | 阻塞等待聊天/受伤/死亡/复活事件，返回自上次调用以来的事件与 `cursor`（聊天/掉血约 0.5s 送达） |

## 记忆

| 工具 | 参数 | 说明 |
|---|---|---|
| `memory_read` | `name` | `<workspace>/.mcp-agent/<name>`，最多读 30KB 文本 |
| `memory_write` | `name`, `content`, `mode` replace/append | 原子写；单文件 ≤256KB；自动维护 README 索引 |
| `memory_list` | — | 文件列表/大小/修改时间 |

## 错误字符串

`timeout`、`disconnected`、`not_loaded`、`no_permission`、`unsupported`、`internal`。

- 任务终端 `reason` 为 `cancelled` 或上述错误码；失败时 `error` 为 taxonomy。
- `partial:true` 表示超时前可能已有部分效果（例如挖掘/行走）。
- 未找到 job、参数缺失等使用 `internal` 并在 `message` 给出具体原因。

## 超时

每个同步工具默认 2–10s（MCP server 最多等 30s），异步工具默认 60–300s（可传 `timeoutSeconds` 1..600）。
DSH 的 `toolCallTimeoutMs` 设为 600000；工具本身在 ms 级返回 jobId，不会阻塞模型。
