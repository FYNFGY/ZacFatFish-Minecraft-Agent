# 排错与实测坑位

以下不是猜测，都是本机集成中实际遇到并修掉/绕开的问题。

## 1. 绝对不要用 headless/LWJGL 替换

上一轮尝试用 off-screen LWJGL 替换，产生了 **11 个** `Caught exception during event ... already registered`；真实 LWJGL 下同一套 mod 是 **0**。本实现只支持有头客户端。需要无人值守时用 RDP/虚拟显示，保持真实渲染路径。

本机最终日志：`../../evidence/client-log-checks.txt` 中 `Caught exception during event count: 0`。

## 2. 只发 NeoForge 元数据

`mcpbridge-0.1.0.jar` 只包含 `META-INF/neoforge.mods.toml`，**没有 `fabric.mod.json`**。整合包含 Sinytra Connector，双元数据 jar 会被重复加载。

检查：

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z=[IO.Compression.ZipFile]::OpenRead('D:\mc\MCP Development\dist\mcpbridge-0.1.0.jar')
$z.Entries | Where-Object { $_.FullName -match 'fabric.mod.json|neoforge.mods.toml' }
```

## 3. 不要用 pid 发现

本机沙箱下 `Get-Process java`、`tasklist` 可能看不到由另一个进程启动的 Java；上一轮 `Get-Process` 返回 0 但客户端实际在运行。因此：

- 只用实例 JSON + `heartbeatAt`（5 秒刷新，>20 秒过期）。
- `mc_instances` 只返回白名单字段；MCP server 探测 `/health` + `/info`。
- 多个实例时优先 `info.inWorld=true`，其次最新心跳。
- 若旧客户端停在主菜单，它仍会心跳；关闭它，或用 `mc_attach` 指定实例文件。

## 4. LWJGL natives：不要用 `natives-windows` 子串

PowerShell 启动器若用 `$rel -match 'natives-windows'`，会同时匹配 `natives-windows-x86` / `natives-windows-arm64`，后用 32 位 `lwjgl.dll` 覆盖 64 位，报：

```
java.lang.UnsatisfiedLinkError: Failed to locate library: lwjgl.dll
```

用 `scripts/prepare-natives.ps1`（只匹配 `:natives-windows$`）或 PCL 自带的 `假期增援计划-natives`。正确 `lwjgl.dll` 约 479 KB。

## 5. PowerShell 5.1 不能可靠传中文参数

本机 `pwsh` 实际是 Windows PowerShell 5.1，`ProcessStartInfo.ArgumentList` 不存在；`.Arguments` 字符串把非 ASCII 路径转成 ANSI，导致：

```
Failed to write ... config\fml.toml: AccessDeniedException
```

表现是 gameDir 变成 `PCL 姝ｅ紡鐗?2.12.2...`。解决：

- 正常游玩用 PCL 启动。
- 自动测试用 `launch-client.mjs`（Node 的 `spawn` 数组参数，UTF-16）。
- 不要用 `.Arguments` 拼接中文路径；也不要在 `.bat` 写非 ASCII。

## 6. 暂停菜单会冻结游戏

单人游戏失焦时 Minecraft 会打开 `PauseScreen`；此时 `get_state` 仍返回、`pathing=true`，但 **player 不移动**、`pathIndex=0`、`timeLeft` 一直增长。`path_to` 会看起来“running 但 progress 不动”。

处理：

- 保持客户端窗口前台；RDP/虚拟显示时也一样。
- 测试脚本可用 `scripts/focus-game.ps1`（Win32 `AttachThreadInput`+`SetForegroundWindow`）尝试聚焦。
- 若窗口失焦后已有 `PauseScreen`，用 HMC-Specifics `close` 关闭，再开始动作。
- 注意：本机 `options.txt` 的 `pauseOnLostFocus:false` 仍会因整合包/窗口行为出现暂停，不能只依赖该选项。
- 2026-10-29 修复：`PathJob` 现在忽略暂停期间的 Baritone 停止，不会再把任务判成 `internal`（未运行时复测）。

## 7. Baritone 视角不旋转（本整合包实测）

Baritone 1.11.3 在包内加载正常，但路径移动时 **player yaw 不随路径改变**（`freeLook`/相机 mod/输入冲突），导致移动键方向与路径不一致，会走向错误方向并卡在屏障角落。

已修复：`BaritoneControl.configureForPathing()` 关闭 `freeLook/blockFreeLook`，`PathJob` 每 tick `steer()` 朝下一个路径节点设置 yaw/pitch。修复后 `(0,100,-50) → (0,100,50)` 100 格 progress=100，~18s。

其他 Baritone 日志：

```
Method overwrite conflict for hashCode in mixins.baritone.json:MixinVec3i ...
  previously written by zank.mods.efficient_hashing.mixin.MixinVec3i. Skipping method.
```

这是无害的 Mixin 冲突（跳过 Baritone 的优化），未导致崩溃；路径功能可用。

## 8. `--demo`

测试启动参数如果包含 `--demo` 会进入演示模式（5 天游戏时间，DemoIntroScreen），不要用。`launch-client.mjs` 会过滤 `--demo`；PCL 正常启动不带。

## 9. 容器/模组 GUI

`container_open` 只对标准 `AbstractContainerMenu` 操作；打开后仍要 `container_read` 确认槽位索引。模组容器若不是标准菜单（自定义 Screen 不暴露 `AbstractContainerMenu`）返回 `unsupported`，不要盲点。操作后 `container_move` 会回读并返回 `verified`；若服务器拒绝，`verified=false`。

## 10. `scan_area` 体积

`scan_area(radius=16)` 可能匹配数千方块；实现用紧凑 `[x,y,z,id]`、`maxBlocks`、30KB 硬截断和 `blockCounts`。实测 22,986 bytes、795 blocks、`truncated=false`。若加 `includeProperties/includeCollision` 会变大并更早截断。

## 11. 目标服务器连接

首次连接可能被服务器以 `Erreur de protocole réseau` 拒绝；重试一次后本机成功加入 `zmcp3.rhymc.com:1069`（players=1，dimension overworld），未做任何服务端修改。若持续失败，先确认服务器在线/端口/整合包版本，不要把问题归因于 bridge。

## 12. DSH 注册

- 先启动 `start-mcp.ps1`，再让 DSH 连接；`failOnStartupError:false` 时服务器未启动只会暂时没有工具。
- 本机 DSH 在修改 `cordis.patch.yml` 后热加载了 MCP client；若你的版本不热加载，重启 DSH。
- 工具名前缀是 `mcp__mc__`；如果只看到 `mcp__headlessmc__`，说明还是旧的 HMC bundle，检查 profile patch 顺序/名称。
- **stdio 在本机失败**：`StdioClientTransport` 启动子进程返回 `spawn EPERM`（§3.5 的同类问题），因此本机使用 streamable-http。stdio 实现保留；无沙箱限制的机器可用。

## 13. token

token 只在实例 JSON 中；**不要**把实例文件、Authorization 头、token 文件贴到聊天/日志/模型上下文。MCP server 的 `mc_instances`/`bridge_info`/`mc_attach` 都不会返回 token。排查时只看端口、文件名、heartbeat 年龄。

## 14. Java / 构建

必须 Java 21。构建时不要用 `--release 17`；NeoForge 21.1 类和记录类需要 21。`build.ps1` 默认使用 `D:\java\jdk-21_windows-x64_bin\jdk-21.0.8`，可用 `-Jdk` 覆盖。

## 15. TickScheduler 同 tick 排空（2026-10-29 修复）

症状：`container_move` 的 4-tick 回读几乎瞬间返回；`trade_select` 在 14–22ms 内返回 `verified=false`，但服务器随后真的执行了交易；任何 `TickScheduler.poll` 都像立刻超时。

原因：`tick()` 用 `while (TASKS.poll() != null) task.run()`，任务里再 `schedule()` 的新任务会被同一循环继续取出，60 次 poll 在 1 个 tick 内跑完。

修复：每个 tick 先把队列取成本批任务，执行期间新排队的任务留到下一 tick。修复后一笔交易约 0.4–1.6s，容器回读真实等待 4 tick。

## 16. HMC-Specifics 的 MCP 注册偶尔缺失

`hmc-mcp-bridge` 正常时会在 `%LOCALAPPDATA%\state\headlessmc-mcp\instances\<pid>.json` 写注册（`/info` 返回 `hmc-mcp-bridge`），`scripts/hmc-call.ps1` 可直接调用 `gui/click/text/key/screenshot/quit/menu/close/disconnect`。

但 2026-10-29 的一次启动里日志有 `HMC-Specifics initialized!`，却没有注册文件、也没有监听端口；此时 GUI 输入通道不可用（本项目 bridge 不受影响）。桌面锁屏时这是唯一可用的点击方式，所以：

- 先确认游戏是否在内世界（`get_state`），再决定是否需要 GUI。
- **HMC 不可用时不要退出世界或重启客户端**，否则可能无法再进入世界。
- HMC 的 MCP server（`headlessmc`）使用重定向的 HOME（`D:\mc\tools\hmc-home`），默认看不到这份注册文件；不要指望 `mcp__headlessmc__*` 能附加，直接用 `hmc-call.ps1`。

## 17. 锁屏桌面 + 多人断开 = GLFW 卡死

在锁屏桌面上从多人服务器 `disconnect`，客户端会卡在 `Minecraft.disconnect()` → `glfwSwapBuffers`（jstack 可见 Render thread 在 `Window.updateDisplay`），主线程不再 tick：`get_state` 超时、HMC 也不响应。处理：`Stop-Process -Name java -Force` 后用 `LatestLaunch.bat` 重启。

防止误入服务器：用 `scripts/join-world.ps1`，它按目录名过滤单人世界、检查“Jouer dans ce monde”按钮可用、进入后校验玩家 UUID（`57904bd0-2ee2-4ee7-8116-bff31b2ad9bc`），不符立即 `disconnect`。

## 18. 村民交易核验

- 客户端 `MerchantMenu` 的 offer `uses` 不一定实时刷新，不要只靠它判断成功。
- `trade_select` 现在按**物品数量差**核验（结果 +sell、付款 -cost），返回 `verifiedBy: "item-count"`。
- 服务端 `tryMoveItems` 会把**整叠**付款移进交易槽；`QUICK_MOVE` 会一直交易到付款耗尽。`trade_select` 改为每次 `PICKUP`，实现“一笔一次”。
- 不要在光标持有物品时退出或异常关闭商人界面，物品可能丢失。
