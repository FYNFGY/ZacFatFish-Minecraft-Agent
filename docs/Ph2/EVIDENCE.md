# Phase 2 Evidence Index

These files support the acceptance claims in [ACCEPTANCE.md](ACCEPTANCE.md) and the handoff in [agent-prompt2.md](agent-prompt2.md).

## Main evidence

- [runtime-tests.md](../../evidence/runtime-tests.md)
- [multiplayer.txt](../../evidence/multiplayer.txt)
- [container.txt](../../evidence/container.txt)
- [block-actions.txt](../../evidence/block-actions.txt)
- [path-100.txt](../../evidence/path-100.txt)
- [heartbeat.txt](../../evidence/heartbeat.txt)
- [dsh.txt](../../evidence/dsh.txt)
- [client-log-checks.txt](../../evidence/client-log-checks.txt)
- [token-check.txt](../../evidence/token-check.txt)
- [stdio.txt](../../evidence/stdio.txt)
- [scan16-final.json](../../evidence/scan16-final.json)
- [recipes-final.json](../../evidence/recipes-final.json)

## Screenshots

- Multiplayer acceptance: [shot-1793198705922-1.png](../../runtime/state/screenshots/shot-1793198705922-1.png)
- Earlier multiplayer view: [shot-1793198394470-1.png](../../runtime/state/screenshots/shot-1793198394470-1.png)
- Current PCL bridge screenshots directory: `C:\Users\ALIENWARE\AppData\Local\state\headlessmc-mcp\screenshots`

## Current runtime evidence (not yet a saved file)

At the handoff, the survival build at `x=7..9, z=0, y=100..102` was verified with `block_query`:

- diamond: `(7,100,0)`, `(9,100,0)`, `(8,101,0)`, `(8,102,0)`
- air: `(8,100,0)`, `(7,101,0)`, `(9,101,0)`, `(7,102,0)`, `(9,102,0)`
- ground: `(7..9, 99, 0)` solid stone

The old buried structure still has two diamond blocks at `(1,99,0)` and `(-1,99,0)` (the earlier handoff said only one; `block_query` on 2026-10-29 shows both). Do not hand-mine them; see `agent-prompt2.md`.

## 2026-10-29 acceptance run

- [chat-send.json](../../evidence/chat-send.json), [chat-history-after-send.json](../../evidence/chat-history-after-send.json) — chat send + server echo
- [fix-use-block-open.json](../../evidence/fix-use-block-open.json), [fix-use-block-close.json](../../evidence/fix-use-block-close.json) — door open/close with before/after properties
- [door-placed.json](../../evidence/door-placed.json), [lever-on.json](../../evidence/lever-on.json), [lever-off.json](../../evidence/lever-off.json), [button-pressed.json](../../evidence/button-pressed.json), [button-released.json](../../evidence/button-released.json) — placed blocks and toggles
- [sched-container-open.json](../../evidence/sched-container-open.json), [sched-quick.json](../../evidence/sched-quick.json), [sched-split.json](../../evidence/sched-split.json), [sched-move.json](../../evidence/sched-move.json), [sched-quick-back.json](../../evidence/sched-quick-back.json) — container round trip under the fixed tick scheduler
- [fix-entity-interact.json](../../evidence/fix-entity-interact.json), [villager-trades.json](../../evidence/villager-trades.json) — trade menu open + offers
- [trade-final.json](../../evidence/trade-final.json), [trade-times2.json](../../evidence/trade-times2.json), [trades-final-reopen.json](../../evidence/trades-final-reopen.json) — real trades, item-count verification, server uses after reopen
- [job-timeout.json](../../evidence/job-timeout.json), [job-disconnect2.json](../../evidence/job-disconnect2.json) — job fault injection
- [no-screenshot-run.json](../../evidence/no-screenshot-run.json) — zero-screenshot end-to-end run (19 calls, 0 screenshots)
- [build-verify-2026-10-29.json](../../evidence/build-verify-2026-10-29.json), [old-structure-2026-10-29.json](../../evidence/old-structure-2026-10-29.json) — diamond build and the two remaining old blocks
- Before/after of the trade bug (kept for the record): [fix-trade-select.json](../../evidence/fix-trade-select.json), [trade-select-villager2.json](../../evidence/trade-select-villager2.json)
- Regression on the final build: [path-cancel-final.json](../../evidence/path-cancel-final.json), [scan8-final.json](../../evidence/scan8-final.json), [recipe-bread-final.json](../../evidence/recipe-bread-final.json), [state-final-2026-10-29.json](../../evidence/state-final-2026-10-29.json)
