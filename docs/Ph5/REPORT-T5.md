# T5 - `/zff stop` 变成事件

## 做了什么

1. `/zff stop` 现在是**服务端指令**（`ZffServer.cmdStop`）：把服务端 TodoList 里 `pending/running` 的任务全部
   标成 `cancelled(reason=zff_stop_command)`，然后 `PacketDistributor.sendToAllPlayers(new Stop(reason,"command"))`。
2. 客户端收到 `Stop` payload 后在主线程执行 `StopAll.run("zff_stop_command")`（取消所有 job、停 Baritone、
   清 TickScheduler、关界面），并往 `EventJournal` 追加
   `{type:"zff_stop", reason, by:"command", jobsCancelled, baritoneStopped, tasksDropped, source:"mod"}`。
3. Node 侧：`sensors.mjs` 从 `wait_events` 读到 `zff_stop` 后转发到总线；`sr.mjs` 的 `onZffStop` 取消 AR、
   `list.cancelRunning(reason)`、把 `last.zff_stop` 写入记忆，并发出 `stop_handled`。

## 验收（`test/ph5-zff-stop.mjs`）

- 先起一个 400 tick 的 `walk` job，并把一个服务端 todo 标成 running；
- 客户端发 `/zff stop`（`known=true`），**5 ms** 后 job 变 `cancelled`，`reason=zff_stop_command`；
- **11 ms** 后 `wait_events` 收到 `zff_stop{by:"command", jobsCancelled:1, reason:"zff_stop_command"}`；
- 服务端 todo 该任务变 `cancelled`，`running` 计数为 0；`chat_history` 里能看到服务端回的 `zff stop: ...`。

## 证据

`evidence/ph5-zff-stop-event.json`；脚本 `test/ph5-zff-stop.mjs`。
（上一轮的 `evidence/ph5-zff-stop.json` 仍然有效：那是客户端指令版本的记录。）
