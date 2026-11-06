# Ph8 开发任务书：生命周期、M2 生存/模组 GUI、M3 战斗、地形可达性

> 状态：**方案定稿，待实现**（2026-11-04）
> 本轮目标：
> 1. 修 MCP 关不掉/残留进程；恢复 `wait_events` 但最多 10 s。
> 2. M2：钓鱼（只钓一次）、通用 mod GUI 协议（Create 重点特化）、`query_env_info`、工具耐久紧急事件。
> 3. M3：全部原版武器/三叉戟、Bridge 内建 AutoAim（永远启用）、可选 Pendulum、自动防卫/主动出击。
> 4. 地形：`tower` 预探测、`query_layer`/`query_layer_foot`/`reachable_synthese`。
> 约束：所有新工具必须能在真实客户端里用；新增/修改的工具必须有证据文件；release 目录同步更新。

---

## 0. 背景

上一轮（Ph7）已经把 MCP 中断改成 `EmergencyEventList + InfoTxt`，并修了 `dig_shaft` / `dig_tunnel` / `branch_mine` / `mine_block` / `container_move`。  
本轮还要补 M2 的生存/模组 GUI 和 M3 的战斗，同时解决 **“MCP 自己关不干净”** 和 **“AI 在复杂地形里看不出可走路线”**。

核心思路：

- 生命周期：MCP server / daemon 必须能被**确定性杀掉**，且客户端关闭后不留下占端口的 node 进程。
- 等待：`wait_events` 恢复，但硬上限 10 s，防止再出现“站着发呆几分钟”。
- 地形：新增 1 层查询和 4 层可达性综合，让 CR 能直接看到“脚下/头顶/上坡/深坑/竖井/卡脚/卡头/非完整方块”。
- `tower`：执行前先检查 `h+2` 格竖直列，任何完整/非完整方块都立即返回，不创建 job。
- M2/M3：AutoAim 由 Bridge 内建并**永远启用**；Pendulum 可选，缺失时只禁用 Pendulum 专属战斗工具，不影响内建 AutoAim。

---

## 1. 缺陷修复

### 1.1 插件：MCP 服务器关不掉，关闭客户端后仍有进程占用

#### 现象

1. 面板点“关闭 MCP”后，`57610` / `57611` 仍被占用，或 node 进程仍在。
2. 关闭 Minecraft 客户端后，MCP server / daemon 仍活着。
3. 再启动时出现 `EADDRINUSE`、`unknown MCP session`、旧 workspace 的 server 顶替新 workspace。

#### 必须达到的行为

- `stopMcp` 返回 `ok:true` **当且仅当**：
  - `57610`、`57611` 没有 LISTEN；
  - 由本次 MCP 启动的 server、daemon、其子进程全部退出；
  - `runtime/zff-mcp-server.json` 已删除或标记为 dead。
- 点“关闭 MCP”重复点击必须幂等，不报错、不重复杀。
- 关闭 DSH/插件时，即使 UI 没点关闭，也不能留下由插件启动的 server/daemon。
- 关闭 **Minecraft 客户端**（不是 DSH）后，最多 `N` 秒内 server/daemon 退出；`N` 默认 60，可配置。
- 若进程是**用户自己手动启动的**（`startedBy !== "plugin"`），不要误杀；返回 `conflict` 让用户处理。

#### 根因候选（实现前逐项验证，不要只改表象）

1. `stopMcp` 只按 **state.pid + 端口占用 PID** 杀；server 关闭端口后，daemon/子进程仍在，端口检查看不到。
2. `pidsOnPort` 依赖 `netstat -ano` 的本地化输出；某些 Windows 语言/权限下解析为空。
3. `killTree` 只 `taskkill /PID <pid> /T /F`；如果 state 里记的是 **wrapper PowerShell/cmd PID**，或子进程已脱离进程树，杀不到。
4. 插件 `stop()` 是同步的，DSH 退出时没有等待 `stopMcp` 的 Promise；`process.on('exit')` 不能做异步。
5. 启动竞态：多个会话同时 `ensureServer`，state 文件被后写者覆盖，旧 pid 丢失（上一轮已做 single-flight，但 state 仍需多 pid 记录）。
6. 客户端关闭后，MCP server 只依赖 DSH 心跳；DSH 仍开着时，server 不会因为“游戏没了”退出。
7. daemon 没有 owner heartbeat / no-instance TTL，server 退出后 daemon 仍在。

#### 建议实现

**A. 进程身份与状态文件**

`runtime/zff-mcp-server.json` 至少记录：

```jsonc
{
  "pid": 1234,
  "daemonPid": 1235,
  "startedBy": "plugin",
  "startedAt": 1793...,
  "workspace": "D:\\MC Agent Play",
  "ports": { "server": 57610, "daemon": 57611, "internal": 57612 },
  "argv": ["src/server.js", "--http", ...],
  "daemonArgv": ["src/mc-daemon.mjs", ...]
}
```

启动前：

- 读 state；若 `pid` 不在活进程表，或端口 owner 与 state 不一致 → 清 state，重新探测。
- 若端口被“看起来像 ZFF”的 node 占着但 state 缺失 → 尝试按 **命令行 + workspace** 识别并清理；不确定则报 `conflict`，不要盲杀。

**B. `stopMcp` 的收集范围**

按顺序收集，去重：

1. `state.pid`、`state.daemonPid`；
2. `pidsOnPort(server)`、`pidsOnPort(daemon)`；
3. 递归子进程（`Win32_Process.ParentProcessId`）；
4. 兜底：`Win32_Process` 中 `Name=node.exe` 且 `CommandLine` 含：
   - `zff-mcp-server` 或 `src/server.js`
   - `mc-daemon.mjs`
   - 当前 `workspace`

对每个 PID：

- 先 `taskkill /PID <pid> /T`（不加 `/F`，给 3 s 优雅退出）；
- 再 `taskkill /PID <pid> /T /F`；
- 每步后复查 PID 是否还在。

杀完：

- 轮询端口最多 10 s；
- 返回 `{ok, killed, leftover, ports:{server,daemon}, stateRemoved}`；
- `ok:false` 时把 leftover PID/命令行返回给面板，**不要 UI 假装成功**。

**C. 关闭客户端后的退出**

两种都做：

1. **插件 tick**：当 `mcpStatus.instances === 0` 持续 `clientGoneMs`（默认 60 s），且 `startedBy === 'plugin'`，调用 `stopMcp` 并把面板切成“未启动”。
2. **server 自退**：给 server 加 `--exit-without-instance <seconds>`：
   - 由插件启动时传入 60；
   - 每 5 s 扫描 instances；连续无 live instance 超过阈值 → `cancelActiveJobs` → 关闭 HTTP → `process.exit(0)`。
   - 注意：用户在菜单/换世界时 instance 仍在，不要误退。

daemon 同理：加 `--owner-heartbeat` 或由 server 退出时显式杀掉；不能留下只占 57611 的 daemon。

**D. 插件退出钩子**

- `stop()` 同步阶段：`spawnSync` 调用 `stop-mcp.ps1`（或 `taskkill` 命令）做最后清理；不能用 async 等待。
- 同时注册 `process.on('SIGINT'/'SIGTERM'/'exit')`，只做**幂等、快速**的杀进程，不写大文件。
- 新增 `release/stop-mcp.ps1`，支持 `-Workspace`、`-Port`，供用户和测试手动清理。

#### 验收

- 启动 → 停止 → `57610/57611` 立即空；`Win32_Process` 中无本次 workspace 的 `zff-mcp-server`/`mc-daemon`。
- 重复 stop 10 次，不报错。
- 启动 → 关闭客户端 → 等 60 s → 端口空、进程空。
- 启动 → 关闭 DSH → 30 s 内 server/daemon 退出。
- 两个 workspace 同时启动，stop 只杀当前 workspace，不误杀另一个。
- 证据：`evidence/ph8-lifecycle-*.json`，含 `before/after` 的 `Get-NetTCPConnection`、`Win32_Process` 命令行、stop 返回。

---

### 1.2 MCP：`wait_events` 允许存在，但最多 10 s

#### 行为

- 恢复 `mcp__mc__wait_events`（不是隐藏）。
- `timeoutSeconds` 允许 `0..10`；**大于 10 一律按 10 处理**，返回 `capped:true`。
- 0 仍表示“立即取积压事件”。
- server 内部分片保持 1 s/片；10 s 上限后必须返回。
- 插件只读工具面重新加入 `wait_events`；提示词写“只在需要短暂等事件时用，最多 10 s；长等待用 query_event/job_status”。
- 旧 `test/m1-interrupt-live.mjs` 可以继续用 10 s 内的等待。

#### 验收

- `wait_events {timeoutSeconds:60}` 最多 10 s 返回，`capped:true`。
- `wait_events {timeoutSeconds:10}` 正常。
- `wait_events {timeoutSeconds:0}` 立即返回。
- 没有事件时不会超过 10 s；有事件时提前返回。
- evidence：`evidence/ph8-wait-events-*.json`。

---

## 2. 新增工具

工具公共前缀仍是 `mcp__mc__`；工具名用下划线（与现有工具一致）：

- `query_layer`
- `query_layer_foot`
- `reachable_synthese`
- `tower`（加 preflight，不新增工具名）

所有新工具都走 mod 客户端线程采样，返回短 JSON；不要用 X-ray `scan_area` 实现。

---

### 2.1 `tower` 执行前可达性探测

#### 参数

`tower` 原有参数不变：

```json
{ "blocks": 10, "item": "minecraft:cobblestone" }
```

#### 规则

在创建 `TowerJob` **之前**，检查玩家脚下方块上方 `h+2` 格竖直列：

- `h = blocks`（1..64）；
- 列坐标 = `player.blockPosition()` 的 x,z；
- 检查 `dy = 0 .. h+1`，共 `h+2` 个方块；
- 任一格 **不是空气**（包括按钮、火把、活板门、告示牌、藤蔓、雪层等非完整方块）→ 立即返回，不创建 job；
- 若 `h+2` 列中有未加载/世界外 → 也视为 blocked。

#### 返回

```json
{
  "ok": false,
  "error": "tower_blocked",
  "h": 10,
  "from": [x, y, z],
  "column": [
    { "dy": 0, "pos": [x,y,z], "id": "minecraft:air", "air": true, "nonFull": false },
    { "dy": 1, "pos": [x,y+1,z], "id": "minecraft:air", "air": true, "nonFull": false },
    { "dy": 2, "pos": [x,y+2,z], "id": "minecraft:torch", "air": false, "nonFull": true }
  ],
  "blocked": [
    { "dy": 2, "pos": [x,y+2,z], "id": "minecraft:torch", "nonFull": true }
  ]
}
```

- `column` 必须包含 **全部 h+2 格**，不只是第一个 blocker；CR 需要分析整列（比如“上面 3 格是火把，清理后 OK”）。
- 成功时正常创建 `tower` job，返回原 job 结构；可附带 `preflight:{cells:h+2,blocked:0}`。

#### 实现位置

- mod 新增 `tower_probe`（客户端线程）：
  - 参数 `blocks`、可选 `x/z`（默认玩家）；
  - 返回上述 column；
  - 用 `BlockState` / `getCollisionShape` / shape 判断 `nonFull`；
  - 任何 `!isAir()` 都算 blocked，不只看碰撞。
- server `tower` handler：
  1. 调 `tower_probe`；
  2. `blocked.length > 0` → 返回错误，不调 `tower`；
  3. 否则调原 `tower`。
- 旧的 `TowerJob.start` 头顶检查保留作为第二道防线。

#### 验收

- 平地上方空 → 正常起 job。
- 在头顶 2 格放火把/按钮/活板门 → 立即返回 `tower_blocked`，无 job，`column` 长度 = h+2。
- 放置石头在 h+2 内 → blocked。
- h=1/10/64 都测；64 时 column 66 条，不能截断。
- evidence：`evidence/ph8-tower-preflight.json`。

---

### 2.2 `query_layer`

#### 参数

```json
{ "y": 64 }
```

`y` 是绝对方块 Y。

#### 行为

- 以玩家所在方块 `(px,pz)` 为中心；
- `x ∈ [px-7, px+7]`，`z ∈ [pz-7, pz+7]`；
- 返回该 **15×15×1** 平面的全部方块（包括空气）；
- 不改变世界、不创建 job；客户端线程一次采样。

#### 返回

```json
{
  "ok": true,
  "y": 64,
  "center": [px, 64, pz],
  "origin": [px-7, 64, pz-7],
  "rows": 15,
  "cols": 15,
  "grid": [
    [
      { "x": 0, "z": 0, "id": "minecraft:stone", "air": false, "full": true, "nonFull": false },
      { "x": 1, "z": 0, "id": "minecraft:air", "air": true, "full": false, "nonFull": false }
    ]
  ]
}
```

- `grid[row][col]`：`row 0 = 北（z 最小）`，`col 0 = 西（x 最小）`；
- 所有 225 格都要有 `id/air/full/nonFull`；
- 未加载格：`id: "minecraft:unloaded"`，`loaded:false`。

#### 验收

- 平地上 `y=feet` 全是空气/草方块；`y=feet-1` 是地面。
- 15×15 边界正确；中心是玩家所在格。
- 未加载/世界外不崩溃，标 unloaded。

---

### 2.3 `query_layer_foot`

等价于：

```json
{ "y": player.blockY }
```

返回结构同 `query_layer`，`tool` 名为 `query_layer_foot`。  
不要重复实现采样逻辑，内部复用 `query_layer`。

---

### 2.4 `reachable_synthese`

#### 输入范围

- 以玩家为中心，`x/z` 各 15 格（±7）；
- 4 层：`feetY-1, feetY, feetY+1, feetY+2`；
- 额外允许：
  - 向下多看一层 `feetY-2`，用于 `d`；
  - 向上多看一层 `feetY+3`，用于 `t`；
  - 总采样最多 15×15×6，但只有 4 层参与主判定。

#### 输出

```json
{
  "ok": true,
  "center": [px, feetY, pz],
  "layers": [feetY-1, feetY, feetY+1, feetY+2],
  "origin": [px-7, pz-7],
  "rows": 15,
  "cols": 15,
  "grid": [
    "***...+........",
    "..."
  ],
  "legend": {
    "o": "reachable, landing same height",
    "+": "reachable, landing one higher",
    "=": "reachable same-height vertical transition",
    "-": "reachable, landing one lower",
    "*": "unreachable, solid in player's 2-high body",
    "l": "unreachable, block catches feet",
    "u": "unreachable, block at head",
    "d": "deep pit",
    "t": "upward shaft",
    "%": "player position",
    "&": "non-full block in probe area"
  }
}
```

- `row 0 = 北`，`col 0 = 西`；中心 `%` 在 `row 7,col 7`。
- 单元格是**字符串**，允许两字符：`%=`（当前位置同时符合 `=`）、`%&`（当前位置探测到非完整方块）；其余尽量单字符。
- 必须同时返回 `blockers`（可选）用于解释 `l/u/&`：`[{x,z,dy,id,reason}]`。

#### 列分类（基础）

对每个 `(x,z)`：

```text
feet   = block(x, feetY, z)
head   = block(x, feetY+1, z)
above  = block(x, feetY+2, z)
below  = block(x, feetY-1, z)
below2 = block(x, feetY-2, z)
above3 = block(x, feetY+3, z)
```

- `air`：`isAir()` 或可替换且无碰撞（草、花等也按可替换空气处理；但火把/按钮/活板门算非完整实体，不能当空气）。
- `full`：完整立方体碰撞（`getCollisionShape().isFull()` 的近似）。
- `nonFull`：非空气但不是 full（火把、按钮、活板门、台阶、栅栏、雪层等）。
- `bodyBlocked`：`feet` 或 `head` 不是可通行空气。
- `headBlocked`：`head` 是 full/nonFull，`feet` 可通行。
- `feetBlocked`：`feet` 是 full/nonFull。

#### 标记判定（优先级从上到下，先命中先定）

1. `%`：`(px,pz)` 永远是起点；如果它也满足 `=`，输出 `%=`；如果列内还有非完整方块，输出 `%&`。
2. `&`：该列 `feet..feetY+2` 或探测方向内出现 `nonFull`，且不是 `%` 本身 → `&`（覆盖 `o/+/-/t/l/u/*`，但保留 `%` 前缀）。
3. `*`：`feet` 与 `head` 都不可通行，或 `feet` 是 full 且 `head` 是 full → 实心/堵死。
4. `u`：`head` 被 full/nonFull 占据，`feet` 可通行 → 卡头。
5. `l`：`feet` 是非完整方块（卡脚），`head` 可通行 → 卡脚；`feet` 是 full 则按 `+`/`o` 进入可达性判定，不要标 `l`。
6. `d`：`below..below2` 连续无实心支撑，且 `feet/head` 至少 2 格空气 → 深坑；不可达。
7. `t`：`feet..above3` 至少 2 格连续空气，且下方有支撑/可站立 → 向上竖井；不可达（除非 `tower`）。
8. `o` / `+` / `-` / `=`：由 BFS 判定。

#### 可达性 BFS

在 15×15×4 的 `(x,z,level)` 上做 BFS，`level ∈ {feetY-1, feetY, feetY+1, feetY+2}`：

- 起点 `(px,pz,feetY)`。
- 4 邻域（北/南/西/东），不斜走。
- 从当前 `(x,z,y)` 到邻居 `(nx,nz,ny)`：
  - `ny = y`：邻居 `feet` 可通行、`head` 可通行、`support(nx,nz,y)` 为实心 → 可达 `o`。
  - `ny = y+1`：邻居 `feet` 是 full、`head`/`above` 可通行、支撑为 `feet` → 可达 `+`。
  - `ny = y-1`：邻居 `feet/head` 可通行、支撑在 `y-1` → 可达 `-`。
  - 不允许一次上下 2 格；`t/d` 只标注，不参与普通步行 BFS。
- 只有 BFS 到达的格才可能标 `o/+/-/=`；不可达格用上面的 `* / l / u / d / t / &` 解释。
- BFS 中 `&`、`l`、`u`、`*` 都视为不可通行。

#### `=` 的定义

按玩家示例：

- `=` 必须是 **可达** 且 **落脚点高度等于当前 feetY**；
- 并且从该格可以“上下一格”：
  - 该格的 4 邻域中至少有一个可达 `+`（上）或 `-`（下），或
  - 该格自身列存在可站立的一格台阶/竖井过渡。

建议实现：

```text
if (reachable at same y) {
  if (any 4-neighbor is '+' or '-' or 't' or 'd') mark '='
  else mark 'o'
}
if (start cell meets the same test) mark '%='
```

如果实际示例要求“同时能上又能下”才标 `=`，在实现前先用示例 3 验证；这一点写进测试，不要凭直觉改。

#### 示例（必须作为单元/真机用例）

**示例 1：死胡同下矿通道**

```text
* u *
* % *
* + *
```

期望：`u` 卡头，`+` 可上一步，两侧 `*`；北边 `u` 提示需要挖掉头/视角水平方块。

**示例 2：矿道角落**

```text
* * * * *
+=o * *
* * o * *
o o % o o
* * * * *
```

期望：`%` 周围 `o` 可达；`=` 表示向上路口；`+` 在`=`旁边；`*` 不可达。

**示例 3：崎岖矿道**

```text
* * * * *
%= - = = +
* * * * *
```

期望：`%=` 是当前位置同时符合 `=`；`-` 可下一格；`+` 可上一格；`=` 是可达同高过渡。

**示例 4：右侧高台**

```text
* * * * *
* + = o %
* * * * *
```

期望：`%` 与 `o` 同高；`+` 高一格；`=` 是过渡；左上 `*` 可能是“堵死”也可能是“两格高台阶”，用 4 层数据判定，不能猜。

**示例 5：火把卡 tower**

```text
* * *
* &t% *
* * *
```

期望：`&` 标出非完整方块（火把/按钮），`t` 竖井，`%` 玩家；CR 应先清 `&` 再 tower。

#### 已知边界

- 只允许使用 15×15×4（外加 d/t 各一层）；不做全局寻路。
- 15×15 外一律 `*`（或不在 grid 内）。
- `&` 的“方向探测区域”优先按**该格列**判定；如果实现成“射线方向”，必须在文档和测试里固定，不要两种混用。
- `l/u` 与 `&` 冲突时，`&` 优先（用户要求“有非完整方块就标 &”）；如果 `l/u` 仍要保留，输出两字符 `l&`/`u&`。
- `t/d` 是信息标记，默认不算 BFS 可达；CR 只有用 `tower`/`mlg_water`/手动挖才处理。
- 任何格 `nonFull` 都要记录 `id`，便于 CR 知道是火把还是台阶。

---

## 3. 实现顺序

1. **生命周期**（最高优先，先修“关不掉”）：
   - state 多 pid、stopMcp 全量收集、killTree 验证、daemon owner TTL、插件 stop 钩子、`stop-mcp.ps1`。
2. **wait_events 10 s**：恢复注册/工具面/提示词/测试。
3. **query_layer / query_layer_foot**：mod 客户端采样 + server 注册 + 15×15 边界测试。
4. **tower preflight**：mod `tower_probe` + server handler + 火把/按钮/活板门/石头测试。
5. **reachable_synthese**：先实现列分类 + BFS + 标记，再用 5 个示例逐格断言。
6. **release**：`build-release.mjs`，跑 `run-all --slow`，更新 `SHA256SUMS.txt`。

---

## 4. 测试矩阵

> 详细可执行用例（ID/前置/步骤/期望/证据）见 [TEST-CASES.md](TEST-CASES.md)。

| 类别 | 用例 | 期望 |
|---|---|---|
| 生命周期 | start → stop | 两端口空、进程空、state 删除 |
| 生命周期 | stop ×10 | 幂等、无报错 |
| 生命周期 | 关客户端 → 60 s | server/daemon 退出 |
| 生命周期 | 关 DSH | 30 s 内退出 |
| 生命周期 | 两个 workspace | 只杀当前 |
| wait | 0 / 10 / 60 | 立即 / ≤10 / 最多 10 + capped |
| tower | 空列 h=1/10/64 | 正常 job |
| tower | 火把/按钮/活板门/石头 | 立即 tower_blocked + 全列 |
| query_layer | 平地/洞穴/边界 | 225 格、坐标正确 |
| query_layer_foot | 玩家脚高 | 与 query_layer 一致 |
| reachable | 5 个示例 | 逐格 marker 断言 |
| reachable | 15×15 外/未加载 | 不崩、标 `*`/unloaded |
| 回归 | run-all --slow | 全绿 |

证据目录：

- `evidence/ph8-lifecycle-*.json`
- `evidence/ph8-wait-events-*.json`
- `evidence/ph8-tower-preflight.json`
- `evidence/ph8-reachable-*.json`
- `evidence/ph8-query-layer-*.json`

---

## 5. 交付

- 代码：`zff-mc/src/mcp-control.mjs`、`zff-mc/index.js`、`mcp-server/src/server.js`、
  `mcp-server/src/mc-daemon.mjs`、`mod-mdk/.../Tools.java`、
  `mod-mdk/.../Reachability.java`（新）、`mod-mdk/.../TowerProbe.java`（新）、
  `mod-mdk/.../FishingJob.java`（新）、`mod-mdk/.../GuiProtocol.java`（新）、
  `mod-mdk/.../EnvInfo.java`（新）、`mod-mdk/.../CombatJob.java`（新）、
  `mod-mdk/.../CombatController.java`（新）。
- 测试：`test/ph8-lifecycle.mjs`、`test/ph8-wait-events.mjs`、`test/ph8-reachability.mjs`、
  `test/ph8-query-layer.mjs`、`test/ph8-fishing.mjs`、`test/ph8-gui.mjs`、
  `test/ph8-env-info.mjs`、`test/ph8-tool-low.mjs`、`test/ph8-combat.mjs`。
- 文档：本文件 + `docs/Ph8/REPORT.md`（完成后填）+ `docs/Ph8/PENDULUM.md`（Pendulum 安装/API/缺失降级）。
- release：`ZFF MCP Server.zip`、`ZFF Bridge.jar`、`zff-mc-plugin/`、`SHA256SUMS.txt`。

---

## 6. 尚未定死的决策（实现前用真机示例确认）

1. `=` 是否要求“同时能上又能下”，还是“任意一侧有一格台阶/竖井过渡”即可？  
   先用示例 2/3/4 验证；若与用户预期不符，改 `=` 判定并补测试。
2. `&` 的探测范围是“该格所在列”还是“朝玩家方向的一条射线”？  
   本文按**该格列**实现；若要射线，必须改算法和示例 5。
3. `l` 与 `&` 同时命中时，输出 `l`、`&` 还是 `l&`？  
   本文建议 `&` 优先；保留 `l&` 作为可读性选项。
4. 15×15×4 的 4 层固定为 `feet-1..feet+2` 是否正确？  
   若用户希望包含 `feet+3` 作为主层，需要把 BFS 也扩到 5 层。
5. 客户端关闭后的 `N` 秒默认 60 是否合适？  
   建议 60；太短会在换世界/重连时误杀。

---

## 7. 完成标准

- `run-all --slow` 全绿，新增 Ph8 测试全绿。
- 真机证据覆盖至少：stop 后端口/进程、tower 火把、reachable 5 示例、query_layer 边界。
- release 目录文件时间/哈希更新，`SHA256SUMS.txt` 与文件一致。
- `agent-prompt8.md` 的每个新工具都有对应 evidence，不允许只有代码没有证据。

---

# M2：生存与模组交互

## 8. M2.1 钓鱼（只钓一次）

### 现状

没有 fishing job。`use_item` 只能按住右键，不检测浮漂、不等咬钩、不收竿、不捡鱼。

### 工具

新增 `fish`（一次只抛一竿，不自动连钓）：

```json
{ "timeoutSeconds": 90 }
```

- 默认 90 s，范围 10–180；
- `casts` 参数**不要**提供；用户明确“只钓一次”；
- 成功后立即结束，不自动再抛。

### 状态机

```text
找水（水面/岸边）→ 选鱼竿 → 抛竿 → 等咬钩 → 收竿 → 捡鱼 → done
```

要点：

- 用 `FishingHook` / `player.fishing` 确认浮漂；
- 需要 mixin/accessor 读取 `FishingHook` 的咬钩状态（`currentState`、`timeUntilLured`、`nibble`）；
- 收竿用 `useItem`/`releaseUsingItem`，不能只右键一次；
- 捡鱼用 `collect_drops` 或内联走近；
- 鱼竿耐久低时按 M2.4 触发 `TOOL:`，不继续；
- 无水域/无鱼竿/背包满/超时返回明确 `error`，不创建新 job。

### 验收

- 天然水域一次抛竿，`caught` 至少 1 条（或明确 `timeout`）；
- 只抛一次，不自动连钓；
- 鱼竿耐久 1 时停止并触发 `TOOL:`；
- evidence：`evidence/ph8-fishing.json`。

---

## 9. M2.2 通用 mod GUI 协议（Create 重点特化）

### 9.1 为什么现有工具不够

`container_*` 只覆盖 `AbstractContainerMenu` 的槽位；没有：

- 按钮、标签页、复选框、输入框、滑动条；
- 自绘面板（Create `ValueSettingsScreen`）；
- 空手右键（Create 物理化）。

### 9.2 协议（GUI Protocol v1）

工具：

```text
gui_describe
gui_click { index | id | label }
gui_set { index, value }
gui_scroll { index?, delta }
gui_drag { from:[x,y], to:[x,y] }
gui_click_at { x, y }
gui_key { text | key }
gui_close
```

`gui_describe` 返回：

```json
{
  "screen": { "class": "...", "title": "..." },
  "widgets": [
    { "index": 0, "type": "button", "label": "Confirm", "x": 120, "y": 80, "w": 60, "h": 20, "enabled": true },
    { "index": 1, "type": "slider", "label": "Speed", "value": 32, "min": 0, "max": 256 },
    { "index": 2, "type": "editbox", "value": "16" }
  ],
  "menu": { "slots": 63, "playerInventory": true },
  "hitboxes": [{ "index": 3, "role": "tab", "x": 10, "y": 10, "w": 20, "h": 20 }]
}
```

坐标统一使用 **GUI 缩放坐标**（相对 screen 左上角），不是窗口像素；`gui_click_at` 也接受 GUI 坐标。

### 9.3 通用实现

- 遍历 `Screen.children()` / `renderables`；
- 识别 `AbstractWidget`、`Button`、`AbstractSliderButton`、`EditBox`、`Checkbox`、`TabButton`；
- 反射 `getX/getY/getWidth/getHeight/getMessage/active/visible`；
- `gui_click` 调 `onPress` 或注入 `mouseClicked`；
- `gui_set` 对 slider 调 `setValue`，对 editbox 调 `setValue`；
- `gui_scroll` 注入 `mouseScrolled`；
- 槽位继续用 `container_read/move`，不要重复造轮子；
- 不 import 任何 mod 类，只在适配器里反射；原版 + NeoForge + Bridge 无 mod 也能跑。

### 9.4 Create 特化（必须）

已确认 mods 内有 `create-1.21.1-6.0.10.jar`。重点：

1. **空手右键物理化**
   - `use_block` / `entity_interact` 增加 `hand:"empty"`；
   - 当前槽有物品时，临时切到空快捷栏槽，执行 `useItemOn`/`interact`，再切回；
   - 无空槽返回 `no_empty_hand`，不要拿手上物品硬点。

2. **转速控制器滑动**
   - 识别 `com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen`；
   - 适配器暴露当前值/上下限/可点击 value box；
   - `gui_set` 映射到 Create 的 value settings；`gui_click_at`/`gui_drag` 作为兜底；
   - 识别 `SpeedControllerBlockEntity` / `ScrollValueBehaviour` 的当前值，不用 OCR。

3. 其他 Create 界面（Clipboard、Blueprint、Toolbox、Linked Controller）先走通用层；自绘部分加适配器。

### 9.5 Apotheosis 特化

已确认 `[神化] Apotheosis-1.21.1-8.5.3.jar`。  
重点 Screen：

- `AugmentingScreen`
- `ReforgingScreen`
- `SalvagingScreen`
- `GemCuttingScreen`
- `GemCaseScreen`
- `WorldTierSelectScreen`

这些主要是 `AbstractContainerScreen` + 标准按钮/标签页：

- 槽位用 `container_move`；
- 按钮/标签页用 `gui_click`；
- 刷新/确认按钮用 `gui_click`，不要写死坐标。

### 9.6 覆盖边界

| 界面 | 覆盖 |
|---|---|
| 标准 ContainerScreen + 标准控件 | ✅ 通用协议 |
| Apotheosis 工作台 | ✅ 槽位 + 按钮/标签页 |
| Create ValueSettingsScreen | ✅ Create 适配器 + 坐标兜底 |
| 纯 canvas 自绘、无 widget | ⚠️ 只能 `gui_click_at`/`gui_drag` + screenshot，不能保证 |
| 需要拖拽排序/复杂手势 | ⚠️ 需适配器 |

不要承诺“所有 mod 100%”，只承诺：标准 GUI 全支持，Create/Apotheosis 重点适配，纯自绘有坐标兜底。

### 9.7 验收

- 原版：任意按钮/滑块 screen 可 describe/click/set；
- Create：空手物理化成功；转速控制器读出当前值并设置到目标值；
- Apotheosis：打开 Augmenting/Reforging，放入物品、点击确认、取出结果；
- 无 mod 的 vanilla+Bridge 客户端：协议仍能 describe/click 原版 GUI；
- evidence：`evidence/ph8-gui-*.json`、截图（Create/Apotheosis）。

---

## 10. M2.3 `query_env_info`

新增工具，立即返回，不创建 job：

```json
{
  "ok": true,
  "sounds": ["<走路声", ">僵尸低吼"],
  "time": {
    "game": { "day": 5, "dayTime": 6000, "phase": "noon" },
    "real": "2026-11-04T00:52:00+08:00"
  },
  "weather": { "rain": false, "thunder": false },
  "season": { "season": "SUMMER", "subSeason": "MID_SUMMER", "source": "sereneseasons" },
  "staleMs": 120
}
```

### 声音

- 优先读 `SubtitleOverlay` 的 `subtitles`（mixin accessor），格式与屏幕右下角字幕一致（含 `<`/`>` 方向）；
- 最多返回最近 8 条，去重；
- 字幕关闭时用 `SoundEngine.play` mixin 记录玩家能听到的 `SoundInstance`，避免依赖字幕设置；
- 不回放声音、不改客户端设置。

### 时间

- `game`：`level.getDayTime()`、`day`、`phase`；
- `real`：服务器本地时间 ISO 字符串（带时区）；
- 两者都要，不能只给一个。

### 天气 / 季节

- 天气：`isRaining` / `isThundering`；
- 季节：反射 Serene Seasons：
  - `sereneseasons.api.season.SeasonHelper.getSeasonState(level)`
  - `getSeason()` / `getSubSeason()` / `getDay()`
- 未安装 Serene Seasons → `season: null, source: "not_installed"`；
- 热带/季节关闭 → `season: "disabled"`；
- 不硬依赖 Serene Seasons。

### 验收

- `/playsound` 在玩家旁播放，返回对应字幕/声音；
- 时间/天气与实际一致；
- 安装/不安装 Serene Seasons 都能返回，不崩；
- evidence：`evidence/ph8-env-info.json`。

---

## 11. M2.4 工具耐久紧急事件

### 行为

- 自动挖掘（`mine_block`、`dig_shaft`、`dig_tunnel`、`branch_mine`、`tower`）时：
  - 每次 `BlockMiner` 造成耐久伤害前检查当前手头工具；
  - `remaining <= 1`（下一击会破）→ **立刻停止当前 job**；
  - 不自动换工具（让 CR 决定换/修/撤）；
  - 写入 EmergencyEventList 的 `tool_low`；
  - InfoTxt 行：
    ```text
    TOOL: minecraft:iron_pickaxe 耐久耗尽 (1/250)
    ```
    用户确认 `{工具}耐久耗尽` 语义；不要写成普通 `JOB:` 行。
- 对所有自动挖掘 job 生效；手动 `entity_interact` 不强制。
- 报警后清空等待下一次拉取；不要每 tick 刷屏。

### 验收

- 给 `Damage=1559/1561` 的镐，挖第 2 格前停止；
- job `failed/tool_low`；
- 下一次工具响应/`query_event` 出现 `TOOL:`；
- 不自动换备用镐；
- evidence：`evidence/ph8-tool-low.json`。

---

# M3：战斗系统

## 12. M3.1 全部原版武器（含三叉戟投掷）

### 现状

- `entity_interact attack` 直接攻击，没有冷却/距离/瞄准；
- `use_item` 能按住右键，但没有“蓄力满→释放”；
- `throw_item` 只右键一次，不能投三叉戟；
- 没有战斗 job。

### 工具

新增：

```text
combat_attack { target, weapon?, mode: melee|ranged }
combat_block  { shield: true }
combat_eat    { food }
combat_stop
```

覆盖：

- 剑/斧/锤：面向目标、`getAttackStrengthScale() >= 1`、暴击跳劈、盾牌；
- 弓：蓄力 20 tick、弹道下坠、释放；
- 十字弩：25 tick、多箭/穿透；
- 三叉戟：近战/蓄力投掷、忠诚返回；
- 盾：举盾、破盾、格挡；
- 雪球/鸡蛋可选。

### 远程弹道

- 目标位置 + 目标速度预测 + 重力/阻力迭代 2–3 次；
- 计算 yaw/pitch；
- 射线/视线检查，避免隔墙；
- 三叉戟与箭的初速/重力不同，按武器类型配置。

### 验收

- 对僵尸/骷髅：剑、斧、锤、弓、弩、三叉戟（近战+投掷）都能命中；
- 攻击冷却正确，不无限连点；
- 盾牌能挡一次攻击；
- evidence：`evidence/ph8-combat-weapons.json`。

---

## 13. M3.2 内建 AutoAim + Pendulum 可选适配

### 原则（玩家已确认）

- **AutoAim 由 Bridge 自己实现，永远启用**：
  - 不依赖任何外部 AutoAim mod；
  - 原版 + NeoForge + Bridge 组合也能用；
  - 不是“可选开关”，不允许被配置关闭；只允许 `combat_stop` 停止当前战斗动作，不能永久禁用自动瞄准。
- **Pendulum 是可选模组，允许安装**：
  - 允许把 Pendulum 加入整合包/测试客户端；
  - 所有**为 Pendulum 定制的战斗工具/功能**（自动近战、自动格挡、Pendulum 配置项）在缺少该模组时一律 `disabled`；
  - 缺少 Pendulum 不影响 Bridge 内建 AutoAim、原版武器、普通 combat。
- **不再寻找外部 AutoAim 模组**，也不再生成 AutoAim 调研文档；改为生成 `docs/Ph8/PENDULUM.md`，记录 Pendulum 的 mod id、版本、API/配置、安装方式、缺失时的降级行为。
- 默认 `combatMode: defend`；AutoAim 始终启用，自动攻击是否发起由 combatMode 决定。

### Bridge 内建 AutoAim

实现为 mod 内客户端能力，不依赖外部 mod：

```text
目标筛选（Enemy、可见、距离、威胁）
→ 目标点（眼睛/身体中心）
→ 弹道预测（箭/弩/三叉戟）
→ yaw/pitch 旋转
→ combat_attack 释放
```

要点：

- 近战：面向目标、攻击冷却、跳劈暴击；
- 远程：目标速度 + 重力/阻力迭代 2–3 次；
- 视线检查：射线不能穿墙；
- 只对 `Enemy` 生效，不对玩家/村民/友军；
- 公开服务器可能有反作弊风险，提示词/文档要明确告知玩家；
- AutoAim 不改变 `combatMode`：即使 `off`，手动/CR 发起的远程攻击仍自动瞄准；`off` 只表示不主动扫描/不自动攻击。

### Pendulum 适配

- 允许安装 Pendulum；任务包/测试环境可以加 jar；
- capability 探测：
  - mod id / 类是否存在；
  - 版本是否兼容 NeoForge 1.21.1；
  - 是否能读取/设置自动格挡/自动近战配置。
- 缺失时：
  - `pendulum.available = false`；
  - 所有 Pendulum 专属工具/配置项从 Agent 工具面隐藏或返回 `disabled`；
  - **不实现“内置自动近战”来冒充 Pendulum**；普通 `combat_attack` 仍可按 M3.1 手动打。
- 存在时：
  - 读取/设置其配置，或调用其事件；
  - 不把 Pendulum 当作硬依赖；启动/战斗不因缺失失败。

### Combat Controller

```text
query_env_info / EmergencyState.monsters
→ 目标筛选（Enemy、距离、视线、威胁）
→ 武器选择（远/近/盾/食物）
→ AutoAim（始终启用）
→ 攻击（冷却/暴击/盾）
→ 低血/多怪 → 撤退/吃/格挡
```

模式：

- `off`：不主动扫描/不自动攻击；手动 combat 仍可用 AutoAim；
- `defend`：被攻击/怪物靠近才反击；
- `aggressive`：主动扫描并出击；
- 默认 `defend`，玩家显式改 `aggressive`。

安全：

- 只打 `Enemy`，不打玩家/村民/友军；
- 服务器 PvP/反作弊规则优先；
- `combat_stop` 停止当前自动战斗，但 AutoAim 仍随下一次手动/自动攻击生效。

### 验收

- 未安装 Pendulum：`pendulum.available=false`，Pendulum 专属工具 disabled，不崩；AutoAim 仍可用；
- 安装 Pendulum：capability=true，自动近战/格挡配置可读/可设；
- 对僵尸/骷髅：`defend` 能自卫，`aggressive` 能清怪；
- 对玩家/村民/友军：不攻击；
- AutoAim 无开关；不依赖任何外部 AutoAim mod；
- evidence：`evidence/ph8-combat-auto.json`、`docs/Ph8/PENDULUM.md`。

---

## 14. 总体实现顺序（采纳建议）

1. **P0 生命周期**（MCP 关不掉/残留进程）——先修，否则所有测试都会被旧进程污染。
2. **P0 `wait_events` 10 s**。
3. **P0 M2.4 工具耐久**：改动小、收益高，先保护挖掘。
4. **P1 M2.3 `query_env_info`**：声音/时间/天气/季节。
5. **P1 M2.1 钓鱼**（只钓一次）。
6. **P1 M3.1 原版武器**（先近战/弓/三叉戟）。
7. **P1 地形工具**（`tower` 预探测、`query_layer`、`reachable_synthese`）——与生存/战斗共用底层方块采样。
8. **P2 M2.2 GUI 通用协议**：标准控件 + 空手右键 + Create/Apotheosis 适配器。
9. **P2 M3.2 Bridge 内建 AutoAim（永远启用）+ 可选 Pendulum**：AutoAim 不依赖外部 mod；Pendulum 缺失时禁用其专属功能。
10. **release + evidence**：所有项完成后统一打包。

> 顺序可并行，但 **M2.2 GUI** 不要和 M1 生命周期混在一起；GUI 是最大工作量，单独验收。

---

## 15. M2/M3 测试矩阵补充

| 类别 | 用例 | 期望 |
|---|---|---|
| 钓鱼 | 天然水域一次 | 抛一次、收一次、捡鱼/明确超时 |
| GUI | 原版按钮/滑块 | describe/click/set |
| GUI | Create 物理化 | 空手右键成功 |
| GUI | Create 转速控制器 | 读值/设值 |
| GUI | Apotheosis 工作台 | 放料/点击/取结果 |
| env | 声音/时间/天气/季节 | 字段齐全，无 mod 不崩 |
| tool | 耐久 1 挖矿 | 停止 + `TOOL:`，不换镐 |
| 武器 | 剑/斧/锤/弓/弩/三叉戟 | 命中，冷却正确 |
| 自动战斗 | 未装 Pendulum/已装 Pendulum | `pendulum.available` 正确；缺失只禁用 Pendulum 专属工具 |
| 自动战斗 | AutoAim | 无开关、始终启用；远程命中 |
| 自动战斗 | defend/aggressive | 自卫/清怪，不打友军 |

证据：

- `evidence/ph8-fishing.json`
- `evidence/ph8-gui-*.json`
- `evidence/ph8-env-info.json`
- `evidence/ph8-tool-low.json`
- `evidence/ph8-combat-weapons.json`
- `evidence/ph8-combat-auto.json`
- `docs/Ph8/PENDULUM.md`

---

## 16. 新增待确认点（M2/M3）

1. `fish` 默认超时 90 s 已确认；只钓一次已确认。
2. AutoAim 不找外部 mod，由 Bridge 内建、永远启用；不提供关闭开关。
3. Pendulum 允许安装；mod id / API 在安装后确认，当前整合包未安装，缺失时只禁用 Pendulum 专属工具。
4. `TOOL:` InfoTxt 用中文“耐久耗尽”；本文按 `TOOL: <id> 耐久耗尽 (x/y)`。
5. GUI `gui_click_at` 坐标是 GUI 缩放坐标；如果用户希望窗口像素坐标，需要额外参数。
6. 服务器反作弊：内建 AutoAim 永远启用，公开服务器风险由玩家承担；Pendulum 缺失/服务器禁止时只禁用 Pendulum 专属功能。
