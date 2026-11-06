/**
 * ZFF MC / src/tool-def.mjs —— 自己造**宿主工具定义形状**（不依赖任何 `@deepseek-ai/*` 包）
 * ============================================================================
 * 为什么要自己造：`@deepseek-ai/dsh-tools` 是插件的 **optional peer**，包管理器永远不装它；
 * whale_craft 在真机上踩过"顶层静态 import 导到模块链接期失败、整个插件加载不起来"
 * （`whale_craft/src/tool-def.mjs:1-20` 的头注释记着那次事故）。本插件采取更保守的策略：
 * **根本不 import 宿主包**，直接产出注册所需的普通对象。
 *
 * 形状取自 whale_craft 的内置实现（与宿主 `defineTool` 的产出一致，`src/tool-def.mjs:42,278-292`）：
 *   `{ name, description, parameters, output:{schema, render}, [timeoutMs], execute }`
 * 其中 `parameters` 是**已编译的 JSON Schema**（`{type:'object',properties,required?}`）。
 *
 * 入参校验：宿主会自己按 schema 校验一遍，我们这里再校验一遍 —— 因为**报错文案是模型可见信息**
 * （schema 对不上时宿主报的英文错误，低思考等级的模型往往看不懂；中文提示更有效）。
 * ============================================================================
 */

/** 允许的标量类型（与宿主 defineTool 的 spec 一致） */
const SCALAR = new Set(['string', 'number', 'integer', 'boolean'])

/**
 * 把简写的参数表编译成 JSON Schema。
 * @param {Record<string, {type:string, description?:string, enum?:any[], items?:object, required?:boolean}>} spec
 * @returns {{type:'object', properties:Record<string,object>, required?:string[]}}
 */
export function compileParameters (spec = {}) {
  const properties = {}
  const required = []
  for (const [key, raw] of Object.entries(spec ?? {})) {
    const node = raw && typeof raw === 'object' ? raw : {}
    const type = String(node.type ?? 'string')
    if (!SCALAR.has(type) && type !== 'array' && type !== 'object' && type !== 'json') {
      throw new Error(`参数 ${key} 的 type 不认识：${type}（可用 string/number/integer/boolean/array/object/json）`)
    }
    const prop = type === 'json' ? {} : { type }
    if (node.description !== undefined) prop.description = String(node.description)
    if (Array.isArray(node.enum)) prop.enum = [...node.enum]
    if (type === 'array') prop.items = node.items && typeof node.items === 'object' ? node.items : { type: 'string' }
    if (type === 'object') { prop.properties = {}; prop.additionalProperties = true }
    properties[key] = prop
    if (node.required === true) required.push(key)
  }
  const out = { type: 'object', properties }
  if (required.length) out.required = required
  return out
}

/** 入参错误（形状对齐宿主 `ToolArgsError`：`code='INVALID_ARGS'`，文案是模型可见的） */
export class ToolArgsError extends Error {
  constructor (violations) {
    super(`参数不合法：${violations.join('；')}`)
    this.name = 'ToolArgsError'
    this.code = 'INVALID_ARGS'
    this.violations = violations
  }
}

/** 按编译好的 schema 做最小校验（必填 / 标量类型 / enum）。返回违规文案数组。 */
export function validateArgs (schema, args) {
  const out = []
  const a = args && typeof args === 'object' && !Array.isArray(args) ? args : {}
  if (args !== undefined && args !== null && (typeof args !== 'object' || Array.isArray(args))) out.push('入参必须是一个对象')
  for (const key of schema?.required ?? []) {
    if (a[key] === undefined || a[key] === null) out.push(`缺少必填参数 "${key}"`)
  }
  for (const [key, prop] of Object.entries(schema?.properties ?? {})) {
    const v = a[key]
    if (v === undefined || v === null) continue
    if (prop.type === 'string' && typeof v !== 'string') out.push(`"${key}" 必须是字符串`)
    if (prop.type === 'number' && (typeof v !== 'number' || !Number.isFinite(v))) out.push(`"${key}" 必须是数字`)
    if (prop.type === 'integer' && (!Number.isInteger(v))) out.push(`"${key}" 必须是整数`)
    if (prop.type === 'boolean' && typeof v !== 'boolean') out.push(`"${key}" 必须是布尔值`)
    if (prop.type === 'array' && !Array.isArray(v)) out.push(`"${key}" 必须是数组`)
    if (prop.type === 'object' && (typeof v !== 'object' || v === null || Array.isArray(v))) out.push(`"${key}" 必须是对象`)
    if (Array.isArray(prop.enum) && !prop.enum.includes(v)) out.push(`"${key}" 只能是 ${JSON.stringify(prop.enum)} 之一`)
  }
  return out
}

/**
 * 造一个工具定义。
 * @param {{
 *   name:string,
 *   description:string,
 *   parameters?:Record<string,object>,
 *   render?:(args:any, value:any)=>string,
 *   outputSchema?:object,
 *   timeoutMs?:number,
 *   execute:(args:any, exec:any)=>any,
 * }} o
 */
export function makeTool ({ name, description, parameters = {}, render = null, outputSchema = null, timeoutMs = null, execute }) {
  if (!name || typeof name !== 'string') throw new Error('makeTool: name 必填')
  if (!description || typeof description !== 'string') throw new Error(`makeTool(${name}): description 必填`)
  if (typeof execute !== 'function') throw new Error(`makeTool(${name}): execute 必填`)
  const compiled = compileParameters(parameters)
  const tool = {
    name,
    description,
    parameters: compiled,
    output: {
      // 宽松输出 schema（与 whale_craft 的 `text()` 同款）：任何对象都合法，
      // 真正的"给人看的形状"由 render 决定。
      schema: outputSchema ?? { type: 'object', properties: {}, additionalProperties: true },
      render: (args, value) => [{ type: 'text', text: render ? String(render(args, value)) : defaultRender(value) }],
    },
    async execute (args, exec) {
      const violations = validateArgs(compiled, args)
      if (violations.length) throw new ToolArgsError(violations)
      return execute(args ?? {}, exec)
    },
  }
  if (Number.isFinite(Number(timeoutMs)) && Number(timeoutMs) > 0) tool.timeoutMs = Math.round(Number(timeoutMs))
  return tool
}

/** 默认渲染：有 text 字段就用它，否则 JSON（缩进 2） */
export function defaultRender (value) {
  if (value === undefined || value === null) return '（无返回值）'
  if (typeof value === 'string') return value
  if (typeof value?.text === 'string') return value.text
  try { return JSON.stringify(value, null, 2) } catch { return String(value) }
}
