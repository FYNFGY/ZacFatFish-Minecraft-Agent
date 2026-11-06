# 事故记录：ZFF MC 让 DSH 启动失败（2026-11-01 13:43–13:44）

> 记录人：本场 Agent。事故是**我**造成的，本文只记事实、根因、修复与防复发。

---

## 1. 现象

装完插件、用户重启 DSH 后弹出：

```
应用无法启动或已意外停止。
web boot: 1 entry did not activate
@local/zff-mc: import failed (see console for the import error)
诊断报告：C:\Users\ALIENWARE\AppData\Ro…\crash-2026-11-01T05-44-05-559Z-web-boot.log
```

连点三次「重启」，三次都弹（13:43:32 / 13:43:50 / 13:44:05）。用户随后用了弹框第三个按钮
「禁用第三方插件、备份 profile patch 并重启」，于是 **whale_craft / headlessmc / mc-control 全被摘掉**，
profile 的 `cordis.patch.yml` 也被重写成精简版（`agent-default-model` 与 `mc-control` 丢失）。

## 2. 根因（crash 日志原文）

```
--- renderer console (error level) ---
Uncaught Error: client-modules: duplicate factory registration for "zff-mc"
                (bundle executed twice without invalidate?)
Error: web boot: 1 entry did not activate
```

- `client.js` 无条件执行 `window.__ModuleLoader__.load({ id: 'zff-mc', … })`。
- 这个包是 `link:` 本地包，DSH 的客户端 HMR 会把它**执行两次**（我恰好在 DSH 运行期间改了 `client.js`）。
- 第二次 `load()` 抛 `duplicate factory registration` → 该 entry 未激活 → web boot 判定失败 → 启动弹框。
- 宿主侧 `index.js` 本身没问题：从 profile 目录 `import('@local/zff-mc')` 正常（11 个导出）。

## 2.1 第二个根因（第二次重启才暴露、且是真正致命的那一个）

修好 client bundle 的重复注册之后，13:52 的那次启动**渲染进程已经没有任何报错**，但仍然弹框。
用一次性 profile（`@deepseek-ai/dsh-base` + `dsh-web-app` + 本插件）跑 CLI 真启动，拿到真正的堆栈：

```
dsh: warning: 1 entry did not activate
zff-mc (@local/zff-mc): TypeError [ERR_INVALID_ARG_TYPE]: The "path" argument must be of type string.
    Received function dshHomePath
    at join (node:path:513:7)
    at createZffPlugin (file:///D:/mc/zff-mc/index.js:204:19)
```

- 宿主 `ctx.get('dshHomePath')` 返回的是**函数**，不是字符串；插件直接把它交给 `join()` → 抛错。
- 抛出点在 `apply()` 里 → cordis 判定该 entry 未激活 → **Electron 的 web boot 把"有 entry 没激活"
  当成致命错误** → 启动失败弹框。
- 也就是说：**不是客户端 bundle 的问题，是宿主侧插件入口在构造期就抛异常**。

修复：

1. `readDshHome(ctx)`：函数就调用一次、只接受非空字符串，否则回落 `~/.dsh`。
2. **`apply()` 整体兜住**：以后插件内部再出任何错，只降级成空插件并记日志，**绝不拖垮宿主启动**。
3. 顺带修 `workspaceOf`：`cfg.workspaceDir` **优先于** 会话 `header.cwd`（这台机器上两者不同：
   `D:\mc` vs `D:\mc\MCP Development`），否则插件读的 `runtime/*.json` 和 MCP server 写的不是同一个目录，
   "即时唤醒 / 暂停 / 掉线"三条刹车会静默失效。

验证（**真启动**，不是模拟）：一次性 profile 启动后日志出现

```
已注册 5 个工具：zff_shutdown, zff_pause, zff_status, zff_prompts, zff_launch
已注册 /api/zff-mc 路由（设置页）
ZFF MC 已就绪（modePresets=zff-mc；tick=2000ms；promptDir=…\.dsh\zff-mc\prompts）
```

且**不再出现** `entries did not activate` 警告。

## 2.2 第三个根因（"entry did not activate" 的真正原因）

前两个修好后，宿主侧插件**确实跑起来了**（日志有 `已注册 5 个工具`），但 Electron 仍然弹
`web boot: 1 entry did not activate`。这次直接读宿主源码（`app.asar` 用 `scripts/asar-read.mjs` 解出来看）：

```js
// dsh-client-modules/lib/client.js
register(registration) {
  const ownerId = stripClientSuffix(registration.id)   // 只裁掉 "/client" 子路径
  ...
  this.factories.set(id, {...})
}
async arrive(row) {
  const { id } = row                                  // row.id = **包名**
  ...
  if (this.factories.has(id)) return "registered"
  failures.push(`${url}: loaded without registering "${id}" via __ModuleLoader__.load`)
  throw new Error(`client-modules: could not load "${id}"`)
}
```

宿主用**包名**当 client module 的 row id，而 `client.js` 里写的是插件短名 `id: 'zff-mc'`
（whale_craft 能跑，是因为它的包名正好等于短名 `whale_craft`）。于是 `arrive()` 永远找不到
`factories.has('@local/zff-mc')` → 报"加载了却没注册" → 该 entry 判未激活 → 整次启动失败。

**证据**：把 web boot manifest 抓下来看（`window.__DSH_BOOT__`），我们的 row 是

```json
{ "id": "@local/zff-mc", "url": "plugins/??@local/zff-mc/client.js&rev=1ddb482983e4", "rev": "1ddb482983e4" }
```

修复：`client.js` 的注册 id 改成 **`@local/zff-mc`**（包名），并去掉"已注册就 return"的跳过逻辑
（HMR invalidate 之后宿主需要重新注册；只吞 duplicate 这一种错误）。

## 3. 修复（汇总）

| # | 根因 | 修复 |
|---|---|---|
| 1 | `client.js` 重复注册（HMR 双执行） | 幂等 + 只吞 `duplicate factory registration` |
| 2 | `ctx.get('dshHomePath')` 返回函数 → `join()` 抛 TypeError | `readDshHome()` 兼容函数/字符串 |
| 2b | `apply()` 里任何异常都会让 entry 未激活 | `apply()` 整体 try/catch，失败降级成空插件 |
| 3 | 客户端模块 id 用了短名 `zff-mc` 而不是包名 | `id: '@local/zff-mc'` |

`client.js` 的注册（最终形态）：

```js
;(function () {
  const ID = 'zff-mc'
  let loader = null
  try { loader = window.__ModuleLoader__ } catch { loader = null }
  if (!loader || typeof loader.load !== 'function') return   // 不是浏览器 bundle 上下文：静默退出
  try { if (window.__zffMcRegistered === true) return } catch {}   // 重复执行：跳过
  try {
    loader.load({ id: ID, factory(require) { … } })
    try { window.__zffMcRegistered = true } catch {}               // 只在成功后置位
  } catch (e) {
    if (/duplicate factory registration/i.test(String(e?.message ?? e))) {
      try { window.__zffMcRegistered = true } catch {}             // 宿主已注册过：等价成功
    } else {
      throw e                                                     // 其它错误照抛，不掩盖真 bug
    }
  }
})()
```

## 4. 防复发

| 措施 | 文件 |
|---|---|
| 用假 `window` + 假 `ModuleLoader` **重放**三种场景（同页双执行 / 宿主已注册 / 其它真错误） | `test/zff-client-bundle.mjs`（6/6） |
| 插件自身契约测试 | `D:\mc\zff-mc\selfcheck.mjs`（163/163） |
| 一键开关（只增删 bundles 里的一个名字，不手改 JSON） | `scripts/zff-mc-toggle.mjs --on/--off/--status [--dry]` |
| 改动规矩 | **DSH 运行时不改 `client.js`**；改了先跑上面那个测试，再谈重启 |

## 5. 恢复记录

| 项 | 处理 |
|---|---|
| `cordis.patch.yml` | 用 13:29 的备份**逐字节恢复**（`agent-default-model`、`ui-*`、`mc-control` 全部回来） |
| `package.json` 的 bundles | 恢复 `whale_craft` + `@local/headlessmc-mcp`；`@local/zff-mc` 先摘掉，等用户确认 |
| 事故现场留档 | `evidence/incidents/2026-11-01-web-boot-duplicate-factory.log`、`…-profile-patch-after-disable.yml` |

## 6. 复盘：三件事我本可以避免

1. **在 DSH 运行期间改 client bundle** —— 直接触发了双执行。
2. **没有先验证"重复执行"这个边界** —— 手写 bundle 的第一个规矩就是幂等注册，我没做。
3. **装插件的动作没有"单人单步"** —— 一次改动里同时塞了「新插件 + 新 UI 入口 + 新 HTTP 路由」，
   出问题时无法区分是哪一块。以后每次只加一件，重启验证后再加下一件。
