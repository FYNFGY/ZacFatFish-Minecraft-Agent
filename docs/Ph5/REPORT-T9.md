# T9 - 上一轮遗留项

## 1. `place_boat` 实体轮询（server.js）

- 桥接层 `place_boat` 仍可能返回 `entityId=-1`（船实体在 use 包之后才生成），server.js 现在会等 **8×120 ms**
  轮询 `entity_query {type:"boat"}`，找到后返回真实 id，并把误导性的 `note` 换成
  `entity resolved by polling entity_query`。
- 实机：本轮早先一次成功的放置里返回 `entityId=160, resolvedMs=120`，`entity_query` 同时列出该实体
  （日志见 `evidence/ph5-t9-leftovers.json` 的 `steps`）。
- **反例（同样是证据）**：后端在自建平台上、以及在水塘边再放一次时都拿到 `result=FAIL`（`pick` 明确命中 water），
  实体没有生成，轮询自然也无从解析——这是"放置前置条件"的问题（船 AABB 与地形/玩家冲突、玩家与水面同高），
  不是轮询逻辑的问题。`evidence/ph5-t9-boat.json` 保留了这次失败记录。
- 另注：本整合包的船实体类型是 `minecraft:boat`（不是原版 `*_boat`），`isBoatType` 已兼容。

## 2. `collect_drops` 的 3D 兜底（server.js）

发现并修了两个真问题：
1. **工具没有等 job 结束**：`collect_drops` 是异步 job，旧代码把"刚启动的 job"当成结果读 `metrics.collected`，
   于是永远读到 0、永远走一次 `path_to` 兜底，然后返回另一个刚启动的 job。现在用 `waitJob()` 轮询到终态再判断。
2. **早退条件写错**：`collected > 0 || state == done` 会跳过兜底；一个"挖了一圈什么都没捡到"的 done job
   恰恰最需要 3D 兜底。改成只在 `collected > 0` 时早退。

Java 侧计数也修了：所有物品消失时（`items.isEmpty()`）以前不更新 `metrics.collected`，会出现
"物品已进背包但 metrics=0"。现在该分支把 `collected = initial` 写进 metrics 和 result。

实机（`test/ph5-t9b-collect.mjs`）：把一颗绿宝石放在玩家脚下 2-3 格深的洞里、先清空附近掉落物，
`collect_drops` 结束后绿宝石数量 0 -> 1（物品确实捡到），`evidence/ph5-t9-collect3d.json`。

## 3. `walk_to` 的 2D 限制

文档已写明：掉落物在头顶/脚下时用 `path_to`（带 y）；`collect_drops` 现在正是这样兜底。
