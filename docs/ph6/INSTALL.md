# ZFF MC 瀹屾暣瀹夎鎸囧崡锛堢粰鍙︿竴涓敤鎴蜂粠闆跺鐜帮級

> 鐩爣锛氬湪涓€鍙?*骞插噣**鐨?Windows 鏈哄櫒涓婏紝鎶娿€孌SH + ZFF MC 鎻掍欢 + MCP server + Minecraft mod銆嶆暣濂楄璧锋潵锛?> 璁?AI 鍦ㄤ綘鐨勭湡瀹?Minecraft 瀹㈡埛绔噷闄綘鐜┿€?> 鍏ㄧ▼澶х害 20 鍒嗛挓銆傛瘡涓€姝ラ兘鏈?*楠屾敹鍔ㄤ綔**锛屽仛瀹屽啀寰€涓嬭蛋銆?
> 鈿狅笍 **鍚姩椤哄簭锛?026-11-02 鐪熸満韪╁潙锛?*锛氬厛璁?`127.0.0.1:57610` 鐨?MCP server 璺戣捣鏉ワ紝**鍐嶅惎鍔?DSH**銆?> DSH 鐨?`mc-control` 鏄湪 Host 鍚姩鏃惰繛鎺ョ殑锛涘鏋滆繖鏃?server 杩樻病璧凤紝瀹冨彲鑳介噸璇曞埌涓婇檺鍚庡交搴曟斁寮冿紝
> 涔嬪悗鍗充娇鎻掍欢鎶?server 鎷夎捣鏉ワ紝`mcp__mc__*` 涔熶笉浼氬啀鍑虹幇锛堣〃鐜颁负 `unknown tool "mcp__mc__get_state"`锛夈€?> 鏈€绋崇殑鍋氭硶锛氬厛杩愯 `start-mcp.ps1 -Workspace <宸ヤ綔鐩綍>`锛岀湅鍒?`/health` 杩斿洖 `ok`锛屽啀寮€ DSH銆?
---

## 0. 浣犱細瑁呬粈涔堬紙鍏堢湅鎳傝繖寮犲浘锛?
```
鈹屸攢 DSH 妗岄潰鐗?鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹? profile: desktop                          鈹?鈹?  鈹溾攢 鎻掍欢 @local/zff-mc   鈫?鏈粨搴撶殑 plugin鈹?鈹?  鈹斺攢 MCP client (mc-control) 鈹€鈹€鈹?          鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                 鈹?HTTP 127.0.0.1:57610/mcp
                     鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                     鈹?MCP server锛圢ode锛?     鈹? 57610 MCP / 57611 鑱婂ぉ鎺ㄩ€?/ 57612  ingest
                     鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                 鈹?TCP 127.0.0.1:57610 涔嬪鐨勬湰鍦?socket
                     鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                     鈹?Minecraft 瀹㈡埛绔?+ mod  鈹? 鈫?浣犺嚜宸卞紑鐨勬父鎴?                     鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

**鎻掍欢涓嶅惎鍔ㄦ父鎴?*銆備綘鑷繁寮€ Minecraft锛孉I 閫氳繃 MCP server 杩炰笂娓告垙閲岀殑 mod锛岀劧鍚庢帴鎵嬫搷浣溿€?
## 1. 鍓嶇疆鏉′欢

| 闇€瑕?| 鐗堟湰 / 璇存槑 |
|---|---|
| Windows | 10 / 11 x64 |
| DSH 妗岄潰鐗?| 0.2.1-rc.3锛堝畨瑁呭悗 `%LOCALAPPDATA%\Programs\DeepSeek Harness\`锛?|
| Node.js | 鈮?22锛坄node -v` 鑳借窇灏辫锛汥SH 鑷甫 node 涔熷彲锛?|
| Minecraft | Java 鐗?**1.21.1** + **NeoForge 21.1.228**锛坢od 鍙敮鎸佽繖涓粍鍚堬級 |
| 浣犵殑鏁村悎鍖?| 鍙互甯?mod锛屼絾蹇呴』鑳借涓嬭繖涓?NeoForge 鐗堟湰 |

## 2. 鍑嗗鏂囦欢锛坮elease 鐩綍閲屽氨杩欏嚑鏍凤級

```
release/
鈹溾攢 ZFF MCP Server.zip   鈫?**鏀惧埌浣犵殑宸ヤ綔鐩綍鏍逛笅**锛堟彃浠朵細鑷姩瑙ｅ帇骞跺惎鍔紝涓嶇敤 npm install锛?鈹溾攢 ZFF Bridge.jar       鈫?鏀捐繘 .minecraft\mods
鈹溾攢 zff-mc-plugin\       鈫?鎻掍欢鍖咃紙index.js / client.js / src / defaults / presets / package.json 鈥︼級
鈹溾攢 start-mcp.ps1        鈫?鍙€夛細鎵嬪姩璧?server
鈹斺攢 README.md / INSTALL.md / LICENSE / SHA256SUMS.txt
```

> Ph7 璧锋彃浠?*鑷繁鍦ㄥ伐浣滅洰褰曢噷鎵?* `ZFF MCP Server.zip` 鎴?`zff-mcp-server/`锛屾湁 zip 灏辫В鍘嬨€佹病 server 灏卞惎锛?> 涓嶉渶瑕佸啀鎶?`mcp-server\` 鍗曠嫭鎷峰埌鏌愪釜鍥哄畾浣嶇疆锛屼篃涓嶉渶瑕侀瑁呬緷璧栵紙zip 閲屽甫 `node_modules`锛夈€?> 宸ヤ綔鐩綍閲屼袱鏍烽兘娌℃湁鏃讹紝鎻掍欢**涓嶄細**缁窇锛屽彧浼氭彁绀轰綘鎶?zip 鏀捐繘鍘伙紙Agent 涓嶄細琚嚜鍔ㄥ敜閱掞級銆?> 鎻掍欢鍖呬笉瑕佹斁涓枃璺緞銆佷笉瑕佹斁 OneDrive 鍚屾鐩綍锛堝悗闈㈣ `link:` 鍒?profile锛夈€?
## 3. 瑁?DSH 骞跺缓 profile

1. 瀹夎 DSH 妗岄潰鐗堬紝鍏堝惎鍔ㄤ竴娆″啀瀹屽叏閫€鍑猴紙鎵樼洏閫€鍑猴級銆?2. 鎵撳紑 PowerShell锛?
```powershell
$dsh = "$env:LOCALAPPDATA\Programs\DeepSeek Harness\resources\runtime\cli\bin\dsh.cmd"
& $dsh --version
& $dsh plugin --profile desktop add "link:C:/zff/plugin"
```

**楠屾敹**锛歚C:\Users\<浣?\.dsh\profiles\desktop\package.json` 閲屽嚭鐜?
```json
"@local/zff-mc": "link:C:/zff/plugin"
```

骞朵笖 `dsh.profile.bundles` 閲屽浜嗕竴琛?`"@local/zff-mc"`銆?锛坄plugin add` 浼氳嚜鍔ㄦ敼杩欎袱澶勶紱娌¤嚜鍔ㄥ姞 bundle 灏辨墜鍔ㄨˉ銆傦級

## 4. 閰?profile 鐨?patch锛圡CP 杩炴帴锛?
缂栬緫 `C:\Users\<浣?\.dsh\profiles\desktop\cordis.patch.yml`锛?*杩藉姞**锛?
```yaml
- insert:
    - id: mc-control
      name: "@deepseek-ai/dsh-mcp-client"
      config:
        serverName: mc
        transport: streamable-http
        url: http://127.0.0.1:57610/mcp
        toolCallTimeoutMs: 600002
        failOnStartupError: false        # MCP server 娌¤捣涔熶笉鎷﹀惎鍔?        reconnect:
          enabled: true
          initialDelayMs: 500
          maxDelayMs: 5000
          maxAttempts: 240
```

鍚屾椂鎶?ZFF MC 鐨勫伐浣滃尯閽夋锛?*蹇呴』**涓?MCP server 鐨?`--workspace` 涓€鑷达級锛?
```yaml
- id: zff-mc
  name: "@local/zff-mc"
  config:
    workspaceDir: "C:\\zff\\workspace"
    adminPlayers: ["浣犵殑娓告垙鍚?]
```

**楠屾敹**锛歚node -e "console.log(require('js-yaml').load(require('fs').readFileSync(process.argv[1],'utf8')).length)" <閭ｄ釜鏂囦欢>` 涓嶆姤閿欙紙鎴栫敤浠绘剰 YAML 鏍￠獙鍣級銆?
## 5. MCP server锛氭彃浠朵細鑷繁璧凤紙涔熷彲浠ユ墜鍔ㄨ捣锛?
**鎺ㄨ崘浠€涔堥兘涓嶅仛**锛氭妸 `ZFF MCP Server.zip` 鏀惧伐浣滅洰褰曟牴涓嬶紝鎻掍欢鍦ㄧ涓€娆¤繘鍏?ZFF MC Mode 鏃朵細
鑷姩瑙ｅ帇鍒?`<宸ヤ綔鐩綍>\zff-mcp-server\`锛岀劧鍚?spawn
`node src/server.js --http --port 57610 --workspace "<宸ヤ綔鐩綍>"`锛屾棩蹇楀湪
`<宸ヤ綔鐩綍>\runtime\zff-mcp-server.{out,err}.log`銆?
鎯虫墜鍔ㄨ捣锛堟瘮濡傛帓鏌ワ級锛?
```powershell
powershell -ExecutionPolicy Bypass -File .\start-mcp.ps1 -Workspace "C:\浣犵殑宸ヤ綔鐩綍"
```

**楠屾敹**锛堜换涓€锛夛細

```powershell
(Invoke-WebRequest http://127.0.0.1:57610/health -UseBasicParsing).Content      # ok
(Invoke-WebRequest http://127.0.0.1:57610/instances -UseBasicParsing).Content   # {"ok":true,"workspace":"鈥?,"instances":[鈥}
```

> 鈿狅笍 鍚屼竴涓鍙ｅ彧鑳藉睘浜?*涓€涓伐浣滅洰褰?*锛氬鏋滀綘鎵嬪姩璧风殑 server 鎸囧悜 A 鐩綍锛岃€屼細璇濆湪 B 鐩綍锛?> 鎻掍欢浼氭姤 `server_workspace_mismatch` 骞跺仠涓嬶紙涓嶄細鎷块敊鐩綍鐨?runtime 鍘昏窇锛夈€?
## 6. 瑁?mod 鍒版父鎴?
鎶?`mcpbridge.jar` 鏀捐繘**浣犺鐜╃殑閭ｄ釜鏁村悎鍖?*鐨?`.minecraft\mods\` 閲屻€?锛圥CL锛氶€変腑鐗堟湰 鈫?鎵撳紑鐗堟湰鏂囦欢澶?鈫?`mods`銆傛暣鍚堝寘鑷甫 mod 涔熶笉鍐茬獊銆傦級

**楠屾敹**锛氬惎鍔ㄦ父鎴忥紝涓昏彍鍗曟甯歌繘鍏ワ紱鏃ュ織閲屾病鏈?`Incompatible client! Please use NeoForge 21.1.228`銆?
## 7. 鍚姩 DSH 骞堕€夋ā寮?
1. 瀹屽叏鍚姩 DSH锛堜笉鏄?鏂板紑绐楀彛"锛屾槸閲嶆柊璧疯繘绋嬶級銆?2. 鏂板缓浼氳瘽 鈫?妯″紡涓嬫媺閫?**ZFF MC Mode**锛堜笉鏄?whale_craft 鐨勩€孧C妯″紡銆嶏級銆?3. 杈撳叆妗嗛偅涓€琛屽彸渚у簲鍑虹幇 **銆屸柖 寮€濮嬫父鎴?路 璁剧疆 路 ?銆?*锛涘凡鏈変細璇濈殑鏍囬鏉″彸渚т篃鏄繖涓変釜銆?
**楠屾敹**锛氳 AI 璋冧竴娆?`zff_status`锛岃繑鍥為噷 `isZffMode: true`銆乣presetId: "zff-mc"`銆乣run.state: "idle"`锛堥粯璁?鈻讹級銆?
## 8. 寮€娓告垙 鈫?鐐?鈻?鈫?AI 鎺ユ墜

**鐜╂硶鏍稿績鏄竴涓紑鍏?*锛?026-11-01 鐜╁瀹氱殑璇箟锛夛細

| 鎸夐挳 | 鍚箟 | 琛屼负 |
|---|---|---|
| **鈻?寮€濮嬫父鎴?* | 宸茬粨鏉燂紙榛樿锛孌SH 閲嶅惎鍚庝篃鍥炲埌杩欓噷锛?| 涓嶆敞鍏ヤ换浣曠画璺戯紱娓告垙宸ュ叿鍙鏀捐锛堣兘闂?鎴戝湪鍝?锛夛紝鏀瑰彉涓栫晫鐨勫伐鍏疯鎷?|
| **鈴?杩涜涓?* | 娓告垙杩涜涓?| 鑷姩缁窇锛涘啀鐐逛竴涓?= 缁撴潫娓告垙 鈫?绔嬪埢鍋滄缁窇 + 缁欏綋鍓嶈疆涓€鏉?鍋滄墜"鎻愮ず |

1. 浣犺嚜宸卞惎鍔?Minecraft锛圥CL / 瀹樻柟鍚姩鍣ㄩ兘琛岋級锛岃繘瀛樻。锛堝崟鏈鸿寰?*鍏佽浣滃紛**锛岃涓嬶級鎴栨湇鍔″櫒锛岃兘鑷敱璧板姩銆?2. 鍥炲埌 DSH 瀵硅瘽锛岀偣 **鈻?寮€濮嬫父鎴?*锛堟寜閽彉鎴?鈴革級銆?   - 娓告垙杩樻病杩涗笘鐣屼篃鑳界偣锛氭寜閽梺浼氭樉绀恒€岋紙绛夊緟瀹㈡埛绔繘涓栫晫锛夈€嶏紝涓€杩涗笘鐣屽氨寮€濮嬬画璺戙€?3. 鎻掍欢鎸?`launcher.playerName`锛堣缃?鈫?娓告垙锛夊湪瀹炰緥鍒楄〃閲屾壘**鍚屽悕**瀹㈡埛绔細
   - 鎵惧埌 1 涓?鈫?鎺ョ锛?   - 涓€涓兘娌℃湁 / 鏈?2 涓悓鍚?/ 鏈夊疄渚嬭繕娌¤繘涓栫晫鎷夸笉鍒板悕瀛?鈫?**涓嶇画璺?*锛屽彧鎻愮ず浣犲幓澶勭悊銆?4. AI 浼氳皟 `mcp__mc__get_state` 纭鍦ㄧ嚎锛岀劧鍚庡紑濮嬪共娲汇€傛兂璁╁畠鍋滐紝鐐?**鈴?*锛涙兂璁╁畠缁х画锛屽啀鐐?**鈻?*銆?
**鍗曟満蹇呴』鍏佽浣滃紛**锛堝惁鍒?`/give`銆乣/setblock` 杩欎簺鎸囦护鍏ㄩ儴澶辫触锛屾柟鍧楁祴璇曚篃娌℃硶鍋氾級锛?杩涗笘鐣屽悗鎸?`Esc` 鈫?**Open to LAN** 鈫?`Allow Commands: ON` 鈫?`Start LAN World`銆?
**楠屾敹**锛歚mcp__mc__get_state` 杩斿洖閲屾湁浣犵殑鍧愭爣/琛€閲?缁村害锛屼笖 `inWorld: true`銆?`mcp__mc__mc_instances`锛堟垨 `GET /instances`锛夐噷浣犵殑瀹炰緥 `username` 绛変簬閰嶇疆鐨?`playerName`銆?
## 9. 璁剧疆椤碉紙4 涓爣绛撅紝淇濆瓨鍗崇敓鏁堬級

| 鏍囩 | 浣滅敤 | 瀛樺摢 |
|---|---|---|
| 鎻愮ず璇?A/B/C | 棰勮榛樿鍊?/ 鍦ㄧ嚎缂栬緫 / 涓婁紶 `.md` / 鎭㈠榛樿锛汢 娓呯┖鍒欒娈典笉娉ㄥ叆锛涙瘡浠?鈮?4 KB | `~/.dsh/zff-mc/prompts/*.md` |
| 娓告垙 | 浣犵殑娓告垙鍚嶃€佹湇鍔″櫒鍦板潃锛堝彧浣滀负"浜嬪疄"鍐欒繘鎻愮ず璇嶏級 | `~/.dsh/zff-mc/settings.json` |
| 缁窇 | 鎬诲紑鍏炽€佷粠鑱婂ぉ璇?鍙仠"銆佺┖杞?N锛?0锛夈€佽嚜涓?M锛?锛夈€佷綆鑰楅棿闅斺€?| 鍚屼笂 |
| 绠＄悊鍛?| `adminPlayers`锛堟瘡琛屼竴涓紱鐣欑┖ = 璋侀兘涓嶈兘璁?AI 涓嬬嚎锛?| 鍚屼笂 |
| 鑱婂ぉ瑙勫垯 | 涓柇姝ｅ垯锛坄^楸糮锛夈€佸繀椤诲洖澶嶇殑鍏抽敭璇嶃€佽眮鍏嶅伐鍏?| `<宸ヤ綔鍖?/runtime/zff-chat-rules.json`锛堢儹鍔犺浇锛?|

> 鎻愮ず璇嶆槸銆屼笅涓€涓細璇濈敓鏁堛€嶏紝鍏朵綑**淇濆瓨绔嬪嵆鐢熸晥**銆?
## 10. 鏁呴殰鎺掓煡

| 鐜拌薄 | 鍘熷洜 | 澶勭悊 |
|---|---|---|
| 鍚姩寮规 `web boot: 1 entry did not activate` + `@local/zff-mc: import failed` | 鈶?瀹㈡埛绔?bundle 閲嶅鎵ц 鈶?鎻掍欢 `apply()` 鎶涢敊 鈶?**瀹㈡埛绔ā鍧?id 鈮?鍖呭悕**锛堝巻鍙蹭笁杩烇級 | 褰撳墠鐗堟湰宸蹭慨锛涜嫢澶嶇幇锛岀湅 `%APPDATA%\@deepseek-ai\dsh-desktop\logs\crash-*.log` |
| 璁剧疆椤垫姤 `unknown endpoint GET /api/zff-mc/settings` | 瀹夸富杩樺湪璺戞棫浠ｇ爜 | **瀹屽叏閲嶅惎 DSH** |
| `mcp__mc__*` 宸ュ叿涓嶅嚭鐜?| MCP server 娌¤捣 / `mc-control` 娌￠厤 / 绔彛琚崰 | 瑙佺 5 姝ワ紱`netstat -ano | findstr 57610` |
| 宸ュ叿鍑虹幇浣嗚皟涓嶅姩 | 娓告垙娌″紑 / mod 鐗堟湰涓嶅尮閰?| 鐪嬫父鎴忔棩蹇楃殑 mod 鍔犺浇琛岋紱纭 NeoForge 21.1.228 |
| AI 涓嶅姩 | `zff_status` 鐪?`paused` / `terminated` / 鎺夌嚎锛涙ā寮忔槸涓嶆槸 ZFF MC Mode | 璇淬€岀户缁€嶈В闄ゆ殏鍋?|
| 鎻掍欢鎶?DSH 鎼炴寕 | 浠讳綍鎻掍欢 bug | `node scripts\zff-mc-toggle.mjs --off` 鎽樻帀鍚庨噸鍚?|

**鏃ュ織浣嶇疆**

- 鎻掍欢锛歚C:\Users\<浣?\.dsh\zff-mc\logs\zff-mc.log`
- DSH 宕╂簝锛歚%APPDATA%\@deepseek-ai\dsh-desktop\logs\crash-*.log`
- MCP server锛歚start-mcp.ps1` 鐨勭獥鍙ｈ緭鍑?- 娓告垙锛歚.minecraft\logs\latest.log`

## 11. 鍗歌浇

```powershell
$dsh = "$env:LOCALAPPDATA\Programs\DeepSeek Harness\resources\runtime\cli\bin\dsh.cmd"
& $dsh plugin --profile desktop remove "@local/zff-mc"
```

鍐嶄粠 `cordis.patch.yml` 鍒犳帀 `zff-mc` 閭ｄ竴娈点€乣mods` 閲屽垹鎺?jar銆乣~/.dsh/zff-mc` 鍙暀鍙垹銆?
## 12. 瑁呭畬鑷锛堝彲閫夛紝30 绉掞級

```powershell
node C:\zff\plugin\selfcheck.mjs            # 鏈熸湜 148/148
node C:\zff\plugin\..\test\zff-client-bundle.mjs   # 鏈熸湜 7/7
```

涓ら」鍏ㄧ豢锛岃鏄庢彃浠剁殑濂戠害锛堝埞杞?娉ㄥ叆/鎻愮ず璇?涓嬬嚎/宸ュ叿/鎵撳寘锛夊湪浣犺繖鍙版満鍣ㄤ笂涔熸垚绔嬨€?
## MCP 鐘舵€佹爮锛?026-11-01 鏂板锛?
鏍囬鏉★紙鎴栨柊浼氳瘽椤碉級閭ｄ竴琛岄櫎浜?鈻?鈴搞€佽缃€佸洟闃燂紝杩樺浜嗕竴鏉?**MCP 鐘舵€佹爮**锛?
```
鈼?MCP 杩愯涓? pid 1234  宸ヤ綔鐩綍 D:\MC Agent Play  闈㈡澘鍚姩          [鍏抽棴 MCP]
妗ユ帴瀹炰緥锛?锛夛細DSHDeepSeek@65146(涓栫晫鍐?
```

- **鈼?鐏?* = 鏈惎鍔紱**鈼?缁?* = 鍋ュ悍锛?*鈼?榛?* = 杩涚▼鍦ㄤ絾鍋ュ悍妫€鏌ヤ笉閫氾紙鎸傝捣锛夈€?- 銆屽惎鍔?MCP銆嶅湪褰撳墠浼氳瘽鐨勫伐浣滅洰褰曞惎鍔細**鏈夎В鍘嬪ソ鐨勬枃浠跺す灏辩敤锛涘彧鏈?zip 鎵嶄細瑙ｅ帇**銆?- 銆屽叧闂?MCP銆嶄細鎶?**server + 鑱婂ぉ瀹堟姢杩涚▼ + 瀹冧滑鐨勫瓙杩涚▼**鏁存５鏍戞潃鎺夛紙涓嶆槸鍙潃涓€涓級銆?- 鐐?鈻?鏃跺鏋?MCP 娌¤捣鏉ワ細**鑷姩鍚姩**锛涜捣涓嶆潵銆佹垨宸叉湁鍒殑宸ヤ綔鐩綍鐨?server 鎸傜潃 鈫?寮圭獥璇存槑骞?*淇濇寔 鈻?*銆?- 娓告垙杩涜涓?MCP 鎺変簡 鈫?鑷姩鍒囧洖 鈻讹紙鏆傚仠锛夈€?- DSH 鍏虫帀鍚庯紝server 30 绉掓敹涓嶅埌蹇冭烦浼氳嚜宸遍€€鍑猴紝涓嶄細鐣欏鍎胯繘绋嬨€?
