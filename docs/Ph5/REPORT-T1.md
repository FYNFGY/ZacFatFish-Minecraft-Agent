# T1 - one mod for the client and the server（2026-10-31 第二轮）

## 做了什么

1. `McpBridge` 从 `@Mod(dist = Dist.CLIENT)` 改成**通用 @Mod**（无 dist 限制），构造器里：
   `ZffConfig.load()` -> `ZffServer.init(modBus)`（两端共有）-> `if (FMLEnvironment.dist == Dist.CLIENT) ZffClient.init(modBus)`。
   新增 `dev.mcp.bridge.client` 包（`ZffClient`、`ZffRemote`、`ZffWatchdog`），客户端专属代码全部搬到这里。
2. 公共/客户端之间只留**一条**编译期引用：`McpBridge -> ZffClient.init`，且被 `FMLEnvironment.dist` 包住。
   其余跨端调用走 `ZffHooks`（`clientSide` 在专用服务端保持 null，所有 payload handler 变成 no-op）。
3. `/zff` 从**客户端指令**改为**服务端指令**（`ZffServer.registerCommands`，`RegisterCommandsEvent`）；
   `ZffCommand.java` 删除（其客户端 `/zff stop` 的职责改由服务端指令 + `Stop` payload 完成）。
   服务端只加载：配置、SavedData、指令、payload 通道、服务端 tick 采样（看不到任何 `net.minecraft.client.*`）。
4. `neoforge.mods.toml`：`neoforge`/`minecraft` 依赖 `side = "BOTH"`，Baritone/JEI 保持 `side = "CLIENT"`。
   NeoForge 21.1.228 已**没有** `displayTest` 键（loader jar 里不存在 `DisplayTest`），因此改用 optional
   channel（`registrar.optional()`）来避免版本不一致时踢客户端，并在 toml 里注明原因。

## 验收

- 真正起了一个 **NeoForge 1.21.1 专用服务端**（`server/`，用 `java -jar neoforge-installer --installServer` 装好，
  `mc-server-run.mjs` 托管），日志：`[zff] mcpbridge 0.1.0 loading: dist=DEDICATED_SERVER`、
  `[zff] server-side initialised: commands=/zff ...`、`[zff] server side only: no loopback HTTP bridge and no client classes`。
- 服务端控制台 `zff status` 返回服务端 JSON（`side=server`、`dist=DEDICATED_SERVER`、`data.world=ZffServerTest`）。
- 静态闸门 `node scripts/check-sides.mjs`：从 `McpBridge` 可达的 13 个类，**0** 处引用
  `net.minecraft.client.*` / `com.mojang.blaze3d.*` / `neoforge.client.*` / `dev.mcp.bridge.client.*`
  （唯一允许边 `McpBridge -> client.ZffClient` 被 dist 守卫）。
- 单机客户端同样正常：`bridge_info.side=client`，所有既有工具可用。

## 证据

`evidence/ph5-server-side.json`（服务端日志 + 判定）、`evidence/ph5-server-sides.json`（字节码可达性）、
`runtime/server-ph5.log`（完整服务端日志）。安装/启动脚本：`scripts/server-run.mjs`、`scripts/check-sides.mjs`。
