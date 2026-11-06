# mod-mdk —— ZFF Bridge 的 NeoForge 开发工程（ModDevGradle）

这是 `ZFF Bridge`（modid `mcpbridge`）的**可重建**工程：源码在里面，`gradlew build` 能出 jar。
它补上了 P6-19（之前 `mod/` 只剩 `src/`，jar 无法重建）。

## 目录

```
mod-mdk/
├─ build.gradle / settings.gradle / gradle.properties   ← ModDevGradle 模板（已改成本 mod 的元数据）
├─ gradle/wrapper/…                                      ← Gradle 9.2.1 wrapper
├─ src/main/java/dev/mcp/bridge/…                        ← 我们的源码（67 个 .java）
├─ src/main/templates/META-INF/neoforge.mods.toml         ← 元数据模板（build 时展开）
└─ build/libs/mcpbridge-0.2.0.jar                        ← 构建产物
```

## 构建

```powershell
cd D:\mc\MCP Development\mod-mdk
$env:JAVA_HOME = "D:\java\jdk-21_windows-x64_bin\jdk-21.0.8"
.\gradlew.bat build --console=plain
# 产物：build\libs\mcpbridge-0.2.0.jar
```

本机实测：**BUILD SUCCESSFUL in 21s**（第二次；首次要跑 NeoForm 反编译/打补丁，约 5 分钟）。

## 本机的两个特殊之处（换机器前先看）

1. **`gradle-wrapper.properties` 里的 `distributionUrl` 指向本地 zip**
   （`file:///D:/mc/MCP%20Development/runtime/gradle-9.2.1-bin.zip`）。
   原因：这台机器的 JVM 访问 Gradle 发行包的 CDN 会 `PKIX path building failed`，wrapper 下载不了。
   **开源发布前把这一行改回官方地址**：
   ```
   distributionUrl=https\://services.gradle.org/distributions/gradle-9.2.1-bin.zip
   ```
2. **`maven.neoforged.net` 在这台机器上不可达**（TCP 443 超时，`curl` 21 s 拿不到响应）。
   本次构建能用，是因为 `~/.gradle/caches` 里已有 NeoForge 21.1.228 的全套依赖
   （`net.neoforged*` 等 205 个 jar，之前构建留下的）。换机器/清缓存后必须能访问该 Maven 才能构建。

## 与 `mod/` 的关系

- `mod/` 保留为**源码参考**；本工程把 `mod/src/main/java` 与 `mod/src/main/resources` 拷了过来。
  以后改代码请以本工程为准（或两边同步）。
- `scripts/build-release.mjs --from-source` 会优先用本工程的 `build/libs/mcpbridge-*.jar`。

## 元数据（`src/main/templates/META-INF/neoforge.mods.toml`）

| 键 | 值 |
|---|---|
| modId | `mcpbridge` |
| version | `0.2.0` |
| displayName | `ZFF Bridge (client + server)` |
| license | MIT |
| 依赖 | neoforge `[21.1.228,)`、minecraft `[1.21.1,1.21.2)`；baritone/jei 可选（CLIENT） |
| displayTest | `IGNORE_ALL_VERSION`（服务端没装也能连，客户端才不会被踢） |

> 注意：`src/main/resources/META-INF/neoforge.mods.toml` **不要**再放一份 ——
> ModDevGradle 会从 `templates/` 生成同名文件，两份会撞 `processResources`（本轮踩过：
> `Entry META-INF/neoforge.mods.toml is a duplicate`）。
