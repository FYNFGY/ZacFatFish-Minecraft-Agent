# 06 — Ph9 路线图

## 1. 总览

```text
M9.1 要塞/传送门
   ↓
M9.2 进入末地 + 虚空保护
   ↓
M9.3 水晶处理
   ↓
M9.4 末影龙战斗
   ↓
M9.5 回归、文档、release
```

每个里程碑必须“代码 + 测试 + evidence + 文档”四件套齐全。

## 2. M9.1 — 要塞与传送门

### 交付

- `locate_stronghold`
- `path_to_stronghold`
- `end_portal_status`
- `activate_end_portal`

### 技术要点

- 要塞定位：优先真实搜索，命令只作测试
- 长距离导航：分层（地面 Baritone + 室内局部 A*）
- 传送门框架扫描：12 个 frame block / eye state
- 放置末影之眼：真实右键/交互
- 传送门激活检测：`end_portal` block 出现

### 退出条件

```text
生存存档：定位 → 到达 → 补眼 → 激活
至少 1 个完整成功证据
```

## 3. M9.2 — 进入末地 + 虚空保护

### 交付

- `enter_end_portal`
- `void_guard`
- `safe_move_to`
- 末地落点安全检查

### 技术要点

- 进入前校验：护甲/弓/箭/食物/盾
- 进入后校验：dimension、平台、脚下方块
- 平台边缘 2 格安全边界
- 被击退后的 `mlg_water` / 回退策略

### 退出条件

```text
进入 End 后 60 秒不因虚空/坠落死亡
平台边缘 10 次测试 0 坠落
```

## 4. M9.3 — 水晶处理

### 交付

- `query_crystals`
- `destroy_crystal`
- 铁栏杆保护识别

### 技术要点

- 水晶实体 vs 基岩/铁栏杆
- 从爆炸半径外射击
- 水晶被摧毁后更新 remaining
- 失败回退：换角度/换武器/撤退

### 退出条件

```text
清除至少 1 个暴露水晶
清除至少 1 个铁栏杆保护水晶（如地图存在）
```

## 5. M9.4 — 末影龙

### 交付

- `query_dragon`
- `kill_ender_dragon`
- 龙阶段识别（circling/strafing/perching/breath）
- 多部位处理

### 技术要点

- 只攻击 EnderDragon 本体/头，不把 part 当目标
- perch 窗口才射击
- 飞行阶段停火、举盾、躲避
- 低血吃牛肉/撤退
- 输出结构化指标

### 退出条件

```text
至少一次 perch 有效伤害
完整击杀为加分项；未击杀必须记录 blocked 原因
```

## 6. M9.5 — 回归与发布

### 交付

- 全量 `test/run-all.mjs --slow`
- Ph9 evidence JSON
- `MCP-TOOLS.md` 更新
- `09-release-checklist.md` 通过
- release SHA256

### 退出条件

```text
所有 P0/P1 测试 pass 或明确 blocked
release 可解压即用
```

## 7. 依赖关系

```mermaid
flowchart LR
    A["M9.1 要塞/传送门"] --> B["M9.2 进入末地/虚空"]
    B --> C["M9.3 水晶"]
    C --> D["M9.4 龙"]
    D --> E["M9.5 回归/发布"]
    R["路径/盾牌/远程回归"] --> A
    R --> B
```

## 8. 估算（仅供参考）

| 阶段 | 主要工作 | 风险 |
|---|---|---|
| M9.1 | 定位/寻路/传送门 | 高（长距离导航） |
| M9.2 | 维度/虚空 | 中高 |
| M9.3 | 水晶/射击 | 中 |
| M9.4 | 龙阶段/多部位 | 高 |
| M9.5 | 回归/发布 | 中 |

## 9. 停止条件

出现以下任一情况，暂停并向用户报告，而不是无限重试：

- 连续 3 次同一 case 失败
- 世界无 End 维度且无法修复
- 客户端崩溃/存档损坏风险
- 需要管理员权限/外部服务才能继续
