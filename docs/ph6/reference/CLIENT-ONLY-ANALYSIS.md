# 方案分析：只装客户端模组，靠「服务器 ↔ 客户端」通信做数据传递与同步

> 结论先说：**做得到，但要接受一个硬约束**——服务器上没有任何自定义代码时，客户端**不能**再发明一条私有信道；
> 所谓"服务器与客户端通信"只能走原版已有的东西（聊天、命令、世界内容），或者把同步挪到游戏之外（MCP 层）。
> 本文给出 4 个方案、评分、推荐路线和改动点。

---

## 1. 现状：服务端模组到底提供了什么

同一个 jar 装在服务端时，`ZffServer` 提供 5 件事（`ZffServer.java`）：

| # | 能力 | 客户端能否独立实现 | 备注 |
|---|---|---|---|
| 1 | 自定义 payload 通道（`mcpbridge:hello/request/response/stop`） | ❌ 不能 | 需要两端都注册；`registrar.optional()` 只是"对方没有就跳过"，不是"对方没有也能用" |
| 2 | `SavedData` 持久化 Memory/TodoList（`<world>/data/zff_agent.dat`） | ❌ 不能（落不到服务端） | 客户端只能存本地或交给 MCP 进程 |
| 3 | `/zff status|stop|reload|memory|todo` 命令树 | ⚠️ 部分可以 | 无权限的普通玩家用不了自定义命令；客户端可改用自己的聊天前缀 |
| 4 | 服务端 tick 采样在线玩家（`zff_status.players`） | ⚠️ 部分可以 | 客户端能读 tab 列表（名字/延迟），但拿不到别人精确坐标 |
| 5 | `broadcastStop()`（管理员 `/zff stop` 通知所有装了模组的客户端） | ✅ 可以替代 | 客户端监听聊天里的约定前缀即可 |

客户端本来就独立完成的部分（**不需要服务端**）：移动/挖掘/寻路、扫描、容器操作、聊天、命令、截图、job 调度、
**看门狗（`ZffWatchdog` 跑在客户端 tick）**、循环回环 HTTP bridge、以及全部 80+ 个游戏工具。

> 也就是说：**服务端模组只贡献了"共享存储 + 一条 RPC 通道 + 服务器管理命令"**，其余能力天然是客户端的。

### 1.1 当前在无模组服务器上的实际表现（代码事实）

- `ZffClient.loggingIn` 里 `sendToServer(Hello)` 被 `try/catch` 包住 → 发不出去只打一行日志，**不会崩、不会被踢**。
- 但 `Tools.zff(...)` → `ZffRemote.call(...)` 会**等满 12 秒**再抛 `timeout`。
  → 在纯原版服务器上，`zff_status` / `zff_memory_*` / `zff_todo_*` 表现为"卡 12 秒然后报错"，体验很差，**这是必须改的第一处**。

---

## 2. "服务器 ↔ 客户端通信"到底还能走哪些通道

| 通道 | 是否可行 | 容量/频率 | 服务器要不要改 | 主要问题 |
|---|---|---|---|---|
| 自定义 payload（现在用的） | ❌ | 大 | **要装模组** | 服务端没注册就收不到；这是硬约束 |
| 公共聊天 `/say` 式广播 | ✅ | 每条约 256 字符，受聊天限流 | 不用 | 所有人都看得见；会被聊天插件改写；限流 |
| 私聊 `/msg` | ✅ | 同上 | 不用 | 一对一，广播要给每个接收者各发一次；需要知道在线名单 |
| 命令 + 计分板/`/data storage` | ⚠️ | 整数/小 NBT | 不用（但需要 op 或 datapack） | 普通玩家没权限；`/trigger` 只能传整数 |
| 世界内容（告示牌/成书/箱子/物品展示框） | ✅ | 一本书约 1–2 KB | 不用 | 需要走到固定地点、有破坏性、延迟高 |
| **MCP 层中转（推荐）** | ✅ | 基本无限 | 不用 | 同步不在游戏里，多机需要共享存储/服务 |

> 关键判断：**"只装客户端"和"用游戏内私有信道"是互斥的**。
> 只要服务端不装东西，游戏内就只剩原版通道；要自由的数据同步，就必须把同步点放在游戏之外。

---

## 3. 四个方案

### 方案 A（推荐）：客户端本地回退 + MCP 层共享存储

```
[客户端 A]──HTTP──┐
[客户端 B]──HTTP──┼──► MCP server(57610) ──► 共享存储（文件/SQLite）
[客户端 C]──HTTP──┘         │
                        daemon(57611) ──► SR/CR/AR 子代理
```

- 服务端模组缺失时，`zff_*` **不超时**，直接落到本地/MCP 存储：
  - 单机：`<gameDir>/zff/agent.json`（客户端本地）
  - 多客户端：`<workspace>/.mcp-agent/agent-store.json`（或 SQLite），由 MCP server 统一读写
- 现有资产直接复用：`memory_*` 工具已经写 `<workspace>/.mcp-agent/`；`agent/todolist.mjs` 本来就有 `ZFF_BACKEND=file` 模式。
- 替换 `/zff stop`：客户端监听聊天前缀（例如 `!zff stop`），本地 `StopAll.run()`；同时 MCP 侧提供 `stop_all`。

**优点**：改动集中在客户端与 MCP 层；零服务端依赖；同步可靠（文件事务 + rev 号）。
**缺点**：数据不在世界存档里（换机器要拷文件）；多机需要共享目录或小服务。

**评分**：可行性 10/10、工作量 中、同步能力 9/10、对原版服务器友好 10/10。

### 方案 B：聊天总线（游戏内纯原版同步）

协议示例（分片 + 序号 + 校验）：

```
!zff1 <seq> <total> <base64(JSON 片段)>
```

- 客户端把 `memory/todo` 的增量写进聊天；其他装了模组的客户端解析、合并（last-write-wins + rev）。
- 私聊版：对每个在线 AI 发 `/msg <name> !zff1 ...`（需要维护在线名单，可从 tab 列表读）。

**优点**：完全不依赖服务端；跨机器天然可用。
**缺点**：**所有玩家都能看到**（公共聊天）或要逐人私聊；256 字符/条 + 限流 → 一次同步可能几十条；聊天插件（过滤/翻译/反 spam）容易破坏协议；观感差。
**评级**：可行性 8/10、工作量 中、同步能力 5/10、体验 2/10。**只建议做"紧急开关"（`!zff stop`）**，不建议承载数据。

### 方案 C：世界邮箱（成书/告示牌/箱子）

- 固定坐标放一个"信箱"（箱子 + 成书），客户端写/读 NBT。
- **优点**：真正落在服务端存档里，换客户端也在。
- **缺点**：必须走到地点；书页容量小、要分页；容易和玩家交互冲突；不适合高频同步。
**评级**：可行性 7/10、工作量 中、同步能力 3/10。适合"低频备份/留言"。

### 方案 D：数据包 + `/trigger`（服务器能装 datapack、但不能装 mod 时）

- 服务端放一个 datapack：定义 scoreboard 目标与函数，用 `/data storage zff:agent` 做 KV。
- 非 op 玩家用 `/trigger <obj> set <int>` 触发函数；读回只能读到整数（侧边栏/分数）。
- **优点**：不装 mod 也能有服务端持久化；比聊天干净。
- **缺点**：只能传整数/小 NBT；需要服务器主人同意装 datapack；实现复杂、调试痛苦。
**评级**：可行性 6/10（有条件）、工作量 高、同步能力 4/10。

### 评分对比

| 方案 | 服务端零改动 | 同步能力 | 实现成本 | 体验 | 综合 |
|---|---|---|---|---|---|
| **A 本地+MCP** | ✅ | ★★★★☆ | 中 | ★★★★★ | **推荐** |
| B 聊天总线 | ✅ | ★★☆☆☆ | 中 | ★☆☆☆☆ | 仅作紧急指令 |
| C 世界邮箱 | ✅ | ★☆☆☆☆ | 中 | ★★☆☆☆ | 可选备份 |
| D datapack+trigger | ❌（要装包） | ★★☆☆☆ | 高 | ★★★☆☆ | 条件方案 |

---

## 4. 推荐路线（分三期，可独立交付）

### 一期：让"只装客户端"不报错、能单机跑通（1–2 天）

1. 新增 `client/ZffLocalStore`：`<gameDir>/zff/agent.json`，接口与 `ZffSavedData` 对齐（memory/todo/rev/actorId）。
2. `Tools.zff(...)` 改为：
   - 先探测服务端是否装了模组（登录后 `hello` 成功 → `serverMod=true`；3 秒没有回应 → `false`）；
   - `true` → 走现在的 payload RPC；`false` → 直接读写本地 store，`zff_status` 返回 `{"backend":"local", ...}`。
3. 客户端聊天前缀：收到 `!zff stop` → `StopAll.run()` + `EventJournal` 记事件（替代服务端 `/zff stop`）。
4. `zff_*` 工具在任何情况下都不再等 12 秒（探测缓存，超时 1.5 秒）。
5. 测试：纯原版服务器 + 单客户端；模组服务器回归。

### 二期：多客户端共享（1 天）

1. MCP server 增加 `agent-store`（`<workspace>/.mcp-agent/agent-store.json`，带 `rev` 与原子写）。
2. 新增 MCP 工具 `store_get/store_set/store_list/store_watch`（或复用 `memory_*` + 新增 `todo_*`）。
3. `zff_*` 的本地模式改为优先写 MCP store（daemon 不可用时退回本地文件）。
4. 子代理（`ZFF_BACKEND=file`）直接共用这个 store，天然跨客户端。

### 三期（可选）：游戏内同步通道

- 只做 `!zff stop` + `!zff say` 这类**控制消息**（公共聊天，短、低频）。
- 数据同步仍走 MCP store；除非明确要求"必须只在游戏内"，再考虑方案 B/C。

### 迁移

- 现在服务端 `zff_agent.dat` 里已有记忆/待办：在服务端模组还在时调用 `zff_memory_list` / `zff_todo_list` 导出为 JSON，
  写进新 store（写一个 `scripts/migrate-zff-store.mjs`）。
- 之后服务端模组可以卸掉，客户端行为不变（`backend` 从 `server` 变 `local/mcp`）。

---

## 5. 具体改动点（文件级）

| 文件 | 改动 |
|---|---|
| `mod/.../client/ZffRemote.java` | 增加 `serverAvailable` 探测与 1.5 s 快速失败；不再固定等 12 s |
| `mod/.../client/ZffClient.java` | `hello` 成功/失败写 `BridgeConfig.serverMod`；新增聊天 `!zff` 前缀处理 |
| `mod/.../client/ZffLocalStore.java`（新） | 本地 JSON store（原子写、rev、加锁） |
| `mod/.../Tools.java` | `zff(...)` 增加 `local` 分支；`zff_status` 增加 `backend` 字段 |
| `mcp-server/src/server.js` | 新增 `store_*` 工具（或把 `zff_*` 路由到 store） |
| `mcp-server/src/agent/store.mjs`（新） | 共享 store（文件/SQLite），给 SR/CR/AR 共用 |
| `scripts/migrate-zff-store.mjs`（新） | `zff_agent.dat` → 新 store 的迁移脚本 |

---

## 6. 风险与边界

1. **服务端模组仍是最优解**：它提供的东西（服务端持久化、广播、管理命令）在纯客户端模式下要靠外部存储补，换机器要拷文件。
2. **公共聊天总线不可靠**：限流、插件改写、隐私；只适合控制指令。
3. **`/zff stop` 语义变化**：变成"客户端自己停"，不再是"服务器管理员一键停所有 AI"；多 AI 时每个客户端各自监听。
4. **本地 store 与存档不同步**：回档/换世界时记忆可能对不上，建议按 `serverId` 分目录（`zff/<serverId>/agent.json`）。
5. **服务端模组版本不一致**：`optional()` 已能避免被踢；但装了旧版模组的服务器会走 RPC 分支，需保留兼容与 `dist`/`protocol` 校验。
6. **安全**：本地 store 里不要放 token；`!zff` 前缀要防伪造（只在白名单玩家发言时生效）。

---

## 7. 验收清单

- [ ] 纯原版服务器（无模组）：客户端能进服；`get_state`/`walk`/`mine_block` 正常；`zff_status` **< 2 s** 返回 `backend=local`。
- [ ] 模组服务器：`zff_status` 返回 `backend=server`，读写仍走 SavedData；`/zff stop` 仍生效。
- [ ] 两个客户端 + 一个 MCP server：A 写记忆，B 立刻能读到（`zff_memory_get` / `store_get`）。
- [ ] 断网/掉线：`zff_*` 不卡死，本地 store 可用；恢复后自动切回 `server`。
- [ ] 迁移：现有 `zff_agent.dat` 的记忆/待办全部出现在新 store。

---

## 8. 工作量估算

| 阶段 | 内容 | 估时 |
|---|---|---|
| 一期 | 本地 store + 快速探测 + `!zff stop` + 测试 | 1–2 天 |
| 二期 | MCP 共享 store + 工具 + 子代理接入 | 1 天 |
| 三期（可选） | 聊天控制通道 | 0.5–1 天 |
| 迁移脚本 | 导出→导入 | 0.5 天 |
| **合计** | | **3–4.5 天** |

---

## 9. 一句话总结

> **只装客户端完全可行**，但"数据同步"不能指望游戏内私有信道——要么落在客户端本地，要么交给 MCP 层做共享存储；
> 游戏内的服务器↔客户端通信只能承担**低频控制消息**（如 `!zff stop`）。
> 推荐：**方案 A（本地回退 + MCP 共享 store）**，一期先解决"纯原版服务器不卡 12 秒"，二期再做跨客户端同步。

---

## 10. 附录：游戏内私有通道（自定义 payload）到底强在哪

### 10.1 和替代品的横向对比

| 维度 | 自定义 payload（现在） | 公共聊天 | 世界邮箱（成书/箱子） | MCP 层中转 |
|---|---|---|---|---|
| 额外端口 | **不开**（走 MC 连接） | 不开 | 不开 | 要 host 之间可达 |
| 身份认证 | **服务器连接本身**（`ServerPlayer`/UUID） | 无（谁都能打同样前缀） | 无 | 自己发 token |
| 双向结构化 | **请求-应答 + JSON** | 单向广播/文本 | 读-写文件式 | 请求-应答 |
| 单条容量 | KB 级（远大于聊天） | 256 字符 | 书页 1–2 KB | 无限制 |
| 频率限制 | **无**（受 tick 影响） | 有（反刷屏） | 无（但要走路） | 无 |
| 隐私 | **只有模组客户端可见** | 全服可见 | 打开箱子的人可见 | 仅 agent 侧 |
| 数据落点 | `SavedData` 跟随世界存档 | 无 | 世界内 | workspace 文件 |
| 服务端上下文 | **有**（服务端线程 + 世界数据） | 无 | 无 | 无 |
| 服务端是否要装东西 | **要** | 不用 | 不用 | 不用 |

### 10.2 六个具体好处（都能在本项目里指出来）

1. **不开新端口，不碰防火墙**：服务端不需要监听任何 TCP 端口，payload 走 Minecraft 自己那条已经打通的连接（Ph5 T4 的要求）。托管服/NAT 环境下这点很关键。
2. **身份直接由服务器保证**：handler 拿到的是 `IPayloadContext.player()`，就是那个已登录的 `ServerPlayer`；不用 token、不会被冒名。聊天前缀则**任何人都能打**，`!zff stop` 可被其他玩家伪造。
3. **真正的请求-应答**：`Request(id, op, arg)` → `context.reply(Response(id, ok, json))`，带 id 匹配、超时、错误码。聊天做不到"回答某一条请求"，世界邮箱要自己发明轮询。
4. **能读服务端状态**：`zff_status` 里的 `players[].stillMs` 是**服务端 tick 采样**出来的——这次"服务器到底有没有看到我移动"就是靠它证明的（客户端自己说动了不算数）。`SavedData` 的 memory/todo、在线玩家列表、服务器 TPS 也只有服务端能拿。
5. **可控广播**：`broadcastStop()` 只发给发过 `Hello` 的客户端（`KNOWN_CLIENTS`），原版玩家**完全不会被暴露**到这条通道；而聊天广播是给全服的。
6. **数据落在存档里**：`<world>/data/zff_agent.dat` 跟着世界走——服务器重启、备份、换世界、开新周目都在；聊天和 MCP 文件都不具备这个语义（MCP 文件换机器要拷，聊天根本不持久）。

### 10.3 代价（什么时候不值得）

- 服务端必须装 **同一个 jar** 并保持版本一致；托管服/整合包服可能不让装。
- 版本升级要两端同步（`registrar("1")` + `optional()` 只能保证"不匹配不踢"，不能保证"旧版能用"）。
- 单人/单机陪玩用不上跨客户端共享，本地 store 就够，装服务端是多余成本。

> 结论：**要"多客户端共享 + 服务端可管 + 服务端视角数据"，这条私有通道就是最优解**；
> 只是"客户端能玩"，那就用方案 A 的本地回退，别为它装服务端。

---

## 11. 名词解释：这四条通道分别是什么

### 11.1 私有通道 = NeoForge 自定义 payload（`mcpbridge:*`）

- **是什么**：在 Minecraft 自己的网络连接上多开的一组"自定义数据包频道"，和游戏原生的 `minecraft:brand`、`minecraft:register` 同级，
  但只有两端都注册了才存在。本项目有 4 个：`mcpbridge:hello / rpc_request / rpc_response / stop`。
- **怎么走**：
  ```
  客户端 PacketDistributor.sendToServer(Request)  →  服务端 playToServer handler
  服务端 context.reply(Response)                  →  客户端 playToClient handler
  ```
  注册在 `ZffServer.registerPayloads()`：`registrar("1").optional()` + `playToServer/playToClient`；
  序列化用 `StreamCodec`（`ZffPayloads`），消息体是 JSON 字符串（`{"ok":true,...}`）。
- **本项目的例子**：`zff_memory_set` → 客户端 `ZffRemote.call("memory.set", {...})` → 服务端 `ZffSavedData.memorySet` →
  `Response` 回到客户端 → MCP 工具返回 JSON。`/zff stop` 则是反向：服务端 `broadcastStop()` → 客户端 `StopAll.run()`。
- **优点**：不开端口、身份由连接保证、双向应答、能读服务端状态、只发给装了模组的客户端、数据落 `SavedData`。
- **缺点**：**两端都要装同一个 mod**；版本要匹配；托管服可能不让装。

### 11.2 公共聊天 = 原版聊天包（`ServerboundChatPacket` / `ClientboundPlayerChatPacket`）

- **是什么**：玩家在聊天框敲一行字，客户端发 `ServerboundChatPacket`，服务端校验后广播给所有玩家；模组通过
  `ClientChatReceivedEvent` 能看到每一行（本项目 `ChatLog` 就是这么记录 `chat_history` 的）。
- **怎么当通道用**：约定一个前缀（如 `!zff1 <seq> <total> <base64片段>`），把数据切成多条聊天发出去，
  装了模组的客户端解析、拼回、合并。`!zff stop` 就是最轻量的一例。
- **本项目的例子**：`chat` 工具发一句话；`!zff stop` 触发本地 `StopAll`。
- **优点**：**零服务端改动**，任何原版服务器都能用，跨机器天然可用。
- **缺点**：**全服可见**（隐私 0）；单条 256 字符；受服务端反刷屏限流（阈值由服务端/插件决定）；
  聊天插件会改写/过滤/翻译；任何人都能伪造同样前缀；没有"应答"概念。

### 11.3 世界邮箱 = 把数据写进世界里的方块/物品

- **是什么**：用一个固定地点当"信箱"，数据以游戏内容形式存在：
  - **成书**（`written_book` 的 `pages` / `book_and_quill`）——每页约 1–2 KB；
  - **告示牌**（方块实体里的 4 行文本）；
  - **箱子/木桶**（物品 + NBT）；
  - 高级一点：`/data storage`（需要 op/datapack）。
- **怎么走**：一个客户端走过去，把书放进箱子；另一个客户端走过去，开箱、读、写回。数据随世界存档走。
- **本项目的例子**：在仓库放一个"信箱"箱子，里面放一本成书；`container_open` / `container_read` 读物品。
  ⚠️ 注意：现在 `container_read` 对物品**组件只给预览**（例如潜影盒显示 `ItemContainerContents@hash`），
  要真正读出书页文本，得先扩展 NBT/组件读取。
- **优点**：**落在服务端存档里**，换客户端也在；不占聊天、不开端口。
- **缺点**：必须走到固定地点；容量小、要分页；有破坏性/和玩家交互冲突；延迟以"走过去"计。

### 11.4 MCP 中转 = 在游戏之外，由 MCP server/daemon 做共享存储

- **是什么**：不是游戏内的通道，而是 agent 主机上的 Node 进程：
  `mcpbridge`（客户端）→ 回环 HTTP → **daemon 57611** → **MCP server 57610** → 磁盘上的共享文件。
  同步发生在**游戏外面**，世界服务器完全不知道。
- **怎么走**：
  ```
  客户端 mod ──HTTP──► daemon(57611) ──► MCP server(57610) ──► <workspace>/.mcp-agent/*、runtime/agent/*.json
  ```
- **本项目的例子**：`memory_write` / `memory_read` 写读 `<workspace>/.mcp-agent/`；子代理的 TodoList 用
  `ZFF_BACKEND=file` 存 `runtime/agent/todolist.json`。
- **优点**：容量无限、结构化、可版本化（rev）、跨机器只要共享目录/服务；**不依赖游戏服务器**。
- **缺点**：数据不在存档里（换机器要拷）；⚠️ **当前实现是"单客户端"**——`mc_attach` 会把 store 钉在一个实例上，
  真要多客户端同时共享，需要扩展路由（每客户端一个 daemon 实例，或 server 层做 per-instance 分流）。

### 11.5 一图流

```
      ┌──────────── 游戏连接（只有两端都装 mod 才有私有通道）────────────┐
客户端 A ── mcpbridge:request/response ──► Minecraft 服务器（mod）──► SavedData
   │                                              ▲
   │ 公共聊天（256 字符，全服可见）                 │ 世界邮箱（成书/箱子）
   └────────────► 服务器广播 ◄────────────────────┘
   │
   └── 回环 HTTP ──► MCP server/daemon ──► workspace 文件（MCP 中转，游戏外）
```

---

## 12. 取消服务端的代价

> 前提：把服务端模组**彻底删掉**（不是"可以不装"）。如果只是"客户端不依赖服务端"，代价接近 0（见 12.5）。

### 12.1 直接消失的能力（不可完美替代）

| 能力 | 没了会怎样 | 替代 / 影响 |
|---|---|---|
| `SavedData`（`<world>/data/zff_agent.dat`） | 记忆/待办不再跟着世界走：换机器、换存档、回档都要手动搬 | 只能放 MCP 文件（`<workspace>/.mcp-agent/`）或客户端本地；**备份策略要改** |
| `/zff stop`（含**服务器控制台**执行） | 管理员在控制台一键停所有 AI 的能力消失 | 只能客户端侧 `!zff stop`；控制台无法触发（除非 `/say` 广播 + 客户端解析） |
| 服务端 tick 采样（`zff_status.players[].stillMs`） | **"服务器视角"没了** —— 无法再证明"服务器看到我移动"，掉线/回滚排查变猜 | 客户端只能看附近实体（跟踪距离内），拿不到远处玩家精确坐标 |
| `broadcastStop()` + `KNOWN_CLIENTS` 门控 | 没有"服务端→所有模组客户端"的控制面；某个客户端卡死时聊天指令也可能不生效 | 每个客户端各自监听；权威性下降 |
| 服务端启动诊断（`SERVER READY`、hello 日志） | 少一处排障线索 | 只能看客户端日志 |

> 注意：**看门狗不在此列**——`ZffWatchdog` 跑在客户端 tick；`/zff reload` 读的是客户端 `ZffConfig`。

### 12.2 工程代价

1. **一期（1–2 天）**：`ZffLocalStore` + 快速探测 + `!zff stop` + 让 `zff_*` 不再等 12 秒。
2. **二期（1 天，且更贵）**：MCP 层共享 store。**当前 MCP 工具面是"单角色"的**——`mc_attach` 把 store 钉在一个实例上，
   `get_state` 返回"那个玩家"。要真正支持"多个 AI 客户端同时在线并共享"，需要 per-instance 路由/多 daemon，
   这是结构性改动，不是加个 JSON 文件就行。
3. **迁移（0.5 天）**：把现有 `zff_agent.dat` 里的记忆/待办导出到新 store，否则等于清空。
4. **测试矩阵翻倍**：原版服务器 / 装模组服务器 / 单客户端 / 多客户端 / 重连 / 回档，每种都要回归。

### 12.3 运维代价

- 数据落点从"世界存档"变成"workspace 文件"：**备份、迁移、权限**都要另建流程；
- 出现"双份真相"的风险（有的客户端写本地、有的写 MCP），需要 `backend` 字段和优先级规则；
- 失去"服务器视角"这个调试金手指——这次"服务器到底有没有看到我移动"就是靠它定性的；
- 卸载服务端模组后，**已经写在 `zff_agent.dat` 里的数据仍留在存档里但没人读**，容易产生"记忆丢了"的错觉。

### 12.4 什么时候这个代价可以接受

- 只有**单人陪玩**，不需要跨客户端共享 → 本地 store 足够；
- 服务器主人**不允许装 mod**（托管服/公共服）→ 没有选择，只能客户端本地；
- 你愿意接受"数据不进存档、备份靠拷文件"。

### 12.5 最省的做法：不删服务端，只让它变成"可选增强"

保留 jar 里的服务端部分（`optional()` 已经做到"没装也不会踢人"），只把客户端改成**不依赖**它：

| 场景 | 行为 |
|---|---|
| 服务器装了模组 | `backend=server`，走 payload RPC + `SavedData`（现状，功能全满） |
| 服务器没装模组 | `backend=local`，1.5 秒内回退本地/MCP store，`!zff stop` 可用 |

这样**代价 ≈ 0**：既不冒"删掉服务端就没数据落点"的风险，又拿到"只装客户端也能玩"。
先做一期的本地回退与快速探测即可；二期再看是否真的需要多客户端共享。

