# Ph8 测试用例设计

> 状态：测试设计稿（2026-11-04）
> 对应任务书：[agent-prompt8.md](agent-prompt8.md)
> 原则：每个用例必须有 `caseId`、前置条件、步骤、精确期望、证据文件；P0 不过不允许进入下一阶段。

---

## 0. 测试环境与约定

### 0.1 环境

- 客户端：`D:\mc\MCP Development\ph7-env\client`
- 世界：`New World`（测试前备份；破坏性用例前 `save_world`）
- 模式：Survival；需要命令时临时开 cheats（已开）
- 难度：Peaceful 为默认；战斗/怪物用例临时 Normal
- 必装：NeoForge 1.21.1、Bridge、Create、Apotheosis、Serene Seasons
- 可选：Pendulum（有/无两套用例）
- 工具调用：统一走 `scripts/tool.mjs` + daemon，不用过期 MCP session

### 0.2 证据格式

```json
{
  "caseId": "PH8-LIFE-01",
  "result": "pass|fail",
  "startedAt": "2026-11-04T...",
  "preconditions": ["..."],
  "steps": ["..."],
  "observed": {},
  "infoTxt": [],
  "evidence": ["evidence/...json", "evidence/...png"],
  "notes": ""
}
```

### 0.3 严重级别

- **P0**：阻塞验收，必须全过。
- **P1**：核心功能，必须过。
- **P2**：边界/兼容性，允许带已知限制，但要有记录。

### 0.4 通用前置/清理

每个用例开始：

```text
save_world
stop_all
cancel_active
关闭残留容器/界面
记录 Get-Date、玩家坐标、维度、难度
```

每个用例结束：

```text
save_world
stop_all（战斗/长任务）
记录 infoTxt 最终值
```

---

# 1. 生命周期（LIFE）

## 套件前置

- 没有正在运行的 MCP server/daemon；
- 已知 `57610`、`57611`、`57612` 空闲；
- 能读取 `runtime/zff-mcp-server.json`。

| ID | 级别 | 用例 | 步骤 | 期望 |
|---|---|---|---|---|
| LIFE-01 | P0 | 启动→停止基线 | 启动 MCP；等 `/health`；`stopMcp`；查端口/进程 | stop 返回 `ok:true`；57610/57611 无 LISTEN；state 删除；无本次 workspace 的 node |
| LIFE-02 | P0 | 重复停止 | 连续 `stopMcp` 10 次 | 每次 `ok:true` 或 `already_stopped`；不报错；端口始终空 |
| LIFE-03 | P0 | 关客户端后自退 | 启动服务/客户端；关闭客户端；等 70s | 60s 内 server/daemon 退出；端口空 |
| LIFE-04 | P0 | 关 DSH 后自退 | 启动；关闭 DSH/插件；等 35s | 30s 内 server/daemon 退出；无残留 |
| LIFE-05 | P1 | daemon 孤儿 | 手动只杀 server PID；观察 daemon | daemon 按 owner TTL 退出；57611 空 |
| LIFE-06 | P1 | 双 workspace | 两个 workspace 各起一套（不同端口）；停 A | 只杀 A；B 仍健康；A 端口空 |
| LIFE-07 | P1 | 端口被旧进程占用 | 制造旧 node 占 57610/57611；启动 | 识别为 ZFF 旧进程并清理，或返回 `conflict` 且不覆盖；不能静默失败 |
| LIFE-08 | P1 | 清理脚本 | 运行 `stop-mcp.ps1 -Workspace ...` | 与 `stopMcp` 相同；返回 leftover 明细 |
| LIFE-09 | P2 | 权限不足 | 用普通权限尝试停高权限进程 | 返回明确错误；不谎报成功 |
| LIFE-10 | P2 | 插件异常退出 | 强杀 DSH（模拟崩溃） | 30s 内 owner heartbeat 超时；server/daemon 退出 |

证据：`evidence/ph8-life-*.json`，含 `Get-NetTCPConnection` 和 `Win32_Process` 命令行前后对比。

---

# 2. `wait_events` 10s 上限（WAIT）

| ID | 级别 | 用例 | 步骤 | 期望 |
|---|---|---|---|---|
| WAIT-01 | P0 | 0s | `wait_events {timeoutSeconds:0}` | 立即返回（<1s）；不阻塞 |
| WAIT-02 | P0 | 10s | 无事件，timeout=10 | 约 10s 返回；不提前、不超 11s |
| WAIT-03 | P0 | >10 被截断 | timeout=60 | 最多 10s 返回；`capped:true` 或等价字段 |
| WAIT-04 | P0 | 事件提前 | 另起线程 2s 后注入 chat | 2s 左右返回事件；不等到 10s |
| WAIT-05 | P1 | 积压事件 | 先制造 2 条事件，再 timeout=10 | 立即返回积压；cursor 正确 |
| WAIT-06 | P1 | 连续调用 | 连续 5 次 timeout=10，无事件 | 每次 ≤10s；无 CPU 空转；flag 不丢 |
| WAIT-07 | P1 | 与 query_event 一致 | wait 取到事件后再 query | 不重复返回同一事件；`infoTxt:[]` |
| WAIT-08 | P2 | 非法参数 | timeout=-1 / 非数字 | 参数校验错误；不崩溃 |

证据：`evidence/ph8-wait-*.json`，记录 `startedAt/endedAt/durationMs/events`。

---

# 3. `tower` 预探测（TOWER）

## 套件前置

- 玩家站在自然/测试房间地面，头顶有空间；
- 物品栏有 `cobblestone` 64；
- 记录玩家 `feetY`。

| ID | 级别 | 用例 | 步骤 | 期望 |
|---|---|---|---|---|
| TOWER-01 | P0 | h=1 空列 | 站在平地，头顶 3 格空气；`tower {blocks:1}` | 创建 job 并完成；预探测 `column` 3 格全 air |
| TOWER-02 | P0 | h=10 空列 | 站高顶空间/竖井；`tower {blocks:10}` | job 完成；`column` 12 格全 air |
| TOWER-03 | P1 | h=64 | 人工竖井 66 格空气；`tower {blocks:64}` | 预探测 66 格不截断；job 正常或按高度限制明确失败 |
| TOWER-04 | P0 | 火把在 dy=2 | 头顶 2 格放火把；h=10 | **立即返回** `tower_blocked`；不创建 job；`column` 12 格；`blocked` 含火把 `nonFull:true` |
| TOWER-05 | P0 | 按钮在 dy=5 | 放置按钮；h=10 | 同上；`nonFull:true` |
| TOWER-06 | P0 | 活板门在 dy=1 | 关闭的活板门；h=5 | blocked；含完整 `column` |
| TOWER-07 | P0 | 石头在 dy=h+1 | 在最顶格放石头；h=10 | blocked；`blocked` 含石头 |
| TOWER-08 | P1 | 未加载/世界外 | 传送到极高/未加载位置；h=10 | `blocked`/`unloaded`；不崩；不无限等待 |
| TOWER-09 | P0 | 不创建 job | 火把场景调用 `tower` 后 `jobs_list` | 无新 tower job；返回是错误不是 jobId |
| TOWER-10 | P1 | 第二道防线 | 预探测通过但执行中头顶被放方块 | TowerJob 仍能安全失败/停止，不穿墙 |
| TOWER-11 | P2 | 非完整方块列表 | 雪层、藤蔓、告示牌、台阶 | 都被视为 blocked，`nonFull` 正确 |
| TOWER-12 | P2 | 玩家在 1×2 洞内 | h=1，头顶 1 格空气、上方实心 | blocked at h+1；不启动 |

证据：`evidence/ph8-tower-*.json` + 每个场景截图。

---

# 4. `query_layer` / `query_layer_foot`（LAYER）

## 套件前置

- 平坦测试区/天然洞穴；
- 记录玩家 `blockPosition`。

| ID | 级别 | 用例 | 步骤 | 期望 |
|---|---|---|---|---|
| LAYER-01 | P0 | 脚高平层 | `query_layer {y:feetY}` | 15×15=225 格；中心为玩家格；全 air/可替换 |
| LAYER-02 | P0 | 地面层 | `query_layer {y:feetY-1}` | 225 格；中心/周围为地面方块；`full:true` |
| LAYER-03 | P0 | 头顶层 | `query_layer {y:feetY+1}` | 225 格；中心 air |
| LAYER-04 | P0 | foot 等价 | `query_layer_foot` vs `query_layer {feetY}` | id/坐标/顺序完全一致 |
| LAYER-05 | P1 | 边界 | 传送到区块边界/世界边界 | 不崩；未加载 `id:"unloaded",loaded:false` |
| LAYER-06 | P1 | 非完整方块 | 在 y 层放火把/按钮/台阶 | `air:false,full:false,nonFull:true` |
| LAYER-07 | P1 | 坐标方向 | 在玩家北/南/西/东放不同方块 | row0=北、col0=西；中心 row7/col7 |
| LAYER-08 | P1 | y 越界 | y=-100 / 400 | 明确错误或全 unloaded；不崩 |
| LAYER-09 | P2 | 大面积 | 15×15 边界外不放 | 只返回 15×15，不多不少 |
| LAYER-10 | P2 | 性能 | 连续 100 次 | 平均 <500ms；无内存泄漏 |

证据：`evidence/ph8-layer-*.json`，固定坐标/种子。

---

# 5. `reachable_synthese`（REACH）

## 套件前置

- 用固定测试场景（自然地形或人工精确场景；人工场景只用于验证算法，不用于 `walk_to` 等运动测试）；
- 记录 `feetY` 和玩家朝向；
- 输出 `grid` 必须 15 行 × 15 列（允许 `%=` 两字符）。

| ID | 级别 | 用例 | 场景 | 期望 |
|---|---|---|---|---|
| REACH-01 | P0 | 平地 | 15×15 平地，玩家中心 | 中心 `%`；其余 `o`；无 `+/-/d/t` |
| REACH-02 | P0 | 上一格 | 北侧放 1 格高台阶 | 台阶格 `+`；台阶后平地 `o`；不跳两格 |
| REACH-03 | P0 | 下一格 | 南侧挖 1 格深 | 坑边 `-`；坑底可站立则 `o`/`-` 按规则 |
| REACH-04 | P0 | 示例 1 死胡同 | `* u * / * % * / * + *` | 北 `u`、南 `+`、两侧 `*`；CR 可读到北边卡头 |
| REACH-05 | P0 | 示例 2 矿道角落 | 用户 5×5 图 | `%` 周围 `o`；`=` 向上路口；`+` 相邻；`*` 不可达 |
| REACH-06 | P0 | 示例 3 崎岖矿道 | `* * * * * / %= - = = + / * * * * *` | 中心 `%=`；有 `-`、`+`、`=`；不把 `=` 全标成 `o` |
| REACH-07 | P0 | 示例 4 右侧高台 | `* * * * * / * + = o % / * * * * *` | `%`/`o` 同高；`+` 高一格；`=` 过渡；左上 `*` 由 4 层数据决定 |
| REACH-08 | P0 | `u` 卡头 | 头顶一格放石头 | 该格 `u`；不标 `o` |
| REACH-09 | P0 | `l` 卡脚 | 脚高一格放台阶/活板门/雪层 | 该格 `l`；`head` 空 |
| REACH-10 | P0 | `*` 实心 | 脚+头两格都放石头 | `*` |
| REACH-11 | P0 | `&` 非完整 | 头顶上方放火把/按钮 | 该格 `&`（或 `%&`，若在中心）；优先级高于普通标记 |
| REACH-12 | P0 | `&t%` | 用户示例：火把+竖井+玩家 | `&` 与 `t`、`%` 正确分列；不把火把当空气 |
| REACH-13 | P0 | `d` 深坑 | 挖 2–3 格深竖井，无底 | 坑口/坑内 `d`；能区分 1 格坑和深坑 |
| REACH-14 | P1 | `t` 竖井 | 头顶连续 2+ 格空气竖井 | 标 `t`；不标 `o`/`+` |
| REACH-15 | P1 | `%=` | 玩家所在格邻接 `+`/`-` | 输出 `%=`，不是单独 `%` |
| REACH-16 | P1 | 4 层边界 | 脚上/下第 5 层放方块 | 不影响主判定；`d/t` 只用额外一层 |
| REACH-17 | P1 | 15×15 边界 | 玩家在边界；外侧放方块 | grid 仍 15×15；外侧不采样/标 `*` |
| REACH-18 | P1 | 未加载 | 半区未加载 | 未加载列 `*`/unloaded；不崩 |
| REACH-19 | P1 | 台阶/半砖 | 上下各半砖 | `=`/`+`/`-` 与 `o` 区分正确 |
| REACH-20 | P2 | 水/岩浆 | 水池/岩浆池 | 不误判为可站立 `o`；标记合理（由实现定义，需记录） |
| REACH-21 | P2 | 15×15 性能 | 连续 100 次 | <500ms/次；无异常内存 |
| REACH-22 | P2 | 玩家在 1×2 洞 | 头顶实心、两侧实心 | `u`/`*` 正确；不误报 `+` |

> REACH-04~07 是用户给的**逐格断言**用例；实现若与期望不同，先确认 `=`/`&` 规则，不能静默改期望。

证据：`evidence/ph8-reach-*.json` + 每个场景的 grid 原文。

---

# 6. 钓鱼（FISH）

## 套件前置

- 天然水域（海/湖），岸上有安全落脚；
- 物品栏有钓鱼竿（普通/带饵）；
- 难度 Peaceful。

| ID | 级别 | 用例 | 步骤 | 期望 |
|---|---|---|---|---|
| FISH-01 | P0 | 一次成功 | 对水面 `fish {timeoutSeconds:90}` | 只抛一次；等咬钩；收竿；捡鱼；job done |
| FISH-02 | P0 | 只抛一次 | 观察 `casts`/日志 | 不自动第二次抛竿；完成即结束 |
| FISH-03 | P1 | 无水域 | 在沙漠/洞穴调用 | 立即/短时失败 `no_water`；不抛竿 |
| FISH-04 | P1 | 无鱼竿 | 空手/拿剑 | 失败 `no_rod`；不误用其他物品 |
| FISH-05 | P1 | 超时 | 小水坑/10s | `timeout`；只抛一次；不卡死 |
| FISH-06 | P0 | 鱼竿耐久 1 | 给 `Damage` 满-1 的鱼竿 | 收竿前/后触发 `TOOL:` 并停止，不把竿用断 |
| FISH-07 | P1 | 背包满 | 36 格填满 | 钓鱼仍可抛/收；掉落捡取失败有明确提示（或提前拒绝） |
| FISH-08 | P2 | 雨天/开阔水 | 雨天海面 | 正常抛收；不崩 |
| FISH-09 | P2 | 岩浆/无浮漂 | 对岩浆右键 | 不产生浮漂；失败/无 `player.fishing` |

证据：`evidence/ph8-fish-*.json` + 视频/截图。

---

# 7. 通用 GUI（GUI）

## 套件前置

- 原版 `gui_describe` 能找到标准控件；
- Create/Apotheosis 测试台坐标固定；
- 记录 `gui_describe` 快照。

| ID | 级别 | 用例 | 步骤 | 期望 |
|---|---|---|---|---|
| GUI-01 | P0 | 原版按钮 | 打开任意有按钮的 Screen（如配方书/告示牌） | describe 返回 `type:button`、label；click 生效 |
| GUI-02 | P1 | 原版滑块 | 有 slider 的 screen | describe 有 `value/min/max`；set 后值变化 |
| GUI-03 | P0 | 空手右键 | 手持方块对可空手交互方块 `use_block hand:"empty"` | 临时切空手、交互成功、恢复原槽 |
| GUI-04 | P0 | 无空槽 | 36 格全满，要求 empty hand | 明确 `no_empty_hand`；不改物品 |
| GUI-05 | P0 | Create 物理化 | 固定测试方块，手持物品 vs 空手 | 手持失败/空手成功；记录方块 id/坐标 |
| GUI-06 | P0 | Create 转速控制器读值 | 放置转速控制器；右键；describe | 返回当前值/上下限；不靠 OCR |
| GUI-07 | P0 | Create 转速控制器设值 | `gui_set` 到目标值；关闭重开 | 值被设置；重开读到同一值 |
| GUI-08 | P1 | Create 滑动兜底 | 禁用 adapter，仅 `gui_click_at`/`gui_drag` | 能通过坐标操作；记录坐标 |
| GUI-09 | P0 | Apotheosis Augmenting | 打开 Augmenting 工作台；放物品；点按钮 | describe 有按钮/槽位；click/container_move 生效 |
| GUI-10 | P1 | Apotheosis Reforging/Salvaging | 各打开一次；放入物品；执行 | 槽位/按钮可用；结果正确 |
| GUI-11 | P1 | Apotheosis GemCutting/GemCase | 同上 | 不崩；标准控件可操作 |
| GUI-12 | P1 | 无 mod vanilla | 仅 vanilla+Bridge 启动 | GUI 协议仍能 describe/click 原版 screen |
| GUI-13 | P1 | 纯自绘兜底 | 无 widget 的自绘 screen | `gui_click_at` 可点；无法操作时返回 `unsupported`，不崩 |
| GUI-14 | P2 | 坐标边界 | 点击 GUI 外/负坐标 | 明确错误；不点击世界 |
| GUI-15 | P2 | 关闭恢复 | 任何 GUI 操作后 `gui_close` | screen=null；玩家可继续移动 |

证据：`evidence/ph8-gui-*.json` + Create/Apotheosis 截图。

---

# 8. `query_env_info`（ENV）

| ID | 级别 | 用例 | 步骤 | 期望 |
|---|---|---|---|---|
| ENV-01 | P0 | 声音字幕 | 玩家旁 `/playsound` 走路/升级音 | `sounds` 含对应字幕（`<...`/`>...`） |
| ENV-02 | P1 | 字幕关闭 | 关闭字幕，再 playsound | 仍通过 SoundEngine 兜底返回声音 |
| ENV-03 | P0 | 游戏时间 | `/time set day` 后查询 | `game.dayTime`/phase 与游戏一致 |
| ENV-04 | P0 | 现实时间 | 查询 | `time.real` 是带时区 ISO；与系统时间误差 <2s |
| ENV-05 | P0 | 天气 | `/weather rain` / `thunder` | `rain/thunder` 正确 |
| ENV-06 | P0 | 季节-有 Serene | 设置/推进到夏季 | `season.source="sereneseasons"`，season/subSeason 正确 |
| ENV-07 | P0 | 季节-无 Serene | 无 Serene 客户端启动 | `season:null, source:"not_installed"`；不崩 |
| ENV-08 | P1 | 立即返回 | 连续 100 次 | 平均 <300ms；不建 job |
| ENV-09 | P1 | 多声音去重 | 同时播放 5 个同字幕 | 去重/上限 8 条；不刷屏 |
| ENV-10 | P2 | 维度切换 | 下界/末地查询 | 时间/天气/季节合理不崩 |

证据：`evidence/ph8-env-*.json` + 截图。

---

# 9. 工具耐久（TOOL）

| ID | 级别 | 用例 | 步骤 | 期望 |
|---|---|---|---|---|
| TOOL-01 | P0 | mine_block | 给剩余 1 耐久的镐，挖 2 格 | 第 1 格后/破前停止；job failed `tool_low`；`TOOL:` 行 |
| TOOL-02 | P0 | dig_shaft | 同上，向下挖 | 停止；不继续用拳头/低耐久工具 |
| TOOL-03 | P0 | dig_tunnel | 同上 | 停止；已挖方块不回滚 |
| TOOL-04 | P0 | branch_mine | 同上 | 停止；不自动换备用镐 |
| TOOL-05 | P0 | tower | 同上（搭塔） | 停止；不继续放置/挖 |
| TOOL-06 | P0 | 备用镐 | 背包有第二把好镐 | **不自动切换**；仍停止并报 `TOOL:` |
| TOOL-07 | P1 | 手动攻击 | 耐久 1，手动 `entity_interact` | 不强制拦截（仅自动挖掘要求） |
| TOOL-08 | P1 | 不重复 | 一次触发后连续查询 | 只报一次；pull 清空后不重复；新低耐久再报 |
| TOOL-09 | P1 | 工具断裂 | 剩余 1 时继续挖到断 | 不崩；`TOOL:` 含工具 id/0；job 停止 |
| TOOL-10 | P2 | 非耐久物品 | 木棍/方块当工具 | 不误报 `tool_low` |

证据：`evidence/ph8-tool-*.json`。

---

# 10. 原版武器（WEAPON）

## 套件前置

- 战斗场地，Normal 难度；
- 命令召唤僵尸/骷髅，`NoAI` 目标用于命中测试；
- 玩家装备对应武器/弹药。

| ID | 级别 | 用例 | 步骤 | 期望 |
|---|---|---|---|---|
| WEAPON-01 | P0 | 剑 | `combat_attack` 僵尸 | 命中、冷却正确、不隔墙 |
| WEAPON-02 | P0 | 斧 | 同上 | 命中；可破盾 |
| WEAPON-03 | P1 | 锤 | 同上 | 命中；重击/冷却正确 |
| WEAPON-04 | P0 | 弓 | 对 10 格目标满蓄力 | 箭命中；不提前放 |
| WEAPON-05 | P0 | 十字弩 | 装填后射击 | 命中；装填时间正确 |
| WEAPON-06 | P0 | 三叉戟近战 | 对近处目标 | 命中 |
| WEAPON-07 | P0 | 三叉戟投掷 | 对远处目标 | 投出、命中、可捡回/忠诚返回 |
| WEAPON-08 | P1 | 盾 | 骷髅射箭时举盾 | 挡住伤害；盾耐久消耗 |
| WEAPON-09 | P1 | 视线 | 中间放墙 | 不射击/不攻击；不穿墙 |
| WEAPON-10 | P1 | 冷却 | 连续调用 10 次 | 受攻击冷却限制；不无限连击 |
| WEAPON-11 | P1 | 友军 | 目标村民/玩家 | 默认不攻击；返回 `not_enemy` |
| WEAPON-12 | P2 | 弹药耗尽 | 弓无箭/弩无箭 | 明确 `no_ammo`；不空放 |

证据：`evidence/ph8-weapon-*.json` + 命中截图。

---

# 11. AutoAim / Pendulum（AUTO）

## 套件前置

- 内建 AutoAim 永远启用（无开关）；
- Pendulum 两套环境：未安装 / 已安装；
- 目标：僵尸/骷髅；不召唤玩家/村民作为目标。

| ID | 级别 | 用例 | 步骤 | 期望 |
|---|---|---|---|---|
| AUTO-01 | P0 | 内建自瞄-静态 | 对静止目标射箭 | 自动转向命中；无外部 mod |
| AUTO-02 | P0 | 内建自瞄-移动 | 目标横向移动 | 有提前量；命中率记录 |
| AUTO-03 | P0 | 无开关 | 查找配置/工具 | 没有 `autoaim:false` 开关；始终启用 |
| AUTO-04 | P0 | defend | 僵尸攻击玩家 | 自动反击；不主动追远处怪 |
| AUTO-05 | P1 | aggressive | 附近多怪 | 主动清怪；目标选择合理 |
| AUTO-06 | P1 | off | combatMode=off | 不自动攻击；手动 combat 仍自瞄 |
| AUTO-07 | P0 | 不打友军 | 村民/玩家在旁 | 不攻击；目标筛选正确 |
| AUTO-08 | P0 | Pendulum 缺失 | 未装 mod | `pendulum.available=false`；Pendulum 工具 disabled；AutoAim 正常；不崩 |
| AUTO-09 | P0 | Pendulum 存在 | 安装 jar | capability=true；自动近战/格挡可启用；记录版本/API |
| AUTO-10 | P1 | 服务器限制 | 多人服务器/反作弊 | 文档警告；不因 AutoAim 导致连接崩溃；由玩家承担风险 |
| AUTO-11 | P1 | 多目标 | 3 个怪 | 选最近/威胁最高；不来回抖 |
| AUTO-12 | P2 | 低血 | 玩家低血 | 撤退/吃/格挡（按 combat 策略） |

证据：`evidence/ph8-auto-*.json` + `docs/Ph8/PENDULUM.md`。

---

# 12. 回归（REG）

| ID | 级别 | 用例 | 期望 |
|---|---|---|---|
| REG-01 | P0 | `run-all --slow` | 10/10 全绿 |
| REG-02 | P0 | InfoTxt 拉取清零 | 每次工具返回带 `infoTxt` 并清空；空查询 `[]` |
| REG-03 | P0 | 旧工具面 | 99 工具，`query_event` 在，`wait_events` 在（恢复后） |
| REG-04 | P1 | 旧 job | `mine_block`/`dig_*`/`branch_mine` 仍正常 |
| REG-05 | P1 | 容器 | `container_move put/take/move` 仍 verified |
| REG-06 | P1 | release | 解压 zip、起 server、jar 版本/哈希一致 |
| REG-07 | P2 | 空 world | 未进世界时工具返回明确错误，不崩 |

---

# 13. 通过门槛

- 所有 **P0** 用例通过；
- M2/M3 的 P1 用例至少通过 90%，失败项有文档化限制；
- 每个用例有 evidence JSON，不能只有口头结论；
- `run-all --slow` 全绿；
- release 哈希与 `SHA256SUMS.txt` 一致；
- 未安装 Pendulum 时不能阻塞启动/测试；
- AutoAim 不能有“关闭”入口，且不能攻击非 Enemy。

---

# 14. 建议执行顺序

1. LIFE-01/02/03/04
2. WAIT-01/02/03/04
3. TOOL-01~06
4. ENV-01/03/04/05/06/07
5. FISH-01/02/06
6. WEAPON-01/04/06/07
7. TOWER-01/04/05/07/09
8. LAYER-01~04
9. REACH-01~15
10. GUI-01/03/05/06/07/09
11. AUTO-01/03/04/07/08/09
12. REG-01~06

> 先跑 P0 的“能启动、能停、能报错、能打中、能识别地形”，再跑兼容性/边界。
