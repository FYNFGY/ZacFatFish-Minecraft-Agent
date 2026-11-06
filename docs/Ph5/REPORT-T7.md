# T7 - 需求缺口（本轮做 D5，其余标注现状）

## D5 时间/天气感知（已实现并验收）

- `get_state` 的 `world` 新增：`time{dayTime, timeOfDay, day, isDay, isNight, phase, moonPhase, fixedTime, sunAngle, skyDarken}`
  与 `weather{rain, thunder, rainLevel, thunderLevel}`（同时保留旧的 `dayTime/day/raining/thundering` 平铺字段）。
- **坑**：`Level.isDay()` 用的是客户端 `skyDarken` 字段，本整合包（着色器/光照类模组）里它一直是 0，
  导致午夜也报 `isDay=true`。改成按 `dayTime % 24000` 计算（`< 12000` 为白天，`13000..23000` 为 `isNight`），
  并把原始 `skyDarken` 一起暴露，方便判断是不是客户端光照没更新。
- 验收（`test/ph5-time-weather.mjs`）：`/time set day` -> `isDay=true, phase=morning`；
  `/time set midnight` -> `isDay=false, isNight=true, phase=midnight`；`/weather rain` -> `weather.rain=true, rainLevel=0.3`；
  `/weather clear` -> `rain=false`。证据 `evidence/ph5-time-weather.json`。
- `server.js` 的 compact 投影同步加了 `isDay/moonPhase/time/weather`，否则工具层会把新字段裁掉。

## 其它缺口现状

- **C4 喂食动物**：仍走 `entity_interact {action:use}`，本轮没有专门验收（需要附近有可喂的牛/羊，世界是和平模式深林，未遇到）。
- **C6(*) Carry On / C7(*) 机械动力查询**：仍未实现，优先级低于 T1-T5；本轮没有触碰。
- **E1 密封竖井**：代码在（`SealShaftJob`），本轮没有实机验收（见 `ROUND2-REPORT.md` 的剩余项）；
  **E2-E4 水下原语**仍未实现。
