/**
 * ZFF MC —— 浏览器半端（client bundle，**手写 factory，无需构建**）
 * ============================================================================
 * 形状照 whale_craft/client.js（生产可用证据）：
 *   `window.__ModuleLoader__.load({ id, factory(require) { … return { inject:['slots'], apply(ctx) { … } } } })`
 * 声明侧在 package.json：`exports["./client"]` + `dsh.client.platform = "web"`。
 *
 * 这里只做**一件事**：插件详情页上的「提示词设置」按钮 + 一个模态框（三张卡片 A/B/C）：
 *   预览默认值 · 在线编辑（textarea）· 上传 `.md` · 恢复默认
 * 数据面走宿主 HTTP 路由 `/api/zff-mc/prompts`（index.js 里 `ctx.webServer.register`）。
 *
 * 🔴 客户端**没有文件系统**：所有读写都必须回到插件（Node 侧）——本文件只有 fetch。
 * 🔴 文案规矩：UI 里只写玩家需要的信息，不写实现细节（token / 接口 / 凭据库之类一个都别出现）。
 * 🔴 容错：宿主没给 `slots`、`/api/zff-mc` 404、后端没起 —— 一律**静默降级**（不抛、不白屏）。
 *
 * 🔴🔴 2026-11-01 真机事故（别再犯）：这个文件被宿主**执行了两次**（HMR 重新执行同一个 bundle），
 *   第二次 `__ModuleLoader__.load({id:'zff-mc'})` 抛
 *     `client-modules: duplicate factory registration for "zff-mc" (bundle executed twice without invalidate?)`
 *   → 变成 web boot 的致命错误，DSH 弹「应用无法启动或已意外停止」。
 *   所以注册必须**幂等**：同一个页面只注册一次，重复执行直接 return；万一宿主仍然抛 duplicate，
 *   也只吞这一种错误，绝不把首页带崩。
 * ============================================================================
 */
;(function () {
  // 🔴🔴 2026-11-01 真机事故（第 3 个根因，也是"entry did not activate"的真正原因）：
  //   `client-modules` 用**包名**当 graph row id（见 dsh-client-modules/lib/index.js:
  //   "The manifest package name identifies the browser module"；`stripClientSuffix` 只裁掉
  //   `/client` 子路径）。注册 id 与包名不一致时，宿主 `arrive()` 永远找不到
  //   `factories.has('<包名>')` → 报 "loaded without registering …" → 该 entry 判未激活 →
  //   Electron 的 web boot 直接把整次启动判失败。whale_craft 能跑，正是因为它的 id == 包名。
  //   所以这里**必须**写死包名，不能写插件短名 'zff-mc'。
  const ID = '@local/zff-mc'
  // 只在**浏览器 bundle 上下文**里做事；没有 loader（Node 里被误加载 / 预加载上下文）直接静默退出。
  let loader = null
  try { loader = window.__ModuleLoader__ } catch { loader = null }
  if (!loader || typeof loader.load !== 'function') return

  try {
    // 不跳过重复执行：HMR invalidate 之后宿主需要**重新注册**才能激活新版本；
    // 只有"未 invalidate 却执行两次"这种宿主边界才会抛 duplicate，那一种吞掉即可。
    loader.load({
      id: ID,
      factory(require) {
    const React = require('react')
    const h = React.createElement

    const API = '/api/zff-mc'
    const CARD_TITLES = {
      tools: { part: 'A', title: '工具使用', hint: '怎么调工具、job 模型、容器/寻路/聊天规则' },
      rules: { part: 'B', title: '游戏规则', hint: '玩家定的硬约束（清空则本段不注入）' },
      persona: { part: 'C', title: '角色', hint: '我是谁、怎么说话' },
    }

    const styles = {
      overlay: {
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,.45)', zIndex: 99999,
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
      },
      panel: {
        width: 'min(920px, 96vw)', maxHeight: '88vh', overflow: 'auto', borderRadius: 12,
        background: 'var(--dsh-bg, #1e1f24)', color: 'var(--dsh-fg, #e6e6e6)',
        boxShadow: '0 12px 48px rgba(0,0,0,.45)', padding: 20, font: '13px/1.6 system-ui, sans-serif',
      },
      head: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 },
      card: { border: '1px solid rgba(255,255,255,.12)', borderRadius: 10, padding: 12, marginBottom: 14 },
      row: { display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 8 },
      badge: { fontSize: 11, padding: '1px 8px', borderRadius: 999, border: '1px solid rgba(255,255,255,.25)', opacity: .85 },
      btn: { padding: '4px 10px', borderRadius: 6, border: '1px solid rgba(255,255,255,.25)', background: 'transparent', color: 'inherit', cursor: 'pointer' },
      btnPrimary: { padding: '4px 10px', borderRadius: 6, border: '1px solid #4c8dff', background: '#4c8dff', color: '#fff', cursor: 'pointer' },
      ta: { width: '100%', minHeight: 180, resize: 'vertical', borderRadius: 8, padding: 8, boxSizing: 'border-box', font: '12px/1.6 ui-monospace, Consolas, monospace', background: 'rgba(0,0,0,.25)', color: 'inherit', border: '1px solid rgba(255,255,255,.15)' },
      pre: { whiteSpace: 'pre-wrap', maxHeight: 220, overflow: 'auto', background: 'rgba(0,0,0,.25)', borderRadius: 8, padding: 8, font: '12px/1.6 ui-monospace, Consolas, monospace', margin: 0 },
      muted: { opacity: .65, fontSize: 12 },
      err: { color: '#ff7b72', fontSize: 12, whiteSpace: 'pre-wrap' },
      ok: { color: '#7ee787', fontSize: 12 },
    }

    const jsonFetch = async (url, init) => {
      const res = await fetch(url, { headers: { accept: 'application/json', ...(init?.body ? { 'content-type': 'application/json' } : {}) }, ...init })
      const text = await res.text()
      try { return text ? JSON.parse(text) : {} } catch { return { ok: false, error: `响应不是 JSON（HTTP ${res.status}）` } }
    }

    /** 一张卡片：预览默认值 / 编辑 / 上传 / 恢复默认 */
    function PromptCard(props) {
      const { slot, data, onSaved } = props
      const meta = CARD_TITLES[slot] ?? { part: '?', title: slot, hint: '' }
      const [text, setText] = React.useState(data?.userText || data?.defaultText || '')
      const [showDefault, setShowDefault] = React.useState(false)
      const [busy, setBusy] = React.useState(false)
      const [msg, setMsg] = React.useState(null)      // {kind:'ok'|'err', text}
      const fileRef = React.useRef(null)

      // M1-2：没有自定义时输入框**预填默认文本**（可编辑、可全选复制），而不是空白。
      React.useEffect(() => { setText(data?.userText || data?.defaultText || '') }, [data?.userText, data?.defaultText])

      const save = async () => {
        setBusy(true); setMsg(null)
        try {
          const j = await jsonFetch(`${API}/prompts`, { method: 'PUT', body: JSON.stringify({ slot, text }) })
          if (j?.ok) { setMsg({ kind: 'ok', text: `已保存（${j.bytes} 字节）· 下一个会话生效` }); onSaved?.() } else setMsg({ kind: 'err', text: String(j?.error ?? '保存失败') })
        } catch (e) { setMsg({ kind: 'err', text: `保存失败：${e?.message ?? e}` }) } finally { setBusy(false) }
      }
      const reset = async () => {
        // M1-2：只把输入框恢复成默认文本（还没保存，可再编辑/撤销）；真正删除自定义用下面的小按钮。
        setMsg(null)
        setText(String(data?.defaultText ?? ''))
        setMsg({ kind: 'ok', text: '已填入默认文本（还没保存；点「保存」才会覆盖）' })
      }
      const removeUser = async () => {
        setBusy(true); setMsg(null)
        try {
          const j = await jsonFetch(`${API}/prompts?slot=${encodeURIComponent(slot)}`, { method: 'DELETE' })
          if (j?.ok) { setText(String(data?.defaultText ?? '')); setMsg({ kind: 'ok', text: '已删除自定义文件，回落到包内默认' }); onSaved?.() } else setMsg({ kind: 'err', text: String(j?.error ?? '删除失败') })
        } catch (e) { setMsg({ kind: 'err', text: `删除失败：${e?.message ?? e}` }) } finally { setBusy(false) }
      }
      const onFile = (ev) => {
        const f = ev?.target?.files?.[0]
        if (!f) return
        if (f.size > 64 * 1024) { setMsg({ kind: 'err', text: `文件 ${f.size} 字节，超过 64 KB 上限` }); return }
        const reader = new FileReader()
        reader.onload = () => { setText(String(reader.result ?? '')); setMsg({ kind: 'ok', text: `已载入 ${f.name}（还没保存）` }) }
        reader.onerror = () => setMsg({ kind: 'err', text: '读取文件失败' })
        reader.readAsText(f, 'utf-8')
      }

      const source = data?.source === 'user' ? '自定义' : (data?.source === 'default' ? '默认' : '缺失')
      return h('div', { style: styles.card, 'data-zff-card': slot },
        h('div', { style: styles.row },
          h('strong', null, `${meta.part} · ${meta.title}`),
          h('span', { style: styles.badge }, `生效来源：${source}`),
          data?.truncated ? h('span', { style: { ...styles.badge, color: '#ff7b72' } }, '超过 64 KB 已截断') : null,
          h('span', { style: styles.muted }, meta.hint),
        ),
        h('textarea', {
          style: styles.ta, value: text, spellCheck: false, placeholder: '预填默认文本，可直接编辑 / 全选复制；保存后覆盖默认值',
          onChange: (e) => setText(e.target.value), 'data-zff-editor': slot,
        }),
        h('div', { style: { ...styles.row, marginTop: 8 } },
          h('button', { style: styles.btnPrimary, disabled: busy, onClick: save, 'data-zff-save': slot }, '保存'),
          h('button', { style: styles.btn, disabled: busy, onClick: () => fileRef.current?.click() }, '上传 .md'),
          h('input', { ref: fileRef, type: 'file', accept: '.md,text/markdown,text/plain', style: { display: 'none' }, onChange: onFile }),
          h('button', { style: styles.btn, disabled: busy, onClick: () => setShowDefault((v) => !v) }, showDefault ? '收起默认值' : '预览默认值'),
          h('button', { style: styles.btn, disabled: busy, onClick: reset, 'data-zff-reset': slot }, '恢复默认'),
          data?.source === 'user'
            ? h('button', { style: { ...styles.btn, opacity: .8 }, disabled: busy, onClick: removeUser }, '删除自定义')
            : null,
          msg ? h('span', { style: msg.kind === 'ok' ? styles.ok : styles.err }, msg.text) : null,
        ),
        showDefault ? h('pre', { style: { ...styles.pre, marginTop: 8 } }, String(data?.defaultText ?? '（包内没有这份默认值）')) : null,
      )
    }

    /** 单行输入 */
    const inputStyle = {
      width: '100%', boxSizing: 'border-box', borderRadius: 6, padding: '4px 8px',
      background: 'rgba(0,0,0,.25)', color: 'inherit', border: '1px solid rgba(255,255,255,.15)',
      font: '12px/1.6 ui-monospace, Consolas, monospace',
    }
    const labelStyle = { display: 'block', fontSize: 12, opacity: .75, marginBottom: 2 }

    /** 字段定义：设置页每张表就是这么渲染的（加字段只改这里） */
    const SETTING_FIELDS = {
      connection: [
        ['versionDir', '客户端版本目录（…\\.minecraft\\versions\\<版本名>）', 'text'],
        ['javaPath', 'java 可执行文件', 'text'],
        ['memoryMb', '内存（MB）', 'number'],
        ['server', '启动后直连（空 = 不直连）', 'text'],
        ['playerName', '离线玩家名', 'text'],
      ],
      resume: [
        ['enableResume', '总开关（关掉 = 完全不注入续跑）', 'bool'],
        ['autoPauseFromChat', '从聊天里读"叫停/休息/别干了"', 'bool'],
        ['idleStreakLimit', '连续空转 N 轮 → 改成"自主找任务"', 'number'],
        ['selfStreakLimit', '连续自主 M 轮无进展 → 低耗待机', 'number'],
        ['lowPowerIntervalMs', '低耗待机间隔（ms）', 'number'],
        ['minIntervalMs', '两次注入的最小间隔（ms）', 'number'],
        ['staleMaxMs', '环境过期判掉线（ms）', 'number'],
        ['tickMs', '轮询 agent.status 的间隔（ms）', 'number'],
      ],
    }

    /** 一个表单字段（text / number / bool） */
    function Field(props) {
      const { label, kind, value, onChange } = props
      if (kind === 'bool') {
        return h('label', { style: { ...styles.row, gap: 6, marginBottom: 6 } },
          h('input', { type: 'checkbox', checked: value === true, onChange: (e) => onChange(e.target.checked) }),
          h('span', null, label),
        )
      }
      return h('label', { style: { display: 'block', marginBottom: 8 } },
        h('span', { style: labelStyle }, label),
        h('input', {
          type: kind === 'number' ? 'number' : 'text',
          style: inputStyle, value: value === undefined || value === null ? '' : String(value),
          onChange: (e) => onChange(kind === 'number' ? Number(e.target.value) : e.target.value),
        }),
      )
    }

    /** 启动器 / 续跑 / 管理员 / 聊天规则 四个表单（都走同一个 settings 或 chat-rules 接口） */
    function ConfigForm(props) {
      const { kind } = props
      const [form, setForm] = React.useState(null)
      const [chat, setChat] = React.useState(null)
      const [busy, setBusy] = React.useState(false)
      const [msg, setMsg] = React.useState(null)

      /** 把后端的原始错误翻译成"玩家能懂 + 能行动"的一句话 */
      const friendly = (raw) => {
        const s = String(raw ?? '')
        if (/unknown endpoint/i.test(s)) {
          return '这个设置项需要**重启 DSH** 后才可用：界面已经是新版本，但宿主还在跑旧代码（新增的 /api/zff-mc/settings 路由要重启才注册）。'
        }
        if (/no_workspace/i.test(s)) return '当前会话还没有工作区，插件无法定位 runtime/ 目录。'
        return s || '保存失败'
      }

      const load = React.useCallback(async () => {
        try {
          const j = await jsonFetch(`${API}/settings`)
          if (!j?.ok) { setMsg({ kind: 'err', text: friendly(j?.error) }); return }
          setForm(j)
          if (kind === 'chat') {
            const c = await jsonFetch(`${API}/chat-rules`)
            setChat(c?.rules ?? { enabled: true, matcher: '^鱼', mustReplyKeywords: [], exemptTools: [] })
          }
        } catch (e) { setMsg({ kind: 'err', text: friendly(`读不到设置：${e?.message ?? e}`) }) }
      }, [kind])
      React.useEffect(() => { load() }, [load])

      const setField = (section, key, v) => setForm((f) => ({ ...f, [section]: { ...(f?.[section] ?? {}), [key]: v } }))

      const save = async () => {
        setBusy(true); setMsg(null)
        try {
          if (kind === 'chat') {
            const words = String(chat?.mustReplyKeywordsText ?? (chat?.mustReplyKeywords ?? []).join(','))
              .split(/[,，\s]+/).map((s) => s.trim()).filter(Boolean)
            const exempt = String(chat?.exemptToolsText ?? (chat?.exemptTools ?? []).join(','))
              .split(/[,，\s]+/).map((s) => s.trim()).filter(Boolean)
            const j = await jsonFetch(`${API}/chat-rules`, {
              method: 'PUT',
              body: JSON.stringify({ matcher: chat?.matcher, enabled: chat?.enabled !== false, mustReplyKeywords: words, exemptTools: exempt }),
            })
            setMsg(j?.ok ? { kind: 'ok', text: '已保存（MCP server 下一次收到消息时热加载）' } : { kind: 'err', text: friendly(j?.error) })
            return
          }
          if (kind === 'admin') {
            const list = String(form?.adminPlayersText ?? (form?.adminPlayers ?? []).join('\n'))
              .split(/[\n,，]+/).map((s) => s.trim()).filter(Boolean)
            const j = await jsonFetch(`${API}/settings`, { method: 'PUT', body: JSON.stringify({ adminPlayers: list }) })
            setMsg(j?.ok ? { kind: 'ok', text: `已保存（${list.length} 个管理员）` } : { kind: 'err', text: friendly(j?.error) })
            return
          }
          const payload = kind === 'connection' ? { connection: form?.connection } : { resume: form?.resume }
          const j = await jsonFetch(`${API}/settings`, { method: 'PUT', body: JSON.stringify(payload) })
          setMsg(j?.ok ? { kind: 'ok', text: '已保存并立即生效' } : { kind: 'err', text: friendly(j?.error) })
        } catch (e) {
          setMsg({ kind: 'err', text: `保存失败：${e?.message ?? e}` })
        } finally { setBusy(false) }
      }

      if (!form) return h('div', { style: styles.muted }, msg?.text ?? '读取中…')

      let body = null
      if (kind === 'admin') {
        body = h('div', null,
          h('span', { style: labelStyle }, '管理员玩家名（每行一个；只有他们能让 AI 下线）'),
          h('textarea', {
            style: { ...styles.ta, minHeight: 90 },
            value: form.adminPlayersText ?? (form.adminPlayers ?? []).join('\n'),
            onChange: (e) => setForm((f) => ({ ...f, adminPlayersText: e.target.value })),
            placeholder: 'FYNyesterday',
          }),
          h('div', { style: styles.muted }, '留空 = 谁都不能让 AI 下线。'),
        )
      } else if (kind === 'chat') {
        body = h('div', null,
          h(React.Fragment, null,
            h(Field, { label: '中断正则（玩家消息匹配它 → 打断阻塞工具，默认 ^鱼）', kind: 'text', value: chat?.matcher ?? '^鱼', onChange: (v) => setChat((c) => ({ ...c, matcher: v })) }),
            h(Field, { label: '必须回复的关键词（逗号分隔）', kind: 'text', value: chat?.mustReplyKeywordsText ?? (chat?.mustReplyKeywords ?? []).join(','), onChange: (v) => setChat((c) => ({ ...c, mustReplyKeywordsText: v })) }),
            h(Field, { label: '豁免工具名（逗号分隔，置位期间也放行；默认空）', kind: 'text', value: chat?.exemptToolsText ?? (chat?.exemptTools ?? []).join(','), onChange: (v) => setChat((c) => ({ ...c, exemptToolsText: v })) }),
          ),
          h('div', { style: styles.muted }, '写到 MCP server 的 runtime/zff-chat-rules.json，它会热加载，不用重启。'),
        )
      } else if (kind === 'connection') {
        const fields = SETTING_FIELDS.connection ?? []
        body = h('div', null,
          h('div', { style: { ...styles.card, background: 'rgba(76,141,255,.08)' } },
            h('strong', null, '插件**不会**启动 Minecraft'),
            h('div', null, '按玩家要求，启动器已移除。正确顺序是：'),
            h('div', null, '1. 你自己用 PCL / 官方启动器打开 Minecraft，进入服务器或存档，能自由行动即可。'),
            h('div', null, '2. 回到这里，在对话里说一句「我进游戏了」（或点「开始游戏」看说明后再说）。'),
            h('div', null, '3. AI 会用 `mcp__mc__*` 先 `get_state` 确认它真的在线，然后直接接手操作。'),
            h('div', { style: { ...styles.muted, marginTop: 6 } },
              '下面两项只是"事实"，会写进 AI 的运行时提示词（让它知道该叫谁、在哪个服）：不改也能用。'),
          ),
          ...fields.map(([key, label, t]) => h(Field, {
            key, label, kind: t,
            value: form[kind]?.[key],
            onChange: (v) => setField(kind, key, v),
          })),
        )
      } else {
        const fields = SETTING_FIELDS[kind] ?? []
        body = h('div', null, ...fields.map(([key, label, t]) => h(Field, {
          key, label, kind: t,
          value: form[kind]?.[key],
          onChange: (v) => setField(kind, key, v),
        })))
      }

      return h('div', null,
        body,
        h('div', { style: { ...styles.row, marginTop: 10 } },
          h('button', { style: styles.btnPrimary, disabled: busy, onClick: save, 'data-zff-save-settings': kind }, busy ? '保存中…' : '保存'),
          h('button', { style: styles.btn, disabled: busy, onClick: load }, '重新读取'),
          msg ? h('span', { style: msg.kind === 'ok' ? styles.ok : styles.err }, msg.text) : null,
        ),
        h('div', { style: { ...styles.muted, marginTop: 8 } }, `设置文件：${form.file ?? ''}`),
      )
    }

    /** 模态框：提示词 / 启动器 / 续跑 / 管理员 / 聊天规则 */
    function SettingsModal(props) {
      const { onClose } = props
      const [tab, setTab] = React.useState('prompts')
      const [state, setState] = React.useState({ loading: true, error: null, snapshot: null })
      const load = React.useCallback(async () => {
        try {
          const j = await jsonFetch(`${API}/prompts`)
          if (j?.ok) setState({ loading: false, error: null, snapshot: j })
          else setState({ loading: false, error: String(j?.error ?? '读不到提示词'), snapshot: null })
        } catch (e) { setState({ loading: false, error: `读不到提示词：${e?.message ?? e}`, snapshot: null }) }
      }, [])
      React.useEffect(() => { load() }, [load])

      const tabs = [
        ['prompts', '提示词 A/B/C'],
        ['connection', '游戏'],
        ['resume', '续跑'],
        ['admin', '管理员'],
        ['chat', '聊天规则'],
      ]
      const tabBtn = (id, label) => h('button', {
        key: id, style: tab === id ? styles.btnPrimary : styles.btn, onClick: () => setTab(id),
        'data-zff-tab': id,
      }, label)

      let content = null
      if (tab === 'prompts') {
        const cards = (state.snapshot?.slots ?? []).map((s) => h(PromptCard, { key: s.slot, slot: s.slot, data: s, onSaved: load }))
        content = h('div', null,
          state.loading ? h('div', { style: styles.muted }, '读取中…') : null,
          state.error ? h('div', { style: styles.err }, state.error) : null,
          state.snapshot?.dir ? h('div', { style: { ...styles.muted, marginBottom: 10 } }, `保存位置：${state.snapshot.dir}`) : null,
          ...cards,
          h('div', { style: styles.muted }, 'B（游戏规则）清空后这一段不会注入；「恢复默认」会回到插件自带的默认文件。'),
        )
      } else {
        content = h(ConfigForm, { key: tab, kind: tab })
      }

      return h('div', { style: styles.overlay, onMouseDown: (e) => { if (e.target === e.currentTarget) onClose?.() } },
        h('div', { style: styles.panel, 'data-zff-settings': '' },
          h('div', { style: styles.head },
            h('strong', { style: { fontSize: 15 } }, 'ZFF MC 设置'),
            h('span', { style: styles.muted }, '保存即生效（提示词是"下一个会话生效"）'),
            h('span', { style: { flex: 1 } }),
            h('button', { style: styles.btn, onClick: onClose }, '关闭'),
          ),
          h('div', { style: { ...styles.row, marginBottom: 14 } }, ...tabs.map(([id, label]) => tabBtn(id, label))),
          content,
        ))
    }

    /* ── 会话标题条：启动游戏 + 设置（只在 ZFF MC 模式渲染）───────────────
     * 照 whale_craft 的实证做法：插 `conversation.session.header.actions`（list 槽，增量安全），
     * **不要**替换 `conversation.session.header`。
     * 门控判据是**本地**的会话 preset（`props.useSessions` 快照里的
     * `projectionValues.agentPreset`）—— 和标题条那个官方模式标签同源，所以"用户看到什么模式，
     * 入口就按什么模式出现"，不受网络成败影响；`/api/zff-mc/presets` 只用来刷新名单，挂了也有兜底。
     */
    const PRESET_FALLBACK = ['zff-mc']
    let presetIds = PRESET_FALLBACK.slice()
    let presetAsked = false
    function loadPresetIds() {
      if (presetAsked) return
      presetAsked = true
      try {
        jsonFetch(`${API}/presets`)
          .then((j) => {
            const list = Array.isArray(j?.modePresets) ? j.modePresets.map(String).filter(Boolean) : []
            if (list.length) { presetIds = list; presetAsked = true } else presetAsked = false
          })
          .catch(() => { presetAsked = false })   // 失败允许下次挂载再试，绝不因此永久隐藏入口
      } catch { presetAsked = false }
    }

    /**
     * 门控：只在该会话的 preset ∈ modePresets 时渲染。
     * @param props 插槽 props（宿主给 session 作用域注入 `sessionId` / `useSessions`）
     * @param wantBlank true = 只认**新会话页**（还没开始对话），false = 只认已有会话，
     *                  undefined = 都认
     */
    function useZffGate(props, wantBlank) {
      const sessionId = props?.sessionId ?? props?.session?.id
      const useSessions = props?.useSessions
      const sess = typeof useSessions === 'function' ? useSessions : null
      const blank = sess
        ? sess((state) => {
          const s = state?.byId?.[sessionId]
          return s === undefined ? undefined : s.blank === true
        })
        : undefined
      const preset = sess
        ? sess((state) => {
          const v = state?.byId?.[sessionId]?.projectionValues?.agentPreset
          return typeof v === 'string' ? v : undefined
        })
        : undefined
      React.useEffect(() => { loadPresetIds() }, [])
      if (blank === undefined || preset === undefined) return false   // 本地判不了：不渲染
      if (wantBlank !== undefined && blank !== wantBlank) return false
      return presetIds.includes(preset)
    }

    /**
     * 开始游戏的说明（**插件不再自己启动 Minecraft**）。
     * 玩家要求：去掉启动器，改成"提示玩家自己开好游戏，AI 直接接手"。
     */
    function StartGuide(props) {
      const { onClose, onSettings } = props
      const step = (n, text) => h('div', { style: { ...styles.row, alignItems: 'flex-start', gap: 8 } },
        h('span', { style: { ...styles.badge, minWidth: 22, textAlign: 'center' } }, String(n)),
        h('span', null, text),
      )
      return h('div', { style: styles.overlay, onMouseDown: (e) => { if (e.target === e.currentTarget) onClose?.() } },
        h('div', { style: styles.panel, 'data-zff-start-guide': '' },
          h('div', { style: styles.head },
            h('strong', { style: { fontSize: 15 } }, '开始游戏'),
            h('span', { style: styles.muted }, '插件不会替你启动 Minecraft'),
            h('span', { style: { flex: 1 } }),
            h('button', { style: styles.btn, onClick: onClose }, '关闭'),
          ),
          h('div', { style: styles.card },
            h('div', { style: { marginBottom: 8 } }, '按这个顺序做，AI 就能直接接手：'),
            step(1, '你自己用 PCL / 官方启动器打开 Minecraft（本体、整合包都行），进服务器或存档。'),
            step(2, '确认你**能自由走动**了（别停在标题画面或加载中）。'),
            step(3, '回到这个对话，直接说一句「我进游戏了」——或者随便说点别的，AI 都会先确认状态。'),
            step(4, 'AI 会调 `mcp__mc__hello` / `get_state` 确认真的在线，然后开始干活（走路、挖矿、说话、听你指挥）。'),
          ),
          h('div', { style: { ...styles.card, background: 'rgba(76,141,255,.08)' } },
            h('strong', null, '两条提示'),
            h('div', null, '· 想让它知道"你是谁、在哪个服"，可以在设置 → 游戏 里填一下（不填也能用）。'),
            h('div', null, '· 你随时可以说「停 / 休息 / 别干了」让它暂停，再说一句解除；管理员还能让它下线。'),
          ),
          h('div', { style: styles.row },
            h('button', { style: styles.btnPrimary, onClick: onClose }, '知道了，我去开游戏'),
            h('button', { style: styles.btn, onClick: onSettings }, '打开设置'),
          ),
        ))
    }

    /** 两个按钮：开始游戏（说明）/ 设置。标题条和新会话页共用。 */
    /**
     * ▶/⏸ 运行开关 + 设置 + 说明（Ph7.2）。
     *
     * 玩家的原话：「按钮 ▶ 按下变为 ⏸ 表示开始游戏，⏸ 按一下变为 ▶ 表示结束游戏」。
     * 状态存在 `<workspace>/runtime/zff-run.json`，**只有 ⏸ 时插件才续跑**；
     * DSH 重启后由 bootId 自动复位成 ▶。
     */
    /**
     * Ph7.4 团队面板：SR / AR / CR / ESRA / ESRC 各自**当前在干什么**（一行摘要，3 s 刷新）。
     * 数据来自 `GET /api/zff-mc/team`（和 `zff_team` 工具同一份）。
     */
    /**
     * 需求 2/4：MCP 状态栏 —— 实时状态 / 从哪个工作目录起的 / 一键启停 / 已连接的桥接实例（按账号名标）。
     */
    function McpBar(props) {
      const { onNotify } = props
      const [data, setData] = React.useState(null)
      const [busy, setBusy] = React.useState(null)
      const [err, setErr] = React.useState(null)
      const load = React.useCallback(async () => {
        try {
          const j = await jsonFetch(API + '/mcp')
          if (j?.ok) { setData(j); setErr(null) } else setErr(String(j?.error ?? '读不到 MCP 状态'))
        } catch (e) { setErr('读不到 MCP 状态：' + (e?.message ?? e)) }
      }, [])
      React.useEffect(() => {
        load()
        const t = setInterval(load, 3000)
        return () => clearInterval(t)
      }, [load])
      const act = React.useCallback(async (action) => {
        setBusy(action)
        setErr(null)
        try {
          const j = await jsonFetch(API + '/mcp', { method: 'POST', body: JSON.stringify({ action }) })
          if (!j?.ok) {
            const msg = j?.error ? (j.error + (j.detail ? '：' + j.detail : '')) : '操作失败'
            setErr(msg)
            onNotify?.(msg)
          }
          await load()
        } catch (e) { setErr(String(e?.message ?? e)) } finally { setBusy(null) }
      }, [load, onNotify])

      const st = data?.status ?? null
      const instances = data?.instances ?? []
      const running = st?.running === true
      const healthy = st?.healthy === true
      const dotColor = healthy ? '#3fb950' : running ? '#d29922' : '#8b949e'
      const label = healthy ? 'MCP 运行中' : running ? 'MCP 异常（进程在，健康检查不通）' : 'MCP 未启动'
      const where = st?.workspace || null
      const by = st?.startedBy ? ({ ui: '面板启动', dsh: 'DSH 启动', manual: '手动启动' }[st.startedBy] ?? st.startedBy) : null

      return h('div', { style: { ...styles.card, padding: '6px 10px', marginTop: 6, fontSize: 12 }, 'data-zff-mcp-bar': '' },
        h('div', { style: { display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' } },
          h('span', { style: { width: 8, height: 8, borderRadius: 4, background: dotColor, display: 'inline-block' } }),
          h('strong', { style: { fontWeight: 600 } }, label),
          st?.pid ? h('span', { style: styles.muted }, 'pid ' + st.pid) : null,
          where ? h('span', { style: { ...styles.muted, maxWidth: 360, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }, title: where }, '工作目录 ' + where) : null,
          by ? h('span', { style: styles.muted }, by) : null,
          h('span', { style: { flex: 1 } }),
          healthy
            ? h('button', { type: 'button', style: { ...styles.btn, color: '#ff7b72' }, disabled: busy === 'stop', onClick: () => act('stop'), 'data-zff-mcp-stop': '' }, busy === 'stop' ? '关闭中…' : '关闭 MCP')
            : h('button', { type: 'button', style: styles.btn, disabled: busy === 'start', onClick: () => act('start'), 'data-zff-mcp-start': '' }, busy === 'start' ? '启动中…' : '启动 MCP'),
        ),
        err ? h('div', { style: { ...styles.muted, color: '#ff7b72', marginTop: 4 } }, err) : null,
        // P7-7：MCP 在跑但本会话一个 mcp__mc__* 都没有 = 宿主还拿着旧 session（只能重启 DSH）。
        data?.gameTools === 0
          ? h('div', { style: { ...styles.muted, color: '#ff7b72', marginTop: 4 } },
            '本会话没有注册任何游戏工具：如果工具报 `unknown MCP session`，请**完全退出 DSH 再打开**（重启客户端没用）。')
          : null,
        // P7-9：chatPush.failed 持续增长 = 玩家消息根本到不了 MCP，聊天中断会失效。
        st?.chatPushWarning
          ? h('div', { style: { ...styles.muted, color: '#d29922', marginTop: 4 } },
            `聊天推送异常：failed=${st.chatPush?.failed ?? '?'} dropped=${st.chatPush?.dropped ?? '?'}（玩家消息可能收不到；检查 57611 daemon）`)
          : null,
        h('div', { style: { ...styles.muted, marginTop: 4 } },
          instances.length
            ? ('桥接实例（' + instances.length + '）：' + instances.map((i) => (i.username || i.account || '未登录/未知账号') + '@' + i.port + (i.inWorld ? '(世界内)' : '(菜单)')).join(' · '))
            : (healthy ? '还没有检测到装了桥接模组的客户端实例' : '—'),
        ),
      )
    }

    function TeamPanel(props) {
      const { onClose } = props
      const [data, setData] = React.useState(null)
      const [err, setErr] = React.useState(null)
      const load = React.useCallback(async () => {
        try {
          const j = await jsonFetch(`${API}/team`)
          if (j?.ok) { setData(j); setErr(null) } else setErr(String(j?.error ?? '读不到团队状态'))
        } catch (e) { setErr(`读不到团队状态：${e?.message ?? e}`) }
      }, [])
      React.useEffect(() => {
        load()
        const t = setInterval(load, 3000)
        return () => clearInterval(t)
      }, [load])

      const stateText = (s) => ({
        running: '执行中', idle: '空闲', ok: '正常', alert: '告警',
        unreported: '未上报', stale: '可能已结束', unknown: '未知',
        done: '已完成', failed: '失败', cancelled: '已取消',
      }[s] ?? s)

      const rows = data?.rows ?? []
      return h('div', { style: styles.overlay, onMouseDown: (e) => { if (e.target === e.currentTarget) onClose?.() } },
        h('div', { style: { ...styles.panel, width: 'min(760px, 96vw)' }, 'data-zff-team': '' },
          h('div', { style: styles.head },
            h('strong', { style: { fontSize: 15 } }, '团队'),
            h('span', { style: styles.muted }, '谁在干什么（3 秒刷新；子代理靠自报）'),
            h('span', { style: { flex: 1 } }),
            h('button', { style: styles.btn, onClick: load }, '刷新'),
            h('button', { style: styles.btn, onClick: onClose }, '关闭'),
          ),
          err ? h('div', { style: styles.err }, err) : null,
          h('div', { style: { ...styles.card, padding: 0 } },
            h('div', { style: { ...styles.row, padding: '6px 10px', borderBottom: '1px solid rgba(255,255,255,.12)', marginBottom: 0, fontWeight: 600 } },
              h('span', { style: { width: 64 } }, '角色'),
              h('span', { style: { width: 74 } }, '状态'),
              h('span', { style: { flex: 1 } }, '当前任务'),
              h('span', { style: { width: 64, textAlign: 'right' } }, '持续'),
            ),
            ...rows.map((r) => h('div', {
              key: r.role,
              style: { ...styles.row, padding: '6px 10px', borderBottom: '1px solid rgba(255,255,255,.06)', marginBottom: 0, alignItems: 'flex-start' },
            },
            h('span', { style: { width: 64 } }, r.role),
            h('span', {
              style: {
                width: 74,
                color: r.state === 'alert' ? '#ff7b72' : r.state === 'running' ? '#4c8dff' : r.state === 'ok' ? '#7ee787' : 'inherit',
                opacity: r.state === 'idle' || r.state === 'unreported' ? 0.7 : 1,
              },
            }, stateText(r.state)),
            h('span', { style: { flex: 1 } },
              r.task ?? (r.state === 'unreported' ? h('span', { style: styles.muted }, '（还没上报：子代理开工时 store_set team.' + r.role + '）') : '—'),
              r.note ? h('div', { style: { ...styles.muted, fontSize: 11 } }, r.note) : null,
            ),
            h('span', { style: { width: 64, textAlign: 'right', opacity: 0.75 } }, r.sinceText ?? '—'),
            )),
          ),
          (data?.notes ?? []).length ? h('div', { style: { ...styles.muted, marginTop: 8 } }, ...(data.notes).map((n, i) => h('div', { key: i }, `· ${n}`))) : null,
          h('div', { style: { ...styles.muted, marginTop: 8 } },
            'SR/AR/ESRC 的"当前任务"来自它们自己写的 CR store（`team.<角色>`）；CR 与 ESRA 由插件/mod 直接读，不用自报。'),
        ))
    }
    function ActionButtons(props) {
      const [guide, setGuide] = React.useState(false)
      const [open, setOpen] = React.useState(false)
      const [team, setTeam] = React.useState(false)
      const [runErr, setRunErr] = React.useState(null)
      const [run, setRun] = React.useState(null)      // { state, game }
      const [busy, setBusy] = React.useState(false)
      const [note, setNote] = React.useState(null)
      // P7-6：按钮必须按**自己所属会话**读状态。宿主在标题条/输入区都会给 sessionId；
      // 第一次 POST 后服务端也会把它回传，后续 GET 一直带着，不再靠"最近活跃的 agent"猜。
      const sidRef = React.useRef(props?.sessionId ?? props?.session?.id ?? null)
      React.useEffect(() => {
        if (props?.sessionId || props?.session?.id) sidRef.current = props.sessionId ?? props.session?.id
      }, [props?.sessionId, props?.session?.id])

      const refresh = React.useCallback(async () => {
        try {
          const sid = sidRef.current
          const q = sid ? `?session=${encodeURIComponent(sid)}` : ''
          const j = await jsonFetch(`${API}/run${q}`)
          if (j?.ok) {
            if (j.sessionId) sidRef.current = j.sessionId
            setRun({ state: j.state === 'running' ? 'running' : 'idle', game: j.game ?? null })
          } else setRun({ state: 'idle', game: null, error: j?.error ?? null })
        } catch { /* 静默：等下一次轮询 */ }
      }, [])
      React.useEffect(() => {
        refresh()
        const t = setInterval(refresh, 5000)
        return () => clearInterval(t)
      }, [refresh])

      const toggle = async () => {
        const next = run?.state === 'running' ? 'idle' : 'running'
        setBusy(true); setNote(null)
        try {
          const j = await jsonFetch(`${API}/run`, { method: 'POST', body: JSON.stringify({ state: next, sessionId: sidRef.current ?? null }) })
          if (j?.ok) {
            if (j.sessionId) sidRef.current = j.sessionId
            setRun((r) => ({ ...(r ?? {}), state: j.state === 'running' ? 'running' : 'idle' }))
            if (next === 'running') {
              const inst = run?.game?.instance
              setNote({ kind: 'muted', text: inst?.inWorld ? '已开始：AI 会自己往下干' : '等待客户端进世界…' })
            } else {
              setNote({ kind: 'muted', text: '已结束：不会再自动续跑，可以自由提问' })
            }
            setTimeout(refresh, 800)
          } else if (j?.error === 'no_workspace') {
            setNote({ kind: 'err', text: '当前会话还没有工作目录' })
          } else if (j?.error) {
                // 需求 5：MCP 没起好 / 有挂着的 server → 弹浮窗，且不切到 ⏸
                const map = {
                  hung: 'MCP 服务器卡住了（进程在，但不响应）',
                  other_workspace: '已有 MCP 服务器在跑，但工作目录不是当前会话',
                  no_zip: '当前目录没有 MCP（既没解压文件夹，也没有 zip）',
                  start_failed: 'MCP 启动失败',
                  extract_failed: 'MCP 解压失败',
                  probe_failed: '探测 MCP 状态失败',
                }
            setRunErr({ title: map[j.error] ?? '开始游戏被拒绝', detail: j.detail ?? null, error: j.error })
            setNote({ kind: 'err', text: '保持暂停：' + (map[j.error] ?? j.error) })
          } else {
            setNote({ kind: 'err', text: String(j?.error ?? '切换失败') })
          }
        } catch (e) {
          setNote({ kind: 'err', text: `切换失败：${e?.message ?? e}` })
        } finally { setBusy(false) }
      }

      const running = run?.state === 'running'
      const waiting = running && run?.game && !run.game.instance
      const label = run === null ? '…' : (running ? '⏸ 进行中' : '▶ 开始游戏')
      return h(React.Fragment, null,
        h('button', {
          type: 'button',
          style: running ? { ...styles.btn, borderColor: '#4c8dff', color: '#4c8dff' } : styles.btn,
          'data-zff-run': running ? 'running' : 'idle',
          disabled: busy || run === null,
          title: running
            ? '游戏进行中：AI 会自动续跑。点击结束（不会自动续跑，但可以继续问它问题）'
            : '开始游戏：让 AI 接着干（需要先把游戏开起来并进世界）',
          onClick: toggle,
        }, label),
        waiting ? h('span', { style: styles.muted }, '（等待客户端进世界）') : null,
        h('button', {
          type: 'button', style: styles.btn, 'data-zff-open': '',
          title: '编辑 ZFF MC 的设置（提示词 / 游戏 / 续跑 / 管理员 / 聊天规则）',
          onClick: () => setOpen(true),
        }, '设置'),
        h('button', {
          type: 'button', style: styles.btn, 'data-zff-team-btn': '',
          title: '看 SR / AR / CR / ESRA / ESRC 各自现在在干什么',
          onClick: () => setTeam(true),
        }, '团队'),
        h(McpBar, { onNotify: (m) => { setNote({ kind: 'err', text: m }); setTimeout(() => setNote(null), 6000) } }),
        h('button', {
          type: 'button', style: styles.btn, 'data-zff-help': '',
          title: '开始游戏之前要做什么（四步说明）',
          onClick: () => setGuide(true),
        }, '?'),
        note ? h('span', { style: note.kind === 'err' ? styles.err : styles.muted }, note.text) : null,
        guide ? h(StartGuide, { onClose: () => setGuide(false), onSettings: () => { setGuide(false); setOpen(true) } }) : null,
        open ? h(SettingsModal, { onClose: () => setOpen(false) }) : null,
        team ? h(TeamPanel, { onClose: () => setTeam(false) }) : null,
        runErr ? h('div', { style: styles.overlay, onMouseDown: (e) => { if (e.target === e.currentTarget) setRunErr(null) } },
          h('div', { style: { ...styles.panel, width: 'min(460px, 92vw)' }, 'data-zff-runerr': '' },
            h('div', { style: styles.head },
              h('strong', null, '不能开始游戏'),
              h('span', { style: styles.muted }, 'MCP 状态不满足'),
              h('span', { style: { flex: 1 } }),
              h('button', { style: styles.btn, onClick: () => setRunErr(null) }, '知道了'),
            ),
            h('div', { style: { marginTop: 8 } }, runErr.title ?? '开始游戏被拒绝'),
            runErr.detail ? h('div', { style: { ...styles.muted, marginTop: 6 } }, runErr.detail) : null,
            h('div', { style: { ...styles.muted, marginTop: 8 } }, '已保持 ▶（暂停）。处理上面的问题后再点一次「开始游戏」。'),
          )) : null,
      )
    }

    /** ① 已有会话：标题条上的入口 */
    function HeaderActions(props) {
      const show = useZffGate(props, false)
      if (!show) return null
      return h(ActionButtons, { sessionId: props?.sessionId ?? props?.session?.id ?? null })
    }

    /**
     * ② 新会话页（hero）：这是 whale_craft 的实证做法 ——
     *   注册到 `conversation.input.right`（真实 list 插槽，新会话页也渲染），
     *   只在 `blank === true` 且 preset 是 ZFF MC 时渲染。
     *   与 ① 按 blank 互斥，所以页面上任何时候只有一套入口。
     */
    function ComposerDock(props) {
      const show = useZffGate(props, true)
      if (!show) return null
      return h(ActionButtons, { sessionId: props?.sessionId ?? props?.session?.id ?? null })
    }

    /** 插件详情页上的入口按钮 */
    function Entry() {
      const [open, setOpen] = React.useState(false)
      return h(React.Fragment, null,
        h('button', {
          type: 'button', style: styles.btn, 'data-zff-open': '', title: '编辑 ZFF MC 的提示词三件套',
          onClick: () => setOpen(true),
        }, '提示词设置'),
        open ? h(SettingsModal, { onClose: () => setOpen(false) }) : null,
      )
    }

    return {
      inject: ['slots'],
      apply(ctx) {
        // ① 会话标题条（只在 ZFF MC 模式的**已有会话**里渲染）：启动游戏 / 设置
        try {
          ctx.slots.inject('conversation.session.header.actions', () => ctx.slots.register(
            { name: 'conversation.session.header.actions', id: 'zff-mc-header', order: 45 },
            HeaderActions,
          ))
        } catch (e) {
          try { console.warn('[zff-mc] 注册会话标题条入口失败：', e?.message ?? e) } catch { /* 忽略 */ }
        }
        // ② 新会话页（hero）：和 whale_craft 一样借 `conversation.input.right` 拿挂载时机，
        //    只在 blank + ZFF MC 模式时渲染「ZFF 设置」。
        try {
          ctx.slots.inject('conversation.input.right', () => ctx.slots.register(
            { name: 'conversation.input.right', id: 'zff-mc-hero', order: 20 },
            ComposerDock,
          ))
        } catch (e) {
          try { console.warn('[zff-mc] 注册新会话页入口失败：', e?.message ?? e) } catch { /* 忽略 */ }
        }
        // ② 插件详情页（如果插件管理器把这个包列出来）
        try {
          ctx.slots.inject('plugins.detail.actions', () => ctx.slots.register(
            { name: 'plugins.detail.actions', id: 'zff-mc-prompts', order: 20 },
            Entry,
          ))
        } catch (e) {
          // 宿主没有这个插槽（老版本 / 非插件页）：静默降级，设置页仍可用 /api/zff-mc/prompts 访问
          try { console.warn('[zff-mc] 注册设置页入口失败：', e?.message ?? e) } catch { /* 忽略 */ }
        }
        return undefined
      },
    }
  },
    })
  } catch (e) {
    if (/duplicate factory registration/i.test(String(e && e.message ? e.message : e))) {
      // 宿主没 invalidate 就重放了同一个 bundle：旧的 factory 仍然有效，等价于"已注册"，吞掉。
    } else {
      throw e
    }
  }
})()
