# 验收记录

环境：Windows 11，`假期增援计划`，MC 1.21.1，NeoForge 21.1.228，Java 21.0.8 (Oracle)，JDK 21 构建。
客户端：真实有头客户端，真实 LWJGL；未使用 headless patch。

## Definition of Done

| 项 | 结果 | 证据 |
|---|---|---|
| 启动日志 0 个 `Caught exception during event` | **通过**（全日志 0） | [client-log-checks.txt](../../evidence/client-log-checks.txt) |
| 多人世界 `get_state` / `screenshot` | **通过**；`zmcp3.rhymc.com:1069`，players=1，overworld | [multiplayer.txt](../../evidence/multiplayer.txt)，[shot-1793198705922-1.png](../../runtime/state/screenshots/shot-1793198705922-1.png) |
| `scan_area(radius=16)` < 32 KB | **通过**：22,986 bytes，795 blocks，truncated=false | [scan16-final.json](../../evidence/scan16-final.json) |
| 容器 open → 取一组 → 回读 | **通过**：ChestMenu 63 slots，64 stone 往返，verified=true | [container.txt](../../evidence/container.txt) |
| `path_to` 走 100 格不卡死 | **通过**：progress=100，~18s，distanceLeft=0.72 | [path-100.txt](../../evidence/path-100.txt) |
| `path_to` 干净取消 | **通过**：state=cancelled，2s 后停止 | [path-100.txt](../../evidence/path-100.txt) |
| 客户端重启后任务从 memory 恢复 | **通过**：`acceptance.md` 多次重启后可读 | `D:\mc\.mcp-agent\acceptance.md` |
| MCP client 附加手动启动的客户端 | **通过**：`mc_attach` 实例文件 + `port+tokenFile`；DSH `mcp__mc__*` 已注册 | [dsh.txt](../../evidence/dsh.txt) |

## 其他已验证

| 项 | 结果 | 证据 |
|---|---|---|
| `place_block` | job done，`blocksPlaced=1`，方块回读 stone | [block-actions.txt](../../evidence/block-actions.txt) |
| `mine_block` | job done，`blocksMined=1`，方块回读 air | 同上 |
| `recipe_lookup` | 3 原版 + 3 模组物品均 matched；KubeJS 配方出现 | [recipes-final.json](../../evidence/recipes-final.json) |
| 实例心跳 | 5s 刷新，`mc_instances` 返回 ageMs/live；优先 inWorld | [heartbeat.txt](../../evidence/heartbeat.txt) |
| token 不泄漏 | 日志/evidence 搜索 `token_leaks 0` | [token-check.txt](../../evidence/token-check.txt) |
| DSH 工具数 | 31 | [dsh.txt](../../evidence/dsh.txt) |
| 关闭期 hook | 最终 jar 退出：`bridge shutdown failed` 0、`NoClassDefFoundError` 0、实例文件删除 | [client-log-checks.txt](../../evidence/client-log-checks.txt) |

## 已实现但未做完整运行时验收

这些代码路径存在并通过编译/基本检查，但没有在最终环境完整演练，交付时如实标注：

- `villager_trades` / `trade_select`：读取 `MerchantMenu` 报价和点击结果槽；未在村民旁执行完整交易。
- `entity_interact(action=trade)`：打开村民菜单；未验收。
- `container_move` 的 `split` / `toSlot` 显式移动、`trade_select.times>1`。
- `use_block`（门/按钮/拉杆）：方法为通用 `useItemOn`，未在最终客户端单独验收（避免修改目标服务器）。
- `chat` 的发送路径（`chat_history` 已验证；最终只读验收未向目标服务器发言）。
- 复杂转弯/跨地形 Baritone 路径：100 格直线平台已验证；本包 Baritone look 需要 `PathJob.steer` 辅助，复杂地形建议先小范围试。
- 超时/断开时 job 的 taxonomy：代码路径 `ClientPlayerNetworkEvent.LoggingOut` + deadline 已实现；未做故障注入演练。

## 截图

- 多人验收（最终）：[shot-1793198705922-1.png](../../runtime/state/screenshots/shot-1793198705922-1.png)（1920×1080，708,917 bytes）
- 多人场景（较早）：[shot-1793198394470-1.png](../../runtime/state/screenshots/shot-1793198394470-1.png)

## 测试环境说明

- `mcp-test-world` 是 `Nouveau monde` 的一次性副本，用于容器/挖掘/寻路；原存档未被修改。
- 目标服务器只做了加入、`get_state`、`screenshot`、`scan`（只读）；没有执行 `/tp`、`/fill`、`/gamemode` 或交易。
- 第一次连接目标服务器出现一次 `Erreur de protocole réseau`，重试成功；见排错文档。
- 测试客户端已在关闭回归后退出；旧的菜单客户端也已退出。MCP server 已切回生产默认 state（`%LOCALAPPDATA%\state\headlessmc-mcp\instances`，当前为空），用 PCL 启动客户端后会自动发现。
- 关闭期旧版曾出现 `NoClassDefFoundError: ScreenshotCapture`（shutdown hook 引用未加载类），最终 jar 已改为只关闭 HTTP/registry 并逐项捕获；最终退出回归为 0 错误。

## 2026-10-29 本地补测与修复（0.1.0 验收构建）

环境：真实有头客户端（PCL 启动，桌面处于锁屏状态），单人 `mcp-test-world`（生存/和平/开作弊），MCP `streamable-http`。
最终 jar：`dist/mcpbridge-0.1.0.jar`，**99,474 bytes**，SHA-256 **1DEDD0EA583B0B98142FCCC80B805FF7050E452FB79E3738DA0EF34532BB15F5**（本次修复后；旧交付 `F07ED22F…`、96,290 bytes 仍保存在 `runtime/backup/`）。

| 项 | 结果 | 证据 |
|---|---|---|
| `chat` 发送 + 服务器回显 | **通过**：`sent=true`，`chat_history` 同时出现本地 outgoing 与 `<FYNyesterday> …` 回显 | [chat-send.json](../../evidence/chat-send.json)、[chat-history-after-send.json](../../evidence/chat-history-after-send.json) |
| `use_block` 门 | **通过**：`open` false→true→false，碰撞箱随状态变化 | [fix-use-block-open.json](../../evidence/fix-use-block-open.json)、[door-after-open.json](../../evidence/door-after-open.json) |
| `use_block` 拉杆 | **通过**：`powered` true↔false | [lever-on.json](../../evidence/lever-on.json)、[lever-off.json](../../evidence/lever-off.json) |
| `use_block` 按钮 | **通过**：按下 `powered=true`，2s 后自动复位 | [button-pressed.json](../../evidence/button-pressed.json)、[button-released.json](../../evidence/button-released.json) |
| `container_move` quick / split / 显式 `toSlot`+`count` | **通过**：62→chest；split 31/31；`move count=31` 后光标为空；quick 回背包；石头总数仍 62 | [sched-quick.json](../../evidence/sched-quick.json)、[sched-split.json](../../evidence/sched-split.json)、[sched-move.json](../../evidence/sched-move.json) |
| `entity_interact(action=trade)` | **通过**：现在等待 `MerchantMenu` 打开并返回 `menuOpen=true`、`menuClass` | [fix-entity-interact.json](../../evidence/fix-entity-interact.json) |
| `villager_trades` | **通过**：读取 1 emerald → 3 bread 的报价与 uses | [villager-trades.json](../../evidence/villager-trades.json) |
| `trade_select`（修复后） | **通过**：真实服务器交易，`verified=true`（按物品数量核对），`times=1` 与 `times=2` 均正确，uses 5→6 / 6→8 | [trade-final.json](../../evidence/trade-final.json)、[trade-times2.json](../../evidence/trade-times2.json) |
| job 超时注入 | **通过**：`state=failed`、`reason=timeout`、`partial`，超时后玩家停止移动 | [job-timeout.json](../../evidence/job-timeout.json) |
| job 断线注入 | **通过**：直接 `disconnect` 时 `reason=disconnected` | [job-disconnect2.json](../../evidence/job-disconnect2.json) |
| 零截图完整流程 | **通过**：状态→聊天→寻路→容器→方块校验，19 次工具调用，`screenshot` 调用 0 次 | [no-screenshot-run.json](../../evidence/no-screenshot-run.json) |
| token 泄漏审计 | **通过**：3 个 token、1,716 个文件，`token_leaks 0` | `scripts/token-audit.mjs` 输出（会话记录） |
| 新构建回归：`place_block` / `mine_block` | **通过**：放置 dirt → 回读 → 挖掉 → 回读 air | 会话输出，坐标 `(6,100,-3)` |
| 新构建回归：`path_cancel` | **通过**：`state=cancelled`，2s 后位移 0 | [path-cancel-final.json](../../evidence/path-cancel-final.json) |
| 新构建回归：`scan_area(8)` / `recipe_lookup` | **通过**：5,770 bytes / 194 blocks；bread 匹配 6 条 | [scan8-final.json](../../evidence/scan8-final.json)、[recipe-bread-final.json](../../evidence/recipe-bread-final.json) |
| 工具面 | **通过**：`listTools` 仍为 31 | 同上 |

### 本次修复（都在最终 jar 内）

1. **`trade_select` 重写**：原实现只调用客户端菜单的 `tryMoveItems`，服务器从未收到付款，却因客户端预测把 `uses` 加 2，返回 `verified=true`（假阳性）。现在发送 `ServerboundSelectTradePacket`，等待服务器把付款移入交易槽，再逐次 `PICKUP`（每次恰好一笔），把结果放回背包，并用**物品数量差**核验；`times` 为真实交易笔数。
2. **`TickScheduler` 修复**：`tick()` 原来会在同一次 tick 内把新排队的任务继续跑完，导致 `poll(60)` 在 1 个 tick 内“超时”。现在按批取出本轮任务，期间新排队的任务留到下一 tick；所有依赖真实 tick 的轮询（容器回读、交易确认）才真正生效。
3. **`PathJob` 暂停容忍**：单人打开暂停菜单时 `Baritone` 会停止上报，原逻辑 2 秒后把任务判为 `internal`；现在暂停期间不累计 idle，断线仍映射为 `disconnected`。
4. **`use_block` 前后状态**：`before` 快照改为在交互前采集（旧实现两个字段都在交互后读取，无法看出状态变化）。
5. **`entity_interact(trade)`**：交互后等待 `MerchantMenu`（最多 40 tick），返回 `menuOpen/menuClass`，不再“客户端 SUCCESS 但菜单没开”。

### 未完成 / 风险

- **暂停容忍修复未做运行时复测**：当次客户端 HMC-Specifics 的 MCP 注册文件没有生成（无 `<pid>.json`），无法用 HMC 打开暂停菜单；代码路径已编译进 jar，但按“未测就不算通过”的原则标注为未验收。
- **HMC GUI 通道**：`hmc-mcp-bridge` 在部分启动中不注册（本次最后一次启动即如此）。只要它的 `/command` 可用，`scripts/hmc-call.ps1` 可执行 `gui/click/text/key/screenshot/quit`；不可用时本项目 bridge 仍可独立工作，但无法做 GUI 导航。
- **一次误入只读目标服务器**：桌面锁屏下盲点“多人游戏”列表，进入 `zmcp3.rhymc.com:1069` 约 40 秒，仅 `get_state` 观察后立即 `disconnect`，未移动、未挖掘、未放置、未交易。现已用 `scripts/join-world.ps1` 的 UUID 守卫避免再次发生。
- **两个旧钻石块仍在**：`(1,99,0)` 与 `(-1,99,0)` 都是 `diamond_block`（交接文档原写 `(-1,99,0)` 已为空，实测不符）。未触碰。
- **交易光标物品**：若在商人界面光标上持有物品时退出/关闭异常，物品可能丢失（测试中损失 1 个 emerald + 3 bread，均为测试物品）。修复后的 `trade_select` 结束时保证光标为空。
- 复杂地形/大角度转弯的 Baritone 路径仍只做了短距离验证。

## 2026-10-29 第三轮：生存特性（伤害来源 / 自动复活 / 自动搭高 / 快速搜刮 / walk）

构建：`dist/mcpbridge-0.1.0.jar`，**132,064 bytes**，SHA-256 **31996DAB56B7DCF95B320DA01A39C3801958C596C02F547C4F85DD3999D33745**（42 classes，40 个 MCP 工具）。

| 项 | 结果 | 证据 |
|---|---|---|
| 受伤检测 + 原因（摔落） | **通过**：4 格塔跳下，`watch_damage` 立即返回 `cause:"fall"`、`amount:2`、20→18 | [watch-damage-result.json](../../evidence/watch-damage-result.json) |
| 受伤检测 + 原因（岩浆） | **通过**：站在岩浆块上 `cause:"hotFloor"`、每次 1 点 | [damage-log2.json](../../evidence/damage-log2.json) |
| 自动复活 | **通过**：`/kill @s` 后 3s 内自动复活（`deaths=1`、位置为世界出生点、`lastDeath.lastDamage.cause=genericKill`），`keepInventory` 仅测试期间开启并已恢复 false | 会话记录 / `runtime/afterkill.json` |
| 自动搭高（`tower`） | **通过**：完整方块 4 格塔 y -60→-56；在楼梯/半砖上会快速失败并说明“needs a full block floor”（本轮修复） | [tower-4.json](../../evidence/tower-4.json) |
| 快速搜刮（`loot_chest`） | **通过**：24 组/1.66s、4 组/0.69s；4 次尝试中 2 次因**寻路**超时未到位（容器工具本身正常） | [loot-test.json](../../evidence/loot-test.json) |
| `walk`（客户端移动/跳下） | **通过**：走 8/15 tick 移动并跳下塔 | [walk-fall.json](../../evidence/walk-fall.json) |
| 搭高 26 格 → 摔死 → 自动复活 | **通过**：`tower` 26 格用时 13.0s（y -60→-34），跳下后摔落伤害 20、`cause:"fall"`、20→0；0.5s 内自动复活到世界出生点（`deaths=1`，界面自动关闭）；游戏日志 “a fait une terrible chute”；SimpleTomb 生成墓碑 (-186,-60,7)，`keepInventory` 为 false | [tower-26.json](../../evidence/tower-26.json)、[death-watch-result2.json](../../evidence/death-watch-result2.json)、[death-walk.json](../../evidence/death-walk.json) |

## 2026-10-29 第四轮：小问题修复 + 村庄搜刮基准

构建：`dist/mcpbridge-0.1.0.jar`，**132,854 bytes**，SHA-256 **1EDD6A380C3F8114A241516629895DDB231AEB6E81331219903A0180CE2F1AB6**（42 classes，40 工具）。

| 项 | 结果 | 证据 |
|---|---|---|
| `lastDeath.lastDamage` 竞态 | **修复并验证**：`/kill` 后 `lastDeath.lastDamage` 包含 `cause:"genericKill"`、`amount:20`、20→0 | `runtime/death3.json` |
| batch/tower/walk 取消 path 的 taxonomy | **修复并验证**：启动 batch 时在途 path job 现在是 `state:"cancelled"`（之前会报 `internal`） | `runtime/batch-cancel2.json` |
| `loot_chest` 超距快速失败 | **修复并验证**：253.55 格外的箱子 40ms 内返回 `unsupported: … walk within reach first`，不再等 60 tick | 会话记录 |
| 村庄搜刮基准（4 个箱子） | **3/4 箱成功、14 组物品**；`loot_chest` 实际耗时 0.49s（空箱）/0.97s（10 组）/0.66s（4 组）；单箱总耗时 1.0s/0.1s/4.0s/3.7s；path 1 成功 2 失败（失败时玩家已在 4.5 格内，仍可开箱） | [loot-bench.json](../../evidence/loot-bench.json) |

脚本坑（已修）：bridge 的 `/rpc` 返回 `{ok:true,result:{…}}`，之前的搜刮脚本直接读 `resp.jobId / resp.blocks`，导致“path=undefined / 轮询超时”的假象；`scripts/loot-bench.mjs` 现在会先解包 `result`。搜刮瓶颈仍是寻路到接近点，而不是容器作业。

本轮修复：`TowerJob` 启动时校验脚下是完整方块并取消 Baritone；`BatchJob`/`WalkJob` 启动时取消 Baritone（避免边寻路边建造/行走导致可及范围变化）。已知限制：寻路到箱子“接近点”仍可能超时（村庄屋内/门后），loot 前需先到位；`tower` 在悬空/半砖上不可用。

## 2026-10-29 第二轮：scan_surface / batch / parallel / 相机

构建：`dist/mcpbridge-0.1.0.jar`，**117,837 bytes**，SHA-256 **976A29751A0DD6FEA559F87ADE7DF9F57B1EAEC2752D775B55A34C4F1FE0F030**（36 classes；MCP 工具 34 个）。此构建取代上一节的 99,474 验收构建；旧 jar 在 `runtime/backup/`。

| 项 | 结果 | 证据 |
|---|---|---|
| `scan_surface` connected（radius 8，洞穴村庄房间） | **通过**：surface 616、heightMap 182、pits 54、doors 2、containers 4、22,067 B；超过 24KB 预算后按距离截断并标 `byteCap` | [scan-surface-connected2.json](../../evidence/scan-surface-connected2.json) |
| `scan_surface` los（360° 遮挡） | **通过**：visible 230 / occluded 386 | [scan-surface-los2.json](../../evidence/scan-surface-los2.json) |
| `scan_surface` fov（60°） | **通过**：visible 63 / occluded 553 | [scan-surface-fov2.json](../../evidence/scan-surface-fov2.json) |
| `batch` 放置 | **通过**：3/3 `verifiedOk`，整批约 0.5s，无中途查询 | [batch-place.json](../../evidence/batch-place.json) |
| `batch` 挖掘 | **通过**（无并发寻路时）：1/1；另一次在有 path job 同时移动时出现 2 次 reach 超时（批次被移动打断，已记录为已知限制） | [batch-mine3.json](../../evidence/batch-mine3.json) |
| `parallel` | **通过**：`scan_area` + `get_state` 同时返回 | [parallel-test.json](../../evidence/parallel-test.json) |
| 相机水平 | **通过**：寻路结束后 `pitch=0`；采样中 yaw 最大跳变 0° | 会话记录 / `runtime/final-turn3.json` |
| `container_move mode=take_one` | 已实现（PICKUP 一次 + 收进背包 + 物品计数校验），**未做运行时验收**（需要合成台） | - |

已知限制：`batch` 不主动取消 Baritone，若同时有 path job 在移动，玩家可能离开可及范围导致该次操作超时；发起建造批次前应先确保没有寻路任务在跑（或后续在 batch 启动时自动 `cancelEverything`）。`structureHints` 与“找门→进门→开箱”完整流程仍未实现。

## 2026-10-29 第五轮：事件通知 / 疾跑 / 游泳 / 一键合成

构建：`dist/mcpbridge-0.1.0.jar`，**142,039 bytes**，SHA-256 **D7A19BF32801C5E8FB90EA2E9798CFDC943CE600429F97B14B1AFA5D1404B7EE**（45 classes，42 工具）。

| 项 | 结果 | 证据 |
|---|---|---|
| `wait_events` 聊天通知 | **通过**：`/say` 后约 **0.45s** 内返回 event（type=chat, kind=chat）；阻塞调用在事件到达时立即返回 | `runtime/we-out2.json` |
| `wait_events` 受伤通知 | **通过**：`/damage` 后返回 `type:damage, cause:"generic", amount:2, health 20→18`，位置齐全 | `runtime/we-dmg.json` |
| 疾跑 `walk sprint` | **通过**：40 tick 普通 4.84 格 → 疾跑 **8.5 格** | 会话记录 |
| 游泳 `walk up` | **通过**：沉入 2 格深水池（`underWater=true`, `air=297`）后 `walk up` 浮出水面，`air` 回 300 | 会话记录 |
| 水体感知 | **通过**：`get_state` 新增 `inWater/underWater/swimming/eyeInWater/air/maxAir/feetBlock/eyeBlock` | `runtime/f4.json` |
| `craft` 2x2 | **通过**：木棍 ×4 用时 **688ms**（配方书放置 + 取结果，不再逐格点击） | `runtime/craft1.json` |
| `craft` 3x3（工作台） | **通过**：箱子 ×2 用时 **976ms**，按库存增量计数（`made:2`）；未解锁/材料不足/工作台不在 5 格内都有明确错误 | `runtime/crafty.json` |

已知限制：合成需要配方已解锁（未解锁时返回 `unsupported`，可用 `/recipe give` 或正常游玩解锁）；服务端对配方放置的响应有延迟，工具以库存增量判定成功；两名玩家同时操作同一个箱子会出现客户端不同步（物品在重登后恢复原位），本轮未改。

补充（JEI 式合成回退）：`craft` 现在在配方书放置 3 次无结果时，自动改走 JEI 的“+”按钮同样的路径——按配方图案逐格点入材料（右击放 1 个），再取结果；`manual:true` 可强制。实测 `craft {item:"minecraft:chest",manual:true}` 在 963ms 内合成 1 个箱子，背包 2→3，不依赖配方是否解锁（构建 143,489 bytes / SHA-256 21AE16DB…）。
