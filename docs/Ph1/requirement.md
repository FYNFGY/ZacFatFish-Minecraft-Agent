# MCP Minecraft 客户端控制服务器 — 需求（修订版 v2）

> 本文件面向实现该服务器的 AI/开发者。v1 见 `requirement.original.md`。
> v2 的所有修改都来自一次真实的集成尝试（DSH + HeadlessMC + 该整合包），**§3 的注意事项是实测踩坑，不是推测**。

---

## 0. 相对 v1 的变更摘要

| # | v1 | v2（修订） | 原因 |
|---|---|---|---|
| 1 | "NewForge 1.21.1" | **NeoForge 21.1.1**（服务端实际 `21.1.228`，Java 21） | 名称写错；版本必须写死，否则握手失败（见 3.7） |
| 2 | 未说明运行形态 | **有头客户端 + 回环桥接**，明确禁止无头补丁 | 无头 LWJGL 补丁会破坏模组初始化（3.1） |
| 3 | "每个 Tool 非阻塞" | 明确 **提交/轮询/取消** 三件套 + 建议超时值 | 只写"非阻塞"无法实现（2.4） |
| 4 | "模组可装服务端" | 明确 **客户端/服务端职责划分**（3.2 表） | 感知、寻路、容器 UI 都必须在客户端 |
| 5 | 工作目录 `D:/mc/Development` | **`D:\mc\MCP Development`** | 实际路径 |
| 6 | 未提加载器元数据 | **只允许 `META-INF/neoforge.mods.toml`**，禁止 `fabric.mod.json` | 包里装了 Sinytra Connector，会二次加载（3.4） |
| 7 | 未提进程发现 | **注册文件 + 心跳**，禁止靠 pid 存活判断 | 沙箱/权限下看不到别的进程（3.3） |
| 8 | 未提传输方式 | **优先回环 HTTP（streamable-http）**，stdio 只作备选 | stdio 子进程在受限环境下会写临时目录失败（3.5） |
| 9 | "参考 MineFlyer" | 给出 **具体数据源 API** 与限制 | MineFlyer 未必适配 1.21.1；见 2.3 |
| 10 | "游戏源代码可能被魔改" | 明确 **配方/数据必须运行时读取**（KubeJS） | 该包用 KubeJS 大量魔改（3.7） |

---

## 1. 目标与形态

**目标**：让 LLM 通过 MCP 工具，像玩家一样在 **NeoForge 1.21.1 整合服**里持续游玩：移动、采集、建造、容器/村民交易、聊天。

**形态（硬约束）**：

```
[LLM / MCP Client]
        │  MCP over loopback HTTP (Bearer token)
        ▼
[客户端 Mod: mcp-bridge]  ← 运行在真实客户端进程内，直接调 Minecraft/Baritone/JEI API
        │  同一进程：渲染线程 + 网络线程
        ▼
[NeoForge 1.21.1 客户端] ── 正常联机 ──► [目标服务器]
```

- **必须是"有头客户端"**（可渲染、真实 LWJGL）。不得使用任何"无头/离屏渲染补丁"。
- 客户端由用户用 **PCL/官方启动器**正常启动，mod 只负责暴露控制接口，**不得接管启动器**。
- 允许在服务端加装辅助 mod，但**所有玩家可见能力必须在客户端实现**（见 3.2）。

---

## 2. 功能需求

### 2.1 寻路

- 使用 **Baritone**。控制方式：向 Baritone 发送聊天命令（`#goto x y z`、`#mine <block>`、`#follow player`），通过其状态/聊天输出判断完成。
- **前置验证（必须先做）**：确认 NeoForge 1.21.1 有可用 Baritone 构建。若无：
  - 方案 B：在服务端用"假玩家实体 + 服务端寻路"；
  - 方案 C：自研客户端 A*（只支持走/跳/搭桥，不做复杂挖掘），并明确告知 LLM 能力降级。
- 寻路必须是**异步**的：`start_path` 立即返回 `jobId`，用 `path_status` 轮询 `{state: running|done|failed, distanceLeft, currentGoal}`，`path_cancel` 中止。

### 2.2 交互逻辑

1. **容器交互**：箱子/木桶/潜影盒/末影箱 + 模组容器（Create 的保险库、Sophisticated Storage 等）
   - 必须支持：读槽位、取物、放物、快速移动（shift-click）、按数量拆分。
   - **实现建议**：优先服务端权威操作（自定义网络包 → 服务端 `Container` 操作），客户端 `ClickSlot` 仅作兜底且必须"操作后回读校验"。
   - 模组容器若不是 `AbstractContainerMenu`（如 Create contraption 内部库存），走该模组的 API 或直接判定为"不支持"，不要硬点。
2. **方块破坏/放置**：等价于玩家按键 + 视线。需支持：目标坐标、朝向、工具切换、持续挖掘、放置面/朝向。
3. **工作台/JEI 配方**：通过 **JEI API** 查询（客户端），返回 `{outputs, inputs[], station, recipeId}`；必须能处理 KubeJS 生成的虚拟配方。配方查询是只读的，可直接返回。
4. **生物交互**：村民交易（打开交易界面、枚举 offer、选择并成交）、喂食/繁殖、挤奶、剪毛等。
5. **门/按钮/拉杆**：`use` 交互，返回交互前后方块状态。
6. `(*)` **Carry On**：抱起/放下服务端白名单内的实体/箱子。
7. `(*)` **Create 及附属**：机械交互（转速、应力、传送带、机械臂）。**范围必须写死**，只做明确列出的一小组动作。

### 2.3 游戏环境感知

| 需求 | 数据源（客户端） | 限制 |
|---|---|---|
| 聊天列表 + 发言 + 指令 | `ClientPacketListener` 的 chat 事件 + 自建环形缓冲 | 客户端只留最近 100 条；长期必须自己落盘 |
| 周围/面前方块及状态 | `ClientLevel.getBlockState(pos)`；未加载区块返回空 | **未加载区块无数据**，需要先靠近/等待加载 |
| 实体类型与位置 | `ClientLevel.entitiesForRendering()` + `Entity.getType()/position()` | 只含**客户端已跟踪**的实体；远处无数据 |
| 碰撞箱/交互体积 | `BlockState.getCollisionShape(level,pos)`、`Entity.getBoundingBox()`、`getInteractionShape` | 与服务器实体会有一帧级延迟 |
| 时间 | `level.getDayTime()`、`getGameTime()` | — |
| 玩家状态 | 位置/朝向/血量/饥饿/经验/维度/游戏模式 | 直接读 `LocalPlayer` |

补充要求：
- 增加 `scan_area(center, radius, filter)`：一次返回**方块+实体+掉落物**的结构化快照，带 `truncated` 标记，避免 LLM 逐格查询烧 token。
- 所有坐标统一用 **世界坐标整数**，并注明是否含方块中心偏移。

### 2.4 MCP 接口（非阻塞协议）

**每个工具都必须满足**：

1. 调用立即返回（< 1s），长任务返回 `{jobId}`；
2. 配套 `job_status(jobId)` / `job_cancel(jobId)`；
3. 工具参数带 `timeoutSeconds`（默认见下表，上限 600）；
4. 超时返回 `{ok:false, partial:true, error:"timeout"}`，**不抛异常**；
5. 状态里必须带**可判定的进度**（如 `distanceLeft`、`blocksMined/total`），不能只返回 "running"。

建议工具集（英文 id，描述可用中文）：

| 工具 | 阻塞性 | 默认超时                  | 说明 |
|---|---|-----------------------|---|
| `get_state` | 同步 | 2s                    | 位置/血量/维度/时间/在线玩家 |
| `scan_area` | 同步 | 3s                    | 半径内方块/实体快照 |
| `chat` / `command` | 同步 | 2s                    | 发言、执行 `/cmd` |
| `path_to` / `path_status` / `path_cancel` | 异步 | (起始和目标点的坐标L1距离 * 1s)） | Baritone |
| `mine_block` / `place_block` | 异步 | 60s                   | 目标坐标 + 面 |
| `place_block` | 异步 | 1s                    | 目标坐标 + 面 |
| `container_open` / `container_move` / `container_close` | 同步 | 10s                   | 读/取/放，操作后回读 |
| `recipe_lookup` | 同步 | 5s                    | JEI 查询 |
| `entity_interact` | 同步 | 2s                    | 村民/动物/门/按钮 |
| `screenshot` | 同步 | 5s                    | 返回文件路径（供视觉模型） |

**实现要点（线程模型）**：

- HTTP 线程**不得直接操作世界**。所有游戏操作必须 `Minecraft.getInstance().execute(runnable)` 投递到**客户端主线程**，并用 `CompletableFuture` 回传结果。
- 需要"持续多 tick"的动作（挖掘、寻路、开容器）用**每 tick 轮询任务**，不要在一个 tick 里 while 循环。
- 断线/切维度时，所有进行中的 job 必须置为 `failed(reason=disconnect)`。

### 2.5 上下文记忆

- 落盘位置：`<工作区>/.mcp-agent/`（`README.md` 索引 + 按主题的子文件）。
- **禁止使用 `AGENTS.md`**：DSH 会把它当作工作区指令自动注入到任何会话，造成污染（同类问题我们在另一个插件里遇到过）。
- 记忆写入必须是**追加 + 原子替换**（先写临时文件再 `move`），避免游戏崩溃留下半个文件。

---

## 3. 技术注意事项（实测踩坑，必须遵守）

### 3.1 不要用无头补丁（最重要）

同一份 253 模组客户端：

- **HeadlessMC 无头启动**（LWJGL 被替换为桩实现）→ 启动时出现 **11 个** `Caught exception during event ... already registered`，FML 的并行初始化批次被中断，`glitchcore/owo/create_connected/ritchiesprojectilelib` 的网络通道没注册上，最终被服务器以 `Incompatible client` 拒绝。
- **纯 java / PCL 启动**（真实 LWJGL）→ **0 个**异常，正常进服。

结论：**离屏渲染不要用"替换 LWJGL"的方式**。若确需无人值守，用虚拟显示器/远程桌面会话，保持渲染管线真实。

### 3.2 客户端 vs 服务端职责

| 能力 | 必须位置 | 原因 |
|---|---|---|
| 感知（方块/实体/聊天） | 客户端 | 数据在客户端已同步；服务端不知道"这个玩家看得到什么" |
| 寻路/移动/挖掘/放置 | 客户端 | 等价于玩家输入 |
| 容器 UI 操作 | 客户端发起 + **建议服务端校验** | 客户端可伪造，服务端权威更稳 |
| 跨玩家只读数据（TPS、白名单） | 服务端 | — |

**不要**试图只靠服务端 mod 实现"假人"：那会变成 Carpet 假玩家，没有视觉/容器 UI，且服务器不一定允许。

### 3.3 进程发现：不要靠 pid

实测：在受限环境里，MCP 服务器**看不到**由启动器拉起的 java 进程，`Get-Process` 与 `list_instances` 都把它当"已退出"过滤掉（注册文件明明有效）。

要求：

- 客户端启动后写 `instances/<uuid>.json`：`{protocol, pid, port, token, mcVersion, loader, gameDir, started, heartbeatAt}`；
- **心跳字段**每 5s 更新；MCP 侧以"心跳超时（>20s）"判定失效，**不要**依赖 `pid` 存活；
- 退出时删除文件；
- MCP 侧支持"手动按端口/文件附加"（配置项），不依赖自动发现。

### 3.4 加载器元数据：只写 NeoForge

整合包装了 **Sinytra Connector**。任何同时含 `fabric.mod.json` 的 jar 会被 Connector 当 Fabric 模组**二次加载**，引发重复注册。实测把 bridge 的 `fabric.mod.json` 删除后，重复注册异常消失。

要求：产物只含 `META-INF/neoforge.mods.toml`，构建脚本里显式排除 `fabric.mod.json`。

### 3.5 传输：优先回环 HTTP

- stdio 子进程在受限沙箱里会因**临时目录/命名管道**受限而启动失败（实测 Quarkus 因 `%TEMP%\vertx-cache` 拒绝写入直接崩溃）。
- 建议：mod 内起 `HttpServer`（**仅 127.0.0.1**），MCP 侧用 `transport: streamable-http` 连接；若必须 stdio，则把 `java.io.tmpdir`、`jna.tmpdir`、`org.lwjgl.system.SharedLibraryExtractPath` 全部指到可写目录并显式传参。

### 3.6 中文路径 / 编码

- 整合包路径含中文与特殊字符（`EarthⅢ`、`假期增援计划`）。
- 用 `Path`/`File` API，不要拼命令行字符串；必须拼时用**参数数组**（.NET 的 `ArgumentList` / Java 的 `ProcessBuilder(List)`）。
- Windows `.bat` 不要写中文（代码页 936/65001 混用会把路径变成 `?`，实测直接启动失败）；中文路径放在 `.ps1`（UTF-8 **带 BOM**）或 Java/JSON 里。
- JVM 加 `-Dfile.encoding=UTF-8`。

### 3.7 模组/数据兼容

- 目标包 **250+ 模组**（Create 全系、Apotheosis、拔刀剑、森罗物语、KubeJS…）。
- 所有可选集成：`ModList.get().isLoaded("<modid>")` + 反射/软依赖，**禁止硬依赖**；缺 mod 时必须降级而不是崩溃。
- **配方/掉落/标签必须运行时从 `RecipeManager`/`Registry` 读**：该包用 KubeJS 改了大量配方（含虚拟配方），任何硬编码原版配方都会错。
- 服务端与客户端版本必须**精确匹配**：目标服为 NeoForge **21.1.228**、MC 1.21.1、Java 21；客户端用 21.1.235 会被拒（实测）。

### 3.8 安全

- HTTP 仅 loopback + `Authorization: Bearer <token>`；token 随机生成、写文件、**不得进日志/上下文**。
- 不在工具返回值里泄漏账号密码；登录走设备码/外部流程。
- 工具必须能在"无权限"时返回明确错误（如服务器禁用了某指令）。

### 3.9 其它

- 版本：`engines.dsh` 写清楚；DSH MCP 客户端默认工具超时 60s，长任务必须走 job 轮询（否则调用会被上层掐断——实测 60s 默认值把一次启动直接切断）。
- 单次工具返回体积上限（建议 32KB），超出要截断并给 `truncated:true`。
- 崩溃自愈：客户端崩溃后，注册文件由心跳超时清理；重连后 job 全部失效。

---

## 4. 交付物与验收

| 交付物 | 验收标准 |
|---|---|
| 客户端 mod（NeoForge 1.21.1） | 与 250+ 模组共存不崩；启动日志 0 个 `Caught exception during event` |
| MCP 服务器 | 能被 DSH 以 streamable-http 接入；`list` 到全部工具 |
| 工具集 | 上表工具全部可调用；长任务可轮询、可取消 |
| 寻路 | 能连续走 100 格不卡死；失败有原因 |
| 容器 | 打开箱子→取 1 组物品→回读校验通过 |
| 配方 | 对 3 个原版 + 3 个模组物品能给出正确配方 |
| 感知 | `scan_area(半径16)` 返回 < 32KB 且含方块/实体 |
| 记忆 | 重启后可继续上一次任务 |
| 文档 | 安装步骤、端口/token 配置、故障排查（含本文件 §3） |

**联调顺序**：单机创造模式 → 目标服（`zmcp3.rhymc.com:1069`，离线验证，**只读**，不得修改服务端）→ 生产。

---

## 5. 环境

- 整合包：`假期增援计划`（Mechanomania 系），MC 1.21.1 / **NeoForge 21.1.228** / Java 21（Oracle 21.0.8 已验证）
- 目标服务器：`zmcp3.rhymc.com:1069`（NeoForge，离线协议校验，1 人上限 20）
- 工作目录：`D:\mc\MCP Development`
- 现有可复用资产：`D:\mc\tools\bridge-cmd.mjs`（bridge HTTP 客户端参考）、`D:\mc\tools\plain-java-launch.ps1`（PCL 等价启动器）
