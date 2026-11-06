# Ph6 M0 + M1 报告 —— 测试地基与原语层（含聊天中断）

> 2026-11-01。所有数字来自 `evidence/ph6-*.json`，全部在**本地**服测（`127.0.0.1:25565`），未连玩家服务器。

---

## 1. M0 测试地基（完成）

| 项 | 结果 |
|---|---|
| 本地 NeoForge 服 | `server/`（21.1.228、offline、flat、`ZffServerTest`、端口 25565）——**复用已有服，没装 vanilla jar** |
| 客户端 A（AI） | `D:\mc\client-test`，离线名 `DSHDeepSeek`，只装 mcpbridge（**这是唯一能稳定连本地服的配置**，见 §5） |
| 客户端 B（玩家） | `D:\mc\client-test2`，离线名 `FYNyesterday`，只用来在游戏里说话 |
| 启动方式 | `scripts/make-local-launcher.mjs` 从玩家现有的 PCL 启动脚本复制 JVM/classpath，**只改 quickPlay 目标**（有 `rhymc.com` 就直接拒绝生成） |
| 一键进服 | `scripts/ph6-connect-all.mjs`（用 mod 自己的 `connect` 工具按 ConnectScreen，等价于玩家点"加入"） |
| 验收 | `get_state` ✅、`scan_surface` ✅（249 格表面）、`env_read` ✅、`store_get {store:"mod"}` ✅ —— 见 `evidence/ph6-m0-local-stack.json` |

## 2. M1 原语层

### 2.1 mod 侧（客户端）

| 新增 | 作用 |
|---|---|
| `ChatPush.java` | 每条聊天**立即** POST 到 daemon `57611/chat_event`（Bearer = 桥令牌，非阻塞队列 1024，丢最旧不阻塞 tick） |
| `EnvSampler.java` | 客户端 tick 每 500 ms 采样：位置/血量/饱食/氧气/时间/天气/当前 job/附近/警报；`interrupt:true` 的警报（缺氧、脚下岩浆）**由 mod 直接 `stop_all`**，不经过任何 agent。真机证据：`evidence/ph6-safety-live.json`（alert 硬断言 + 一次 `job cancelled, reason "env alert: lava_at_feet"` 的实测） |
| `ModStore.java` | 每 5 s 原子写 `<gameDir>/zff/mod-store.json`：2000 条聊天环形 + 最近环境 + 背包摘要 |
| `Backend.java` | `server` / `local` / `local(mismatch)` 状态机（M3） |
| 聊天文本归一化 | 玩家消息到达客户端是 `<FYNyesterday> 鱼，回来吃饭`，**剥掉 `<名字> ` 前缀**后再入队，否则 `^鱼` 这类锚定规则永远不命中（这个 bug 在第一次真机测试里就抓到了） |
| `!zff stop` / `!zff save` / `!zff status` | mod 自己处理的保命短语（不依赖 agent 响应） |

### 2.2 Node 侧（MCP server）

| 新增 | 作用 |
|---|---|
| `chat_turn` | 说完再听；`message` 支持数组（≤5，间隔 250 ms）；`drain:false` 只发不听**且不清标志**；发送成功后自动写 CR store `chat.sent` |
| `env_read` | 读 mod 常驻采样；顺手镜像到 `<workspace>/runtime/env-inbox.json`（给插件判断掉线） |
| `store_get/set/list/delete` | CR store 可写、mod store 只读，两者**互不同步** |
| `zff_chat_stats` / `zff_preempt` / `zff_kill_client` | 诊断、抢占记录、管理员下线第 2 步 |
| `lib/chat-hub.mjs` | unread 队列 + cursor + ack + `Flag_User_Chat`；规则文件 `<workspace>/runtime/zff-chat-rules.json` **热加载**（默认 `^鱼`）；`mustReply` 关键词；self 分组与回声去重 |
| 统一守卫 | `reg()` 是所有 **103** 个工具的唯一入口（Ph5 的 84 个 + 本轮新增 19 个），标志置位时**只有 `chat_turn` 与 `exemptTools` 能过**，其余返回中性错误，不转发到 daemon/mod |
| `wait_events` 切片 | 2 s 一片，片间查标志；命中返回 `user_chat_pending`（`partial:true`，**不取消底层 job**）。`timeoutSeconds:0` 仍是一次立即调用（轮询语义不变） |
| ingest 端点 | MCP server 另开 `127.0.0.1:57612/internal/chat_event`，`x-zff-internal` 令牌写在 `runtime/mcp-internal-token`；daemon 转发前校验 mod 的 Bearer 令牌 |

## 3. 真机验收结果（两个真实客户端）

`test/ph6-m1-live.mjs` → **21/21 通过**（`evidence/ph6-m0-m1-live.json`、`evidence/ph6-m1-chat-interrupt-live.json`）：

| 检查 | 实测 |
|---|---|
| `backend=server`（协议协商） | ✅ `serverVersion 0.2.0 / serverProtocol 2 / serverId ZffServerTest` |
| `get_state` / `scan_surface` / `env_read` | ✅ 5 ms / 15 ms / 10 ms（远低于 2 s 预算） |
| **阻塞工具被聊天打断** | ✅ `wait_events {timeoutSeconds:30}` 在玩家发出 `鱼，回来吃饭` 后 **125 ms** 返回 `user_chat_pending`（预算 2500 ms） |
| 不取消底层任务 | ✅ 返回 `partial:true` + `note`，job 未被 cancel |
| 标志置位期间其它工具被拒 | ✅ `get_state` → `user_chat_pending` |
| `drain:false` 不清标志 | ✅ 返回 `flagUserChat:true` |
| `chat_turn` 读消息 + 清标志 | ✅ `received[0] = {sender:"FYNyesterday", text:"鱼，回来吃饭", mustReply:true}`，随后 `flagUserChat:false` |
| 发送被记进 CR store | ✅ `.mcp-agent/cr-store.json → entries["chat.sent"]` |

另有离线管道测试 `test/ph6-chat-smoke.mjs` → **19/19**（不需要游戏，验证规则热加载 / `exemptTools` / self 过滤 / drain 语义）：
`evidence/ph6-m1-chat-interrupt-offline.json`。

## 4. 与设计的偏差（都记进代码注释）

1. **`wait_events` 会被聊天事件本身唤醒**：EventJournal 里一出现 `chat` 事件，mod 的 `wait_events` 立刻返回。所以"切片查标志"之外还加了一条：如果返回的事件里有 `type=="chat"`，先等 200 ms 让推送落地，标志置位就**优先**返回 `user_chat_pending`（事件随 `partial` 一起带回）。否则验收要求里的"返回 user_chat_pending"在真机上永远不会成立。
2. **`Flag_User_Chat` 可能一直置位**：只有 `chat_turn`（drain）能清。这是设计（§2.8.5），但意味着没有续跑的会话会被卡住——因此插件必须保证"标志置位立即唤醒"，见 `docs/ph6/REPORT-M4.md`。
3. 规则文件与 CR store 都落在 `<workspace> = D:\mc\MCP Development`（`start-mcp.ps1` 的默认值已从 `D:\mc` 改过来）。

## 5. 踩到的坑（写给下一位）

1. **257 个 mod 的整合包客户端连不上本地服**：`versions\无头客户端测试` 那个客户端（含 Create、Packet Fixer、Sinytra Connector 等）一连本地 NeoForge 服就被 `Incompatible client!` 踢。根因和 M3 修掉的是同一条规则：**那些 mod 注册的"必需"通道服务端没有**。本地测试请用 `D:\mc\client-test` 这种只装 mcpbridge 的干净实例。顺带：我们自己的 mod 之前也因为同一个 `optional()` 缺陷成了"必需通道"，见 `REPORT-M3.md` §2（一行修复）。
2. **不要用 `Start-Process` 起长期进程再把它所在的命令 kill 掉**（会连带杀掉子进程）；起服/起 MCP server 要用**短命令**，确认后再做别的。同理，别把 `start-mcp.ps1` 的输出管道给 `Out-Null`——它启动的 node 会继承那个管道句柄，命令永远不返回。
3. **PowerShell 5.1 会把 UTF-8 当 GBK**：读文件用 `read` 工具；写文件不要用 `Set-Content`（会加 BOM 并可能改坏中文）。
4. 客户端注册文件的心跳每秒更新，但 `LastWriteTime` 不一定变——判断存活要读 `heartbeatAt`，不要看文件时间。
5. **玩家消息带 `<名字> ` 前缀**：不剥掉的话 `^鱼` 永远不命中（第一次真机测试就是这么失败的）。
6. `wait_events {timeoutSeconds:0}` 必须保持"一次立即调用"，否则用它轮询的脚本永远拿不到积压事件。
