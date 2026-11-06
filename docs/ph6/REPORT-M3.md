# Ph6 M3 报告 —— 服务端可选与版本协商

> 结论：**三态全部真机通过**。`backend=server` / `backend=local(mismatch)`（带双方版本号）/ `backend=local`。
> 中间抓到一个 **Ph5 遗留的真 bug**（`optional()` 的返回值被丢弃），修好之后「服务端没装我们的 mod」
> 才真正能进服。全过程数字在 `evidence/ph6-m3-backends.json`。

---

## 1. 设计（最终实现）

协商**复用旧 jar 已有的通道**，不新增 payload 类型：

```
客户端登录 → hello{"0.2.0+p2"}            （单字符串版本 + 协议号，旧服务端当普通版本串读）
  ├─ 新服务端：立刻用 rpc_response 回一条 id="hello:0.2.0" 的 Response{modVersion,protocol,serverId}
  │            → 客户端 Backend.onAck() → backend=server（协议相同）或 local(mismatch)
  └─ 旧服务端：不回（它的 hello 只记 KNOWN_CLIENTS）
               → 客户端 4 s / 8 s 各探一次 rpc_request{op:"status"}（旧服务端**有这个 op**）
                  → 有应答但版本/协议不同 → local(mismatch) + 双方版本号
                  → 完全没应答（对端根本没有我们的 mod）→ local
```

关键代码：`ZffPayloads.Hello`、`ZffServer.onHello/negotiateStatus`、`ZffClient.onResponse/negotiateTick`、`Backend.java`。

## 2. 🔴 找到并修掉的根因：`PayloadRegistrar.optional()` 是拷贝构造

Ph5 的注册写法是：

```java
var registrar = event.registrar("1");
registrar.optional();            // ← 返回值被丢弃
registrar.playToServer(...);     // ← 注册出来的通道仍然是"必需"
```

`javap -p -c` 看 `PayloadRegistrar.optional()`：

```
0: new  #8   // class net/neoforged/neoforge/network/registration/PayloadRegistrar
5: invokespecial #66   // <init>(Lnet/neoforged/neoforge/network/registration/PayloadRegistrar;)V   ← 拷贝构造
10: iconst_1
11: putfield #7   // optional:Z
15: areturn
```

它**返回一个新实例**，不会修改 `this`。所以旧写法一直是"必需通道"。后果：只要服务端没装我们的 mod，
NeoForge 在握手阶段就直接踢人：

```
服务端: lost connection: Incompatible client! Please use NeoForge 21.1.228
客户端: Channel [mcpbridge:hello] failed to connect:
        Channel of mod "ZFF Bridge (client + server)" failed to connect
```

修法（一行）：

```java
var registrar = event.registrar("1").optional();
```

（`executesOn` / `versioned` 同样是拷贝构造，用的时候也别丢返回值。`mods.toml` 里的
`displayTest = "IGNORE_ALL_VERSION"` 保留但**对通道协商无效**，只影响版本显示。）

## 3. 三态实测

| 态 | 搭法 | 结果 | 客户端工具耗时 |
|---|---|---|---|
| **同版模组服** | `server/mods` 放 0.2.0 | `backend=server`，`serverVersion 0.2.0 / serverProtocol 2 / serverId ZffServerTest` | get_state 13 ms / scan_surface 23 ms / env_read 9 ms / zff_status 8 ms |
| **旧版模组服** | `server/mods` 放 Ph5 的 0.1.0 jar | **客户端不被踢**，`backend=local(mismatch)`，`clientVersion 0.2.0` + `serverVersion 0.1.0`，reason「answered rpc without a version (old server half)」 | 9 / 14 / 10 ms / zff_status 19 ms |
| **无模组服** | `server/mods` 删掉 jar | **客户端能进服**（修好 §2 之后），`backend=local`，reason「no server half answered the negotiation probe」 | 6 / 16 / 9 ms / zff_status 11 ms |

三态都远低于"`zff_*` < 2 s"的预算；`zff_status` 在 `local` 态会快速报错（没有服务端半边），不会挂住。

## 4. 为什么协商不新开通道（走过的弯路）

第一版是新加 `mcpbridge:negotiate` + `mcpbridge:hello_ack` 两个通道（并且以为 `optional()` 已经生效）。
真机结果：**连旧 jar 的服务端都被踢**（客户端的 `ModMismatchDisconnectedScreen` 逐条列出
`mcpbridge:negotiate failed to connect`）。通道集合必须两端一致，所以协商改走 `hello` + `rpc_request`，
这样 0.1.0 与 0.2.0 的通道集合完全相同，旧服务端照常接受新客户端。

## 5. 另一条独立结论：257 mod 的整合包客户端连不上本地服

`versions\无头客户端测试`（含 Create / Packet Fixer / Sinytra Connector 等 257 个 mod）连本地 NeoForge 服
依旧被 `Incompatible client!` 踢。这是同一条规则的另一个方向：**那些 mod 注册的必需通道服务端没有**。
所以本地测试统一用 `D:\mc\client-test`（只装 mcpbridge）这种干净实例。

## 6. 玩家侧建议

1. 自己的 NeoForge 服上装不装 mcpbridge 都可以了：
   - 装了（**任何版本**）→ `backend=server` 或 `local(mismatch)`，共享记忆/待办、`/zff stop` 广播可用；
   - 没装 → `backend=local`，84 个工具、聊天中断、环境采样、mod store 全部照常工作。
2. 对端不是 NeoForge（原版/Paper）时同样走 `local`（原版会忽略未知 payload）；本场按纪律没有安装原版服务端，
   这条只有"同规则推论 + 离线自检"。
3. 版本不一致时 `bridge_info` 会同时给出 `clientVersion` 与 `serverVersion`，一眼能看出是哪边旧。
