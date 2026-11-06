# Ph6 · M0.5 插件 API 技术验证（静态证据版）

> 目的：在写 `ZFF MC` 插件之前，确认 4 个插件 API 到底能不能用。
>
> **方法**：本机已装有一个**生产可用**的同类插件 `whale_craft@0.2.0-beta.3`
> （`C:\Users\ALIENWARE\.dsh\profiles\desktop\node_modules\whale_craft\`），它已经在真机上跑过
> 聊天注入 / 模式判定 / 工具注册 / 设置页。**它的源码就是活证据**——下面每条都给出 `文件:行` + 关键代码。
>
> 本机 `@deepseek-ai/*` 宿主包**只存在于 `app.asar` 归档里**，读不到 `.d.ts`；所以：
> **凡是 whale_craft 里找不到的用法，一律写"未找到证据"，不编造。**
>
> 约束：本次验证**不重启 DSH、不动 whale_craft、不连外网服务器**，纯静态阅读。

---

## 一句话结论

| # | 能力 | 结论 |
|---|---|---|
| 1 | 监听 turn-end（一轮结束） | **不能（无专用钩子）** → 用 `agent.status` 轮询 + `agent.steer()` 空闲起轮替代 |
| 2 | 以"用户消息"形式注入续跑文本 | **能**（`agent.steer(message)`；消息形状 `{role,content,source,id}`） |
| 3 | 读取当前 preset / 模式 | **能**（`agentPresets.composedPreset(agent.ctx)`） |
| 4 | 注册插件级工具 + 设置页 | **能**（`ctx.tools.register` + `ctx.webServer.register` + `client.js` 插槽） |

3 条"能"、1 条"不能"（turn-end 没有钩子，但有两条已验证的替代路径）。
**M4 可以开工；续跑绝不能按"有 turn-end 钩子"来设计。**

---

## 1. 能否监听 turn-end（CR 输出停止 / 一轮结束）？

**结论：不能。宿主没有暴露（至少 whale_craft 从未用到）任何 `turn-end` / `turn/end` /
`session-end` 之类的事件；插件拿到的只有"每个 step 之前"的瀑布，和 `agent.status` 这个字段。**

证据（whale_craft 全仓库 `grep turn-end|turnEnd|turn_end|onTurnEnd` → **0 命中**）。
它一共只注册了 4 个宿主事件（`index.js:4570`、`4575`、`4614`）：

```js
// index.js:4570-4571
for (const ev of ['agent/created', 'agent/session-start']) {
  try { handlers.push(ctx.on(ev, ({ agent } = {}) => touch(agent))) } catch { /* 宿主没有这个事件就跳过 */ }
}
// index.js:4575  —— 两个位置参数，不是对象
handlers.push(ctx.on('agent-preset/selected', (sessionId, presetId) => { … }))
// index.js:4614  —— 每 step 之前（请求组装前），不是一轮结束之后
handlers.push(ctx.on('agent/pre-step', ({ agent } = {}, next) => { const inner = next(); … }))
```

`agent/pre-step` 是**瀑布（waterfall）**，契约是"必须 `next()` 交棒"，否则否决整条链
（`selfcheck.mjs:2058-2063` 记着这次 P0 事故）：

```js
// selfcheck.mjs:2064-2069（宿主 waterfall 事件名单，whale_craft 用它做契约自检）
const HOST_WATERFALL_EVENTS = [
  'agent/pre-step', 'agent/request', 'agent/request-error', 'approval/request',
  'fs/edit-intent', 'fs/write-intent', 'llm/stream', 'session-telemetry/record',
  'system-prompt/assemble', 'tools/execute', 'tools/post-execute', 'tools/pre-execute',
  'tools/ptc-dispatch-log', 'user-questions/request',
]
```

"一轮是否结束"在 whale_craft 里是**自己猜的**——读 `agent.status`：

```js
// src/watchdog.mjs:595
const running = this.agent?.status === 'running'
// index.js:776-783（同一个判据的另一种用法）
const known = agent?.status !== undefined
if (known && agent.status !== 'running') return true
```

**备选方案（推荐，且已经真机验证）**：`agent.steer(message)` 的宿主文档写着
*"An idle driver starts a turn"*——空闲会**直接起一轮**。所以不需要"turn-end 钩子"，
只需要 `setInterval` 轮询 `agent.status`：从 `running` 落到非 `running` 的那一刻就算"本轮结束"，
然后 `steer` 注入续跑文案（见第 2 条）。whale_craft 的看门狗就是这么做的，1 s 一跳
（`src/watchdog.mjs:283`，`tickTimer.unref()`）。

> ⚠️ 不要用 `system-prompt/assemble` 猜 turn-end：它名字出现在上面那份名单里（说明宿主有这个
> 瀑布事件），但**载荷形状在 whale_craft 里没有任何使用样例** → 本仓库按"未找到证据"处理，
> ZFF MC 不挂它。

---

## 2. 能否以「用户消息」形式注入续跑文本？

**结论：能，而且有四条通道，可靠性递减。首选 `agent.steer(message)`（空闲起轮、运行中下一步插话）；
需要"本步立刻生效"时改写 `agent/pre-step` 的 `decision.messages`。**

消息对象形状（`role/content/source/id`，逐字段对齐宿主 `createUserMessage`）：

```js
// src/user-message.mjs:39-44（自带等价实现，字段与宿主一致）
export const builtinUserMessage = (input) => ({
  role: 'user',
  content: Array.isArray(input?.content) ? input.content : [],
  source: input?.source ?? { kind: 'plugin:whale_craft' },
  id: input?.id ?? newMessageId(),
})
```

`source.kind` 的**规范值**（v4 会话格式会拒绝裸露的 `'plugin'`）：

```js
// src/user-message.mjs:19-23
// 🔴 2026-10-04：`source.kind` 必须是 v4 规范值 `plugin:whale_craft`——
//    v4 会话格式**拒绝**裸露的 `kind:'plugin'`（"format v4 message requires a producer-owned
//    source kind"），而且宿主 `createUserMessage` 对传进来的 source **原样透传**、不会帮我们修正。
//    规范形态 = `{ kind: 'plugin:<插件名>', form, summary }`（`plugin` 字段随之去掉）。
```

实际投递处（`form:'notice'` → 宿主渲染成折叠的提示行，不冒充用户发言）：

```js
// src/watchdog.mjs:628-636
const message = userMessage({
  content: [{ type: 'text', text }],
  source: { kind: 'plugin:whale_craft', form: 'notice', summary: `MC 看门狗：${kind}`.slice(0, 120) },
})
agent.steer(message)
```

四条通道（都给了证据）：

| 通道 | 代码位置 | 语义 |
|---|---|---|
| `agent.steer(message)` | `src/watchdog.mjs:626-638` | 空闲**起一轮**、运行中下一步插话（首选） |
| 改写 `decision.messages` | `index.js:3540-3556` + `4614-4626` | 本步消息最前面插一条，与用户那句同批送出 |
| `inbox.append('next-step', msg)` | `index.js:3360-3363` | 入待投递队列（**晚一步**，见 `index.js:3496-3505`） |
| `sessionController.prompt(req, signal)` | `src/watchdog.mjs:649-661` | 兜底；**是用户来源消息**，会冒充用户说话 |

```js
// src/watchdog.mjs:656-661（兜底签名：@Remote 方法，必须传第二个 signal 参数）
const res = sc.prompt({
  requestId: `mc-wake-${Date.now()}-…`,
  sessionId: this.agent?.id ?? this.sess.agentId,
  mode: 'steer',
  content: [{ type: 'text', text }],
}, this.promptSignal)
```

```js
// index.js:3549-3550（本步消息改写：排在用户那句前面）
const msgs = (decision.messages ?? []).filter((m) => !isOurNotice(m))
const out = { ...decision, messages: [...noticeMessagesFor(r.todo), ...msgs] }
```

> ⚠️ 注意 `sessionController.prompt` 的 `mode:'queue'` 走的是 `agent.followup()`，
> 宿主文档说它"becomes the **sole ordinary message** of its own turn"——
> 即**真的往对话里插一条用户消息**（`src/watchdog.mjs:608-612`）。要"看起来像用户说的"用它，
> 要"插件提示行不冒充用户"就用 `steer` + `kind:'plugin:<name>'`。

---

## 3. 能否读取当前 preset / 模式？

**结论：能。`agentPresets.composedPreset(agent.ctx)` 返回 preset id 字符串；
"是不是 MC 模式" = 这个 id **在插件配置的 `mcModePresets` 名单里**。**

```js
// index.js:4139-4151
const mcPresetIdOf = (agent) => {
  if (!agent?.ctx) return null
  try {
    let svc = agentPresetsSvc
    if (!svc) { try { svc = agent.ctx.get('agentPresets') } catch { svc = null } }
    if (!svc) { try { svc = ctx.get('agentPresets') } catch { svc = null } }
    const id = svc?.composedPreset?.(agent.ctx)
    if (typeof id === 'string' && id) { lastPresetSeen.set(agent, id); return id }
    return null
  } catch { return null }
}
const isMcModeAgent = (agent) => pluginConfig.isMcModePreset(mcPresetIdOf(agent))
```

名单（默认值）与判定：

```js
// src/config.mjs:69
mcModePresets: ['minecraft', 'minecraft-plus', 'whale_craft'],
// src/config.mjs:722-731
isMcModePreset (presetId) { … return this.mcModePresets.includes(String(presetId)) },
isMcPlusPreset (presetId) { … return this.mcModePresets.includes(id) && this.mcPlusPresets.includes(id) },
```

preset 与 `presets/*.patch.yml` 的关系：一个 preset 就是**一条 `@deepseek-ai/dsh-agent-preset`
插件行**，随包的 `dsh.bundle.patch` 数组叠进 profile；`config.id` 就是 `composedPreset()` 的返回值：

```yaml
# presets/minecraft.patch.yml:17-29
- insert:
    - id: preset-minecraft
      name: '@deepseek-ai/dsh-agent-preset'
      config:
        id: minecraft
        name: MC模式
        description: DSH加入Minecraft Java版服务器，像玩家一样游玩。…
        order: 5
        plugins:
          - id: persona
            name: '@deepseek-ai/dsh-persona'
            config:
              prefix: '你是一个 Minecraft Java 版游戏助理，由模型 {{model}} 驱动。…'
```

```jsonc
// package.json:72-79（bundle 声明：patch 是**数组**，几个 preset 文件各占一项）
"dsh": { "bundle": { "patch": ["./cordis.patch.yml", "./presets/minecraft.patch.yml", "./presets/minecraft-plus.patch.yml"] },
         "client": { "platform": "web" } }
```

另外宿主会在**模式被选上/切换**时发 `agent-preset/selected`，参数是**两个位置参数**
`(sessionId, presetId)`（`index.js:4575-4582`）；服务本身可能晚就绪，所以 whale_craft
用 `ctx.inject(['agentPresets'], scope => …)` 等它就位（`index.js:3970-3972`），
再在判据里 `agent.ctx.get('agentPresets')` 现场兜一次（上面的 `mcPresetIdOf`）。

> ⚠️ 坑：`agentPresets.list()` 是 **async**，不要在同步 `for…of` 里迭代（`index.js:3819-3827` 记着
> 这个真机 bug）。要枚举 preset 根目录用**同步 getter** `agentPresets.roots`（`index.js:3834`）。

---

## 4. 能否注册插件级工具与设置页？

**结论：都能。工具走 `ctx.tools.register(...)`（需要 `export const inject = ['tools', …]`）；
设置页 = `package.json` 的 `dsh.client.platform:"web"` + `client.js` 工厂 bundle 往插槽注册组件，
数据面走 `ctx.webServer.register({kind:'prefix', path:'/api/…'})`。**

### 4.1 工具注册

```js
// index.js:59-60
export const name = 'whale_craft'
export const inject = ['webServer', 'tools']
```

```js
// index.js:1853-1860（统一入口：套 defineTool）
const asTool = (spec) => defineTool({
  ...spec,
  async execute(args, exec) { … return lossless(await spec.execute(args, exec)) },
})
// index.js:1885
ctx.tools.register(asTool({ … }))
```

工具的完整 option 形状（宿主 `defineTool` 与内置等价实现的**共同契约**）：

```js
// src/tool-def.mjs:42
// 两条路产出的注册形状一致：`{name, description, parameters, output:{schema, render}, [timeoutMs], execute}`
// src/tool-def.mjs:275-292（内置实现把 parameters 编译成 JSON Schema，并在 execute 前校验）
const parameters = parameterSchemaSpecToJsonSchema(options.parameters)
const outputSchema = valueSchemaSpecToJsonSchema(options.output?.schema)
const tool = { name, description, parameters, output: { schema: outputSchema, render }, async execute(args, exec) {…} }
```

`parameters` **不是**裸 JSON Schema，而是"属性名 → 值节点"的简写（属性上写 `required:true`
会被收进父级 `required`）：

```js
// index.js:4653-4657
parameters: {
  action: { type: 'string', description: 'get（默认）/ set / unset / reset / list' },
  path: { type: 'string', description: '配置项点号路径，如 commandWhitelist 或 mcMode.allowOtherTools' },
  value: { type: 'json', description: 'set 用的值（数组 / 字符串 / 布尔 / 对象）' },
},
```

**用哪个库**：入参 schema **不用库**——就是上面这种 spec 对象，由 `defineTool` 编译并校验；
只有**插件配置** `Config` 用 `@deepseek-ai/schemastery`，而且**拿不到就不导出 `Config`**：

```js
// index.js:134-135, 151-154
let z = null
try { z = (await import('@deepseek-ai/schemastery')).default ?? null } catch { /* 宿主没喂 ⇒ 不要 Config */ }
export const Config = z ? z.object({ mentions: z.array(z.string()).default(DEFAULT_MENTIONS) }) : undefined
```

另外两个工具面 API：

```js
// index.js:4401  —— 全局守卫：命中就硬拒（即使可见性限制失效也调不动）
ctx.tools.guard((exec) => { … })
// index.js:4257 / 4190-4199  —— 按会话收窄可见面，返回 disposer（"黏"的，切模式要手动撤销）
mcRestrictRelease.set(agent, t.restrict({ deny }))
```

> ⚠️ `restrict()` 对**不存在的工具名会抛错**（`index.js:4321` 的注释：
> "宿主 index.ts:1078 拿 restrictableNames 校验"）。所以写白名单前先确认名字真的注册了。

### 4.2 设置页 / 客户端 bundle

声明（三处缺一不可）：

```jsonc
// package.json:28-35   —— 客户端半端的入口是 exports["./client"]
"exports": { ".": "./index.js", "./client": "./client.js", "./locale/zh.json": "./locale/zh.json", … },
// package.json:80-83   —— 平台声明
"dsh": { "client": { "platform": "web" } }
```

```js
// client.js:45-47, 2836-2838  —— 手写 factory bundle，**不需要构建**
window.__ModuleLoader__.load({
  id: 'whale_craft',
  factory(require) {
    const React = require('react')
    …
    return { inject: ['slots'], apply(ctx) { ctx.slots.inject('…', () => ctx.slots.register({ name, id, order }, Component)) } }
  },
})
```

设置页的"数据面"（浏览器里没有 fs，必须宿主开 HTTP 路由）：

```js
// index.js:1668-1683
const apiRoute = {
  kind: 'prefix',
  path: '/api/mc',
  handler: async (req, res) => { if (!isTrustedRequest(req.headers, liveTrustedHosts())) { … 403 … } ; await handleMcApi(req, res) },
}
ctx.effect(() => ctx.webServer.register(apiRoute), 'whale_craft: /api/mc 路由')
```

locale 只是插件卡片上的标题/简介（`locale/zh.json:1-6`）：

```json
{ "meta": { "title": "鲸鱼工艺", "description": "让 DSH 加入 Minecraft 服务器游玩：…" } }
```

---

## 5. 结论表

| 能力 | 结论 | 证据 | 备选 |
|---|---|---|---|
| 监听 turn-end（一轮结束） | **不能**（无专用钩子；未找到证据） | 全仓库 `turn-end` 0 命中；只注册 `agent/created`/`agent/session-start`/`agent-preset/selected`/`agent/pre-step`（`index.js:4570,4575,4614`）；"是否在跑"读 `agent.status`（`src/watchdog.mjs:595`、`index.js:776-783`） | ① `setInterval` 轮询 `agent.status`，`running→非running` 即"本轮结束"；② `agent.steer()` 空闲**直接起一轮**（`src/watchdog.mjs:606-638`）；③ 宿主定时提醒（非插件 API） |
| 注入"用户消息" | **能** | 消息形状 `{role,content,source,id}`（`src/user-message.mjs:39-44`）；`source.kind` 必须是 `plugin:<name>`（同文件 `19-23`）；`agent.steer(message)`（`src/watchdog.mjs:626-638`）；改写 `decision.messages`（`index.js:3540-3556`） | 兜底 `sessionController.prompt({…}, signal)`（`src/watchdog.mjs:649-661`）——但是**用户来源**消息 |
| 读当前 preset / 模式 | **能** | `agentPresets.composedPreset(agent.ctx)`（`index.js:4139-4149`）；名单 `mcModePresets`（`src/config.mjs:69,722-731`）；preset 声明（`presets/minecraft.patch.yml:17-29`）；切换事件 `agent-preset/selected(sessionId, presetId)`（`index.js:4575`） | 若服务晚就绪：`ctx.inject(['agentPresets'], …)`（`index.js:3970`）+ `agent.ctx.get('agentPresets')` 现场兜底 |
| 注册插件级工具 | **能** | `inject = ['tools','webServer']`（`index.js:60`）；`ctx.tools.register(defineTool({…}))`（`index.js:1885`）；option 形状 `{name,description,parameters,output:{schema,render},timeoutMs,execute}`（`src/tool-def.mjs:42,278-292`）；`ctx.tools.guard`（`index.js:4401`）、`ctx.tools.restrict`（`index.js:4257`） | 入参 schema 不用第三方库（spec 对象）；插件配置用 `schemastery`，拿不到就不导出 `Config` |
| 设置页 / 客户端 bundle | **能** | `exports["./client"]` + `dsh.client.platform:"web"`（`package.json:28-35,80-83`）；`window.__ModuleLoader__.load({id,factory})` 返回 `{inject:['slots'],apply(ctx)}`（`client.js:45-47,2836-2838`）；插槽 `ctx.slots.inject/register`（`client.js:2878-2881`）；数据面 `ctx.webServer.register({kind:'prefix',path,handler})`（`index.js:1668-1683`） | 设置页也可只用"工具 + 文件"实现（无 UI），但不满足 M4 验收 |

---

## 6. 对 M4（ZFF MC 插件）的直接影响

1. **续跑不要等 turn-end 钩子**：用 `setInterval(2 s)` 看 `agent.status`，`running→idle` 即投递；
   注入用 `agent.steer(plugin 消息)`，失败再退 `decision.messages` 改写（`agent/pre-step`）。
2. **模式判定照抄 `composedPreset` + 名单**：ZFF MC Mode 的 `config.id` 就叫 `zff-mc`，
   插件配置 `modePresets: ['zff-mc']`。
3. **三道注入都要带 `source.kind = 'plugin:zff-mc'`**，否则 v4 会话格式拒收（真机踩过）。
4. **工具注册不需要任何宿主包**：自己造 `{name,description,parameters,output,execute}` 即可（`selfcheck` 可离线跑）。
5. **设置页必须有宿主 HTTP 路由**：`ctx.webServer.register({kind:'prefix'})` + `export const inject` 里带上 `webServer`。
6. **未知项（未找到证据，必须在真机验证）**：
   - `agent.status` 在"等待用户输入"时的**精确取值**（只确认了 `'running'` 这一个值）；
   - `agent/pre-step` 的 `decision` 里除 `kind`/`messages` 外还有哪些字段（只用到这两个）；
   - `ctx.slots.register` 的 `order` 语义（只确认"小的在左/前"）；
   - `system-prompt/assemble` 的载荷形状（**完全未找到证据**，ZFF MC 不挂它）。
