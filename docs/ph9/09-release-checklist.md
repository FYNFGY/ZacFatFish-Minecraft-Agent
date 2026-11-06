# 09 — Release Checklist

## 1. 构建前

```text
[ ] 所有 Ph9 P0 代码已合并
[ ] MCP-TOOLS.md 与 server.js 一致
[ ] 04-tool-contracts 与实现一致
[ ] 没有遗留 debug 工具/临时脚本
[ ] 没有把 Pendulum/Create/Apotheosis 变成硬依赖
```

## 2. 测试

```text
[ ] node test/run-all.mjs --slow 全绿
[ ] 真实进程测试通过
[ ] Ph9 P0 用例 pass 或明确 blocked
[ ] 至少一次安全/路径/战斗回归
[ ] 至少一次 Pendulum 可选缺失测试
```

## 3. 构建

```powershell
cd "D:\mc\MCP Development"
node scripts\build-release.mjs --from-source
```

必须产出：

```text
release/ZFF Bridge.jar
release/ZFF MCP Server.zip
release/zff-mc-plugin
release/SHA256SUMS.txt
release/INSTALL.md
release/README.md
release/start-mcp.ps1
release/stop-mcp.ps1
```

## 4. 内容检查

### Bridge jar

```text
[ ] META-INF/neoforge.mods.toml
[ ] dev/mcp/bridge/BridgeRuntime.class
[ ] 无测试存档/临时类
```

### MCP zip

```text
[ ] src/server.js
[ ] package.json
[ ] start-mcp.ps1
[ ] stop-mcp.ps1
[ ] node_modules
```

### 插件

```text
[ ] index.js
[ ] package.json
[ ] client.js
[ ] defaults/presets/locale
```

## 5. 哈希

```powershell
Get-Content release\SHA256SUMS.txt
```

写入：

```text
docs/ph9/13-acceptance.md
docs/Ph9/REPORT.md（如创建）
evidence/ph9-*.json
```

## 6. 手工冒烟

```text
[ ] 解压 MCP zip 到新目录
[ ] start-mcp.ps1 能启动
[ ] 游戏进入存档
[ ] get_state 正常
[ ] 一个 Job 正常完成
[ ] stop-mcp.ps1 能全杀
[ ] 25566 随游戏关闭
```

## 7. 发布后

```text
[ ] 更新 README/INSTALL
[ ] 告知用户 release 路径与 SHA256
[ ] 保留 Ph8/Ph9 evidence
[ ] 记录未完成项与下一轮入口
```

## 8. 禁止

- 用 `/summon` / `/kill` / `/tp` 结果冒充生存通过
- 把“手动命令跑通”当作工具自动通过
- 在没有 evidence 的情况下宣称“全测试通过”
- 删除旧 phase 文档
