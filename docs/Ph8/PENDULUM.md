# Pendulum 集成记录（Ph8）

> 状态：**已安装并验证 capability**（2026-11-04）
> 结论：Pendulum 是可选模组；缺少时只禁用 Pendulum 专属战斗工具，不影响 Bridge 内建 AutoAim 和原版武器。

## 已安装

- Minecraft 1.21.1 / NeoForge
- `pendulum-1.0-1.21.1-neoforge.jar`（145571 bytes）
- `jupiter-2.3.7-1.21.1-neoforge.jar`（278988 bytes）
- `rhino-2101.2.7-build.81.jar`（已有）

路径：

```text
D:\mc\MCP Development\ph7-env\client\mods\pendulum-1.0-1.21.1-neoforge.jar
D:\mc\MCP Development\ph7-env\client\mods\jupiter-2.3.7-1.21.1-neoforge.jar
```

## 能力探测

实测 `query_env_info` / `combat_status`：

```json
{
  "pendulum": {"available": true, "reason": "loaded"},
  "autoaim": {"enabled": true, "builtIn": true, "canDisable": false}
}
```

Pendulum 的 MCP server 默认关闭（`mcp_enabled=false`），不影响 bridge 战斗。

## 战斗测试

### 场景 1：钻石套 + 钻石剑，五只僵尸，夜晚普通

- 玩家：钻石头盔/胸甲/护腿/靴子 + 钻石剑
- 僵尸：5 只 `NoAI`，夜晚/normal
- Pendulum 脚本 `pendulum/combat_test.js`：
  - 查找附近 10 格内 zombie
  - `lookAt` + `attack`
  - 日志：`[Pendulum] pendulum attacked 5`
- 结果：僵尸全部清除；获得 `Monster Hunter` / `Intro to L2Hostility` 进度。
- 说明：测试用 resistance/strength 保证可重复，不作为正常难度平衡结论。

### 场景 2：60 格开外射杀

- 目标：60.01 格僵尸
- 方法：`kill_target_far` 真实弓蓄力 + `MultiPlayerGameMode.releaseUsingItem`；移动 AI 接近到 24 格内后射击
- 结果：**通过**，`outcome=target_killed`，`attacks=4, hits=3`，箭数 64 → 60
- 说明：没有 `/summon`、没有管理员命令；箭是原版 release 生成并消耗的。
- 任务结束清理：`releaseUse()` 同时清 `keyUse`、注入鼠标和 `gameMode.releaseUsingItem`；实测 `using=false, useItem=air`，不会保持开弓。

## 生存档实测

- 纯净 NeoForge 生存档（仅 Baritone/Pendulum/Jupiter/Rhino/ZFF Bridge）
- 装备由指令给予：钻石套 + 钻石剑 + 副手盾 + 弓/箭
- `kill_target_close`：target_killed
- `kill_target_far`：60 格 target_killed，箭数 64→59
- `escape`：safe
- 远程结束：`using=false`、`useRemaining=0`、`useItem=air`

## Pendulum 自带 MCP Server

已启用：

```json
{"mcp_enabled": true, "mcp_port": 25566}
```

ZFF MCP 不启动、不接管 Pendulum，只路由：

- `pendulum_mcp_status`：25566 可达性 + `health` / `script/status`
- `pendulum_eval {code}`：转发 `tools/call script/eval`
- `pendulum_abort`：转发 `script/abort`

每次调用使用独立的短 TCP 连接，返回后立即关闭；没有常驻 socket、没有遗留 Node 进程。Pendulum 的 MCP server 在 Minecraft 客户端进程内，客户端退出时 25566 随进程关闭。实测关闭客户端后端口消失，`pendulum_mcp_status` 返回 `available:false`。

## 禁止

- 不把 Pendulum 变成硬依赖；缺失时 server/mod 不能启动失败。
- 不默认在公开服务器开启外挂类功能；由玩家自行承担风险。
- 不实现“内置自动近战”来冒充 Pendulum。

