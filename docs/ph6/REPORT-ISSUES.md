# Ph6 ISSUE 修复报告（issuework 并行分支）

> 工作目录：`D:\mc\issuework`（`D:\mc\MCP Development\mod` 的独立副本，稍后由主代理合并回主工程）
> 编译：`powershell -ExecutionPolicy Bypass -File D:\mc\issuework\build.ps1 -ProjectRoot D:\mc\issuework`
> 最后一次构建输出：`jar: D:\mc\issuework\dist\mcpbridge-0.1.0.jar` / `classes: 99`
> 验证范围：**只做了编译验证，没有启动 Minecraft、没有连服务器**（按任务要求）。
> 可改文件：`Actions` / `ItemActions` / `Perception` / `ScanSurface` / `ScreenshotCapture` / `PathOpenJob` /
> `InventoryActions` / `GameState` + 新增 `IssueTools.java`；`Tools.java`、`ChatLog.java`、`BridgeRuntime.java`、
> `ZffPayloads.java`、`ZffServer.java`、`ZffHooks.java`、`McpBridge.java`、`client\*.java`、`neoforge.mods.toml`、
> `build.ps1` **一行未改**。

> 接入方式（主代理做）：`Tools.call` 的 `default` 分支改成
> `default -> { Object r = IssueTools.call(tool, args, requestedTimeout); if (r == null) throw Errors.unsupported("unknown tool: " + name); yield r; }`
> ——`IssueTools.call` 返回 `null` 仍表示“不认识这个工具”。

## ISSUE-2（严重）睡觉后起不来 → `wake`

- **复现**：`use_block` 点床 → `screen=InBedChatScreen`，之后没有任何工具能下床；`stop_all` 的 `screenClosed:false`，`disconnect`/`walk`/`jump` 都无效，只能等天亮或全员入睡。
- **根因**：bridge 只有“上床”（床方块的 `useItemOn`），没有“下床”；睡觉是服务端同步的 entity data（`LivingEntity.SLEEPING_POS_ID`），不是 `mc.screen`，所以 `mc.setScreen(null)` 关不掉，`player.isSleeping()` 又没被任何工具读出来。
- **改动**：新增 `wake` 工具（`IssueTools`）：客户端线程发 `ServerboundPlayerCommandPacket(player, STOP_SLEEPING)`，再用 `TickScheduler.poll(60)` 逐 tick 回读 `player.isSleeping()`；`GameState.player` 增加 `sleeping`、`bedPos`（`getSleepingPos()`，有才给）、`sleepTimer` 三个字段。
- **游戏内验证**：`use_block` 上床 → `get_state` 应出现 `player.sleeping:true` 与 `player.bedPos`；调用 `wake` → 返回 `{sleepingBefore:true, sleeping:false, woke:true, ticks:N}`；`get_state` 的 `screen.open` 变 false。
- **风险**：服务端插件若拒绝 `STOP_SLEEPING`，60 tick 后会返回 `{woke:false, sleeping:true, reason:...}` 而不是抛 timeout（可诊断）；`InBedChatScreen` 的关闭仍依赖客户端自己的 tick，代码只做兜底 `setScreen(null)`。

## ISSUE-3（中）不能吃东西 → `eat` + `throw_item.holdTicks`

- **复现**：`throw_item {item:"minecraft:bread"}` 返回 SUCCESS，但面包数量不变、food 不变；连发两次也不行；只有蛋糕方块（`use_block`）能吃。
- **根因**：`ItemActions.throwItem()` 只调一次 `gameMode.useItem` 就返回，相当于“按下右键的瞬间”；进食需要客户端保持 `isUsingItem` 至少 `getUseDuration` tick（食物通常 32 tick），再由 `releaseUsingItem`/自然完成收尾，bridge 没有这个状态机。
- **改动**：新增 `eat {slot?|item?, hand?, maxTicks?}`：选中/自动把主背包物品换到快捷栏 → `gameMode.useItem` → `TickScheduler.poll` 逐 tick 等 `!isUsingItem`（超时强制 `releaseUsingItem`）→ 回读 `foodBefore/foodAfter/countBefore/countAfter/consumed`。另加通用 `use_item`（弓/药水也行）和 `throw_item.holdTicks`（按住 N tick 后由 tick 线程松开；同步返回，不等完成）。
- **游戏内验证**：`eat {item:"minecraft:bread"}` → 返回 `consumed:true`、`countAfter=countBefore-1`、`foodAfter>foodBefore`；`throw_item {item:"minecraft:bow", holdTicks:20}` 返回 `holdState:"scheduled"`，20 tick 后应松手。
- **风险**：`eat` 要求物品带 `DataComponents.FOOD`（1.21 原版/绝大多数模组食物都有；纯自定义 use 逻辑的食物请用 `use_item`）。`throw_item.holdTicks` 是“排程松开”，不保证同一次响应里看到吃完——需要确认完成请用 `eat`/`use_item`。

## ISSUE-4（中）`place_block` 只认快捷栏 → 自动 hotbar 交换

- **复现**：木桶在主背包 35 格，`place_block` 直接 `unsupported: no matching block item in the hotbar`。
- **根因**：`Actions.PlaceTask` 只用 `findItemSlot`（0..8 快捷栏）找方块，没有“从背包换到快捷栏”的路径。
- **改动**：新增 `Actions.findItemSlotAll`（快捷栏优先，再搜 9..35）；`PlaceTask` 找到主背包物品时调 `InventoryActions.quickSwap(player, invSlot, 当前快捷栏槽)` 换进来再放置，3 tick 后校验时自动换回；`InventoryActions` 抽出 `quickSwap`（带“容器界面不能开”的保护），`swap` 复用同一实现。结果增加 `item/autoSwap/swappedFrom/hotbarSlot/restored`。
- **游戏内验证**：把木桶放进背包第 35 格 → `place_block {item:"minecraft:barrel",x,y,z}` → 放置成功且返回 `autoSwap:true, restored:true`；随后 `inventory_slots` 里原快捷栏物品应回到原槽。
- **风险**：`restored` 表示“回换点击已发出”（客户端预测），不是服务端确认；若放置过程中背包被别的操作改动，回换可能不完美。开着箱子/容器时 `quickSwap` 会拒绝（返回失败），避免点错槽。

## ISSUE-5（中）`path_open` 顺手开箱子 → 只开门 + 自动关界面

- **复现**：`path_open` 到点后 `get_state` 常挂 `ContainerScreen (ChestMenu)`，本场 `doorsOpened` 最高 17，每次都要补 `container_close`。
- **根因**：`PathOpenJob.openNearbyDoors` 只要方块有 `OPEN` 属性就右键；`BarrelBlock` 也有 `OPEN`，于是木桶/箱子被顺手打开（并不只有门）。
- **改动**：默认只对 `DoorBlock` / `FenceGateBlock` / `TrapDoorBlock` 发交互（`openContainers:false`）；`PathOpenJob` 新增 5 参构造（true 时恢复旧的全开行为，并单独统计 `containersOpened`）；寻路成功、超时、blocked、取消时都调用 `closeContainerIfOpen()`（直接 `closeContainer()+setScreen(null)`，等价于 `Containers.close()` 但不用多等一 tick）。结果增加 `containersOpened/containerClosed/openContainers`。
- **游戏内验证**：`path_open` 穿过有木桶的村庄 → 结束后 `get_state.screen.open` 应为 false，`result.containerClosed` 为 true，`doorsOpened` 只统计门/栅栏门/活板门。
- **风险**：`Tools.pathOpen` 仍是 4 参构造，默认就是“只开门”；需要旧行为请用新增别名 `path_open_ex {..., openContainers:true}`（或主代理在 `Tools.pathOpen` 里透传 `Json.b(args,"openContainers",false)`）。若玩家本来就开着箱子去寻路，结束时会把它关掉。

## ISSUE-6（中）1 格门永远 blocked → 只做“对齐重试一次”（保守）

- **复现**：小屋北门 `/setblock` 在 `-4809,73,1157`（云杉门 `open=true`），`path_to`/`walk` 西向永远 `blocked`；南门正常。
- **根因**：Baritone 对“门打开后的碰撞板 + 1 格通道 + 0.3 格对齐误差”的判定，属于寻路器内部行为；改 Baritone 风险大，且会动到现有 84 个工具的寻路稳定性。
- **改动**：**不改 Baritone**。`PathOpenJob` 在“140 tick 无位移”时只做一次：找 3 格内最近的门/栅栏门（关着的优先）→ 取消 Baritone → 直接把玩家速度指向门方块中心 `(x+0.5,z+0.5)`（最多 80 tick，卡住会跳）→ 到位后重新开同一条 path；再次卡住仍按原来的 `blocked` 退出。`path_open` 的日志里会出现 `metrics.state=align` / `alignedTo`。
- **游戏内验证**：在北门内侧 `path_open` 到门外 → 若仍失败，`job_status` 的 `metrics` 里应能看到 `aligningTo`/`alignedTo`，且失败信息与之前一致（不会更糟）；若成功则 `doorsOpened` 增加且不再 blocked。
- **风险**：**未真正修复**（Baritone 行为未变），只增加一次“贴到门中心再 path”的自救；门被方块堵住、门上方有台阶、或目标本身不可达时仍会 `blocked`。对齐会短暂脱离 Baritone 控制（最多 80 tick），不影响其它 job 的现有逻辑。

## ISSUE-7（低）`walk` 的 yaw 语义 → 文档 + `walk_dir`

- **复现**：手册没写 yaw 含义；实测 `0→+Z(南) / 90→-X(西) / 180→-Z(北) / 270→+X(东)`。
- **根因**：纯文档缺失。
- **改动**：核对 `WalkJob.tick` 的公式 `dx=-sin(rad), dz=cos(rad)`，与上述映射**完全一致**（`Tools.walk` 把 `yaw` 原样传给 `WalkJob`，没有额外偏差）；新增语义化工具 `walk_dir {direction:north|south|east|west}`（内部换算成同一组 yaw），并在本文与 `ISSUE-TOOLS.md` 写清映射。
- **游戏内验证**：`walk_dir {direction:"north",ticks:20}` → `get_state` 的 z 减小；`south` 则 z 增大；`east` x 增大；`west` x 减小。
- **风险**：无（`walk`/`WalkJob` 未改动行为）。

## ISSUE-8（低）`scan_surface` 不含非整格方块 → `includeNonFull` + `doors` 修好

- **复现**：门、活板门、床、告示牌都不在 `surface`，`doors` 字段 25 次扫描全空；找门只能用 `block_query` 网格。
- **根因**：`ScanSurface.isOpen()` 把门/活板门/栅栏门一律当成“可通行”，flood fill 直接穿过它们，所以它们既不是 surface 邻接方块，也不进 `doors`（`doors` 只用 `entries` 里的 `DoorBlock` 构造）。
- **改动**：新增 `includeNonFull`（默认 true）：从 flood fill 访问过的格子及其 6 邻接补扫 `*_door` / `*_trapdoor` / `*_fence_gate` / `*_bed` / `*_sign`，追加进 `surface`（第 6 个元素 `"nonfull"` 标记），并汇总到 `doors`；`doors` 改成对象 `{pos,id,type,open?,facing?,part?,occupied?}`（旧格式恒为空，不会破坏真实调用方）。新增 `includeNonFull/nonFullCount/nonFullTruncated/regularSurfaceCount` 字段。
- **游戏内验证**：站在门口 `scan_surface {radius:8}` → `doors` 非空（每扇门上下两半都会列出），`nonFullCount>0`，`surface` 里能看到带 `"nonfull"` 的条目；`includeNonFull:false` 时行为回到旧版。
- **风险**：`doors` 元素从数组变成对象（此前恒为空，实际风险低）；非整格补扫最多 25 万次邻接检查，`radius` 很大时可能触发 `nonFullTruncated:true`（结果不完整但显式标记）；`los/fov` 模式不对 nonfull 做视线过滤。

## ISSUE-11（中）营火 `unsupported` → `use_block.mode` + 顶面重试

- **复现**：把 `extradelight:green_coffee` 放到营火上烘烤，`use_block` 对营火返回 `unsupported`/无效果，贴到 1.9 格、换正面/顶面都一样。
- **根因**：旧 `use_block` 只做“方块状态交互 + 不消耗时回退 useItem”，没有把“手上物品放到方块顶面”的 `useItemOn` 语义单独暴露，也没有针对营火顶面的命中点重试。
- **改动**：`Actions.useBlock` 增加 `mode: "block"(默认)|"item_on"|"use_item"`；`item_on` 强制走 `gameMode.useItemOn`（不回落 useItem），`use_item` 强制走 `gameMode.useItem`；营火/烘干架（`*campfire`、`drying_rack`、`*_rack`）在首次交互不消耗时，用 `Direction.UP` + 顶面精确命中点 `(x+0.5, y+0.44, z+0.5)` 再试一次，然后才回退 `useItem`。返回增加 `mode/attempts/heldBefore/heldAfter/itemConsumed`。
- **游戏内验证**：手持咖啡豆 `use_block {x,y,z,mode:"item_on"}` 对营火 → 返回 `result` 为 SUCCESS/CONSUME，`itemConsumed:1`（豆子少 1），营火方块状态/内容物出现（可用 `block_query` 的 `properties` 看）；之后 `use_block {mode:"block"}` 应能取回。
- **风险**：不同模组营火的交互入口可能不是 `useItemOn`（或需要空手/蹲下），失败时仍返回 `PASS` 但不会崩；未在游戏内验证。

## ISSUE-12（中）`entity_query` 大半径静默返回空 → 同心环扫描

- **复现**：`entity_query {radius:64}` / `{radius:128}` 返回 `entities:[]`，换 32 立刻查到 3 头牛；羊/鸡同样。
- **根因**：大半径一次 `level.getEntities` 依赖已加载区块，且慢查询/异常被上层吞掉后只剩空数组，调用方无法区分“真没有”和“没查到”。
- **改动**：`Perception.entityQuery` 半径上限提到 128；`radius>48` 时按 32 格同心环分块 `getEntities`，按 entityId 去重合并、按距离排序，再过滤 type/name/limit；新增 `requestedRadius/scanMode("rings")/rings/scanBoxes/candidates/scanErrors`；结果为空时附 `note:"... distant chunks may be unloaded"`，**不再静默空数组**。
- **游戏内验证**：`entity_query {radius:96,type:"cow"}` → 返回里 `scanMode:"rings"`、`rings:3`，若附近有牛应能查到；没有时看 `note` 而不是误判“世界上没牛”。
- **风险**：环扫描会重复查询重叠区域（结果已去重，但开销比单次大）；超大半径仍受区块加载限制，`note` 只是提示、不能凭空找到未加载实体；某个环查询异常时会记 `scanErrors` 但继续返回其它环结果。

## ISSUE-13（低）缺 `sneak` → `use_block.sneak` / `entity_interact.sneak`

- **复现**：烘干架（`extradelight:drying_rack`）取货必须“空手 + 下蹲右键”；bridge 没有 sneak 参数，只能放不能取。
- **根因**：交互只调 `useItemOn`/`interact`，没发 `ServerboundPlayerCommandPacket(PRESS_SHIFT_KEY)`，服务端不认为玩家在蹲。
- **改动**：`use_block` 与 `entity_interact` 增加 `sneak:true`：`Actions.startSneaking` 置 `keyShift` + `setShiftKeyDown(true)` 并发包，交互后 `TickScheduler.afterTicks(2)` 发 `RELEASE_SHIFT_KEY` 并复位；结果回传 `sneak`。`use_block` 的 `mode` 可同时用（例如 `{mode:"block",sneak:true}` 取烘干架物品）。
- **游戏内验证**：空手 `use_block {x,y,z,sneak:true}` 对烘干架 → 手上出现成品、`result` 消耗动作；`get_state.player.sneaking` 在交互瞬间应为 true（2 tick 后恢复 false）。
- **风险**：松开 shift 是 2 tick 后由客户端 tick 执行；若这 2 tick 内玩家自己按了 shift，会被一起松开（极端情况下影响一次移动，不影响交互）。依赖服务端在同一 tick 处理交互（原版/绝大多数模组成立）。

## ISSUE-15（高，任务里也写作 ISSUE-14/15）读书 / 读告示牌 / 含 GUI 截图

- **复现**：玩家给 `minecraft:written_book`，`throw_item` 能打开 `BookViewScreen`，但 `screenshot` 只有 3D 世界、`container_read`/`inventory_slots` 只有 id/count，agent 完全读不到书名/作者/正文；告示牌同理。
- **根因**：bridge 没有读取 `DataComponents.WRITTEN_BOOK_CONTENT` / `SignBlockEntity` 的工具；截图在 `RenderGuiEvent.Post` 触发时 GUI 的顶点缓冲还没 `GuiGraphics.flush()` 到主渲染目标，抓到的是“世界层”。
- **改动**：新增 `read_book {slot?|hand?}`（`WRITTEN_BOOK_CONTENT.getPages(false)`→纯文本；也支持 `WRITABLE_BOOK_CONTENT`，返回 title/author/generation/resolved/pages/pageCount）、`read_sign {x,y,z|pos}`（`SignBlockEntity` 的 front/back 4 行 + `isWaxed`）、`item_info {slot?|hand?}`（`ItemStack.getComponents()` 全量，codec→JSON，书页展开成纯文本，超长截断）；`ScreenshotCapture` 支持 `withGui`：为 true 时先 `event.getGuiGraphics().flush()` 再 `Screenshot.takeScreenshot(mc.getMainRenderTarget())`，落到同一 `screenshots` 目录，文件名带 `-gui` 后缀并返回 `withGui/guiFlushed`。`Tools` 里的 `screenshot` 仍是旧调用（默认 false），**现在可直接用新工具名 `screenshot_gui`**，或让主代理把 `Tools` 的 screenshot 分支改成 `ScreenshotCapture.request(args)`。
- **游戏内验证**：拿着书面 `read_book {}` → 返回 `title/author/pages` 与书页原文一致；对告示牌 `read_sign {x,y,z}` → `front` 4 行是牌子上的字；`item_info {}` → `components` 里 `minecraft:written_book_content` 是展开的 JSON；打开书面后 `screenshot_gui {}` → 返回路径的 PNG 里能看到书页文字（用视觉/读图工具确认）。
- **风险**：`screenshot_gui` 依赖 `GuiGraphics.flush()` 在 `RenderGuiEvent.Post` 时可用（1.21.1 NeoForge 已确认存在该方法，未实测截图效果）；`read_book` 读的是客户端同步下来的组件，书在未同步的容器里时需要先 `container_read`/打开；`item_info` 的 codec 编码对少数需要 registry 的组件可能失败，失败时回退为 `String.valueOf`（仍可读但结构差一些）。

---

## 明确“未修”的 ISSUE（本次任务范围外/依赖其它模块）

| ISSUE | 结论 |
|---|---|
| ISSUE-1 / ISSUE-10（Chatter 不回复、KeepListening 不回消息） | **未修**。属于 Node 侧 CR/调度器与主代理行为准则，不是 mod 工具层；本次只做 mod 工具。 |
| ISSUE-9（`mcp__mc__*` 掉线后不自愈） | **未修**。DSH MCP client 的重连策略，不在本工程源码内。 |
| ISSUE-14（农夫乐事锅成品取不出，原编号） | **未修**。玩家已确认是“饮品要用玻璃瓶”的机制；需要的是“手持容器右键方块槽”的交互路径（可用 `use_item`/`use_block` 组合尝试），本次未做 `CookingPotMenu` 特判。 |
| ISSUE-6（1 格门） | **未真正修复**，只做了一次保守的“门中心对齐重试”，见上。 |

## 改动文件清单

| 文件 | 改动 |
|---|---|
| `IssueTools.java`（新增） | `wake` / `eat` / `use_item` / `read_book` / `read_sign` / `item_info` / `screenshot_gui` / `walk_dir` / `path_open_ex` 的分发与实现 |
| `Actions.java` | `useBlock` 的 `mode`/`sneak`/营火顶面重试；`entityInteract` 的 `sneak`；`PlaceTask` 自动 hotbar 交换+还原；`findItemSlotAll`；`startSneaking/stopSneaking/scheduleStopSneaking` |
| `ItemActions.java` | `throwItem` 支持 `holdTicks`（排程释放）+ 数量回读 |
| `GameState.java` | `player.sleeping` / `player.bedPos` / `player.sleepTimer` |
| `Perception.java` | `entityQuery` 同心环扫描 + 诊断字段，不再静默空 |
| `ScanSurface.java` | `includeNonFull`、nonfull 入 surface、`doors` 修好并改对象格式 |
| `ScreenshotCapture.java` | `withGui`（GUI flush 后抓主目标）+ `request(args)` 重载 |
| `PathOpenJob.java` | 只开门/`openContainers`、结束自动关容器、一次门对齐重试 |
| `InventoryActions.java` | 抽出 `quickSwap`（带容器界面保护），`swap` 复用 |
