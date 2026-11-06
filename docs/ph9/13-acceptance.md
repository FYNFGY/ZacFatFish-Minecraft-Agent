# 13 — Ph9 验收

> 只有全部门禁满足，才能对外说“Ph9 完成”。

## 1. 功能门禁

| ID | 门禁 | 证据 | 状态 |
|---|---|---|---|
| A-01 | 生存定位要塞 | `locate_stronghold` evidence | [ ] |
| A-02 | 到达传送门房间 | `path_to_stronghold` evidence | [ ] |
| A-03 | 检查 12 框架 | portal status JSON | [ ] |
| A-04 | 激活传送门 | activated=true + screenshot | [ ] |
| A-05 | 生存进入末地 | dimension/safe/voidRisk | [ ] |
| A-06 | 末地平台安全 60s | 0 void death | [ ] |
| A-06b | self-protect 自动落地水 + 收回 | y=500→101，health 20，水收回满水桶，InfoTxt 两条 | [x] |
| A-07 | 水晶查询 | crystals[] 数量正确 | [ ] |
| A-08 | 摧毁暴露水晶 | destroyed=true | [ ] |
| A-09 | 摧毁铁栏杆水晶（如存在） | destroyed=true | [ ] |
| A-10 | 龙本体识别 | query_dragon dragonId/head/phase | [ ] |
| A-11 | perch 有效伤害 | dragonHealth 下降 + hits>0 | [ ] |
| A-12 | 龙击杀（完整目标） | dragonHealth=0 | [ ] |
| A-13 | 玩家存活/可恢复 | health>0 或明确 death | [ ] |

## 2. 质量门禁

| ID | 门禁 |
|---|---|
| Q-01 | `node test/run-all.mjs --slow` 全绿 |
| Q-02 | 真实进程 14/14 |
| Q-03 | `MCP-TOOLS.md` 与实际工具一致 |
| Q-04 | 所有新工具都有错误码测试 |
| Q-05 | 无诚实的 `setupByCommand` 未标注 |
| Q-06 | 没有残留进程/端口 |
| Q-07 | 不在主存档做破坏性测试 |

## 3. 证据门禁

每项必须包含：

```json
{
  "id": "P9-Txx",
  "result": "pass|fail|blocked",
  "environment": "CLEAN|FULL|VANILLA",
  "survivalMode": true,
  "difficulty": "easy|normal|hard",
  "setupByCommand": true,
  "observed": {},
  "screenshot": "path|null",
  "notes": ""
}
```

缺少 `setupByCommand` 的 case 视为无效。

## 4. 发布门禁

```text
[ ] release 构建完成
[ ] SHA256SUMS 更新
[ ] 解压冒烟通过
[ ] 用户可复现安装
[ ] 文档索引更新
```

## 5. 未完成处理

- 未完成项必须写 `blocked`，原因具体到：
  - 缺工具
  - 维度不可用
  - 客户端崩溃
  - 无权限/无管理员
  - 测试环境限制
- 不允许用“部分成功”冒充 pass。

## 6. 签署

```text
Ph9 验收人：__________
日期：__________
release：ZFF Bridge.jar SHA256 __________
          ZFF MCP Server.zip SHA256 __________
结论：pass / blocked
```
