#### 再强大的人工智能，也代替不了你的朋友。
#### No matter how powerful artificial intelligence becomes, it can never replace your friends.
#### Aussi puissante que soit l’intelligence artificielle, elle ne pourra jamais remplacer tes amis.
> > ⚠️ **开发阶段 / Development stage / Phase de développement**
> 
>
> **本系统仍处于开发阶段，不是生产级成品。** 功能、工具名称、配置格式、接口和行为都可能随时变化；请先在测试存档中验证，并做好备份。使用本系统即表示你理解并自行承担相关风险，同时遵守所在服务器与社区规则。
>
> **This system is still under active development and is not production-ready.** Features, tool names, configuration formats, APIs, and behavior may change without notice. Test in a disposable world and keep backups. Use at your own risk and follow all server and community rules.
>
> **Ce système est encore en cours de développement et n’est pas prêt pour la production.** Les fonctionnalités, noms d’outils, formats de configuration, API et comportements peuvent changer sans préavis. Testez dans un monde jetable et conservez des sauvegardes. Utilisez-le à vos propres risques et respectez les règles du serveur et de la communauté.

# ZacFatFish Minecraft Agent

---

### 这是什么

**ZFF MC Agent** 是一个让 AI 作为“游戏内玩家伙伴”进入 Minecraft Java 版的服务端与客户端工具链。它不是客服机器人，也不是只会聊天的 NPC；它通过真实客户端桥接读取世界、移动、挖掘、放置、战斗、开箱、使用 GUI，并把结果以结构化数据和证据返回给 Agent。

系统由三部分组成：

| 组件 | 作用 |
|---|---|
| **DSH 插件** `zff-mc-plugin` | 在 DeepSeek Harness 中提供 ZFF MC Mode、开始/暂停、自动续跑、提示词 A/B/C、管理员下线、团队面板、环境自举、工具调用统计 |
| **MCP Server** `mcp-server` | 注册 122 个 MCP 工具；负责任务路由、Job 管理、聊天、InfoTxt、记忆/存储、工具调用计数 |
| **ZFF Bridge** `zff-bridge` | NeoForge 1.21.1 客户端桥；执行真实输入、扫描、移动、战斗、自卫、自动落地水，并提供看门狗与回读校验 |

### 核心功能

#### 1. 真正的游戏内行动

- 读取玩家、世界、背包、实体、方块、容器、屏幕、伤害与环境
- 移动、寻路、挖掘、放置、合成、钓鱼、开箱、交易、使用 GUI
- 近战、远程、盾牌、逃跑、自卫
- 所有动作都走真实客户端输入，不是只改内部状态

#### 2. Job 化长任务

- 移动、挖掘、战斗、建造等长动作立即返回 `jobId`
- 用 `job_status` 查询进度、指标和终态
- 有 deadline、看门狗和 no-progress 退出，不会无限挂起
- 失败返回结构化 `error/reason`；寻路失败会附带 `layerFoot`（15×15 脚部平面）

#### 3. 安全与自卫

- `self-protect`：苦力怕靠近自动举盾；无盾则逃跑；被敌对生物攻击时反击
- **AutoMlg**：检测高处下落，自动放水；落地后自动把水收回水桶
- 真实盾牌与弓的右键/释放，服务端认可，盾有耐久消耗
- 低血、无食物、无箭、工具损坏时停止或撤退
- 管理员下线需要只读 `chat_history` 复核 `adminPlayers`

#### 4. 可观测与可验证

- 每个工具调用计数：`tool_usage` / `zff_usage` / `GET /api/zff-mc/usage`
- InfoTxt 把紧急状态、战斗、Job、fall、watchdog 注入下一次工具响应
- `COMBAT:` 保留最近多条 self-protect 操作，放水/收水不会互相覆盖
- 支持截图、evidence JSON、release SHA256

#### 5. Agent 协作

- DSH 中的 CR/SR 分工：聊天、调度、任务清单、Memory
- 自动续跑与“玩家消息只回复一次”的防刷屏规则
- 团队面板显示谁在做什么
- 支持子代理与共享 CR store

#### 6. 可选扩展与明确边界

- 完整寻路建议安装 **Baritone 1.11.3 NeoForge**（standalone + API）
- 可选：Create、Apotheosis、Serene Seasons、JEI
- **Pendulum 已废弃，不要安装**；战斗全部由 Bridge 原生实现
- 末地完整链路（要塞 → 传送门 → 水晶 → 末影龙）仍在 Ph9 开发中，尚不能宣称已全部完成

### 典型流程

```text
玩家下达目标
→ DSH 插件进入 ZFF MC Mode
→ Agent 读取世界/聊天
→ 选择工具或创建 Job
→ Bridge 在客户端执行真实动作
→ 工具返回结果/InfoTxt/证据
→ Agent 继续、撤退或报告
```

### 前置条件

```text
Windows 10/11 x64
DSH 桌面版（支持 MCP 与本地插件）
Node.js 22+
Java 21
Minecraft Java 1.21.1 + NeoForge 21.1.228
ZFF Bridge.jar
Baritone 1.11.3 NeoForge（完整寻路需要）
```

安装与启动步骤见 [release/README.md](release/README.md)。

### 文档

- [SADT 系统图](docs/SADT.md)
- [FAST 功能图](docs/FAST.md)
- [MCP 工具目录](docs/MCP-TOOLS.md)
- [Ph9 开发文档包](docs/ph9/README.md)
- [Release 三语说明](release/README.md)

---

### What it is

**ZFF MC Agent** is a server/client toolchain that lets an AI join Minecraft Java Edition as an in-game player companion. It is not a customer-support bot or a chat-only NPC. Through a real client bridge it can observe the world, move, mine, place blocks, fight, open containers, use GUIs, and return structured results and evidence to the agent.

The system has three parts:

| Component | Role |
|---|---|
| **DSH plugin** `zff-mc-plugin` | ZFF MC Mode, start/pause, auto-continuation, A/B/C prompts, admin shutdown, team panel, environment bootstrap, tool-usage stats |
| **MCP Server** `mcp-server` | 122 MCP tools; routing, jobs, chat, InfoTxt, memory/store, tool-call counters |
| **ZFF Bridge** `zff-bridge` | NeoForge 1.21.1 client bridge; real input, scanning, movement, combat, self-protection, AutoMlg, watchdog and read-back verification |

### Core features

#### 1. Real in-game actions

- Read player, world, inventory, entities, blocks, containers, screens, damage and environment
- Move, pathfind, mine, place, craft, fish, loot, trade and operate GUIs
- Melee, ranged combat, shields, escape and self-protection
- Every action uses real client input, not only internal state changes

#### 2. Job-based long tasks

- Long actions return a `jobId` immediately
- `job_status` reports progress, metrics and terminal state
- Deadlines, watchdogs and no-progress exits prevent infinite hangs
- Failures are structured; failed pathing includes `layerFoot` (15×15 foot-level plane)

#### 3. Safety and self-protection

- `self-protect`: raise a shield near a creeper, flee without a shield, counterattack hostile mobs
- **AutoMlg**: detect a dangerous fall, place water, then recover the water into the bucket after landing
- Real right-click/release for shields and bows; server-recognized and shield durability is consumed
- Stop or retreat on low health, no food, no arrows or broken tools
- Admin shutdown requires a read-only `chat_history` check against `adminPlayers`

#### 4. Observability and verification

- Per-tool call counters: `tool_usage` / `zff_usage` / `GET /api/zff-mc/usage`
- InfoTxt injects urgent state, combat, jobs, falls and watchdog events into the next tool response
- Multiple recent `COMBAT:` lines are preserved, so water placement and recovery do not overwrite each other
- Screenshots, evidence JSON and release SHA256 are supported

#### 5. Agent collaboration

- CR/SR roles in DSH: chat, scheduling, todo list and memory
- Auto-continuation with anti-spam rules: reply once per player message
- Team panel shows what each role is doing
- Subagents and a shared CR store are supported

#### 6. Optional extensions and explicit boundaries

- **Baritone 1.11.3 NeoForge** (standalone + API) is recommended for full pathfinding
- Optional: Create, Apotheosis, Serene Seasons, JEI
- **Pendulum is deprecated; do not install it.** Combat is implemented natively in the Bridge
- The full End chain (stronghold → portal → crystals → dragon) is still under Ph9 development and must not be presented as fully complete

### Typical flow

```text
Player gives a goal
→ DSH plugin enters ZFF MC Mode
→ Agent reads world/chat
→ Agent calls a tool or creates a job
→ Bridge performs real client-side actions
→ Tool returns results / InfoTxt / evidence
→ Agent continues, retreats or reports
```

### Prerequisites

```text
Windows 10/11 x64
DSH desktop (MCP + local plugin support)
Node.js 22+
Java 21
Minecraft Java 1.21.1 + NeoForge 21.1.228
ZFF Bridge.jar
Baritone 1.11.3 NeoForge (required for full pathfinding)
```

See [release/README.md](release/README.md) for installation and startup.

### Documentation

- [SADT system diagrams](docs/SADT.md)
- [FAST function diagrams](docs/FAST.md)
- [MCP tool catalog](docs/MCP-TOOLS.md)
- [Ph9 development package](docs/ph9/README.md)
- [Trilingual release guide](release/README.md)

---

### Qu’est-ce que c’est

**ZFF MC Agent** est une chaîne d’outils serveur/client qui permet à une IA de rejoindre Minecraft Java Edition comme compagnon joueur. Ce n’est ni un bot de support client ni un PNJ limité au chat. Grâce à un pont client réel, il peut observer le monde, se déplacer, miner, poser des blocs, combattre, ouvrir des conteneurs, utiliser les GUI et renvoyer des résultats structurés et des preuves à l’agent.

Le système comprend trois parties :

| Composant | Rôle |
|---|---|
| **Plugin DSH** `zff-mc-plugin` | Mode ZFF MC, démarrer/pause, continuation automatique, prompts A/B/C, déconnexion admin, panneau d’équipe, amorçage de l’environnement, statistiques d’outils |
| **MCP Server** `mcp-server` | 122 outils MCP ; routage, jobs, chat, InfoTxt, mémoire/stockage, compteurs d’appels |
| **ZFF Bridge** `zff-bridge` | Pont client NeoForge 1.21.1 ; entrées réelles, scan, déplacement, combat, auto-protection, AutoMlg, watchdog et vérification par relecture |

### Fonctionnalités principales

#### 1. De vraies actions en jeu

- Lire le joueur, le monde, l’inventaire, les entités, les blocs, les conteneurs, les écrans, les dégâts et l’environnement
- Se déplacer, pathfinder, miner, poser, fabriquer, pêcher, piller, échanger et utiliser les GUI
- Combat au corps à corps et à distance, boucliers, fuite et auto-protection
- Toutes les actions passent par de vraies entrées client, pas seulement par un état interne

#### 2. Tâches longues sous forme de jobs

- Les actions longues renvoient immédiatement un `jobId`
- `job_status` rapporte la progression, les métriques et l’état final
- Des délais, watchdogs et sorties sans progression évitent les blocages infinis
- Les échecs sont structurés ; un échec de pathfinding inclut `layerFoot` (plan des pieds 15×15)

#### 3. Sécurité et auto-protection

- `self-protect` : lever le bouclier près d’un creeper, fuir sans bouclier, contre-attaquer les monstres hostiles
- **AutoMlg** : détecter une chute dangereuse, poser de l’eau, puis récupérer l’eau dans le seau après l’atterrissage
- Vrais clics/releases pour boucliers et arcs ; le serveur les reconnaît et la durabilité du bouclier est consommée
- S’arrêter ou battre en retraite si vie basse, pas de nourriture, pas de flèches ou outil cassé
- La déconnexion admin exige une vérification en lecture seule de `chat_history` par rapport à `adminPlayers`

#### 4. Observabilité et vérification

- Compteurs d’appels par outil : `tool_usage` / `zff_usage` / `GET /api/zff-mc/usage`
- InfoTxt injecte l’état urgent, le combat, les jobs, les chutes et le watchdog dans la prochaine réponse d’outil
- Plusieurs lignes `COMBAT:` récentes sont conservées : pose et récupération de l’eau ne s’écrasent pas
- Captures d’écran, JSON de preuve et SHA256 de release pris en charge

#### 5. Collaboration d’agents

- Rôles CR/SR dans DSH : chat, planification, liste de tâches et mémoire
- Continuation automatique avec des règles anti-spam : une seule réponse par message joueur
- Panneau d’équipe indiquant ce que fait chaque rôle
- Sous-agents et store CR partagé pris en charge

#### 6. Extensions optionnelles et limites explicites

- **Baritone 1.11.3 NeoForge** (standalone + API) est recommandé pour un pathfinding complet
- Optionnels : Create, Apotheosis, Serene Seasons, JEI
- **Pendulum est obsolète ; ne l’installez pas.** Le combat est implémenté nativement dans le Bridge
- La chaîne complète de l’End (forteresse → portail → cristaux → dragon) est encore en développement Ph9 et ne doit pas être présentée comme terminée

### Flux typique

```text
Le joueur donne un objectif
→ Le plugin DSH passe en mode ZFF MC
→ L’agent lit le monde/le chat
→ L’agent appelle un outil ou crée un job
→ Le Bridge effectue de vraies actions côté client
→ L’outil renvoie résultats / InfoTxt / preuves
→ L’agent continue, se retire ou fait un rapport
```

### Prérequis

```text
Windows 10/11 x64
DSH bureau (MCP + plugins locaux)
Node.js 22+
Java 21
Minecraft Java 1.21.1 + NeoForge 21.1.228
ZFF Bridge.jar
Baritone 1.11.3 NeoForge (nécessaire pour le pathfinding complet)
```

Voir [release/README.md](release/README.md) pour l’installation et le démarrage.

### Documentation

- [Schémas SADT](docs/SADT.md)
- [Schémas FAST](docs/FAST.md)
- [Catalogue des outils MCP](docs/MCP-TOOLS.md)
- [Dossier de développement Ph9](docs/ph9/README.md)
- [Guide de release trilingue](release/README.md)

---

## Links / 链接 / Liens

- [Release guide / 发布说明 / Guide de release](release/README.md)
- [Install / 安装 / Installation](release/README.md)
- [Source: MCP Server](mcp-server/)
- [Source: ZFF Bridge](zff-bridge/)
- [Source: DSH plugin](zff-mc-plugin/)
- [Docs](docs/)
