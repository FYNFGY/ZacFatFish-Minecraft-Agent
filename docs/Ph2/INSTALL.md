# 安装与运行

## 前置条件

- Windows + Minecraft **1.21.1**，NeoForge **21.1.228**（整合包 `假期增援计划`）。
- 有头客户端：PCL / 官方启动器正常启动，**不要**使用任何 headless/LWJGL 替换补丁。
- JDK 21（构建用；本机 `D:\java\jdk-21_windows-x64_bin\jdk-21.0.8`）。
- Node.js 22（MCP server；本机 `D:\Node\node.exe`，依赖已安装在 `mcp-server\node_modules`）。
- 如需寻路：`baritone-api-neoforge-1.11.3.jar`（已放在 `vendor/baritone`，SHA-1 与官方 checksums 一致）。

## 1. 构建（可选；已提供 dist）

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\build.ps1"
```

直接对本地 NeoForge 类编译，不需要 Gradle/MDK 下载。classpath 来自：

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\scripts\prepare-cp.ps1" `
  -McDir "D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft" `
  -VersionDir "D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft\versions\假期增援计划"
```

`prepare-cp.ps1` 把 `neoforge-21.1.228-*.jar`、full `srg` client jar、库和 Baritone/JEI 复制到 `vendor\cp`（ASCII 文件名），避免中文路径进入 `-cp`。

## 2. 安装 jar

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\install.ps1" `
  -VersionDir "D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft\versions\假期增援计划" `
  -WithBaritone
```

只新增：

- `mods\mcpbridge-0.1.0.jar`
- `mods\baritone-api-neoforge-1.11.3.jar`（仅寻路需要）

不修改 `config/`、`saves/`（测试时另行复制一次性世界）、服务端。旧的 `hmc-mcp-bridge.jar` 可以保留；MCP server 只选择带 `heartbeatAt` 且 `inWorld` 的实例。

## 3. 启动客户端

用 PCL 正常启动。bridge 在客户端设置完成后启动，日志应出现：

```
[mcpbridge/]: MCP bridge listening on 127.0.0.1:<port> (protocol 1, version 0.1.0)
```

实例文件默认写到：

- `%LOCALAPPDATA%\state\headlessmc-mcp\instances\<uuid>.json`

字段：`protocol/port/token/bridgeVersion/loader/mcVersion/gameDir/started/heartbeatAt`，每 5 秒刷新。**不要**把该文件发给模型或提交。

## 4. 启动 MCP server

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\start-mcp.ps1"
```

- 默认端口 `57610`，绑定 `127.0.0.1`。
- 健康检查：`http://127.0.0.1:57610/health`。
- 日志：`runtime\mcp-server.err.log` / `runtime\mcp-server.out.log`。
- Node 在别处：`-Node 'C:\Program Files\nodejs\node.exe'`。
- 自定义状态目录：`-StateDir <dir>`（需与客户端 `-Dmcp.bridge.state` 一致）。
- 如果从 DSH 的沙箱 shell 直接运行，`Start-Process` 的子进程可能随该命令结束被 job object 回收；普通 PowerShell 窗口运行不受影响。需要长期后台运行时，用任务计划程序，或把 `node ... --http ...` 作为受管后台 job 保持父进程存活。本机验收时使用后者。

## 5. DSH 连接

把 [dsh/mc-control.patch.yml](../../dsh/mc-control.patch.yml) 的条目追加到
`C:\Users\<you>\.dsh\profiles\desktop\cordis.patch.yml`，然后重载/重启 DSH。

本机验证：编辑 patch 后 DSH 热加载了该 MCP client，工具以 `mcp__mc__*` 出现，无需重启整个应用。若你的 DSH 版本不热加载，重启 DSH 一次。

stdio 兜底（DSH 自己启动 MCP server，无需 57610）：

```yaml
- insert:
    - id: mc-control-stdio
      name: "@deepseek-ai/dsh-mcp-client"
      config:
        serverName: mc
        transport: stdio
        command: 'D:\Node\node.exe'
        args:
          - 'D:\mc\MCP Development\mcp-server\src\server.js'
          - '--stdio'
          - '--state-dir'
          - 'D:\mc\MCP Development\runtime\state'
          - '--workspace'
          - 'D:\mc'
        cwd: 'D:\mc\MCP Development\mcp-server'
        toolCallTimeoutMs: 600000
        failOnStartupError: false
```

**本机限制**：当前 DSH 进程沙箱对 Node 子进程 `spawn` 返回 `EPERM`（实测 `@modelcontextprotocol/sdk` 的 `StdioClientTransport` → `spawn EPERM`），所以本机推荐/验收使用 streamable-http；stdio 代码路径保留给没有该限制的环境。

## 6. 手动 attach

正常情况 MCP server 自动发现。要锁定某个实例：

```json
{"instanceFile": "C:\\...\\instances\\<uuid>.json"}
```

或 `port + tokenFile`：

```json
{"port": 57608, "tokenFile": "D:\\path\\token.txt"}
```

`mc_attach` 不接受内联 token；token 文件由你从实例 JSON 的 `token` 字段复制（注意保护）。

## 7. 测试启动器（可选）

`launch-client.mjs` 用于开发测试，使用参数列表传递 UTF-8 JSON，避免 Windows PowerShell 5.1 的 `ProcessStartInfo.Arguments` 把中文路径/参数转成 ANSI。需要正确的 x64 natives：

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\scripts\prepare-natives.ps1" `
  -McDir "D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft" `
  -VersionDir "D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft\versions\假期增援计划" `
  -OutDir "D:\mc\MCP Development\runtime\state\natives-x64"

node "D:\mc\MCP Development\launch-client.mjs" `
  --mc-dir "D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft" `
  --game-dir "D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft\versions\假期增援计划" `
  --java "D:\java\jdk-21_windows-x64_bin\jdk-21.0.8\bin\java.exe" `
  --version "假期增援计划" `
  --state-dir "D:\mc\MCP Development\runtime\state" `
  --natives "D:\mc\MCP Development\runtime\state\natives-x64" `
  --quick-play-singleplayer "mcp-test-world"
```

用户实际游玩请用 PCL，不要用测试脚本代替启动器。

## 卸载

删除两个 jar，停止 MCP server，删除 `.mcp-agent`（可选）和测试世界即可；不涉及服务端。
