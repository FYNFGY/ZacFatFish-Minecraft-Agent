# 客户端要不要装这个 mod？（服务器装了的情况下）

**结论：普通玩家不用装。** 装了服务端的一半，玩家客户端保持原样即可进服，不会出现 "Channel 不一致" / "Incompatible client" 一类由本 mod 引起的报错。

## 三个保证

1. **所有 payload 都是 optional**：`ZffServer.registerPayloads()` 里先 `registrar.optional()`，再注册
   `mcpbridge:hello / rpc_request / rpc_response / stop`。NeoForge 的网络协商只对**必需**通道做拒绝
   （`NegotiableNetworkComponent.optional()`），optional 的通道在对端缺失时直接忽略。字节码层面已确认
   `PayloadRegistrar.playTo*` 把 `optional` 字段传进了 `register(...)`。
2. **服务端不向没装 mod 的客户端发任何包**：客户端进服时发 `mcpbridge:hello`，服务端只给"打过招呼"的玩家发
   `stop` 广播（`broadcastStop` 里 `isModdedClient()` 过滤，日志会写
   `stop: N modded client(s) notified, M client(s) without the mod skipped`）。没装 mod 的客户端连我们的通道
   都看不到。
3. **没有内容/注册表改动**：本 mod 不注册方块、物品、维度、数据包（datapack）内容，所以也不会有
   registry / datapack 不一致的问题。

`/zff` 是**服务端指令树**：没装 mod 的玩家也能在聊天框里用（受 op 权限限制，`status/list` 不需要权限），
因为客户端只是把这一行命令发给服务端。

## 玩家仍然需要满足的（与本 mod 无关）

- 和服务器**相同的 Minecraft + NeoForge 版本**（这是任何 NeoForge 服务器都有的常规要求）；
- 服务器本身的其它 mod/资源包要求。

## 实测记录与一个说明

- **hello 往返已实测**：加了 hello 守卫后，`/zff stop` 仍然在 5 ms 内取消 job、193 ms 内产出 `zff_stop` 事件，
  说明服务端确实知道了"这个客户端装了 mod"（`evidence/ph5-zff-stop-event.json`）。
- **"没装 mod 的客户端"在本机没法复现**：这里只有"整合包客户端"和"裸 NeoForge 服务端(只装本 mod)"。
  把 jar 从整合包客户端移走后它连裸服会被 NeoForge 自己拒掉
  （`Incompatible client! Please use NeoForge 21.1.228`），原因是整合包里**其它 mod 的必需通道**在裸服上没有，
  报错里从未出现 `mcpbridge`。要真正复现"玩家不装本 mod"的场景，需要"整合包服务端 + 整合包客户端(去掉本 mod)"，
  本机没有这样的服务端。
- 反过来，本 mod 的所有通道都是 optional，正是为了**不**参与那种"必需通道缺失"的拒绝。

证据：`evidence/ph5-channel-optional.json`。
