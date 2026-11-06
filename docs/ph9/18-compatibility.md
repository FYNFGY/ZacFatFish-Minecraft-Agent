# 18 — 兼容性矩阵

| 组件 | 版本 | 必需 | 缺失时 |
|---|---|---|---|
| Minecraft | 1.21.1 | 是 | 无法启动 |
| NeoForge | 21.1.228 | 是 | Bridge 不加载 |
| Java | 21 | 是 | 无法启动 |
| Node.js | 22 | 是（MCP） | MCP 不启动 |
| ZFF Bridge | release 对应版本 | 是 | 无游戏控制 |
| Baritone standalone | 1.11.3 NeoForge | 否 | 长距离寻路降级 |
| Baritone API | 1.11.3 NeoForge | 否 | 部分 Baritone API 降级 |
| Create | 1.21.1 对应版本 | 否 | create_* 降级 |
| Apotheosis | 1.21.1 对应版本 | 否 | 专用 GUI 降级 |
| Serene Seasons | 1.21.1 对应版本 | 否 | season.available=false |
| JEI | 1.21.1 对应版本 | 否 | recipe_lookup 仅原版 |

> Pendulum/Jupiter/Rhino 已废弃，不再安装、不再使用，不列入兼容矩阵。

## 环境命名

```text
VANILLA : NeoForge + ZFF Bridge
CLEAN   : VANILLA + Baritone（Pendulum 已移除）
FULL    : 当前整合包（Pendulum 已移除）
```

## 版本检查命令

```powershell
Get-ChildItem <instance>\mods | Where-Object Name -match 'bridge|baritone'
```

## 注意

- 不要安装 Pendulum Fabric/Forge/NeoForge 版本。
- Baritone standalone 与 API 建议成对安装。
- Create/Apotheosis 的专用适配失败不应阻塞普通桥接。
