# ZFF MC 模式的 system prompt 是怎么来的（以及怎么改）

> 2026-11-01。回答一个具体问题：「能不能把 ZFF MC 模式下的 system prompt 自动改成
> `You will be a player in a Minecraft world.`」
> 结论：**能，而且已经改成默认值**；但**能改的只有 persona 那一段**，宿主基础提示不归我们管。

---

## 1. ZFF MC 模式下，模型真正看到的「系统提示」由三段拼成

| 来源 | 内容 | 归谁管 | 生效方式 |
|---|---|---|---|
| **宿主基础提示** | DSH 自己的标准 system prompt（身份、工具调用协议、运行环境…） | 宿主，不可改 | — |
| **preset 的 `persona.prefix`** | 一句角色锚点（**就是这次改的地方**） | `presets/zff-mc.patch.yml` | bundle 层 → **重启 DSH 生效** |
| **preset 的 `persona.suffix`** | 操作铁律（只用工具、文件边界、每轮汇报） | 同上 | 同上 |

拼装关系：`system = prefix + 宿主基础提示 + suffix`（`@deepseek-ai/dsh-persona` 的语义）。

**注意**：提示词三件套 A/B/C（`prompts/{tools,rules,persona}.md`）**不在** system prompt 里 ——
插件是通过 `agent/pre-step` 把它们作为「每轮的第一条消息」注入的（M0.5 结论：`system-prompt/assemble`
的载荷形状没有证据，所以没挂那个钩子）。所以：

- 想让**每条会话的 system prompt 开头**变成某句话 → 改 preset 的 `prefix`；
- 想改「人设正文 / 工具纪律 / 玩家硬规则」→ 改 A/B/C（插件设置页可编辑，热更新）。

## 2. 这次改了什么

```yaml
# D:\mc\zff-mc\presets\zff-mc.patch.yml
- id: persona
  name: '@deepseek-ai/dsh-persona'
  config:
    prefix: 'You will be a player in a Minecraft world.'      # ← 新：你指定的那句（英文原句）
    suffix: '你是由模型 {{model}} 驱动的 Minecraft 玩家助理：游戏内的一切都只能经由 `mcp__mc__*` 工具完成，
             不臆测没有读到的状态；你只能读写本会话工作目录下的文件；每轮必须有一句汇报：…'
```

原来的中文 prefix（"你是一个 Minecraft Java 版游戏玩家助理…"）被这一句替换；其中**两条铁律**
（只用工具、每轮汇报）挪进了 `suffix`，所以它们仍然在 system prompt 里，没有丢。

校验：
- `node scripts/check-zff-yaml.mjs` → 两个 yml 都被真实 YAML 解析器解析通过，`preset id=zff-mc`、
  `persona.prefix="You will be a player in a Minecraft world."`；
- `node D:\mc\zff-mc\selfcheck.mjs` → 163/163（插件契约没被破坏）。

## 3. 三种「改 system prompt」的落点对比

| 做法 | 是不是真 system prompt | 生效时间 | 风险 |
|---|---|---|---|
| **A. 改包内 `presets/zff-mc.patch.yml`（本次）** | ✅ 是 | **重启 DSH** | 包更新会覆盖（所以注释里写了用编辑器覆盖） |
| **B. Web「preset 编辑器」按行 id `preset-zff-mc` 覆盖 `persona.config`** | ✅ 是 | 热重载（profile 层） | 改错会让该 preset 起不来；玩家自己的配置 |
| **C. 插件 `agent/pre-step` 注入** | ❌ 不是（只是每轮第一条消息） | 立即 | 刷新上下文，长会话里会被 compaction 侵蚀 |

## 4. 语言与行为的影响（要提前知道）

- **英文 system prompt + 中文人设**：模型偶尔会用英文回答。缓解：Part C（`persona.md`，中文，每轮注入）
  和玩家实际说的是中文，通常能压回来；如果压不住，把那句改成中英双语：
  `You will be a player in a Minecraft world.（你是一个 Minecraft 世界里的玩家。）`
- **一句话撑不起行为约束**：真正让低思考等级模型稳定干活的，是 suffix 的铁律 + Part A 的工具规则 +
  Part B 的玩家硬规则。删掉这些只留一句英文，会明显更容易"空转/臆测"。
- **想让它真的「只有」那一句**：把 `suffix` 整行删掉即可（system prompt 就只剩 prefix + 宿主基础提示）。
  不建议 —— 宿主基础提示里不包含"每轮必须汇报"这条。

## 5. 怎么验证（需要一次重启）

1. 完全重启 DSH（bundle 层才会重新组装）。
2. 新开一个会话，模式选 **ZFF MC Mode**。
3. 看会话的第一条 system/meta 信息，或让代理自述："你的 system prompt 第一句是什么？"——
   应回答 `You will be a player in a Minecraft world.`
4. 再让它在游戏里说句话，确认它仍然用中文回玩家（Part C 生效）。

> 本场没有验证第 3 步：硬约束是"不重启 DSH"（会杀掉当前会话）。所以这一条属于**已改、未实机确认**。
