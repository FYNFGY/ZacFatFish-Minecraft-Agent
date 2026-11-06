# Ph6 ISSUE 工具清单（给 MCP server / 主代理）

> 代码位置：`D:\mc\issuework\mod\src\main\java\dev\mcp\bridge\`（新增 `IssueTools.java`，修改 8 个已有文件）。
> 所有工具都按 `Tools.call(tool, args, requestedTimeout)` 的约定工作：
> 同步工具在客户端线程执行、HTTP 线程等待；长任务返回 `Jobs.Job`（`{jobId, tool, state, job}`）。
> `timeoutSeconds` 是 **RPC 请求级**参数（`/rpc` body 的 `timeoutSeconds`），不是 `args` 里的字段——除 job 类工具外，
> 工具内部只读 RPC 的 `requestedTimeout`；同步工具上限 30 s。

## 0. 接入 Tools.java（主代理做，一行）

```java
default -> {
    Object r = IssueTools.call(tool, args, requestedTimeout);
    if (r == null) {
        throw Errors.unsupported("unknown tool: " + name);
    }
    yield r;
}
```

`IssueTools.call` 返回 `null` = 不认识这个工具；返回 `JsonObject` = 同步结果；返回 `Jobs.Job` = 后台 job。
**不要**把 null 直接返回给 HttpBridge（否则会变成 `{"ok":true,"result":null}` 而不是 unknown tool）。

---

## 1. 新增工具（全部在 `IssueTools.call` 里分发）

### 1.1 `wake`（别名：`get_up`、`stop_sleeping`）— ISSUE-2

起床。客户端发 `ServerboundPlayerCommandPacket(player, STOP_SLEEPING)`，逐 tick 回读 `player.isSleeping()`（最多 60 tick）。

**参数**

```json
{
  "type": "object",
  "properties": {},
  "additionalProperties": false
}
```

**返回字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `ok` | bool | 固定 true（真正的失败用 error 码） |
| `sleepingBefore` | bool | 调用前是否在床上 |
| `screen` | string | 调用前的 screen 类名（空串表示无 screen） |
| `bedPos` | [x,y,z]? | 调用前的床方块坐标（不在床上时没有该字段） |
| `packet` | string | 实际发出的包 `STOP_SLEEPING`（不在床上时没有） |
| `sleeping` | bool | 最终是否仍在睡 |
| `woke` | bool | 是否成功醒来 |
| `ticks` | int | 醒来用了多少 tick（超时固定 60） |
| `screenClosed` | bool? | 是否顺手关掉了 `InBedChatScreen` |
| `reason` | string? | 60 tick 仍没醒时的诊断说明（服务端可能拒绝） |
| `note` | string? | 本来就没睡时的说明 |

**示例**：`{"tool":"wake","args":{}}` → `{"ok":true,"sleepingBefore":true,"sleeping":false,"woke":true,"ticks":3}`

---

### 1.2 `eat`（别名：`consume`）— ISSUE-3

完整进食：选中食物 → `gameMode.useItem` → 逐 tick 保持 `isUsingItem` → 等吃完或超时强制 `releaseUsingItem` → 回读 food/数量。

**参数**

```json
{
  "type": "object",
  "properties": {
    "slot":     { "type": "integer", "minimum": 0, "maximum": 35, "description": "背包槽（0..8 快捷栏，9..35 主背包；主背包会自动换到当前快捷栏槽）" },
    "item":     { "type": "string", "description": "物品 id，可写全名或只写路径，如 minecraft:bread / bread" },
    "hand":     { "type": "string", "enum": ["main", "off"], "default": "main" },
    "maxTicks": { "type": "integer", "minimum": 20, "maximum": 400, "default": 140, "description": "最多保持多少 tick" }
  },
  "additionalProperties": false
}
```

`slot` 与 `item` 都不给时用当前选中的快捷栏槽。要求物品带 `minecraft:food` 组件，否则 `unsupported`。

**返回字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `ok` | bool | |
| `item` | string | 实际使用的物品 id |
| `hand` | string | `main` / `off` |
| `slot` | int? | 实际选中的快捷栏槽 |
| `swappedFrom` / `swappedInto` | int? | 若从主背包换进快捷栏，记录原槽/目标槽 |
| `countBefore` / `countAfter` | int | 该物品在 36 格背包里的总数 |
| `foodBefore` / `foodAfter` | int | 饥饿值（0..20） |
| `saturationBefore` / `saturationAfter` | double | 饱和度 |
| `healthBefore` / `healthAfter` | double | 生命值 |
| `using` | bool | 是否进入/仍在“使用中” |
| `useDuration` / `useItemRemaining` | int | 使用总时长/剩余 tick |
| `ticks` | int | 从开始到结束的 tick 数 |
| `result` | string | `gameMode.useItem` 的 `InteractionResult` 字符串 |
| `consumed` | bool | `foodAfter>foodBefore` 或 `countAfter<countBefore` |
| `release` | string? | 超时强制松开时的说明 |
| `note` | string? | 物品没进入 using 状态时的说明 |

**示例**：`{"tool":"eat","args":{"item":"minecraft:bread"}}` →
`{"ok":true,"item":"minecraft:bread","foodBefore":12,"foodAfter":17,"countBefore":3,"countAfter":2,"consumed":true,"ticks":33}`

---

### 1.3 `use_item` — ISSUE-3（通用“按住右键”）

和 `eat` 同一套状态机，但不要求食物：弓、药水、盾、食物都可以；超时会强制松开。

**参数**：同 `eat`（`slot` / `item` / `hand` / `maxTicks`），不支持“食物组件”检查。

**返回**：同 `eat`，但没有 `food*` 的语义保证；`consumed` 按 food/数量变化计算（弓不会消耗，通常 `false`）。

---

### 1.4 `read_book` — ISSUE-15

读主手/指定槽的书写成书或书与笔。

**参数**

```json
{
  "type": "object",
  "properties": {
    "slot": { "type": "integer", "minimum": 0, "maximum": 35, "description": "不填 = 主手" },
    "hand": { "type": "string", "enum": ["main", "off"], "default": "main" }
  },
  "additionalProperties": false
}
```

**返回字段**（`written_book`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `ok` | bool | |
| `item` | string | 物品 id |
| `bookType` | string | `written_book` / `writable_book` |
| `title` | string | 书名（writable 为空串） |
| `author` | string | 作者（writable 为空串） |
| `generation` | int | 抄写代次（仅 written） |
| `resolved` | bool | 是否已解析（仅 written） |
| `pages` | string[] | **纯文本**每页（`Component.getString()`） |
| `pageCount` | int | 页数 |

非书 → `unsupported`。

---

### 1.5 `read_sign` — ISSUE-15

读告示牌正反两面文字。

**参数**

```json
{
  "type": "object",
  "properties": {
    "x": { "type": "integer" },
    "y": { "type": "integer" },
    "z": { "type": "integer" },
    "pos": { "type": "array", "items": { "type": "integer" }, "minItems": 3, "maxItems": 3, "description": "也可以用 [x,y,z]" }
  },
  "required": ["x", "y", "z"]
}
```

**返回字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `ok` | bool | |
| `pos` | [x,y,z] | |
| `block` | string | 方块 id |
| `isWaxed` | bool | 是否打蜡 |
| `front` | string[4] | 正面 4 行纯文本（空行为 `""`） |
| `back` | string[4] | 背面 4 行 |

不是告示牌方块实体 → `unsupported`。

---

### 1.6 `item_info` — ISSUE-15

返回手持/指定槽的**完整 components**（`ItemStack.getComponents()`）。

**参数**

```json
{
  "type": "object",
  "properties": {
    "slot": { "type": "integer", "minimum": 0, "maximum": 35 },
    "hand": { "type": "string", "enum": ["main", "off"], "default": "main" }
  },
  "additionalProperties": false
}
```

**返回字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `ok` | bool | |
| `item` | object | 同 `GameState.stack()`：`{id,count,name,damage?,maxDamage?,hasComponents?,componentPreview?}` |
| `empty` | bool | 槽位是否为空 |
| `slot` | int? | 传入的槽位 |
| `hand` | string | `main`/`off` |
| `components` | object | `{"<component id>": <编码后的 JSON 或字符串>}` 全量组件 |
| `componentCount` | int | 组件数量 |
| `book` | object? | 如果是书，额外给 `read_book` 的展开结果（title/author/pages…） |

单个组件编码超过 1200 字符会被截断为字符串 `"...(N chars)"`；
`minecraft:written_book_content` / `minecraft:writable_book_content` 一定展开成纯文本 pages，不是 codec 原始结构。

---

### 1.7 `screenshot_gui`（别名：`screenshot_with_gui`）— ISSUE-15

含 GUI 覆盖层的截图。实现：在 `RenderGuiEvent.Post` 先 `event.getGuiGraphics().flush()` 把 GUI 顶点刷进主渲染目标，再
`Screenshot.takeScreenshot(mc.getMainRenderTarget())`，落盘到原来的 `screenshots` 目录（文件名带 `-gui`）。

**参数**

```json
{ "type": "object", "properties": {}, "additionalProperties": false }
```

**返回字段**：同现有 `screenshot`，另加

| 字段 | 类型 | 说明 |
|---|---|---|
| `path` | string | PNG 绝对路径 |
| `width` / `height` | int | 像素尺寸 |
| `bytes` | long | 文件大小 |
| `takenAt` | long | 毫秒时间戳 |
| `withGui` | bool | true |
| `guiFlushed` | bool | GUI 缓冲是否成功 flush（false 表示只抓到世界层） |

> `screenshot` 本身没改行为（默认 `withGui:false`）。如果要让 `screenshot {withGui:true}` 生效，主代理把
> `Tools.call` 的 `case "screenshot"` 改成 `await(ScreenshotCapture.request(args), timeoutMs(requestedTimeout, 5))` 即可
> （`ScreenshotCapture.request(JsonObject)` 已支持）；不改就用 `screenshot_gui`。

---

### 1.8 `walk_dir` — ISSUE-7

语义化移动（`WalkJob` 的包装，行为与 `walk` 相同）。

**参数**

```json
{
  "type": "object",
  "properties": {
    "direction": { "type": "string", "enum": ["north", "south", "east", "west", "n", "s", "e", "w", "+x", "-x", "+z", "-z"] },
    "yaw":       { "type": "number", "description": "也可以直接给世界朝向 yaw，覆盖 direction" },
    "ticks":     { "type": "integer", "minimum": 1, "maximum": 400, "default": 10 },
    "jump":      { "type": "boolean", "default": false },
    "sprint":    { "type": "boolean", "default": false },
    "up":        { "type": "boolean", "default": false },
    "timeoutSeconds": { "type": "integer", "minimum": 1, "maximum": 600, "default": 60 }
  },
  "additionalProperties": false
}
```

**yaw 映射（以代码为准，`WalkJob.tick` 的 `dx=-sin(yaw), dz=cos(yaw)`）**

| yaw | 方向 |
|---|---|
| 0 | +Z（南） |
| 90 | -X（西） |
| 180 | -Z（北） |
| 270 | +X（东） |

**返回**：`Jobs.Job`（`{jobId, tool:"walk_dir", state, job:{...}}`），job 结果字段同 `walk`。

---

### 1.9 `path_open_ex`（别名：`path_open2`）— ISSUE-5

和 `path_open` 完全相同的 Baritone 寻路 + 开门，但把 `openContainers` 透传给 `PathOpenJob`。

**参数**

```json
{
  "type": "object",
  "properties": {
    "x": { "type": "integer" },
    "y": { "type": "integer" },
    "z": { "type": "integer" },
    "range": { "type": "integer", "minimum": 0, "maximum": 64, "default": 0 },
    "openContainers": { "type": "boolean", "default": false, "description": "true 时恢复旧行为：有 OPEN 属性的方块（含木桶）都右键" },
    "timeoutSeconds": { "type": "integer", "minimum": 1, "maximum": 600, "default": 300 }
  },
  "required": ["x", "y", "z"]
}
```

**返回**：`Jobs.Job`；job 结果字段见 2.6。

---

## 2. 已有工具的改动（参数 / 行为 / 返回）

### 2.1 `get_state` — ISSUE-2

`result.player` 新增：

| 字段 | 类型 | 说明 |
|---|---|---|
| `sleeping` | bool | `player.isSleeping()`（服务端同步的睡觉状态） |
| `bedPos` | [x,y,z] 或 null | `getSleepingPos()` |
| `sleepTimer` | int | `player.getSleepTimer()` |

### 2.2 `use_block` — ISSUE-11 / ISSUE-13

**新增参数**

```json
{
  "mode":  { "type": "string", "enum": ["block", "item_on", "use_item"], "default": "block" },
  "sneak": { "type": "boolean", "default": false }
}
```

- `block`：旧行为（`useItemOn` → 不消耗时回退 `useItem`）；营火/烘干架额外做一次顶面 `useItemOn` 重试。
- `item_on`：只走 `gameMode.useItemOn`（把物品“放”到方块上，如营火/烘干架），不回落 `useItem`。
- `use_item`：只走 `gameMode.useItem`。
- `sneak:true`：交互前发 `PRESS_SHIFT_KEY`，2 tick 后发 `RELEASE_SHIFT_KEY`。

**返回新增**

| 字段 | 类型 | 说明 |
|---|---|---|
| `mode` | string | 实际使用的模式 |
| `sneak` | bool | |
| `attempts` | string[] | 依次尝试过的 `use_item_on(FACE):RESULT` / `use_item:RESULT`，便于诊断 |
| `heldBefore` / `heldAfter` | object | 主手物品（`GameState.stack`） |
| `itemConsumed` | int | 主手数量减少量（营火放食物成功时应为 1） |

### 2.3 `entity_interact` — ISSUE-13

**新增参数**：`"sneak": {"type":"boolean","default":false}`（交互前蹲下、2 tick 后松开）。
**返回新增**：`sneak: bool`。

### 2.4 `place_block` — ISSUE-4

行为：物品不在快捷栏时**自动**从主背包换到当前快捷栏槽，放置后自动换回（不再要求调用方先 `inventory_swap`）。

**返回新增**

| 字段 | 类型 | 说明 |
|---|---|---|
| `item` | string | 请求放置的物品 id |
| `autoSwap` | bool | 是否发生过自动交换 |
| `swappedFrom` | int? | 主背包原槽（inventory 索引） |
| `hotbarSlot` | int? | 临时使用的快捷栏槽 |
| `restored` | bool | 是否已发“换回”点击（客户端预测，不是服务端确认） |

开箱子/容器时自动交换会失败（`quickSwap` 拒绝），此时 job 失败并给出原因。

### 2.5 `throw_item` — ISSUE-3

**新增参数**：`"holdTicks": {"type":"integer","minimum":0,"maximum":200,"default":0}`

**返回新增**

| 字段 | 类型 | 说明 |
|---|---|---|
| `holdTicks` | int | 请求的按住 tick 数 |
| `usingItem` | bool | 调用后是否进入 using 状态 |
| `countBefore` / `countAfter` | int | 主手数量（客户端预测） |
| `holdState` | string | `instant` / `scheduled` / `not_using` |
| `releaseAfterTicks` | int? | `scheduled` 时：多少 tick 后松开 |
| `projectileNote` | string? | 同 tick 没找到弹射物时的说明（旧的 `note` 字段改名为 `projectileNote`，避免和 `holdState` 的说明冲突） |

> `holdTicks>0` 是“排程松开”：本次响应立刻返回，松开由客户端 tick 在 N tick 后执行，**不等待**吃完/拉满。
> 需要“看到完成”的调用方请用 `eat` / `use_item`。

### 2.6 `path_open` — ISSUE-5 / ISSUE-6

行为变化（默认参数下）：

- 只对 `*_door` / `*_fence_gate` / `*_trapdoor` 发交互，**不再顺手开箱子/木桶**；
- 寻路结束（成功/超时/blocked/取消）时若开着 `ContainerScreen`/`AbstractContainerScreen`，自动关闭；
- 卡住 140 tick 时，做**一次**“吸附到最近门方块中心 → 重新 path”的重试（见 ISSUE-6）。

**返回新增**

| 字段 | 类型 | 说明 |
|---|---|---|
| `containersOpened` | int | 被顺手打开的容器数（默认 0；openContainers:true 时才可能非 0） |
| `containerClosed` | bool | 结束时是否关掉了容器界面 |
| `openContainers` | bool | 本次是否允许开容器 |

> `Tools.pathOpen` 仍用 4 参构造（默认 false）。要 `openContainers:true` 请用 `path_open_ex`；或者主代理在
> `Tools.pathOpen` 里把构造改成 `new PathOpenJob(x, y, z, range, Json.b(args, "openContainers", false))`。

### 2.7 `scan_surface` — ISSUE-8

**新增参数**：`"includeNonFull": {"type":"boolean","default":true}`

行为：

- `surface` 里会追加非整格可交互方块，元素为 `[x,y,z,id,faces,"nonfull"]`（第 6 个元素是标记；老调用方读前 5 个不变）。
- `doors` 字段现在**真的会有内容**，元素从数组改成对象：

```json
{ "pos": [x,y,z], "id": "minecraft:oak_door", "type": "door|trapdoor|fence_gate|bed|sign",
  "open": true, "facing": "north", "part": "head", "occupied": false }
```

（`open`/`facing`/`part`/`occupied` 只在方块状态有对应属性时出现。）

**返回新增**

| 字段 | 类型 | 说明 |
|---|---|---|
| `includeNonFull` | bool | |
| `nonFullCount` | int | 补进来的非整格方块数 |
| `nonFullTruncated` | bool | 邻接扫描超过 25 万次被截断 |
| `regularSurfaceCount` | int | 原有整格 surface 数量 |
| `surfaceCount` | int | `regularSurfaceCount + nonFullCount` |
| `surfaceFormat` | string | 明确 surface 元素格式（含 `shape="nonfull"` 标记的说明） |
| `doorsFormat` | string | 明确 doors 现在是对象格式 |

`includeNonFull:false` 时行为完全回到旧版（`doors` 仍会有床/告示牌，但不会有门）。

### 2.8 `entity_query` — ISSUE-12

**参数**：`radius` 上限从 64 提到 **128**；其它不变（`limit`、`type`、`name`）。

行为：`radius>48` 时改为 32 格同心环分块查询、按 entityId 去重合并；**结果为空时一定带 `note`，不再静默返回空数组**。

**返回新增**

| 字段 | 类型 | 说明 |
|---|---|---|
| `requestedRadius` | int | 请求的半径 |
| `radius` | int | 实际生效半径（=requestedRadius） |
| `scanMode` | string | `single` / `rings` |
| `rings` | int | 扫描的环数（single 为 0） |
| `scanBoxes` | int | 查询的 AABB 数 |
| `candidates` | int | 去重后的候选实体数 |
| `scanErrors` | int? | 某个环查询异常的次数（有才出现） |
| `note` | string? | 结果为空时的解释（区块可能未加载） |

### 2.9 `screenshot` — ISSUE-15

行为不变（默认 `withGui:false`）。`ScreenshotCapture` 新增 `request(JsonObject)` / `request(boolean)`：
如果主代理把 `Tools` 的 `case "screenshot"` 改为传 args，则支持 `{"withGui": true}`；否则直接用 `screenshot_gui`。

### 2.10 `walk` — ISSUE-7（仅文档）

yaw 语义与 `walk_dir` 相同：`0→+Z(南) / 90→-X(西) / 180→-Z(北) / 270→+X(东)`；
`WalkJob.tick` 用 `dx=-sin(yaw), dz=cos(yaw)`，与实测一致，**代码未改**。

---

## 3. 错误码

沿用 `Errors` 现有常量，没有新增：

| 场景 | code |
|---|---|
| 不在世界里 / 客户端未连接 | `not_loaded` |
| 物品不是食物（`eat`）、不是书（`read_book`）、不是告示牌（`read_sign`） | `unsupported` |
| 目标方块没有方块实体/不是预期类型 | `unsupported` |
| 参数缺失（x/y/z、slot 越界、direction 非法） | `internal` |
| 同步工具超过 30 s（例如客户端暂停、不 tick） | `timeout` |
| Baritone 未安装（`path_open_ex`） | `unsupported` |
| 寻路 11 s 无进度 / 对齐超时 | `blocked` |

---

## 4. 备注（给工具定义作者）

1. 新增工具全部通过 `IssueTools.call` 分发，**不需要**改 `Tools` 的 84 个已有 case；只需要 default 分支一行。
2. `screenshot_gui` / `path_open_ex` 是因为 `Tools` 里 `screenshot` / `path_open` 已经被占用且主代理暂不改它们而提供的“别名入口”；
   合并后如果主代理愿意改 `Tools`，这两个别名可以保留也可以删掉（删掉不影响已有调用）。
3. `walk_dir` 的 yaw 表、`throw_item.holdTicks` 的“排程松开”、`place_block.restored` 的“点击已发出”语义，
   在写工具描述时请原样写进 `description`，避免调用方误以为它们保证服务端完成。
4. `read_book` / `item_info` 读的是客户端已同步的组件；物品还在未打开的容器里时，先 `container_open`/`container_read`。
