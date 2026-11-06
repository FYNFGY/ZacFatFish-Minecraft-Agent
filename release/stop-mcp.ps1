param(
  [string]$Workspace = "D:\MC Agent Play",
  [int]$Port = 57610,
  [int]$DaemonPort = 57611
)

$ErrorActionPreference = 'SilentlyContinue'
$killed = New-Object System.Collections.Generic.HashSet[int]

function Kill-Pid([int]$ProcessId) {
  if ($ProcessId -le 0 -or $ProcessId -eq $PID) { return }
  if ($killed.Add($ProcessId)) {
    Write-Host ("taskkill /T /F " + $ProcessId)
    & taskkill /PID $ProcessId /T /F *> $null
  }
}

# state file first
$stateFile = Join-Path $Workspace 'runtime\zff-mcp-server.json'
if (Test-Path $stateFile) {
  try {
    $state = Get-Content $stateFile -Raw | ConvertFrom-Json
    if ($state.pid) { Kill-Pid ([int]$state.pid) }
    if ($state.daemonPid) { Kill-Pid ([int]$state.daemonPid) }
    Remove-Item $stateFile -Force
  } catch { }
}

# port owners
foreach ($p in @($Port, $DaemonPort)) {
  Get-NetTCPConnection -State Listen -LocalPort $p |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object { Kill-Pid ([int]$_) }
}

# current workspace node processes (detached/orphan)
$needle = $Workspace.ToLower()
Get-CimInstance Win32_Process |
  Where-Object { $_.Name -eq 'node.exe' -and $_.CommandLine -and $_.CommandLine.ToLower().Contains($needle) } |
  ForEach-Object { Kill-Pid ([int]$_.ProcessId) }

Start-Sleep -Milliseconds 500
$leftPorts = Get-NetTCPConnection -State Listen |
  Where-Object { $_.LocalPort -in @($Port, $DaemonPort) } |
  Select-Object LocalPort, OwningProcess
$leftProcs = Get-CimInstance Win32_Process |
  Where-Object { $_.Name -eq 'node.exe' -and $_.CommandLine -and $_.CommandLine.ToLower().Contains($needle) } |
  Select-Object ProcessId, CommandLine

[pscustomobject]@{
  ok = (-not $leftPorts -and -not $leftProcs)
  workspace = $Workspace
  killed = @($killed)
  leftoverPorts = @($leftPorts)
  leftoverProcesses = @($leftProcs)
} | ConvertTo-Json -Depth 5
