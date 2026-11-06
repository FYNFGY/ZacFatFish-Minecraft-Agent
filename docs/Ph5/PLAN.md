# Ph5 - plan

来源：`docs/architechture.md`（用户新需求：Agent 架构 + 服务端模组）+ `REQUIREMENT.md`。
**优先级已按用户指示调整：先做 Agent 架构组织与服务端模组开发；测试任务由用户分发；开发期 CR 关闭。**

## P5-A Agent 架构（SR / CR / AR / ESRS / ESRA）——当前最优先

见 `AGENT-ARCHITECTURE.md`。已完成的部分见 `PHASE5-PROGRESS.md`：
- Agent 运行时骨架（事件总线、TodoList、Memory、ESRS/ESRA、AR、SR、CR 关闭）——**已实机跑通**
- Bridge mod：`zff_bridge.config` + `/zff stop|status|reload`——**已实机验证 `/zff stop`**

剩余：
1. 服务端模组拆分（common/server/client 同一 mod）
2. Memory/TodoList 存服务端 SavedData（带 actorId）
3. 看门狗移入 mod（Java tick，3 s）
4. 服务端无需额外端口的通信
5. `/zff stop` 向 MCP 写 stop 事件
6. CR 联调（发布时开启）

## P5-B 服务端模组开发（ZacFatFish Bridge Mod）

1. 同一 mod 双端加载：`@Mod(dist = Dist.DEDICATED_SERVER)` 的通用部分 + 客户端部分。
2. 服务端只跑：看门狗、指令、SavedData（Memory/TodoList）、事件推送；不监听端口。
3. 兼容无其他模组的 NeoForge 存档；Baritone 为客户端前置。
4. 配置 `zff_bridge.config`：看门狗时间、增量阈值、CR 开关。

## P5-C 需求缺口（`REQUIREMENT.md` 里标"待实现"的）

1. D5 时间感知（`get_state.world.time/weather` 或 `time_status`）
2. C4 喂食动物验收
3. C6(*) Carry On、C7(*) Create 机械查询

## P5-D 水下原语与 Test A

1. `dig_sealed_shaft` 实机验收（代码已在，`SealShaftJob.java`）
2. 水呼吸、排水、水下隧道
3. Test A 重开（进入海底强堡、激活末地传送门）——在 P5-D 完成后再议

## 规则

- 只通过 `clean-start.ps1`/`stop-all.ps1` 重启（先 `save_world`）；
- Java UTF-8 无 BOM；中文 .ps1 带 BOM；
- 每个验收写 `evidence/*.json` + `docs/Ph5/` 报告；
- 开发期 `ZFF_CR` 不设 1（CR 关闭）。
