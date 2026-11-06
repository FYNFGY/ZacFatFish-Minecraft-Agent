# 10 — Risk Register（Ph9）

| ID | 风险 | 概率 | 影响 | 触发 | 缓解 |
|---|---|---|---|---|---|
| R-01 | 世界无 End 维度 | 中 | 高 | `Unknown dimension` | `dimension_unavailable`；用完整存档；探测后停止 |
| R-02 | 长距离导航不可靠 | 高 | 高 | `stalled/no_progress` | checkpoint + 分段重试 + 3D A* |
| R-03 | 末地虚空坠落 | 高 | 高 | 平台边缘/龙击退 | `void_guard` + 安全回退 + MLG |
| R-04 | 水晶爆炸死亡 | 高 | 高 | 近距摧毁 | 远程射击 + 爆炸半径检查 |
| R-05 | 龙多部位误锁 | 高 | 中 | `entity_query` 多 part | `query_dragon` + 本体过滤 |
| R-06 | 拉弓时被龙撞死 | 高 | 高 | 3 HP 实测 | 低血撤退/吃牛肉/停火 |
| R-07 | 盾牌假状态 | 中 | 高 | 耐久不降、伤害照吃 | 真实右键 + 服务端校验 |
| R-08 | 箭耗尽 | 中 | 中 | 192 箭不够 | `ammo_empty` 退出；补充策略 |
| R-09 | MCP 实例错连 | 中 | 中 | get_state 空/旧 | 重启 server/daemon；校验实例端口 |
| R-10 | 进程残留 | 低 | 中 | stop 后端口在 | `stopMcp` 进程快照清理 |
| R-11 | 存档损坏 | 低 | 极高 | 崩溃/强杀 | 测试存档 + save_world + 备份 |
| R-12 | 模组冲突 | 中 | 高 | 弓/盾不生效 | CLEAN 环境复现；最小 mod 集 |
| R-13 | 管理员权限误用 | 低 | 高 | `zff_shutdown` | 只读 chat_history 复核 adminPlayers |
| R-14 | 证据造假/误标 | 中 | 高 | 命令 setup 未标注 | `setupByCommand` 强制字段 |
| R-15 | 文档漂移 | 中 | 中 | 工具变更未更新 | release checklist 检查 MCP-TOOLS |

## 风险处理原则

1. 高风险 + 高概率（R-02/R-03/R-04/R-06）必须在 Ph9 前解决或明确 blocked。
2. 任何“死亡/虚空/存档”风险优先于“击杀龙”。
3. 连续 3 次同类失败 -> 停止自动重试，转为人工分析。
4. 不在主存档上做破坏性测试。

## 监控指标

```text
job_success_rate
job_no_progress_rate
player_death_count
void_fall_count
crystal_clear_rate
dragon_damage_per_attempt
arrows_per_kill
```

## 回滚策略

- 保留上一版 release
- 新版本先跑 CLEAN，再跑 FULL
- 出现存档风险立即停止，不回写主世界
- 保留 `evidence/ph8` 与 `ph9` 结果，不覆盖历史
