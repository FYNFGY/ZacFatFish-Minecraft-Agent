# 16 — Evidence 模板

## 1. 文件头

```json
{
  "phase": "ph9",
  "date": "2026-11-06",
  "environment": "CLEAN",
  "survivalMode": true,
  "difficulty": "normal",
  "release": {
    "bridgeJarSha256": "",
    "serverZipSha256": ""
  },
  "cases": [],
  "notes": []
}
```

## 2. 单个 case

```json
{
  "id": "P9-T05",
  "result": "pass|fail|blocked",
  "environment": "CLEAN|FULL|VANILLA",
  "survivalMode": true,
  "difficulty": "easy|normal|hard",
  "setupByCommand": false,
  "observed": {
    "jobId": "j-9-xxxx",
    "outcome": "target_killed|reached|blocked|...",
    "health": 20,
    "armor": [],
    "arrowsBefore": 192,
    "arrowsAfter": 160,
    "attacks": 4,
    "hits": 3,
    "extra": {}
  },
  "screenshot": "C:/Users/.../shot-xxxx.png",
  "notes": "why it passed/failed"
}
```

## 3. 失败 case

```json
{
  "id": "P9-T10",
  "result": "blocked",
  "observed": {
    "reason": "dimension_unavailable",
    "message": "Unknown dimension 'minecraft:the_end'",
    "attempts": 2
  },
  "notes": "存档维度缺失；需要完整 WorldGenSettings，不是工具 bug"
}
```

## 4. 证据要求

- `setupByCommand` 必填
- 战斗必须有 `health/armor/attacks/hits`
- 远程必须有 `arrowsBefore/After`
- 移动必须有 `outcome/finalPos/distanceLeft`
- 失败必须有 `reason` / `error.code`
- 截图路径必须是实际存在的文件

## 5. 不要写

- token / 密钥
- 玩家隐私
- 未验证的“通过”
- 大段聊天原文（必要时脱敏）
