# Ph8 文档包

本目录是下一轮开发的**自包含任务包**：主任务书 + 历史/参考文档，不需要再翻 Ph7 目录。

## 主任务书

- [agent-prompt8.md](agent-prompt8.md) —— Ph8 全部需求、算法、测试矩阵、交付标准
- [TEST-CASES.md](TEST-CASES.md) —— 全部测试用例（ID、步骤、期望、证据）
- [REPORT.md](REPORT.md) —— 当前实现进展与未完成项

## 历史任务书

- [previous/agent-prompt7.md](previous/agent-prompt7.md) —— Ph7 中断/提示词/容器修复
- [previous/ISSUE.md](previous/ISSUE.md) —— P7 问题根因
- [previous/REPORT.md](previous/REPORT.md) —— Ph7 交付报告

## 参考文档

- [reference/TOOLS.md](reference/TOOLS.md) —— MCP 工具契约（99 个工具的完整说明）
- [reference/ARCHITECTURE-PH6.md](reference/ARCHITECTURE-PH6.md) —— 插件/MCP/mod 架构
- [reference/AGENT-ARCHITECTURE-ph5.md](reference/AGENT-ARCHITECTURE-ph5.md) —— Agent 架构
- [reference/PH6-PLUGIN-API.md](reference/PH6-PLUGIN-API.md) —— 插件 API
- [reference/AIPlayerManual.md](reference/AIPlayerManual.md) —— AI 玩家手册
- [reference/AIPlayer.md](reference/AIPlayer.md) —— AI 玩家规则
- [reference/CLIENT-ONLY-ANALYSIS.md](reference/CLIENT-ONLY-ANALYSIS.md) —— 客户端侧分析
- [reference/INSTALL.md](reference/INSTALL.md) —— 安装说明
- [reference/ISSUE-ph6.md](reference/ISSUE-ph6.md) / [ISSUE-ph7.md](reference/ISSUE-ph7.md)
- [reference/ISSUE-TOOLS-ph6.md](reference/ISSUE-TOOLS-ph6.md)
- [reference/HANDOVER-ph6.md](reference/HANDOVER-ph6.md)
- [reference/M5-MEMORY-DESIGN.md](reference/M5-MEMORY-DESIGN.md)
- [reference/REPORT-ph7.md](reference/REPORT-ph7.md)
- [reference/ZFF-MC-SYSTEM-PROMPT.md](reference/ZFF-MC-SYSTEM-PROMPT.md)
- [reference/architechture.md](reference/architechture.md)
- [reference/Requirement.original.md](reference/Requirement.original.md)
- [reference/STORAGE-INVENTORY.md](reference/STORAGE-INVENTORY.md)
- [reference/agent-prompt6.md](reference/agent-prompt6.md)

## 待补充

- [PENDULUM.md](PENDULUM.md) —— Pendulum 安装/API/缺失降级记录（当前为待填骨架）。
- `REPORT.md` —— Ph8 完成后填交付报告与证据索引。

## 使用方式

1. 先读 `agent-prompt8.md` 的 §0–§7（M1 生命周期/地形）。
2. 再读 §8–§16（M2/M3）。
3. 按 §14 的顺序实现；每个工具按 §4/§15 留 evidence。
4. 不把 `reference/` 下的历史文档当当前契约；以 `agent-prompt8.md` 为准。
