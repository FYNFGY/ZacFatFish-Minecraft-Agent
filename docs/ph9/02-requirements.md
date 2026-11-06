# 02 — Ph9 需求

> 需求编号用于测试与证据引用。每条需求都必须能映射到 `05-test-plan.md`。

## 1. 功能需求（FR）

### FR-9.1 要塞定位

| 项 | 内容 |
|---|---|
| 需求 | 生存模式下定位最近的要塞并返回坐标/距离 |
| 工具 | `locate_stronghold`（新）或 `command locate structure`（仅测试） |
| 输入 | 无 / 可选 dimension |
| 输出 | `{ok,structure,x,y,z,distance,source}` |
| 失败 | `structure_not_found`, `dimension_unavailable` |
| 验收 | 至少一次真实存档定位并到达 |

### FR-9.2 要塞导航

| 项 | 内容 |
|---|---|
| 需求 | 从当前位置走到传送门房间，不穿墙、不掉岩浆 |
| 工具 | `path_to_stronghold` / 3D 导航（新） |
| 输出 | Job 结果含最终坐标、剩余距离、失败原因 |
| 失败 | `no_path`, `stalled`, `danger_lava`, `player_died` |
| 验收 | 3 次不同地形至少 2 次成功 |

### FR-9.3 末地传送门激活

| 项 | 内容 |
|---|---|
| 需求 | 检查 12 个框架，缺少的眼睛可补，激活传送门 |
| 工具 | `end_portal_status` / `activate_end_portal`（新） |
| 输入 | 可选坐标/半径 |
| 输出 | `{frames:12,eyes:12,activated:true}` |
| 失败 | `missing_eyes`, `no_portal`, `blocked` |
| 验收 | 生存存档从 0–12 眼补到 12 并激活 |

### FR-9.4 进入末地

| 项 | 内容 |
|---|---|
| 需求 | 进入 End 后确认维度、坐标、平台、dragon/crystal 状态 |
| 工具 | `enter_end_portal`（新）/ `get_state` |
| 输出 | `{dimension:"minecraft:the_end",x,y,z,safe:true}` |
| 失败 | `portal_not_found`, `dimension_unavailable`, `void_risk` |
| 验收 | 生存进入后 60 秒不因虚空死亡 |

### FR-9.5 虚空保护

| 项 | 内容 |
|---|---|
| 需求 | 末地移动时避免脚下虚空；被击退后能自救 |
| 工具 | `void_guard` / `safe_move_to` / `mlg_water` |
| 输出 | `{safe:true/false, nearestSafe, fallRisk}` |
| 验收 | 末地平台边缘测试 10 次不坠落 |

### FR-9.6 水晶查询

| 项 | 内容 |
|---|---|
| 需求 | 返回所有末地水晶坐标、是否被铁栏杆保护、是否存活 |
| 工具 | `query_crystals`（新） |
| 输出 | `{crystals:[{id,x,y,z,protected,alive}],count}` |
| 验收 | 至少一个真实 End 存档识别全部水晶 |

### FR-9.7 水晶摧毁

| 项 | 内容 |
|---|---|
| 需求 | 从安全位置摧毁水晶，不因爆炸死亡 |
| 工具 | `destroy_crystal`（新）或 `shoot_at` |
| 输出 | `{destroyed,remaining,damageTaken,arrowsUsed}` |
| 失败 | `out_of_range`, `no_line_of_sight`, `player_died` |
| 验收 | 清掉至少 1 个暴露水晶 + 1 个铁栏杆水晶（如可能） |

### FR-9.8 龙本体识别

| 项 | 内容 |
|---|---|
| 需求 | 区分 EnderDragon 与 EnderDragonPart，返回 head/body/phase |
| 工具 | `query_dragon`（新） |
| 输出 | `{dragonId,health,phase,head,body,perch,crystals}` |
| 验收 | 不把龙翼/龙身片段当独立敌人 |

### FR-9.9 龙战斗

| 项 | 内容 |
|---|---|
| 需求 | 在 perch 窗口射击；飞行阶段停火/防御；低血吃牛肉/撤退 |
| 工具 | `kill_ender_dragon`（新） |
| 输出 | `outcome,health,armor,arrowsBefore/After,attacks,hits,dragonHealth,phase` |
| 失败 | `player_died`, `arrows_empty`, `no_damage_timeout`, `crystals_remaining` |
| 验收 | 至少一次有效 perch 伤害；完整击杀另设加分 |

### FR-9.10 战斗资源校验

| 项 | 内容 |
|---|---|
| 需求 | 弓/箭/盾/食物缺失时立即失败并写 InfoTxt |
| 工具 | 所有战斗工具 |
| 输出 | `error.code=weapon_unusable/ammo_empty/no_food` |
| 验收 | 无箭、坏弓、无盾各测一次 |

## 2. 非功能需求（NFR）

| 编号 | 需求 |
|---|---|
| NFR-9.1 | 所有 Job 必须有 deadline，不能无限运行 |
| NFR-9.2 | 10 秒无有效命中/无进展必须退出 |
| NFR-9.3 | 客户端 tick 线程不被长任务阻塞 |
| NFR-9.4 | Tool schema 与 `MCP-TOOLS.md` 一致 |
| NFR-9.5 | 失败必须返回结构化 `error.code`，不能只写日志 |
| NFR-9.6 | 不硬依赖 Pendulum/Create/Apotheosis/Serene Seasons |
| NFR-9.7 | 每个 Ph9 工具至少一个真机测试 + evidence |
| NFR-9.8 | 真实输入优先：盾/弓必须服务端认可 |
| NFR-9.9 | 证据必须标注 `setupByCommand: true/false` |
| NFR-9.10 | release 可重复构建，SHA256 稳定 |

## 3. 安全需求

- 不自动攻击玩家（除非显式 `force`）
- 不在主城/公共服务器默认开启外挂类战斗
- 管理员下线必须复核 `adminPlayers`
- 末地/虚空场景必须先评估 `void_risk`
- 低血/无食物/无箭必须退出，不得“硬撑”

## 4. 兼容性需求

- Minecraft 1.21.1 / NeoForge 21.1.228
- Baritone 1.11.3 NeoForge（可选）
- Pendulum 1.0 + Jupiter 2.3.7 + Rhino（可选）
- 纯净 NeoForge 存档无 End 维度时，必须明确 `dimension_unavailable`，不能崩溃
