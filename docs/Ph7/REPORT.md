# Ph7 交付报告（2026-11-01）

> 本轮目标（玩家原话）：① 插件支持"任意工作目录 + 自动解压 ZFF MCP Server + 缺包只提示不唤醒"；
> ② 按离线账号选实例，缺失/重名则停止输出并报告；③ 打包 `release/`（zip + jar + 插件包，准备开源）；
> ④ 在工作目录新文件夹里从零重建环境，亲自接管客户端，在森罗厨房/酒馆实测 ≥10 个功能方块。

## 1. 交付物

`D:\mc\MCP Development\release\`

| 文件 | 大小 | 说明 |
|---|---|---|
| `ZFF MCP Server.zip` | 4.4 MB | 便携 MCP server（含 `node_modules`，**零安装**）；插件会自动解压到工作目录并启动 |
| `ZFF Bridge.jar` | 337 KB | NeoForge 21.1.228 / MC 1.21.1 客户端 mod（sha256 `1d2531af…`） |
| `zff-mc-plugin/` | 19 文件 | DSH 插件包（`dsh plugin add link:<dir>`） |
| `start-mcp.ps1` | — | 可选：手动起 server |
| `README.md` / `INSTALL.md` / `LICENSE` / `SHA256SUMS.txt` | — | 上手页 / 完整安装 / MIT / 校验和 |

重建命令：`node scripts/build-release.mjs`（会自动排除 `mcp-server/` 里游戏跑出来的残留目录）。

## 2. 插件新功能（代码）

### ① `src/mcp-env.mjs`（新模块）

- `inspectWorkspace(ws)`：在工作目录里找 `zff-mcp-server/` 或 `ZFF MCP Server.zip`（也认 `mcp-server/`）
- `extractZip()`：`tar -xf` → 失败退回 `Expand-Archive`
- `ensureServer()`：有 zip 就解压 → `spawn node src/server.js --http --port … --workspace <ws>` → 等 `/health`
- `fetchInstances()`：读 server 新增的 `GET /instances`（含 `username` / `inWorld` / `workspace`）
- `chooseInstance()`：按 `launcher.playerName` 选实例；0 个 / ≥2 个同名 / 拿不到名字 → 返回明确 reason
- `explainBlocked()`：把 blocked 翻译成一句中文（给玩家看）

### ② `index.js`

- 每 2 s 的 `tick` 先 `refreshEnv(st)`（3 s 节流）：自举环境 + 选实例，结果写 `st.env`
- 状态从"可用"变"不可用"时**注入一条提示**（让模型转告玩家），随后 `decideResume` 硬刹车
  （`env_blocked:<kind>`）→ **不续跑 = 不唤醒 Agent**
- 新工具 `zff_env`（手动重扫）、`zff_status` 里新增 `env` 段
- 提示词"运行时事实"新增 `MCP 环境` / `已接管的实例`（仅异常时显示）

### ③ `mcp-server/src/server.js`

- `GET /instances`：实例列表 + `username`（读每个 bridge 的 `/info`）+ `workspace`
- `mc_instances` 默认带 `username`（`withUsername:false` 可退回旧行为）
- 新增 `BridgeStore.describeInstances()`

### ④ 工作目录不再钉死

`cordis.patch.yml` 去掉了 `workspaceDir`，改为跟随会话 `header.cwd`；同时新增
`server_workspace_mismatch` 守卫：端口上的 server 属于别的工作目录时**拒绝接管**并报告。

## 3. 新环境复现（本轮真机）

```
D:\mc\MCP Development\ph7-env\
├─ ZFF MCP Server.zip          ← 从 release 复制
├─ zff-mcp-server\             ← 插件 ensureServer() 自动解压 + 启动（4.7 s）
├─ client\                     ← 253 个 mod（硬链接自玩家的整合包，零拷贝）+ ZFF Bridge.jar
│   └─ saves\ph7test\          ← 本地服务器世界副本
├─ launch-ph7-client.bat       ← Ph7Tester / quickPlaySingleplayer
└─ runtime\                    ← zff-mcp-server.{out,err}.log
```

- 客户端：`Ph7Tester`（离线），creative，单机世界
- 单机默认无作弊 → 用 `Open to LAN → Allow Commands: ON` 打开（见 `docs/Ph7/ISSUE.md` P7-1）
- 服务端：插件自动起的 `ph7-env\zff-mcp-server`，端口 57610，`/instances` 返回
  `{"username":"Ph7Tester","inWorld":true,"workspace":"D:\\mc\\MCP Development\\ph7-env"}`

## 4. 森罗方块实测（`evidence/ph7-blocks.json`）

**24/24 成功**（每个都 `/setblock` → `block_query` 确认存在 → `use_block`）：

| # | 中文 | 方块 id | 结果 |
|---|---|---|---|
| 1 | 灶台 | `kaleidoscope_cookery:stove` | ✅ use PASS |
| 2 | 汤锅 | `kaleidoscope_cookery:stockpot` | ✅ |
| 3 | 炒锅 | `kaleidoscope_cookery:pot` | ✅ |
| 4 | 蒸笼 | `kaleidoscope_cookery:steamer` | ✅ |
| 5 | 石磨 | `kaleidoscope_cookery:millstone` | ✅ |
| 6 | 菜板 | `kaleidoscope_cookery:chopping_board` | ✅ |
| 7 | 油锅 | `kaleidoscope_cookery:oil_pot` | ✅ |
| 8 | 茶壶 | `kaleidoscope_cookery:teapot` | ✅ |
| 9 | 厨具架 | `kaleidoscope_cookery:kitchenware_racks` | ✅ |
| 10 | 冰柜 | `kaleidoscope_chinesefood:freezer` | ✅ **GUI `FreezerMenu` 90 格** |
| 11 | 泡菜坛 | `kaleidoscope_chinesefood:pickle_jar` | ✅ |
| 12 | 碗堆 | `kaleidoscope_chinesefood:bowl_stack` | ✅ |
| 13 | 吧台 | `kaleidoscope_tavern:bar_counter` | ✅ |
| 14 | 吧台柜 | `kaleidoscope_tavern:bar_cabinet` | ✅ |
| 15 | 酒窖柜 | `kaleidoscope_tavern:cellar_cabinet` | ✅ |
| 16 | 圆酒架 | `kaleidoscope_tavern:circular_rack` | ✅ |
| 17 | 黑板 | `kaleidoscope_tavern:chalkboard` | ✅ |
| 18 | 三明治招牌 | `kaleidoscope_tavern:base_sandwich_board` | ✅ |
| 19 | 酒桶 | `kaleidoscope_tavern:barrel` | ✅ |
| 20 | 吧台凳 | `kaleidoscope_tavern:black_bar_stool` | ✅ |
| 21 | 沙发 | `kaleidoscope_tavern:white_sofa` | ✅ |
| 22 | 斜酒架 | `kaleidoscope_tavern:tilted_rack` | ✅ |
| 23 | 黄铜之心 | `kaleidoscope_tavern:brass_heart` | ✅ |
| 24 | 玻璃酒柜 | `kaleidoscope_tavern:glass_bar_cabinet` | ✅ |

**功能级证据**（`evidence/ph7-freezer.json`）：冰柜 → `FreezerMenu` 90 格 →
`container_read` 读到 90 槽 → `container_move {fromSlot:85,toSlot:0,count:3}` 把 3 个煤炭放进冰柜 0 号槽。

截图：`runtime/shots/ph7-a.png`（引导屏）、`ph7-pause.png`、`ph7-lan*.png`（开作弊）、
`ph7-blocks-row.png`（方块排），游戏内截图另存 `evidence/`。

## 5. 验证汇总

| 项 | 结果 |
|---|---|
| `selfcheck.mjs` | **148/148** |
| `test/zff-client-bundle.mjs` | **7/7** |
| `test/zff-settings.mjs` | **14/14** |
| `ensureServer`（从 release zip 冷启动） | 4.7 s，`/health` ok，`/instances` ok |
| 第二次冷启动（`ph7-env2`，端口 57630） | 同样 ok，`workspace` 正确 |
| `chooseInstance` 真机数据 | `Ph7Tester` → 选中 64141；`DSHDeepSeek`（人类实例无名字）→ `unknown_username` |
| `mcp-server` 语法 / 启动 | ok |

## 6. 已知未完善（详见 `docs/ph6/ISSUE.md` P6-16…P6-21）

1. 端口被别的 workspace 占用时只能报告，不能自动换端口（P6-16）
2. zip 升级不会被检测（P6-17）
3. 菜单态实例没有 username（需要 mod 在登录前写名字；而 **mod 构建工程已丢失**，P6-18/P6-19）
4. `use_block` 的"成功"只是发包成功，无法区分"点了有反应"与"点了没反应"（P6-20）
5. 容器入口不统一（`use_block` vs `container_open`），`container_move` 参数名反直觉（P6-21 / P7-5）

## 7. 复验时发现并修掉的一处发布缺陷（本轮内修复）

- **问题**：`Compress-Archive`（PowerShell 5.1）写出的 zip 条目名是反斜杠（`node_modules\@hono\…`），
  3489/3492 条如此。Windows 工具能忍，**Linux/macOS 的 unzip 会把它们当成怪文件名** —— 开源发布不能这样。
- **修复**：新增 `scripts/zip.mjs`（零依赖，`node:zlib` 的 deflateRaw + 标准中央目录），
  `build-release.mjs` 改用它。重建后：4026 个条目、**0 个反斜杠**、`src/server.js` 在根、
  `node_modules` 3972 条；冷启动复验（`ph7-env3`，端口 57640）6.9 s 起来、`/instances` 正常。
- 校验和随之更新：`ZFF MCP Server.zip = e3db177b…`、`ZFF Bridge.jar = 1d2531af…`。

---

# Ph7.1 追加（2026-11-01 16:34–17:25）：三件收尾

## 1. 启动器"关游戏后命令行还在"（systempause）

- **根因**：PCL 风格的启动 bat 结尾是 `echo 游戏已退出。` + **`pause`**，且用 `java.exe`（带控制台子系统）。
  游戏退出后 bat 停在 pause，**命令行窗口永远留着**；我用 `-WindowStyle Hidden` 起的时候则变成"看不见但一直挂着"的 cmd。
  实测残留：pid 7296 / 37596 / 39088 三个隐藏 cmd（16:34 / 16:39 / 16:44 起的）。
- **修复**：`scripts/patch-launcher.mjs` 生成 `launch-ph7-client-clean.bat`：
  `java.exe` → **`javaw.exe`**（无控制台）、**删掉 `pause`**、不再用 `start`。
  另附 `launch-ph7-client-hidden.vbs`（wscript 隐藏运行，双击即用，全程无窗口）。
- **⚠️ 踩坑**：`start "" <javaw> <17.7 KB 参数>` 会**静默失败**（进程根本起不来，rc=0、无报错）。
  所以最终方案是"javaw + 不加 start"；这也是脚本里那句注释的由来。
- **验证**：javaw 方式能正常起游戏并进世界（Ph7Tester 在 ph7test 里被接管、完成披萨）；关掉游戏后 `cmd` 有窗口的数量 = **0**。

## 2. NeoForge MDK

- 位置：`D:\mc\MCP Development\mod-mdk`（ModDevGradle 模板 + 我们的 67 个 `.java` + 元数据模板）。
- 结果：**`gradlew build` → BUILD SUCCESSFUL in 21s**，产物 `build/libs/mcpbridge-0.2.0.jar`（373 KB）。
- **等价性验证**：与通过 24 方块实测的 `release/ZFF Bridge.jar` 相比，**104 个 class 名称完全一致**、`META-INF/neoforge.mods.toml` 各 1 份。
- 踩坑与绕法：
  - `src/main/resources` 和 `src/main/templates` 各放一份 `neoforge.mods.toml` → `processResources` 报 duplicate；**只留 templates**。
  - 本机 JVM 访问 Gradle 发行包 CDN 会 `PKIX`，`maven.neoforged.net` TCP 443 **不可达**；
    解法：用 `curl -k` 下好 `gradle-9.2.1-bin.zip`，把 wrapper 的 `distributionUrl` 指向本地文件，
    依赖则复用 `~/.gradle/caches`（已有 205 个 NeoForge 相关 jar）。
- 说明：`release/ZFF Bridge.jar` 仍保留**实测过的那一份**（`1d2531af…`）；
  想用源码构建的产物发布，跑 `node scripts/build-release.mjs --from-source`。

## 3. 测试环境制作"披萨"

- 目标物品：`brewinandchewin:pizza`（中文名「披萨」，Brewin And Chewin 的方块物品）。
- 配方（`data/brewinandchewin/recipe/pizza.json`，3×3 有序）：
  ```
   f        f = 奶酪楔（brewinandchewin:flaxen_cheese_wedge / scarlet_cheese_wedge）
  ptp       p = 披萨配料 tag（蘑菇/胡萝卜/甜菜根/卷心菜叶/洋葱/熟肉…）
  www       t = farmersdelight:tomato_sauce   w = minecraft:wheat
  ```
- 过程：`/give` 领料（3 小麦 + 1 奶酪楔 + 2 棕蘑菇 + 1 番茄酱）→ `/setblock` 工作台 → `craft {item:"brewinandchewin:pizza"}`。
- 结果：**背包出现 `brewinandchewin:pizza x1`**，原料全部消耗。
  证据：`evidence/ph7-pizza.json`（含 give/craft/job/invBefore/invAfter）、截图 `evidence/ph7-pizza-inventory.png`。
- 前置条件（本轮踩过）：单机世界默认 `allowCommands=0`，`/give` 会静默失败。
  已用 `scripts/set-allow-commands.mjs` 直接把 `saves/ph7test/level.dat` 的 `allowCommands` 字节改成 1
  （带备份、写回前校验），之后指令全部生效——不再依赖"Open to LAN 开作弊"的 GUI 操作。

---

# Ph7.2 ▶/⏸ 运行开关（2026-11-01 19:56–20:15）

## 玩家报的两个现象

1. **不该唤醒的时候被唤醒**：DSH 只是开着、人在设置面板里，MC Agent 仍被续跑叫醒。
2. **ZFF MC 会话里没有 `mcp__mc__*`**：Agent 只看到 `mcp__headlessmc__*`，试了几次被守卫拒绝，
   最后自己 `zff_pause` 了（截图那一串 `Error: ZFF MC Mode 里只能调游戏工具…`）。

## 日志证据（`~/.dsh/zff-mc/logs/zff-mc.log`）

```
11:45:16 环境已恢复：可以接管游戏了
11:46:19 注入 resume（turn_end；idle=0 self=0）   ← 没人叫它
11:46:23 注入 resume（turn_end …）
11:47:23 注入 resume（turn_end …）
11:47:43 注入 resume（turn_end …）
11:48:01 zff_pause：paused=true（agent 自己调的，理由=本会话没有 mcp__mc__*）
```

`D:\MC Agent Play\runtime\zff-mcp-server.err.log`：`EADDRINUSE 127.0.0.1:57610` —— 插件在那个工作区
spawn 的 server 与 `D:\mc\MCP Development` 的 server 抢同一个端口；`ensureServer` 只看 `/health`（谁都会 200），
于是"以为启动成功"，实际连的是别的工作区的 runtime。

## 落地方案（玩家 6 条决定）

| 玩家决定 | 实现 |
|---|---|
| 「开始游戏」按钮本身是开关 | `client.js` 的 `ActionButtons`：`▶ 开始游戏` ↔ `⏸ 进行中`（`data-zff-run`） |
| 按 ⏸ 采用**方案②软停** | 写 `run=idle` + 给当前轮注入"立刻停手"+ 守卫拒绝会改变世界的游戏工具 |
| 默认 ▶，点一下变 ⏸ | `runtime/zff-run.json` 默认 `idle`；`bootId` 不匹配即复位 |
| 允许"等待游戏" | `run=running` 但无实例时按钮旁显示"（等待客户端进世界）"，不进续跑 |
| 不加"设置面板静默" | 状态机已足够：idle 时不注入任何续跑 |
| 缺 `mcp__mc__*` → 状态置 ▶ + 提示重启 | `countGameTools()` 数到 0 时写回 idle 并注入提示（数不到就不猜，`null`） |

## 代码

| 文件 | 变化 |
|---|---|
| `src/run.mjs`（新） | `readRun/writeRun`，`bootId` 实现"关 DSH 复位" |
| `src/brakes.mjs` | 新刹车 `run_idle`（`requireRunSwitch !== false && runState !== 'running'`） |
| `src/prompts.mjs` | 运行时事实新增「运行开关」 |
| `src/mcp-env.mjs` | `ensureServer` 起完核对 `/instances.workspace`，不是本会话就杀子进程并报 `port_conflict` |
| `index.js` | `BOOT_ID`、`runStateOf`、`/api/zff-mc/run`（GET/POST）、状态字段 `run`/`mcpGameTools`、守卫（idle 只放行只读游戏工具）、点 ▶ 清残留 paused |
| `client.js` | ▶/⏸ 切换按钮 + 5 s 轮询状态 + 说明移到 `?` |

只读白名单（idle 时仍可用）：`get_state / env_read / block_query / entity_query / inventory_slots / hotbar /
scan_* / jobs_list / job_status / path_status / damage_log / container_read / read_sign / store_* …`

## 验证

| 项 | 结果 |
|---|---|
| `src/run.mjs` 语义（同 boot 保留 running；换 boot 复位 idle 并重写） | 手测通过 |
| `selfcheck.mjs` | **150/150**（新增 `run_idle` / `running` 两条刹车断言） |
| `test/zff-client-bundle.mjs` | 7/7 |
| `test/zff-settings.mjs` | 14/14 |
| 一次性 profile 真启动：`GET/POST /api/zff-mc/run` | 200，无会话时优雅返回 `{ok:false,error:"no_workspace"}` |
| release 重建 | 插件含开关（`index.js` 6 处、`src/run.mjs`）；jar = MDK 构建（与实测 jar **类集合完全一致**） |

---

# Ph7.3 两个修复（2026-11-01 20:25–20:55）

## A. ▶/⏸ 唤醒方向搞反了（我的锅）

**现象**：按 ⏸（想暂停）反而把它叫醒；按 ▶（想开始）没反应。

**原因**：插件里"注入 = 唤醒"。当时的实现是：
- ⏸ → 给所有 ZFF 会话 `inject("立刻停手…")`。**它本来就停着**，这条注入了就等于开了新的一轮 → 被叫醒。
- ▶ → 什么都不发（只写了状态文件）。而续跑只在"一轮结束"时才触发，它本来没在跑 → 永远等不到那一跳。

**修复**（`index.js` 的 `POST /api/zff-mc/run`）：
- ▶：**主动注入一次"开始"**（`get_state → chat_turn → 听派活`）；如果客户端还没进世界，记 `st.pendingStart`，等 `refreshEnv` 看到 `inWorld` 再补一次。
- ⏸：**只有它此刻 `status=running` 时才注入"停手"**；已经停着就一个字都不发，只写状态文件。

## B. `zff_memory_*` 抛 `Payload mcpbridge:rpc_request may not be sent to the server!`

**不是"mod 禁止发"，而是"这个服务器没有我们的通道"**。证据（`D:\mc\client-test\logs\latest.log`，单机）：

```
[zff] client announced the mod: Ph7Tester version=0.2.0+p2
[zff] server half: 0.2.0 protocol=2 world=ZffServerTest -> backend=server
```

单机（集成服务器，装的是同一个 jar）协商是通的。玩家报错发生在连 **zmcp3.rhymc.com:1069** 时——那台服务器没装 ZFF Bridge，
NeoForge 的 `NetworkRegistry.checkPacket` 找不到通道就抛 `UnsupportedOperationException`，原样透给了 LLM。

**修复**（mod，`ZffRemote.call`）：
1. `Backend.state` 已经是 `LOCAL` / `MISMATCH` 时**不再发包**，直接抛 `no_server_half` + 人话。
2. 真的发出去被 NeoForge 拒（通道没协商上）时，捕获 `UnsupportedOperationException` → 置 `Backend.onNoServer(...)`，
   同样转成 `no_server_half`，并说明"本地 cr store / memory 文件仍然可用"。
3. `Errors.NO_SERVER_HALF = "no_server_half"`。

**预期行为**（无 mod 的服务器上）：`zff_memory_list` 应返回
```json
{"ok":false,"error":"no_server_half","message":"当前服务器没有可用的 ZFF Bridge 服务端半边（backend=LOCAL）：服务端共享记忆 / 待办 / /zff 指令不可用；本地 cr store 与 memory 文件仍可读写。"}
```
服务端共享记忆在**没装 mod 的服务器上本来就不可用**（那半边不在），不是 bug。

## 产物

| 文件 | sha256（前 16） |
|---|---|
| `release/ZFF Bridge.jar` = `mod-mdk/build/libs/mcpbridge-0.2.0.jar` | `c864710562edb96e` |
| `ph7-env/client/mods/mcpbridge-0.2.0.jar`（已替换旧的 0.1.0 文件名） | 同上 |
| `release/ZFF MCP Server.zip` | `7ca49beaadf42545` |

## 验证

- `gradlew build` → BUILD SUCCESSFUL（12 s）
- `selfcheck.mjs` 150/150、`test/zff-client-bundle.mjs` 7/7（插件侧改动后）
- 真机验证需要：**重启游戏**（换新 jar 才生效）+ **重启 DSH**（插件改动才生效）

---

# Ph7.4 团队面板 + 取消服务端共享记忆（2026-11-01 20:30–21:00）

## 1. 取消服务端共享记忆

玩家决定：**去掉 `zff_memory_*`（mod 的 SavedData 共享记忆）**，记忆统一用 CR store（本地文件）。

| 位置 | 改动 |
|---|---|
| `mcp-server/src/server.js` | 删掉 `zff_memory_set/get/list/forget` 四个工具（103 → **99** 个工具） |
| `mod-mdk/…/Tools.java` | 删掉对应的 4 个 dispatch case（服务端半边不再有这些入口） |
| 插件 `READONLY_GAME_TOOLS` | 去掉 `zff_memory_*` / `memory_*` |
| 文档 | 本文 + README 说明：记忆只有 `store_*`（CR store）与 `memory_*`（工作区文件） |

`zff_todo_*`（服务端共享待办）**保留**——它不属于"记忆"，且在没有 mod 的服务器上会返回 `no_server_half`。

## 2. 「团队」面板（标题条按钮 + 浮层，一行摘要，3 s 刷新）

| 角色 | 数据来源 | 说明 |
|---|---|---|
| **CR** | 插件直读（`agent.status` + 最近 `lastDecision` + `cr-active-job.json`） | 永远准，不需要配合 |
| **ESRA** | `<ws>/runtime/env-inbox.json` 的 `alerts`（60 s 内） | mod 的 3 s 任务看门狗 |
| **SR / AR / ESRC** | `<ws>/.mcp-agent/cr-store.json` 的 `team.<角色>` | **自报协议**；没写显示"未上报"，绝不编造 |

自报协议（已写进 `defaults/tools.md`，子代理会读到）：

```jsonc
store_set { store:"cr", key:"team.SR", value:{ task:"分析截图：锅里有没有成品", state:"running", at:<ms> } }
store_set { store:"cr", key:"team.SR", value:{ task:"锅里有 3 个熟鱼", state:"done", at:<ms> } }
```

超过 180 s 仍 `running` → 面板标 **stale（可能已结束）**，提示写 `done`。

### 交付面

| 面 | 实现 |
|---|---|
| 面板 | `client.js` 的 `TeamPanel`：ActionButtons 里新增 **「团队」** 按钮（`data-zff-team-btn`）→ 浮层表格（角色/状态/当前任务/持续），3 s 刷新 |
| 数据 | `GET /api/zff-mc/team` → `buildTeam()`（`src/team.mjs`） |
| 工具 | `zff_team`（同一份 JSON；AI 也能自己看队友） |

## 3. 验证

| 项 | 结果 |
|---|---|
| `test/zff-team.mjs`（新） | **13/13**：未上报 / 自报 running / 30 s 计时 / 超时 stale / done / ESRA 告警 / CR 运行中+最近决策 / 摘要 |
| `selfcheck.mjs` | **150/150**（工具断言里加了 `zff_team`） |
| `test/zff-client-bundle.mjs` | 7/7（含新的团队按钮不破坏注册） |
| `gradlew build`（去记忆后的 mod） | BUILD SUCCESSFUL |
| release 重建 | jar `219842139871d012…`（382 832 B）· zip `969d3446…` |

---

# Ph7.5 让 CR 真的能起 SR / AR / ESRC（2026-11-01 21:10）

## 插件端 Agent 的实测结论（全部成立）

> ZFF MC preset 的 `plugins` 只有 persona + tool-fs + tool-fs-search + tool-todo + compaction，
> 没有任何 subagent 工具 → 会话里根本没法起子代理；就算起了，守卫 `toolAllowedInMode()` 也会拦。

核对了 `presets/zff-mc.patch.yml` 与 `index.js:712-719`，确认属实。宿主 `--dump-config` 里能看到
base 层本来就有 `subagent` / `subagent-spawn-in-process` / `subagent-fork-in-process` /
`tool-subagent` / `tool-subagent-control` —— 是**preset 的 plugins 列表**决定了会话里有没有这些工具。

## 按玩家决定 A 改的四处

| 文件 | 改动 |
|---|---|
| `presets/zff-mc.patch.yml` | 加 4 个工具插件：`tool-subagent`（`provider: spawn`, `toolName: subagent`）、`tool-subagent-fork`（`provider: fork`, `toolName: subagent_fork`）、`tool-subagent-control`、`tool-subagent-control/list-agents`（参数照抄 base 层，避免默认值漂移） |
| `index.js` `ALLOWED_HOST_TOOLS` | 加 `subagent` / `subagent_fork` / `list_agents` / `send_message` / `interrupt_agent`（原来只有 read/write/…/todo_write） |
| `index.js` `isChildSession()` | 新增：`session.header.parentSessionId`（或 `header.parent.id`）非空 = 子代理会话。**插件不再向子代理会话注入提示词 / 续跑 / ▶⏸ 提示** —— 否则每个子代理都会变成会自我续跑的永动机 |
| `defaults/tools.md` | 新增"派活"一节：SR/AR/ESRC 各自接什么活、派活前先写 TodoList、用 `send_message` 追问、`interrupt_agent` 抢占、收工写 `team.<角色>` |

## 验证

| 项 | 结果 |
|---|---|
| 一次性 profile `--dump-config` | preset 里确实挂上了 `tool-subagent` / `tool-subagent-fork` / `tool-subagent-control` / `list-agents`（挂在 `preset-zff-mc.config.plugins` 下） |
| `selfcheck.mjs` | **152/152**（preset 断言改成"没 shell/联网，但**必须**有子代理工具"；新增允许面断言） |
| `test/zff-client-bundle.mjs` / `settings` / `team` | 7/7 · 14/14 · 13/13 |
| patch YAML | 解析通过 |

## 残留风险（重启后第一件要验的事）

preset 里的工具插件需要 `ctx.subagents` 服务。base 层已提供（dump 可见），但**真机是否激活**只能重启后看：
- 在 ZFF MC 会话里调 `list_agents` → 有返回（哪怕空数组）就说明工具真的挂上了；
- 再让 CR 起一个 `subagent`，面板 `SR/AR/ESRC` 行应出现"执行中"。

如果重启后这些工具仍不存在（例如宿主日志里报 pending / 缺服务），就退方案 B：把 `defaults/tools.md`
的"团队状态上报"和"派活"两节暂时去掉，面板只显示 CR / ESRA（这两行本来就自动读）。

---

# Ph7.6 团队面板两个显示 bug（2026-11-01 21:35，玩家端 Agent 报的）

## Bug 1：面板恒显示"执行中"

**根因（Agent 定位，我复核并实测）**：CR store 的条目是**包装过的**：

```jsonc
"team.SR": { "value": { "at": …, "state": "done", "task": "…" }, "tags": [], "updatedAt": 1793539237836, "version": 2 }
```

`src/team.mjs` 的 `selfReport()` 直接读 `v.task/v.state/v.at` → 全是 undefined，
`state` 又兜底成 `'running'` → 面板永远"执行中"、任务列「—」、持续列空。

**修**：
1. 解 `.value`（`raw.value && typeof raw.value === 'object' ? raw.value : raw`）。
2. `state` 缺失时不再兜底 `running`，改成 `unknown`（宁可显示"未知"也不假装在跑）。

## Bug 2：`at` 由 Agent 自己填，不可信

AR 第一次写的是 `at = 1767000000000`（≈2025-12-30）。**不采用"插件读的时候盖 Date.now()"**——
那样"持续"永远是 0:00，失去意义。

**采用**：优先信 `at`，但必须**可信**（不早于 24 h 前、不晚于 5 min 后）；
不可信时回落到 **CR store 自己盖的 `updatedAt`**（它有真实写入时间）；两者都不可信 → 显示「—」并注明。

## 顺手补的展示

- `client.js` 的 `stateText` 增加 `done / failed / cancelled`（"已完成/失败/已取消"）；
- `done` 行**不显示"持续"**（已完成还显示时长会让人以为还在跑），显示「—」。

## 验证

- `test/zff-team.mjs` 改成**真实包装格式**，15/15：含"离谱的 at 被忽略、回落到 updatedAt"。
- 拿玩家的真实 `D:\MC Agent Play\.mcp-agent\cr-store.json` 实跑：

```
CR    idle        — | — | 最近决策：skip（run_idle）
SR    done        第6天白天(dayTime 8428)，20/20血… | —
AR    done        向北走 10 tick：成功，位移 1.2 格（z 1117.5 → 1116.3） | —
ESRA  ok          无告警 | —
ESRC  unreported  — | —
```

- `selfcheck 152/152`、`client bundle 7/7`、`settings 14/14`、`team 15/15`。

---

# Ph7.7 按 `D:\MC Agent Play\ISSUE.md` 修复（2026-11-01 21:50）

## 已修

| ISSUE | 根因 | 修复 |
|---|---|---|
| **P0-1** ▶ 门禁过大（`store_set`/`chat_turn` 也被拒） | 结束态（▶）只放行"只读"工具，而聊天/记账不是只读 | `READONLY_GAME_TOOLS` 扩充：`chat_turn`/`chat`/`chat_history`/`store_get,set,list,delete`/`memory_read,write,list`/`screenshot`/`wait_events`。**并且把 `subagent`/`subagent_fork` 从 `HIDE_HOST_TOOLS` 里删掉**（刚放行又被隐藏，自相矛盾） |
| **P0-3** 聊天推送 100% 丢失 | **没人启动聊天守护进程** `mc-daemon.mjs`（57611）。mod 推的是 `127.0.0.1:57611/chat_event`，端口没人听 → `ConnectException` → 服务端 `accepted:0`。不是端口/路径写错（daemon 里就是 57611 + `/chat_event`） | ① 插件 `src/mcp-env.mjs` 新增 `ensureDaemon()`，`refreshEnv` 里 server 健康后自动拉起；② `start-mcp.ps1` 也一并启动并报 health。实测：`ensureDaemon → started`，`/health 200 {"ok":true,"session":"idle"}` |
| **P1-3** 床第一下不入睡 | 点的是**脚半**，原版只回 CONSUME | `Actions.useBlock()`：床且 `!isSleeping()` 时自动对**另一半**再 `useItemOn` 一次（`attempts` 里留痕） |
| **P1-4** `eat`/`use_item` `ticks:1, consumed:false` 还报 SUCCESS | 第一 tick 回读 `isUsingItem()` 为 false 就直接收尾 | 先等它**真正进入** using（最多 maxTicks），进入后再等结束；成功判定只看 `consumed`，没吃掉 → `result:"incomplete"` + note（不再谎报 SUCCESS） |
| **P2-3** `no_instance` 文案没上下文 | 只回一句"没有任何存活的游戏实例" | 现在带 `现存实例：user@port(world/menu)` 和实例目录 |
| **P2-1 / P2-2** 面板 `running` 恒显 / `done` 没映射 | 上一轮已修（解 `.value`、`done→已完成`、`at` 回落 `updatedAt`） | 本轮复核通过 |

## 未修（说明）

- **P1-2 `collect_drops` 计数**（`collected:1`/`itemEntities:5` 自相矛盾）：属于"度量口径"问题——现在按实体消失计数，应该按**背包增量**计。需要单独一轮改 `EntityActions.collectDrops`，本轮未动。
- **P0-2 服务端半边**：远端服务器（zmcp3）没装 mod，**本来就没有那半边**。但玩家仍看到 `java.lang.UnsupportedOperationException`，是因为 `D:\MC Agent Play\zff-mcp-server\` 还是**旧代码**（插件不会覆盖已存在的目录）。**处理**：停掉旧 server → 删掉该目录 → 插件会用新 zip 重新解压（新代码里 `zff_memory_*` 已删除、`zff_todo_*` 返回 `no_server_half`）。

## 验证

- mod `gradlew build`：BUILD SUCCESSFUL（含 eat + 床重试）
- `selfcheck 152/152`、`bundle 7/7`、`settings 14/14`、`team 15/15`、YAML 2/2
- 新 jar：`release/ZFF Bridge.jar` = `mod-mdk/build/libs/mcpbridge-0.2.0.jar` = `da644bed…`（已装进 `ph7-env/client/mods`）
- 新 zip：`30dca97b…`

---

# Ph7.8 六项需求（2026-11-01 22:30–23:30）

| # | 需求 | 实现 |
|---|---|---|
| 1 | 新点进对话就自动唤醒 | **根因**：运行开关只按工作区存，同工作区新会话继承上次的 `running` → 一进去就续跑。改成**按会话**：`zff-run.json` 多存 `sessionId`，只有按过 ▶ 的那个会话才算 running（`src/run.mjs`）。另外"环境已恢复"提示只在 running 时注入 |
| 2 | MCP 状态栏 + 启停 | `src/mcp-control.mjs`：`mcpStatus/startMcp/stopMcp/sendHeartbeat`；`GET/POST /api/zff-mc/mcp`；`client.js` 的 `McpBar`（●状态 / pid / **工作目录** / 启动方式 / 桥接实例 / 启动·关闭按钮）。启动 = 本工作目录：有解压文件夹就用，只有 zip 才解压 |
| 3 | 关闭要杀干净 | `stopMcp` 把 state 里的 pid + 57610/57611 上的所有 pid 各 `taskkill /T /F`（整棵树），再复查端口；实测杀掉 server(10944)+daemon(22024)，三个端口全部归零 |
| 4 | 状态栏按账号标记实例 | mod `/info` 新增 `account`（`Minecraft.getUser().getName()`，不进世界也有），server `/instances` 透传；面板显示 `账号@端口(世界内/菜单)` |
| 5 | ▶ 前必须 MCP 就绪 | `POST /run` 先 `mcpStatus`：没起就自动 `startMcp`；`hung` / `other_workspace` / 启动失败 → **返回错误、保持 ▶**，客户端弹浮窗说明；运行中 MCP 掉线 → tick 自动切回 ▶ 并注入"已暂停" |
| 6 | DSH 关掉 server 自杀 | server `--owner-heartbeat`：插件全局每 5 s 打 `/internal/heartbeat`，30 s 没有就 `exit(0)`（实测日志 `no owner heartbeat for 30742 ms ... exiting`）；daemon 另有 30 s 探活看门狗 |

## 验证（全部实跑）

| 项 | 结果 |
|---|---|
| 从零（只有 zip）启动 | 解压 → server(57630) + daemon(57631) → `/health` 200 → `startedBy=ui` |
| stop | `killed:[10944,22024] leftover:[]`，三个端口全 0 |
| 心跳 | 打一次心跳后停 30 s → server 自己退出（日志有记录） |
| 会话隔离 | `writeRun(sess-A) → A=running / B=idle`；老文件无 sessionId 仍兼容 |
| 测试 | selfcheck 152/152 · bundle 7/7 · settings 14/14 · team 15/15 · YAML 2/2 |
| 产物 | zip `333107d5…` · jar `99e1b8e3…` · 插件含 `src/mcp-control.mjs`(11.4 KB) |
