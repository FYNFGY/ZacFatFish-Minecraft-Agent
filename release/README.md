# ZFF MC Agent — Release / 发布说明 / Notes de version

> Release contents / 发布内容 / Contenu :
>
> - `ZFF Bridge.jar` — NeoForge client + server bridge
> - `ZFF MCP Server.zip` — Node.js MCP server (bundled `node_modules`)
> - `zff-mc-plugin/` — DSH plugin
> - `start-mcp.ps1` / `stop-mcp.ps1` — optional manual server control
> - `SHA256SUMS.txt` — checksums / 校验和 / sommes de contrôle
>
> Source code / 源代码 / Code source is in the repository root:
> `mcp-server/`, `zff-bridge/`, `zff-mc-plugin/`, `docs/`.

---

### 1. 前置条件

| 项目 | 要求 |
|---|---|
| 操作系统 | Windows 10 / 11 x64 |
| DSH | DeepSeek Harness 桌面版，支持 MCP 与本地插件 |
| Node.js | **22 或更高**（`node -v` 可运行；也可使用 DSH 自带 Node） |
| Java | **Java 21** |
| Minecraft | **Java 版 1.21.1** |
| NeoForge | **21.1.228** |
| 游戏桥接模组 | **ZFF Bridge.jar**（本 release 提供） |
| 寻路前置 | **Baritone 1.11.3 NeoForge**：`baritone-standalone-neoforge-1.11.3.jar` + `baritone-api-neoforge-1.11.3.jar` |
| 其他 | 单机测试请在“对局域网开放”中允许作弊；服务器测试需遵守服务器规则 |

> **Pendulum 已废弃，不要安装。** 本版本战斗、盾牌、弓、自动落地水全部由 Bridge 原生实现；Pendulum/Jupiter/Rhino 不是依赖。
>
> 可选模组：Create、Apotheosis、Serene Seasons、JEI。它们只影响对应扩展功能，不安装不影响基础玩法。

### 2. 文件放置

```text
release/
├─ ZFF Bridge.jar          → 游戏 .minecraft/mods/
├─ ZFF MCP Server.zip      → DSH 工作目录根下（推荐，插件会自动解压启动）
├─ zff-mc-plugin/          → DSH profile（通过 dsh plugin 安装）
├─ start-mcp.ps1           → 可选：手动启动 MCP
├─ stop-mcp.ps1            → 可选：停止 MCP
└─ SHA256SUMS.txt
```

Baritone 两个 jar 也放进同一个 `mods/` 目录。

### 3. 安装 DSH 插件

把 `zff-mc-plugin` 放到一个**非中文、非 OneDrive 同步**路径，例如：

```text
C:\zff\zff-mc-plugin
```

然后执行：

```powershell
dsh plugin --profile desktop add "link:C:/zff/zff-mc-plugin"
```

安装后检查 DSH profile 的 `package.json`，应出现类似：

```json
"@local/zff-mc": "link:C:/zff/zff-mc-plugin"
```

### 4. 启动 MCP Server

**推荐方式：让插件自动启动。**

1. 把 `ZFF MCP Server.zip` 放到你准备用于对话的**工作目录根下**。
2. 在 DSH 新建会话，模式选择 **ZFF MC Mode**。
3. 插件在第一次进入 ZFF MC Mode / 点“开始游戏”时会自动解压并启动 MCP。

也可以手动启动（排障时使用）：

```powershell
# 在 release 目录下；先把 zip 解压为 mcp-server/
powershell -ExecutionPolicy Bypass -File .\start-mcp.ps1 -Workspace "C:\你的工作目录"
```

检查：

```powershell
(Invoke-WebRequest http://127.0.0.1:57610/health -UseBasicParsing).Content
# ok
```

> **启动顺序很重要：先启动 MCP，再启动 DSH。**
> 如果 DSH 启动时 MCP 还没起来，`mcp__mc__*` 工具可能注册不上；此时需要完全退出 DSH 再重新打开。

### 5. 安装游戏模组并进入世界

1. 把 `ZFF Bridge.jar` 和两个 Baritone jar 放入目标整合包的 `mods/`。
2. 启动 Minecraft，进入存档或服务器。
3. 单机测试：`Esc` → **Open to LAN** → `Allow Commands: ON` → **Start LAN World**。
4. 确认游戏内没有报 `Incompatible client! Please use NeoForge 21.1.228`。

### 6. 让 Agent 接管游戏

1. 保持 MCP 正在运行，启动 DSH。
2. 新建会话，模式选择 **ZFF MC Mode**。
3. 输入框右侧应出现：

```text
▶ 开始游戏   ·   设置   ·   ?
```

4. 点击 **▶ 开始游戏**（按钮变为 ⏸ 进行中）。
5. Agent 会先调用 `get_state` 确认在线，再通过 `chat_turn` 读取聊天，然后开始执行任务。
6. 想停止时点击 **⏸**；停止后不会再自动续跑，但仍可以继续问问题。

### 7. 工具调用统计

插件提供：

```text
zff_usage { limit: 100 }
```

MCP 侧对应工具：

```text
tool_usage { limit: 100 }
```

也可读取：

```text
GET /api/zff-mc/usage
```

统计持久化在：

```text
<工作目录>/runtime/tool-usage.json
```

### 8. 常见问题

| 现象 | 处理 |
|---|---|
| `mcp__mc__*` 工具不存在 | 先起 MCP，再完全退出并重开 DSH |
| MCP 健康检查失败 | 检查 57610 端口、Node 22、`runtime` 日志 |
| 游戏内 bridge 不注册 | 确认 `ZFF Bridge.jar` 在 mods，且 NeoForge 为 21.1.228 |
| 寻路失败 | 确认 Baritone standalone + API 都已安装；失败时 `job_status` 会附 `layerFoot` |
| `server_workspace_mismatch` | 确保 MCP `--workspace` 与 DSH 会话工作目录一致 |
| 单机指令失败 | 重新 Open to LAN 并允许作弊 |

---


### 1. Prerequisites

| Item | Requirement |
|---|---|
| OS | Windows 10 / 11 x64 |
| DSH | DeepSeek Harness desktop with MCP and local-plugin support |
| Node.js | **22+** (`node -v` must work; the Node bundled with DSH is also acceptable) |
| Java | **Java 21** |
| Minecraft | **Java Edition 1.21.1** |
| NeoForge | **21.1.228** |
| Game bridge | **ZFF Bridge.jar** (included) |
| Pathfinding prerequisite | **Baritone 1.11.3 NeoForge**: `baritone-standalone-neoforge-1.11.3.jar` + `baritone-api-neoforge-1.11.3.jar` |
| Other | For single-player testing, allow cheats via “Open to LAN”; on servers, follow server rules |

> **Pendulum is no longer supported. Do not install it.** Combat, shields, bows and the automatic water-bucket landing are implemented natively in the Bridge. Pendulum/Jupiter/Rhino are not dependencies.
>
> Optional mods: Create, Apotheosis, Serene Seasons, JEI. They only enable optional integrations; the base system works without them.

### 2. File placement

```text
release/
├─ ZFF Bridge.jar          → Minecraft .minecraft/mods/
├─ ZFF MCP Server.zip      → root of your DSH workspace (recommended; plugin auto-extracts it)
├─ zff-mc-plugin/          → DSH profile (install with dsh plugin)
├─ start-mcp.ps1           → optional manual MCP start
├─ stop-mcp.ps1            → optional MCP stop
└─ SHA256SUMS.txt
```

Place both Baritone jars in the same `mods/` directory.

### 3. Install the DSH plugin

Put `zff-mc-plugin` in a path **without non-ASCII characters** and **outside OneDrive-synced folders**, for example:

```text
C:\zff\zff-mc-plugin
```

Then run:

```powershell
dsh plugin --profile desktop add "link:C:/zff/zff-mc-plugin"
```

Verify the DSH profile `package.json` contains something like:

```json
"@local/zff-mc": "link:C:/zff/zff-mc-plugin"
```

### 4. Start the MCP server

**Recommended: let the plugin start it.**

1. Put `ZFF MCP Server.zip` in the **root of the workspace** you will use for DSH.
2. Create a new DSH session and select **ZFF MC Mode**.
3. On first entering ZFF MC Mode / clicking “Start Game”, the plugin extracts and starts the MCP server.

Manual start (for troubleshooting):

```powershell
# In the release directory; first extract the zip as mcp-server/
powershell -ExecutionPolicy Bypass -File .\start-mcp.ps1 -Workspace "C:\your\workspace"
```

Check:

```powershell
(Invoke-WebRequest http://127.0.0.1:57610/health -UseBasicParsing).Content
# ok
```

> **Start order matters: start MCP first, then start DSH.**
> If MCP is not running when DSH starts, the `mcp__mc__*` tools may never register. In that case, fully quit and restart DSH.

### 5. Install the game mod and enter a world

1. Put `ZFF Bridge.jar` and both Baritone jars into the target instance's `mods/`.
2. Launch Minecraft and enter a single-player world or a server.
3. Single-player testing: `Esc` → **Open to LAN** → `Allow Commands: ON` → **Start LAN World**.
4. Make sure the log does not contain `Incompatible client! Please use NeoForge 21.1.228`.

### 6. Let the Agent take over

1. Keep MCP running, then start DSH.
2. Create a new session and select **ZFF MC Mode**.
3. The input bar should show:

```text
▶ Start Game   ·   Settings   ·   ?
```

4. Click **▶ Start Game** (it becomes ⏸ Running).
5. The agent calls `get_state` to confirm it is online, reads chat through `chat_turn`, and starts working.
6. Click **⏸** to stop automatic continuation. You can still ask questions afterwards.

### 7. Tool usage statistics

Plugin tool:

```text
zff_usage { limit: 100 }
```

MCP tool:

```text
tool_usage { limit: 100 }
```

HTTP endpoint:

```text
GET /api/zff-mc/usage
```

Persisted at:

```text
<workspace>/runtime/tool-usage.json
```

### 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `mcp__mc__*` tools missing | Start MCP first, then fully restart DSH |
| MCP health check fails | Check port 57610, Node 22, and `runtime` logs |
| Bridge not registered in game | Verify `ZFF Bridge.jar` in `mods/` and NeoForge 21.1.228 |
| Pathfinding fails | Ensure both Baritone jars are installed; failed jobs include `layerFoot` |
| `server_workspace_mismatch` | Make the MCP `--workspace` match the DSH session workspace |
| Single-player commands fail | Re-open to LAN with cheats enabled |

---


### 1. Prérequis

| Élément | Exigence |
|---|---|
| Système | Windows 10 / 11 x64 |
| DSH | DeepSeek Harness bureau, avec prise en charge MCP et plugins locaux |
| Node.js | **22 ou supérieur** (`node -v` doit fonctionner ; le Node intégré à DSH convient aussi) |
| Java | **Java 21** |
| Minecraft | **Édition Java 1.21.1** |
| NeoForge | **21.1.228** |
| Pont de jeu | **ZFF Bridge.jar** (fourni) |
| Prérequis de pathfinding | **Baritone 1.11.3 NeoForge** : `baritone-standalone-neoforge-1.11.3.jar` + `baritone-api-neoforge-1.11.3.jar` |
| Autre | En solo, autorisez les commandes via « Open to LAN » ; sur serveur, respectez les règles |

> **Pendulum n'est plus pris en charge. Ne l'installez pas.** Les combats, boucliers, arcs et l'atterrissage automatique à l'eau sont implémentés nativement dans le Bridge. Pendulum/Jupiter/Rhino ne sont pas des dépendances.
>
> Mods optionnels : Create, Apotheosis, Serene Seasons, JEI. Ils n'activent que des intégrations optionnelles ; le système de base fonctionne sans eux.

### 2. Placement des fichiers

```text
release/
├─ ZFF Bridge.jar          → .minecraft/mods/ du jeu
├─ ZFF MCP Server.zip      → racine de l'espace de travail DSH (recommandé ; le plugin l'extrait)
├─ zff-mc-plugin/          → profil DSH (installation avec dsh plugin)
├─ start-mcp.ps1           → démarrage manuel facultatif
├─ stop-mcp.ps1            → arrêt manuel facultatif
└─ SHA256SUMS.txt
```

Placez les deux jars Baritone dans le même dossier `mods/`.

### 3. Installer le plugin DSH

Placez `zff-mc-plugin` dans un chemin **sans caractères non ASCII** et **hors d'un dossier synchronisé OneDrive**, par exemple :

```text
C:\zff\zff-mc-plugin
```

Puis exécutez :

```powershell
dsh plugin --profile desktop add "link:C:/zff/zff-mc-plugin"
```

Vérifiez que le `package.json` du profil DSH contient :

```json
"@local/zff-mc": "link:C:/zff/zff-mc-plugin"
```

### 4. Démarrer le serveur MCP

**Méthode recommandée : laissez le plugin le démarrer.**

1. Placez `ZFF MCP Server.zip` à la **racine de l'espace de travail** utilisé par DSH.
2. Créez une session DSH et choisissez **ZFF MC Mode**.
3. À la première entrée dans ZFF MC Mode / au clic sur « Start Game », le plugin extrait et démarre le serveur MCP.

Démarrage manuel (dépannage) :

```powershell
# Dans le dossier release ; extrayez d'abord le zip sous mcp-server/
powershell -ExecutionPolicy Bypass -File .\start-mcp.ps1 -Workspace "C:\votre\workspace"
```

Vérification :

```powershell
(Invoke-WebRequest http://127.0.0.1:57610/health -UseBasicParsing).Content
# ok
```

> **L'ordre est important : démarrez MCP avant DSH.**
> Si MCP n'est pas lancé au démarrage de DSH, les outils `mcp__mc__*` peuvent ne jamais s'enregistrer. Dans ce cas, quittez complètement DSH puis relancez-le.

### 5. Installer le mod et entrer dans un monde

1. Placez `ZFF Bridge.jar` et les deux jars Baritone dans le `mods/` de l'instance cible.
2. Lancez Minecraft et entrez dans un monde solo ou un serveur.
3. Test solo : `Échap` → **Open to LAN** → `Allow Commands: ON` → **Start LAN World**.
4. Vérifiez que le journal ne contient pas `Incompatible client! Please use NeoForge 21.1.228`.

### 6. Laisser l'Agent prendre le contrôle

1. Gardez MCP en marche, puis démarrez DSH.
2. Créez une nouvelle session et choisissez **ZFF MC Mode**.
3. La barre de saisie doit afficher :

```text
▶ Start Game   ·   Settings   ·   ?
```

4. Cliquez sur **▶ Start Game** (le bouton devient ⏸ Running).
5. L'agent appelle `get_state` pour vérifier la connexion, lit le chat via `chat_turn`, puis commence à travailler.
6. Cliquez sur **⏸** pour arrêter la continuation automatique. Vous pouvez toujours poser des questions ensuite.

### 7. Statistiques d'utilisation des outils

Outil du plugin :

```text
zff_usage { limit: 100 }
```

Outil MCP :

```text
tool_usage { limit: 100 }
```

Point d'accès HTTP :

```text
GET /api/zff-mc/usage
```

Persisté dans :

```text
<workspace>/runtime/tool-usage.json
```

### 8. Dépannage

| Symptôme | Solution |
|---|---|
| Outils `mcp__mc__*` absents | Démarrez MCP d'abord, puis redémarrez complètement DSH |
| Échec du health check MCP | Vérifiez le port 57610, Node 22 et les logs `runtime` |
| Bridge non enregistré dans le jeu | Vérifiez `ZFF Bridge.jar` dans `mods/` et NeoForge 21.1.228 |
| Échec du pathfinding | Installez les deux jars Baritone ; les jobs en échec incluent `layerFoot` |
| `server_workspace_mismatch` | Faites correspondre le `--workspace` MCP et l'espace de travail DSH |
| Commandes solo refusées | Réouvrez le monde en LAN avec les commandes autorisées |

---

## Version / Checksums

```text
ZFF MCP Server.zip
033d235b7945283f8767ec124c48541d29d7571a4ddf4d68d13029926a92be04

ZFF Bridge.jar
7d72e2853f2934e49c1beacf50f6b5ef88d400dca33fd9cb89d9a674991c349d
```

See `SHA256SUMS.txt` for the authoritative values.
