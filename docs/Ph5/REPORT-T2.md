# T2 - Memory / TodoList 搬到服务端（SavedData）

## 做了什么

1. `ZffSavedData extends SavedData`：`actorId`、`rev`、`memory{key -> {value, role, tags, updatedAt}}`、
   `todos{id -> {title, status, priority, spec, note, jobId, actorId, createdAt, updatedAt}}`，存在
   `server.overworld().getDataStorage().computeIfAbsent(...)` 的 `zff_agent.dat`（即
   `saves/<world>/data/zff_agent.dat`），每次修改 `setDirty()`。
2. 服务端指令：`/zff memory set|get|list|forget`、`/zff todo add|list|done|clear`、`/zff status`
   （root 无权限要求，写操作 `requires(hasPermission(2))`）。
3. 客户端 **不碰** 世界文件：通过 `ZffPayloads` 的 RPC（`mcpbridge:rpc_request/response`）读写，
   `ZffRemote.call()` 在客户端主线程发包、HTTP 线程等回包。新增 MCP 工具：
   `zff_status`、`zff_memory_set|get|list|forget`、`zff_todo_add|list|done|update|clear`（共 10 个，工具总数 84）。
4. Node 侧镜像：`server-backend.mjs` + `todolist.mjs`/`memory.mjs` 的 `remote` 适配器，
   `ZFF_BACKEND=server` 时 `hydrate()` 拉服务端数据、`flush()` 推本地改动（失败只排队不阻塞），
   本地 `runtime/agent/*.json` 退化为离线缓存。

## 验收

- **专用服务端**：3 次启动的 `SERVER READY` 依次是 `memory=0 todos=0` -> `memory=2 todos=2` ->
  `memory=2 todos=4`，`zff_agent.dat` 342 B，说明数据跨重启持久（`evidence/ph5-saveddata.json`）。
- **客户端（单机）**：`zff_memory_set/list`、`zff_todo_add/list` 全部走 payload 写入集成服务端，第 2 个客户端重启后
  marker 与 todo 仍在（`evidence/ph5-saveddata-client.json`：`markerSurvived=true`, `todoSurvived=true`）。
- **两个观察点同一份数据**：服务端 `/zff memory list` 与 MCP 工具读到同样的条目（同一 `rev`）。

## 顺带修的真问题

`/save-all` 在本整合包里**不存在**（`save_world` 以前只是"把命令发出去"，退出前并没有真正落盘，导致重启回档）。
本轮把 `save_world` 改成：单机时在服务端线程调用 `MinecraftServer.saveEverything(true, true, false)` 并等它写完再返回
（`method=integrated_server.saveEverything, flushed=true`），多人时退回 `/save-all` 命令。
修好后 `zff_agent.dat` 的 mtime 会在 `save_world` 返回前更新，重启不再丢 `rev`。

## 证据

`evidence/ph5-saveddata.json`、`evidence/ph5-saveddata-client.json`；脚本 `test/ph5-saveddata.mjs`（before/after 两阶段）。
