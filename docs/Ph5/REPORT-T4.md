# T4 - 不额外开服务端端口

## 做了什么

1. 客户端保留本地 loopback HTTP（MCP 客户端连它，随机端口 + Bearer token，写在实例文件里）；
   **专用服务端不监听任何端口**：`BridgeRuntime.start()`/`HttpBridge` 只在 `dist == CLIENT` 时被调用，
   服务端分支只做配置、SavedData、指令、payload。
2. 客户端 <-> 服务端走 **Minecraft 自己的通道**：`ZffPayloads`（`mcpbridge:rpc_request` 客户端→服务端、
   `mcpbridge:rpc_response` 服务端→客户端、`mcpbridge:stop` 服务端→客户端），`StreamCodec` + `PayloadRegistrar.playToServer/playToClient`，
   注册为 optional。`ZffRemote.call()` 在客户端主线程发包，HTTP 线程等回包（不阻塞游戏线程）。
3. 不使用 RCON、不开新端口、不写临时 socket；`/zff` 指令由服务端注册并由客户端 `sendCommand` 触发。

## 验收

- 专用服务端进程（pid 20592）监听表只有 `:::25565`（Minecraft 自己的端口），这是 `Get-NetTCPConnection` 按 pid 过滤的原始输出；
- 同时 `zff_memory_*`/`zff_todo_*`/`zff_status` 在该通道上正常工作（见 T2 证据）。

## 证据

`evidence/ph5-no-server-port.json`（pid + 监听表 + 判定），服务端日志 `runtime/server-ph5.log`。
