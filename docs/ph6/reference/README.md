# MCP Minecraft Client-Control Server

在 **NeoForge 1.21.1 真实有头客户端**里运行的 bridge mod + 外部 MCP server，让 DSH（或任何 MCP 客户端）通过工具控制游戏。目标整合包：`假期增援计划`（Mechanomania 系，~250 mods，NeoForge 21.1.228，Java 21）。

> 本实现**不使用** headless/LWJGL 替换、不接管启动器、不修改目标服务器。客户端始终由玩家正常启动（PCL / 官方启动器），bridge 只附加一个回环 HTTP 端点。

## 架构

```
[DSH / MCP client]
   │ MCP streamable-http  http://127.0.0.1:57610/mcp   （stdio 兜底）
   ▼
[mcp-server  Node 22] ── 实例发现/手动 attach、job 代理、memory 文件
   │ HTTP 127.0.0.1:<random> + Bearer
   ▼
[mcpbridge 0.1.0  NeoForge client mod]
   │ Minecraft main thread / ClientTickEvent
   ▼
[真实有头客户端] ── 正常 multiplayer ──► 目标服务器（只读）
```

- 所有世界访问都在 `Minecraft.getInstance().execute(...)` 中执行，结果通过 `CompletableFuture` 返回；HTTP 线程不触碰世界。
- 长任务返回 `jobId`，状态机：`queued → running → done | failed | cancelled`；`progress` 单调递增，路径额外给 `distanceLeft/currentGoal/etaTicks`，挖掘给 `blocksMined/total`。
- 发现使用 `<state>/headlessmc-mcp/instances/<uuid>.json` + 5 秒心跳、20 秒过期；**不读 pid**。支持 `mc_attach` 按实例文件或 `port + tokenFile` 手动附加。
- token 只存在实例文件，不写日志、不出现在任何工具输出/模型上下文。

## 已实现并验证

| 能力 | 状态 / 证据 |
|---|---|
| 客户端启动 0 个 `Caught exception during event` | `evidence/client-log-checks.txt` |
| `/health`、`/info`、`get_state`、`screenshot` | 有头客户端、单人/多人均验证 |
| `scan_area(radius=16)` JSON < 32 KiB | 22,986 bytes，795 blocks，`evidence/scan16-final.json` |
| `recipe_lookup`（运行时 RecipeManager + JEI 兜底） | 3 原版 + 3 模组物品，含 KubeJS 虚拟配方 `minecraft:kjs/create_andesite_alloy`；`evidence/recipes-final.json` |
| `path_to` 100 格 | 平台 `(0,100,-50) → (0,100,50)`，progress 100，~18s，无停滞 |
| `path_cancel` | 行进中取消，state `cancelled`，停止移动 |
| 容器往返 | 箱子 63 槽，64 stone 玩家槽 56 → 箱槽 0，回读 verified，再取回槽 62 |
| `place_block` / `mine_block` | 异步 job，`blocksPlaced=1` / `blocksMined=1`，方块状态回读 |
| 多人世界 | 加入 `zmcp3.rhymc.com:1069`，`get_state`/`screenshot` 正常 |
| DSH 作为 MCP 客户端 | `mcp__mc__*` 工具已注册并调用成功；`mc_attach` 实例文件/端口+token 文件均验证 |
| 记忆跨客户端重启 | `D:\mc\.mcp-agent\acceptance.md` 在多次客户端重启后仍可读 |

## 快速开始

### 0. 一键冷启动（推荐）

```powershell
cd "D:\mc\MCP Development"
powershell -ExecutionPolicy Bypass -File .\start-all.ps1            # 检查环境 + MCP server + daemon + 客户端
powershell -ExecutionPolicy Bypass -File .\start-all.ps1 -NoClient  # 只起服务端
```

脚本结束时会打印实例文件路径，Agent 拿它调 `mc_attach` 即可。详细分步与排错见 [AIPlayerManual.md](AIPlayerManual.md) 的「0.5 冷启动」。

### 1. 安装 jar（需要你已同意）

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\install.ps1" `
  -VersionDir "D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft\versions\假期增援计划" `
  -WithBaritone
```

只复制两个 jar 到 `mods`，不修改配置、存档、服务端。Baritone 使用官方 NeoForge 资产 `baritone-api-neoforge-1.11.3.jar`（SHA-1 与官方 checksums 一致）。

### 2. 启动客户端

用 PCL 正常启动整合包（有头，真实 LWJGL）。进入单人世界或目标服务器均可。bridge 会在 `FMLClientSetupEvent` 后自动监听随机回环端口，并写实例文件。

默认实例目录（与 HMC 约定一致）：

- Windows：`%LOCALAPPDATA%\state\headlessmc-mcp\instances`
- Linux：`$XDG_STATE_HOME/headlessmc-mcp/instances`
- macOS：`~/Library/Application Support/headlessmc-mcp/instances`

测试时可用 `-Dmcp.bridge.state=<dir>` 或环境变量 `MCP_BRIDGE_STATE` 覆盖。

### 3. 启动 MCP server

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\start-mcp.ps1"
```

默认 `http://127.0.0.1:57610/mcp`，日志在 `runtime\mcp-server.err.log`。Node 路径不同时用 `-Node` 指定。

### 4. 让 DSH 连接

把 [dsh/mc-control.patch.yml](dsh/mc-control.patch.yml) 的 `insert` 条目追加到
`C:\Users\<you>\.dsh\profiles\desktop\cordis.patch.yml`，然后重启/重载 DSH。工具会以 `mcp__mc__*` 出现。
本机已验证：修改该 patch 后 DSH 热加载了 MCP client，无需重启整个应用。

不想用 HTTP 时，可把 `transport` 改为 `stdio`，让 DSH 自己启动：

```yaml
transport: stdio
command: 'D:\Node\node.exe'
args: ['D:\mc\MCP Development\mcp-server\src\server.js', '--stdio', '--state-dir', 'D:\mc\MCP Development\runtime\state', '--workspace', 'D:\mc']
cwd: 'D:\mc\MCP Development\mcp-server'
```

**本机注意**：stdio 实现已保留，但当前 DSH 进程沙箱对 Node 子进程 `spawn` 返回 `EPERM`（与 §3.5 的 stdio 失败一致），所以本机验收与推荐配置使用 **streamable-http**。没有该限制的机器可用 stdio。

## 从源码构建

要求 JDK 21。构建不依赖 Gradle/MDK 下载，直接对整合包自带的 NeoForge jars 编译：

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\build.ps1"
```

产物：`dist\mcpbridge-0.1.0.jar`。编译期 classpath 在 `vendor\cp`（ASCII 路径，避免中文路径问题），从本地 `.minecraft\libraries` 生成；需要时重新执行 `scripts\prepare-cp.ps1`（见 `docs/INSTALL.md`）。

## 目录

```
mod/src/main/java/dev/mcp/bridge/   客户端 mod（22 个 Java 文件）
mcp-server/src/server.js             Node MCP server（streamable-http + stdio）
mcp-server/src/smoke-client.mjs      官方 SDK 客户端 smoke test
scripts/                             测试/验证脚本（不含 token）
dist/mcpbridge-0.1.0.jar             构建产物
vendor/baritone/                     Baritone 官方 NeoForge 资产
docs/Ph1/                            历史归档（下一次AI不需要）; docs/Ph2/  当前交接文档（下一次AI使用）
evidence/                            日志/尺寸/配方/截图等证据
.mcp-agent/                          memory 文件（不在 AGENTS.md）
```

## AI 玩家手册（给接手的 Agent）

- [AIPlayerManual.md](AIPlayerManual.md)：进服流程、84 个 MCP 工具地图、job 模型、**SR / CR / AR / ESRS / ESRA 多子代理架构与启用方式**（含 `run.mjs chat` 打开 Chatter）、容器/寻路/编码等实战坑。
- [AIPlayer.md](AIPlayer.md)：角色卡 —— 玩家手填性格与目标；Chatter 读「性格 / Personality」一节。

```powershell
# 全栈（传感器 + 调度 + 执行 + 聊天代理），Ctrl+C 退出
$env:ZFF_CR="1"; $env:ZFF_BACKEND="server"
node mcp-server/src/agent/run.mjs chat
```

## 已知限制与风险

- **暂停菜单会冻结 tick**：单人游戏失焦时 Minecraft 会打开暂停菜单；需要保持客户端前台（或 RDP/虚拟显示）。后台测试脚本可用 `scripts/focus-game.ps1` 辅助聚焦。2026-10-29 起 `PathJob` 会忽略暂停期间的 Baritone 停止，不再把任务判成 `internal`（该修复尚未做运行时复测）。
- **Baritone 视角**：本整合包中 Baritone 的 look behaviour 不旋转玩家（`MixinVec3i` 与 `efficient_hashing` 冲突，路径仍可走）。`PathJob` 每 tick 主动朝下一个路径节点转向；100 格直线已验证，复杂转弯路径仍建议在安全区域先试。
- **村民交易**：`trade_select` 走真实 `ServerboundSelectTradePacket` + 每次 `PICKUP`，按物品数量核验（客户端 offer 的 `uses` 不一定实时刷新）。不要在光标持有物品时异常关闭商人界面。
- **GUI 输入通道**：桌面锁屏时只能靠 HMC-Specifics 的 `/command`（`scripts/hmc-call.ps1`）；该 mod 偶尔不写注册文件，这时本项目 bridge 仍可用，但无法做 GUI 导航。
- **演示模式**：测试启动器会过滤 `--demo`；用 PCL 正常启动不会进入 demo。
- **多客户端**：若有旧客户端停在主菜单，它也会写心跳；MCP server 会优先选择 `inWorld=true` 的实例。关闭多余客户端即可。
- **模组容器**：只对标准 `AbstractContainerMenu` 操作并回读；非标准/特殊 GUI 返回 `unsupported`，不会盲点。
- **JEI 虚拟配方**：通过 `mezz.jei.common.Internal.getJeiRuntime()` 反射读取；JEI 缺失时自动退回 RecipeManager。
- **服务端只读**：本实现未安装/修改任何服务端内容；多人验收只做了加入、观察和截图。

## 安全

- 回环绑定 `127.0.0.1`，Bearer token 32 字节随机；`/health` 免认证，其余认证。
- 实例文件按 5 秒心跳刷新；过期 20 秒；不依赖 pid。
- token 不出现在日志、工具结果、memory、文档。`mc_instances` 返回显式白名单字段。

详细内容见 [docs/Ph2/INSTALL.md](docs/Ph2/INSTALL.md)、[docs/Ph2/TOOLS.md](docs/Ph2/TOOLS.md)、[docs/Ph2/TROUBLESHOOTING.md](docs/Ph2/TROUBLESHOOTING.md)、[docs/Ph2/ACCEPTANCE.md](docs/Ph2/ACCEPTANCE.md)。
