/**
 * 2026-11-03: sanitize text before it reaches Minecraft's sendChat/sendCommand.
 * A multiplayer server can kick the client with "Illegal characters in chat" if the packet
 * contains newlines, control characters, section signs or unpaired UTF-16 surrogates.
 */
export function sanitizeChatText (raw, { command = false } = {}) {
  let s = String(raw == null ? '' : raw)
    .replace(/[\u0000-\u001F\u007F\u00A7\u2028\u2029]/g, ' ')
    .replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/g, '')
    .replace(/(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, '')
  s = s.trim().replace(/\s+/g, ' ')
  if ([...s].length > 256) s = [...s].slice(0, 256).join('')
  return s
}
