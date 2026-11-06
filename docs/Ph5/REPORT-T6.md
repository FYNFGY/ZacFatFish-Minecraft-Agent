# T6 - CR（Chatter）联调（开发期仍关闭）

## 做了什么

1. `cr.mjs` 拆出 `handleMessage()`（可单测，不依赖游戏），修了消息过滤：只处理 `type=="chat"` 且非自己发出的行，
   不再把系统提示/自己的指令当玩家发言；`enabled=true` 时同样注册 `task_request` 处理器回 `"waiting for instructions"`。
2. 集成点：`mine 32 cobblestone` -> `chat_task` + `chat_reply{newTasks:[...]}` -> SR 建任务并派发；
   `hello` -> `chat_smalltalk`，SR 不受影响；SR 空闲 -> `task_request` -> CR 异步回复，SR 不阻塞。
3. **开发期 CR 仍然关闭**（`ZFF_CR` 不设置、`zff_bridge.config` 的 `crEnabled=false`）；测试脚本显式构造
   `new Chatter({enabled:true})`，不动真实游戏配置。

## 验收（`node test/cr-chat.mjs`）

- `hello`: `isTask=false`、`chat_task=0`、任务数不变、`chat_smalltalk=1`；
- `mine 32 cobblestone`: `isTask=true`、`chat_task=1`、SR TodoList 新增 `mine 32 cobblestone`（pending），
  手动 `sr.tick()` 后 AR 收到派发（`dispatched[0].title` 一致，任务变 running）；
- 空闲 `task_request` -> `chat_reply{reason:"idle_waiting", text:"waiting for instructions"}`，`sr.running=true`。

## 证据

`evidence/ph5-cr.json`；脚本 `test/cr-chat.mjs`。
