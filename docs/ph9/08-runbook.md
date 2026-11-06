# 08 — 运行手册（Runbook）

## 1. 依赖

```text
Java 21
Node.js 22
Minecraft 1.21.1
NeoForge 21.1.228
```

可选：

```text
Baritone 1.11.3 NeoForge
Create / Apotheosis / Serene Seasons
```

> Pendulum/Jupiter/Rhino 已废弃，不要安装；战斗全部在 ZFF Bridge 内实现。

## 2. 安装

### 2.1 游戏侧

把：

```text
ZFF Bridge.jar
baritone-standalone-neoforge-1.11.3.jar
baritone-api-neoforge-1.11.3.jar
```

放入：

```text
<instance>/mods/
```

### 2.2 MCP 侧

```text
ZFF MCP Server.zip  → 工作目录根下
zff-mc-plugin/      → DSH profile
```

## 3. 启动顺序

```text
1. 启动 Minecraft，进入存档
2. 确认 ZFF Bridge 注册 JSON / 端口
3. 启动 MCP Server
4. 启动/刷新 DSH
```

### 手动启动

```powershell
.\start-mcp.ps1
```

### 停止

```powershell
.\stop-mcp.ps1
```

## 4. 端口检查

```powershell
Get-NetTCPConnection -State Listen |
  Where-Object { $_.LocalPort -in 57610,57611,57612 }
```

| 端口 | 期望 |
|---|---|
| 57610 | Node MCP Server |
| 57611 | daemon |
| 57612 | internal ingest（本机） |

## 5. 实例注册

```text
%LOCALAPPDATA%\state\headlessmc-mcp\instances\*.json
```

关键字段：

```json
{
  "port": 51841,
  "heartbeatAt": 1793801647864,
  "gameDir": "...",
  "inWorld": true,
  "username": "FYNyesterday"
}
```

## 6. 快速健康检查

```powershell
# 游戏状态
node scripts/tool.mjs get_state

# 实例
node scripts/tool.mjs mc_instances

# （Pendulum MCP 已废弃，不再检查 25566）

# Job
node scripts/tool.mjs jobs_list
```

## 7. 常见故障

### 7.1 ECONNREFUSED 127.0.0.1:57611

原因：daemon 未启动/被杀。

```powershell
cd D:\mc\zff-mc
node --input-type=module -e "import {ensureDaemon} from './src/mcp-env.mjs'; await ensureDaemon({workspace:'D:\\MC Agent Play',log:console.log})"
```

### 7.2 get_state 返回旧实例/空 player

原因：MCP server 缓存了旧 Bridge 端口。

处理：

```powershell
.\stop-mcp.ps1
.\start-mcp.ps1
```

或重启 daemon/src。

### 7.3 Pendulum 已废弃

不再安装/启动 Pendulum，不再监听 25566。若旧实例仍占用 25566，关闭旧游戏即可。

### 7.4 Unknown dimension 'minecraft:the_end'

原因：该存档/维度注册缺失，常见于裁剪过的测试存档。

处理：

- 使用完整世界存档
- 或实现 `dimension_unavailable` 并在 UI 明确提示
- 不要反复执行 `execute in the_end`

### 7.5 客户端崩溃/存档保护

- 测试前 `save_world`
- 使用测试存档，不直接用主存档
- 出现连续崩溃先停 MCP，不要继续发任务

## 8. 纯净测试实例

目录：

```text
D:\mc\MCP Development\ph7-env\clean
```

mods：

```text
ZFF Bridge
Baritone standalone/API
```

启动：

```powershell
node launch-client.mjs `
  --mc-dir "<PCL .minecraft>" `
  --game-dir "D:\mc\MCP Development\ph7-env\clean" `
  --java "<Java21 javaw>" `
  --version "<NeoForge 版本>" `
  --state-dir "$env:LOCALAPPDATA\state\headlessmc-mcp" `
  --natives "D:\mc\MCP Development\runtime\state\natives-x64" `
  --quick-play-singleplayer "New World"
```

## 9. 停止与清理

```text
1. 停止所有 Job
2. save_world
3. 关闭游戏
4. stop-mcp.ps1
5. 检查 57610/57611/57612 无残留
```

## 10. Release 校验

```text
SHA256SUMS.txt:
73d015564b0f4c9d1291a50635d58526fdada2bf1f1836c746530a85efa3ec87  ZFF MCP Server.zip
7a54af36d42185c23741b57cc1c5476ba068413eae63d789d05a7b8beed7a726  ZFF Bridge.jar
```

> 该值随 release 构建变化；以 release 目录内 `SHA256SUMS.txt` 为准。
