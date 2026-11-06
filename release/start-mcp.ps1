param(
  [int]$Port = 57610,
  [string]$StateDir = '',
  # Ph6: the workspace decides where the CR store (.mcp-agent/cr-store.json), the chat rule file
  # (runtime/zff-chat-rules.json) and the env mirror (runtime/env-inbox.json) land.
  [string]$Workspace = 'D:\mc\MCP Development',
  [string]$Node = 'D:\Node\node.exe'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$script = Join-Path $root 'mcp-server\src\server.js'
$logDir = Join-Path $root 'runtime'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$outLog = Join-Path $logDir 'mcp-server.out.log'
$errLog = Join-Path $logDir 'mcp-server.err.log'

try {
  $health = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -UseBasicParsing -TimeoutSec 2
  if ($health.StatusCode -eq 200) {
    Write-Output "MCP server already running on 127.0.0.1:$Port"
    exit 0
  }
} catch {
  # not running yet
}

if (-not (Test-Path -LiteralPath $script)) { throw "server script not found: $script" }
if (-not (Test-Path -LiteralPath $Node)) { throw "node not found: $Node" }

$q = {
  param($s)
  if ($s -match '\s') { '"' + $s + '"' } else { $s }
}
$argList = @($script, '--http', '--port', "$Port", '--workspace', $Workspace)
if ($StateDir) { $argList += @('--state-dir', $StateDir) }
$quoted = ($argList | ForEach-Object { & $q $_ }) -join ' '
$proc = Start-Process -FilePath $Node -ArgumentList $quoted -WorkingDirectory (Join-Path $root 'mcp-server') -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
Write-Output "started pid=$($proc.Id) url=http://127.0.0.1:$Port/mcp"

for ($i = 0; $i -lt 20; $i++) {
  Start-Sleep -Milliseconds 500
  try {
    $health = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -UseBasicParsing -TimeoutSec 2
    if ($health.StatusCode -eq 200) {
      Write-Output "health ok"
      # 🔴 2026-11-01 P0-3：mod 把聊天推 `http://127.0.0.1:57611/chat_event`，而**聊天守护进程**
      #   `mc-daemon.mjs` 以前没人启动 → 每条推送 ConnectException → 玩家说话永远收不到、硬中断失效。
      #   这里在 server 健康之后把它一起拉起来（插件自动起 server 时也会拉，见 src/mcp-env.mjs ensureDaemon）。
      $daemonPort = 57611
      $daemonJs = Join-Path $root 'mcp-server\src\mc-daemon.mjs'
      $daemonUp = $false
      try {
        $h = Invoke-WebRequest -Uri "http://127.0.0.1:$daemonPort/health" -UseBasicParsing -TimeoutSec 1
        $daemonUp = ($h.StatusCode -eq 200)
      } catch { $daemonUp = $false }
      if (-not $daemonUp -and (Test-Path -LiteralPath $daemonJs)) {
        $env:MCP_URL = "http://127.0.0.1:$Port/mcp"
        $env:PORT = "$daemonPort"
        $env:MC_WORKSPACE = $Workspace
        $denv = "MCP_URL=$env:MCP_URL;PORT=$env:PORT;MC_WORKSPACE=$Workspace"
        Start-Process -FilePath $Node -ArgumentList "`"$daemonJs`"" -WorkingDirectory (Join-Path $root 'mcp-server') -WindowStyle Hidden -PassThru `
          -RedirectStandardOutput (Join-Path $logDir 'mc-daemon.out.log') -RedirectStandardError (Join-Path $logDir 'mc-daemon.err.log') | Out-Null
        for ($j = 0; $j -lt 12; $j++) {
          Start-Sleep -Milliseconds 400
          try {
            $h = Invoke-WebRequest -Uri "http://127.0.0.1:$daemonPort/health" -UseBasicParsing -TimeoutSec 1
            if ($h.StatusCode -eq 200) { $daemonUp = $true; break }
          } catch { }
        }
      }
      if ($daemonUp) { Write-Output "chat daemon ok (127.0.0.1:$daemonPort)" }
      else { Write-Output "WARN: chat daemon (127.0.0.1:$daemonPort) not healthy - 玩家说话不会被立刻收到" }
      exit 0
    }
  } catch {
    # retry
  }
}
Write-Output "server did not become healthy; see $errLog"
exit 1
