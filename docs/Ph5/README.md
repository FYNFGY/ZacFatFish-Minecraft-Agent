# Ph5 交接包（自包含）

这是给下一个接手的 AI 的完整资料。从 `agent-prompt5.md` 开始读，按里面的任务干。

| 文件 | 内容 |
|---|---|
| `agent-prompt5.md` | **先读这个**：完整工作总回顾（Ph4 + 本轮修复 + Ph5 已完成）+ 下一步任务 T1-T9（服务端模组拆分、SavedData、看门狗入 mod、无端口通信、CR 联调、需求缺口、水下原语）+ 环境/命令/坑 |
| `ROUND2-REPORT.md` | **第二轮总报告（T1-T5/T6/T7-D5/T9 的结果、发现并修掉的真问题、环境命令、剩余项）** |
| `REPORT-T1.md` .. `REPORT-T9.md` | 每个任务的 3-10 行小报告（做了什么 / 验收 / 证据） |
| `REQUIREMENT.md` | 细化后的需求规格（每条需求 → 工具/行为/验收/当前状态） |
| `PLAN.md` | Ph5 开发计划（水下原语 → 重试 TestA → 需求缺口 → 收尾） |
| `TOOLS.md` | 当前 **84** 个 MCP 工具的自动生成参考（用途 + schema） |
| `AGENT-ARCHITECTURE.md` | SR/CR/AR/ESRS/ESRA 架构、组件落点、协议、mod 侧要求 |
| `PHASE5-PROGRESS.md` | 第一轮 Agent 架构 + 服务端 mod 的已实现/已验证/待办（末尾有第二轮索引） |
| `ARCHITECTURE.md` | 代码地图（mod / MCP server / daemon）、协议、构建/重启流程、已知坑；末尾是 Ph5 双端拆分后的新地图 |
| `ISSUES-PH4.md` | Ph4 的问题登记（P0/P1/P2，含每条的修法与证据） |
| `FIXES-2026-10-31.md` | 上轮修复：重启保存、植物清理、`place_boat`/`boat_sail` |
| `WRAPUP-2026-10-31.md` | 收尾：`save_world`、daemon `/watch`、`collect_drops` 3D 兜底、船实体轮询 |
| `TESTA-RESULT.md` | Test A 结论：强堡在海底 → **做不到**，以及需要哪些原语才能做 |
| `TESTB4-RESULT.md` | Test B v2 结论：生存钻石通过（证据、返程、注意事项） |
| `EXPERIENCE-PH4.md` | 前四阶段踩过的坑与经验（读一遍省很多时间） |
| `PLAN-PH4.md` | Ph4 原始计划（了解验收标准的历史来源） |

## 当前优先级（用户指示）

**Agent 架构 + 服务端模组优先**（见 `AGENT-ARCHITECTURE.md` / `PHASE5-PROGRESS.md`）；
开发期 **CR 关闭**（不要设 `ZFF_CR=1`），测试任务由用户分发。
第二轮已把 T1-T5 的架构项全部落地（见 `ROUND2-REPORT.md`），剩下的是 **T8 水下原语**与 C4/C6/C7。

## 一句话现状

Ph4 全部验收通过；Test A 因强堡在海底标记"做不到"；Ph5 的 T1-T7/T9 已实现并留下证据
（一个 mod 双端、服务端 SavedData、mod 看门狗、无端口通道、`/zff stop` 事件、CR 联调、时间天气感知），
**只剩水下原语 `dig_sealed_shaft` 的实机验收和 E2-E4**。

## 交接时必须知道的三件事

1. 重启客户端只走 `clean-start.ps1`（它先 `save_world`）；**注意本整合包没有 `/save-all`**，
   `save_world` 现在直接调 `MinecraftServer.saveEverything(true,true,false)` 并等落盘，别改回发命令。
2. Java 源码 UTF-8 **无 BOM**；含中文的 `.ps1` 必须**带 BOM**；用 PowerShell 读 UTF-8 JSON 时加 `-Encoding UTF8`
   （PS 5.1 默认按 ANSI 读，会显示乱码，但文件本身是好的）。
3. 满背包时掉落物不会进包（先 `delete_item` 腾格）；水下待久了会淹死，物品会掉。
