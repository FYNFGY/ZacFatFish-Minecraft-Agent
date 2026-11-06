// Ph7 实测：在**新环境**里接管客户端，逐个放置并「使用」森罗厨房/酒馆的功能方块。
// 用法： node src/ph7-blocks.mjs [--out ../../evidence/ph7-blocks.json]
// 只通过 MCP（streamable-http）操作游戏，等价于 AI 自己调工具。
import fs from 'node:fs'
import path from 'node:path'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const url = process.env.MCP_URL || 'http://127.0.0.1:57610/mcp'
const argv = process.argv.slice(2)
const outFile = (() => { const i = argv.indexOf('--out'); return i >= 0 ? argv[i + 1] : path.resolve('D:/mc/MCP Development/evidence/ph7-blocks.json') })()

/** 要测的方块：厨房（cookery + 国味）与酒馆（tavern）。每条带中文名，便于报告。 */
const BLOCKS = [
  ['kaleidoscope_cookery:stove', '灶台'],
  ['kaleidoscope_cookery:stockpot', '汤锅'],
  ['kaleidoscope_cookery:pot', '炒锅'],
  ['kaleidoscope_cookery:steamer', '蒸笼'],
  ['kaleidoscope_cookery:millstone', '石磨'],
  ['kaleidoscope_cookery:chopping_board', '菜板'],
  ['kaleidoscope_cookery:oil_pot', '油锅'],
  ['kaleidoscope_cookery:teapot', '茶壶'],
  ['kaleidoscope_cookery:kitchenware_racks', '厨具架'],
  ['kaleidoscope_chinesefood:freezer', '冰柜'],
  ['kaleidoscope_chinesefood:pickle_jar', '泡菜坛'],
  ['kaleidoscope_chinesefood:bowl_stack', '碗堆'],
  ['kaleidoscope_tavern:bar_counter', '吧台'],
  ['kaleidoscope_tavern:bar_cabinet', '吧台柜'],
  ['kaleidoscope_tavern:cellar_cabinet', '酒窖柜'],
  ['kaleidoscope_tavern:circular_rack', '圆酒架'],
  ['kaleidoscope_tavern:chalkboard', '黑板'],
  ['kaleidoscope_tavern:base_sandwich_board', '三明治招牌'],
  ['kaleidoscope_tavern:barrel', '酒桶'],
  ['kaleidoscope_tavern:black_bar_stool', '吧台凳'],
  ['kaleidoscope_tavern:white_sofa', '沙发'],
  ['kaleidoscope_tavern:tilted_rack', '斜酒架'],
  ['kaleidoscope_tavern:brass_heart', '黄铜之心'],
  ['kaleidoscope_tavern:glass_bar_cabinet', '玻璃酒柜'],
]

const client = new Client({ name: 'ph7-blocks', version: '1.0.0' }, { capabilities: {} })
await client.connect(new StreamableHTTPClientTransport(new URL(url)))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const call = async (name, args = {}) => {
  try {
    const r = await client.callTool({ name, arguments: args })
    const text = (r?.content ?? []).filter((c) => c.type === 'text').map((c) => c.text).join('\n')
    let json = null
    try { json = text ? JSON.parse(text) : null } catch { json = { raw: text.slice(0, 400) } }
    return { isError: r?.isError === true, json, text }
  } catch (e) {
    return { isError: true, json: null, error: String(e?.message ?? e) }
  }
}

const state = async () => (await call('get_state')).json
const tp = async (x, y, z) => call('command', { command: `/tp @s ${x} ${y} ${z}` })

const report = { at: new Date().toISOString(), url, blocks: [], summary: {} }
const st0 = await state()
const base = st0?.player ? { x: Math.floor(st0.player.x), y: Math.floor(st0.player.y), z: Math.floor(st0.player.z) } : { x: 0, y: -60, z: 0 }
report.startState = { player: st0?.player?.name, pos: base, gameMode: st0?.player?.gameMode, dimension: st0?.player?.dimension }

let ok = 0
for (let i = 0; i < BLOCKS.length; i++) {
  const [id, cn] = BLOCKS[i]
  const x = base.x + 3 + (i % 6) * 2
  const z = base.z - 6 - Math.floor(i / 6) * 3
  const y = base.y
  const row = { i: i + 1, id, cn, pos: { x, y, z } }

  // ① 放置
  const placed = await call('command', { command: `/setblock ${x} ${y} ${z} ${id}` })
  row.place = { ok: placed.json?.sent === true, text: (placed.text ?? '').slice(0, 200) }
  await sleep(250)

  // ② 站到方块旁（2 格远，避免超出 4.5 的交互距离）
  await tp(`${x + 0.5}`, `${y}`, `${z + 2.5}`)
  await sleep(350)

  // ③ 确认方块真的在那儿
  const bq = await call('block_query', { positions: [[x, y, z]] })
  row.block = bq.json?.blocks?.[0]?.id ?? bq.json?.blocks?.[0]?.name ?? null

  // ④ 右键使用
  const use1 = await call('use_block', { x, y, z })
  await sleep(250)
  let useOk = !use1.isError && use1.json?.ok !== false
  let detail = use1.json ?? use1.error ?? use1.text?.slice(0, 200)
  if (!useOk) {
    // 有些方块要对着「上面」用（灶台/汤锅这类）
    const use2 = await call('use_block', { x, y, z, face: 'up' })
    await sleep(250)
    if (!use2.isError && use2.json?.ok !== false) { useOk = true; detail = use2.json }
  }
  row.use = { ok: useOk, detail }

  // ⑤ 看有没有开出菜单（容器/工作站 GUI）
  const st = await state()
  row.screen = { open: st?.screen?.open ?? null, menuClass: st?.screen?.menuClass ?? null, menuSlots: st?.screen?.menuSlots ?? null }
  if (row.screen.open) {
    const closed = await call('container_close')
    row.screen.closed = !closed.isError
    await sleep(200)
  }

  row.ok = row.place.ok && row.use.ok
  if (row.ok) ok++
  report.blocks.push(row)
  console.log(`${row.ok ? 'OK  ' : 'FAIL'} ${String(row.i).padStart(2)} ${cn.padEnd(6)} ${id}  menu=${row.screen.menuClass ?? '-'}  use=${useOk}`)
}

report.summary = { total: BLOCKS.length, ok, failed: BLOCKS.length - ok }
fs.mkdirSync(path.dirname(outFile), { recursive: true })
fs.writeFileSync(outFile, JSON.stringify(report, null, 2), 'utf8')

// 收尾：站到方块排中间，拍一张截图当证据
await tp(`${base.x + 6.5}`, `${base.y}`, `${base.z - 2.5}`)
await sleep(600)
const shot = await call('screenshot', {})
report.screenshot = { isError: shot.isError, bytes: shot.text ? shot.text.length : 0 }
fs.writeFileSync(outFile, JSON.stringify(report, null, 2), 'utf8')

console.log(`\nsummary: ${ok}/${BLOCKS.length} ok → ${outFile}`)
await client.close()
