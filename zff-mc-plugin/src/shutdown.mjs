/**
 * ZFF MC / src/shutdown.mjs —— 管理员下线的**授权与标记**
 * ============================================================================
 * M4 规格（`docs/ph6/agent-prompt6.md` §2.6）：
 *   · 插件配置 `adminPlayers: string[]`（游戏名）；
 *   · CR 用**任意语义**判断"管理员要求下线"，确认后调 `zff_shutdown {reason}`；
 *   · 插件用**只读的 `chat_history`** 复核"最近 60 s 内说话的玩家在不在 adminPlayers 里"，
 *     不在 → 拒绝，返回 `{ok:false, error:"not_admin"}`；
 *     在   → 返回 `{ok:true, action:"disconnect_then_kill"}` 并写 `runtime/zff-shutdown.json`
 *            （`terminated=true`）。
 *   · **真正的断连/杀进程由 MCP server 侧执行**，插件只做授权与标记 ——
 *     所以这里绝不 spawn、绝不 kill（越权杀人比"没下线"危险得多）。
 *
 * 🔴 安全取向：**宁可拒绝**。拿不到聊天（daemon 没起 / 超时 / 返回坏）→ 一律 `chat_unavailable`，
 *    不写 terminated；`adminPlayers` 为空 → `not_admin`（默认没有任何管理员）。
 * ============================================================================
 */
import { callDaemonTool, findAdminSender, normalizeChatMessages, ADMIN_WINDOW_MS, DEFAULT_DAEMON_URL } from './chat.mjs'
import { writeShutdownMarker } from './runtime.mjs'

/** 允许的动作（MCP server 侧按这个字符串决定怎么做） */
export const SHUTDOWN_ACTION = 'disconnect_then_kill'

/**
 * 授权 + 落标记。
 * @param {{
 *   workspace:string,
 *   admins:string[],
 *   now?:number,
 *   daemonUrl?:string,
 *   reason?:string|null,
 *   windowMs?:number,
 *   fetchImpl?:Function,
 *   limit?:number,
 * }} o
 * @returns {Promise<
 *   {ok:true, action:string, by:string, at:number, file:string}
 *   | {ok:false, error:'not_admin'|'chat_unavailable'|'no_workspace'|'no_admins', detail?:string, checked?:number, admins?:string[]}
 * >}
 */
export async function verifyAdminAndMark ({
  workspace,
  admins = [],
  now = Date.now(),
  daemonUrl = DEFAULT_DAEMON_URL,
  reason = null,
  windowMs = ADMIN_WINDOW_MS,
  fetchImpl = globalThis.fetch,
  limit = 60,
} = {}) {
  if (!workspace || !String(workspace).trim()) {
    return { ok: false, error: 'no_workspace', detail: '这个会话没有工作区，运行时文件无处安放' }
  }
  const adminList = (Array.isArray(admins) ? admins : []).map((a) => String(a ?? '').trim()).filter(Boolean)
  if (!adminList.length) {
    return { ok: false, error: 'not_admin', detail: 'adminPlayers 是空的（默认没有任何管理员）', admins: [] }
  }

  const res = await callDaemonTool({
    baseUrl: daemonUrl,
    tool: 'chat_history',
    args: { limit },
    timeoutMs: 5000,
    fetchImpl,
  })
  if (!res.ok) {
    // 拿不到聊天就**不敢授权**：这条路径永远不会写 terminated
    return { ok: false, error: 'chat_unavailable', detail: String(res.error ?? 'daemon 未就绪'), admins: adminList }
  }
  const messages = normalizeChatMessages(res.json)
  const hit = findAdminSender({ messages, admins: adminList, now, windowMs })
  if (!hit.found) {
    return {
      ok: false,
      error: 'not_admin',
      detail: `最近 ${Math.round(windowMs / 1000)} s 内没有管理员发言（看过 ${hit.checked} 条玩家消息）`,
      checked: hit.checked,
      admins: adminList,
    }
  }

  const mark = writeShutdownMarker({ workspace, reason: reason ?? null, by: hit.sender, now })
  return { ok: true, action: SHUTDOWN_ACTION, by: String(hit.sender), at: now, file: mark.file }
}
