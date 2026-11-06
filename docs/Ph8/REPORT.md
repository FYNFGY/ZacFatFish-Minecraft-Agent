# Ph8 实现进展报告

> 日期：2026-11-04  
> 状态：核心功能已实现；GUI/Create/Apotheosis 与 Pendulum 的完整真机覆盖仍在补齐。  
> 证据：[../evidence/ph8-progress-2026-11-04.json](../evidence/ph8-progress-2026-11-04.json)

## 已完成

### 生命周期
- `stopMcp`：state pid/daemon + 端口 owner + 后代 + 当前 workspace node 进程；端口/进程双清才返回 `ok:true`。
- `stopMcpSync`：插件 `stop()` 同步兜底。
- server `--exit-without-instance 60`：客户端关闭后 60s（加 20s 心跳过期）退出；实测日志 `no live Minecraft instance for 60086 ms - exiting`。
- release real-process test：14/14。
- `stop-mcp.ps1` 已加入 release。

### 等待
- `wait_events` 恢复，server 端硬上限 10s；`timeoutSeconds:60` 实测 `waitedMs=10064`、`capped:true`。

### 地形
- `tower` 执行前查询 h+2 列；火把测试立即 `tower_blocked`，column=3，未创建 job。
- `query_layer` / `query_layer_foot`：15×15，225 格，0.3s 左右。
- `reachable_synthese`：15×15×4 + d/t 额外层，返回标记网格；本地算法 **13/13**（含用户示例 1、示例 4）。
- `query_env_info`：声音/游戏+现实时间/天气/Serene Seasons；实测 8 条声音、季节 SPRING。

### M2
- `fish`：单次抛竿/等咬钩/收竿；实测 `j-2-fa730e` done，cast/reel SUCCESS，未自动连钓。
- `tool_low`：BlockMiner 在剩余 1 次时停止；实测 `TOOL: minecraft:diamond_pickaxe 耐久耗尽 (1/1561)` + job failed tool_low，未自动换镐。
- GUI：`gui_describe/click/set/click_at/scroll/close`；原版 CraftingScreen 实测 3 widgets/46 slots；**Apotheosis** 已实测 `Augmenting`（5/38）、`Reforging`（2/42）、`Salvaging`（3/54）、`GemCutting`（3/40）、`GemCase`（26/50）；`gui_click` 可用。
- **Create 转速控制器**：`create_probe`/`create_scroll` 验证 `KineticScrollValueBehaviour 16 -> 64`。
- **Create 物理化**：`simulated:physics_assembler` 空手右键返回 SUCCESS，无 GUI。
- `use_block hand:"empty"` 已实测（空手交互后恢复原槽）。

### M3
- `combat_attack`：近战冷却 + 弓/弩/三叉戟蓄力 + 内建 AutoAim；近战 axe、弓（charge=20）、十字弩（25）、三叉戟（10）均已释放。
- `combat_auto`：扫描 EnvSampler 的 Enemy id，启动最多 N 个 combat_attack；两只僵尸均 done。
- `AutoAim` 无开关，内建；Pendulum 1.0 + Jupiter 2.3.7 已安装，`available:true`。
- Pendulum 五僵尸脚本：`pendulum attacked 5`，全部清除。
- `kill_target_close`：实测走向目标、4 attacks / 2 hits、`target_killed`，返回 health/armor。
- `escape`：6 个敌人时 threshold=2，最终 `safe`，20x20 安全。
- Pendulum 自带 MCP server 已启用（25566）；ZFF 只路由 `pendulum_mcp_status` / `pendulum_eval` / `pendulum_abort`，无常驻 socket。关闭客户端后 25566 消失，状态返回 `available:false`。
- 纯净实例（仅 NeoForge 1.21.1 + Baritone + Pendulum/Jupiter/Rhino + ZFF Bridge）实测 `kill_target_far`：4 attacks / 2 hits，`target_killed`，箭数 64→60。
- 生存档全套实测：近战 `target_killed`、远程 60 格 `target_killed`（箭数 64→59）、`escape safe`；远程结束后 `using=false / useItem=air`，不再保持开弓。
- 演示：连续 5 次 `kill_target_close` 分别击杀 5 只僵尸（j-j…j-n，均 `target_killed`）；开启 `self_protect` 后被僵尸伤害，自动生成 `kill_target_close` 反击并击杀（j-o-d35cbc，3 attacks / 2 hits）。
- 移动 AI 僵尸 10 只：10/10 `target_killed`；`kill_target_close` 无命中计时改为 tick 计数 `noHitTicks >= 200`，不再依赖墙钟，避免目标移动/异常伤害导致任务迟迟不退出。
- 苦力怕格挡（生存模式、基岩平台）：无盾爆炸 3.13 伤害（20→16.87）；有盾 + self-protect 爆炸 **0 伤害**，health 20→20，盾牌 damage 11。此前创造模式结果作废。
- 索敌路径：新增本地可行走 BFS（<16 步）；墙后 6 格僵尸 `entity_not_found`，移到同侧后 `target_killed`。
- 远程自卫：`kill_target_far` 在追击和每箭后 700ms 主动举盾，拉弓时放下；5 只烈焰人 5/5 击杀，玩家最终 3.52 HP。
- `kill_target_far`：60 格实测 `target_killed`（4 attacks / 3 hits，箭数 64→60）；真实弓蓄力/release，移动 AI 接近到 24 格，无 `/summon`。
- `self_protect`：远程攻击运行中被僵尸伤害时，far job 已 `cancelled` 并转入 close 自卫；无近战武器时正确返回 `blocked`。

## 未完成 / 需要下一轮

1. GUI Create `ValueSettingsScreen` 的实际 GUI 滑动（控制器已通过 `create_probe/create_scroll`；物理化已通过空手 SUCCESS）。
2. 三叉戟/十字弩已通过；仅需补更多真实移动目标/掩体场景。
3. Pendulum 五僵尸已通过；60 格射杀未命中，需继续定位。
4. `reachable_synthese` 用户示例 2–3、5 的逐格断言。
5. 完整 evidence 文件按 `TEST-CASES.md` 的 137 个用例补齐。

## 自动化

```text
node test/run-all.mjs --slow
11/11 suites passed
真实进程 14/14
```

## release

- `ZFF MCP Server.zip` `7cae58627ac6fe5be40ed83ead1068c63813547d18b3586d9f29434ee4de5809`
- `ZFF Bridge.jar` `b1eb7e461e1c8351d512c070429603dcdd1fdc890ba9a8bc1a807e8f290f6e23`
