# 14 — 术语表

| 术语 | 含义 |
|---|---|
| SR | Scheduler / 主 Agent，负责任务调度与决策 |
| CR | Chatter / 聊天子 Agent，负责玩家沟通 |
| AR | Affecter / 执行子 Agent（历史上用于动作执行） |
| ESRS / ESRA | 环境感受器（SR/AR） |
| DSH | DeepSeek Harness，运行本系统的宿主 |
| MCP | Model Context Protocol；Agent 调用工具的协议 |
| ZFF MCP Server | Node.js 侧 MCP 服务，工具注册/路由/Job/存储 |
| ZFF Bridge | NeoForge 模组，客户端内真实执行层 |
| InfoTxt | 每轮工具响应中注入的紧急提示队列 |
| Job | 客户端 tick 驱动的长任务；HTTP 只返回 handle |
| JobHandle | `{jobId,state,tool,...}` |
| JobResult | `job_status.result`，终态结构化结果 |
| Watchdog | 无进展检测（移动/挖掘/战斗） |
| PathReachability | 本地 BFS 路径可达检查（<16 步） |
| SADT | 结构化分析与设计技术图 |
| FAST | 功能分析系统技术图 |
| VANILLA | 仅 NeoForge + ZFF Bridge，无 Baritone/Pendulum |
| CLEAN | 最小测试 mod 集（Baritone/Pendulum 等） |
| FULL | 完整 253 mod 整合包 |
| perch | 末影龙停在传送门/中央岛上的阶段 |
| crystal | End Crystal，治疗末影龙 |
| EnderDragonPart | 龙的头部/身体/翅膀多部位实体 |
| void | 末地虚空，掉入即死 |
| one-cycle | 用床/爆炸一次击杀龙的策略（高风险） |
| evidence | 测试证据 JSON + 截图 |
| setupByCommand | 是否用 `/summon` `/tp` `/kill` 等命令做准备 |
| release | 可分发构建（Bridge jar + MCP zip + 插件） |
| HMC | HeadlessMc，Minecraft 启动/控制工具 |
| PCL | 当前使用的 Minecraft 启动器 |
| Pendulum | 客户端 JS 执行器，可选战斗增强 |
| Jupiter / Rhino | Pendulum 的前置库 |
| Baritone | 寻路/挖掘自动化模组 |

## 缩写

```text
MC   = Minecraft
MCP  = Model Context Protocol
LLM  = Large Language Model
BFS  = Breadth-First Search
A*   = A-star pathfinding
LOS  = Line of Sight
TPS  = Ticks Per Second
NBT  = Named Binary Tag
JSON = JavaScript Object Notation
```
