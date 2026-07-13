# QA Session: 20260712-191856-validate-dimensional-door-portal

Goal: Validate Dimensional Door portal alignment and inset, manual opening for every door skin, skin conversion crafting, and black-gold user-facing theme.

## Automated Setup

From the Wormholes repository root, run:

```bash
.qa/20260712-191856-validate-dimensional-door-portal/harness_app/run_automated_checks.sh
```

Expected result: both focused test groups pass and the script emits two `QA_EVT` pass lines.

Install the resulting jar through the normal user-owned development task:

```bash
./gradlew buildPsychoLT --no-daemon
```

Reload or restart Wormholes through the existing server workflow before testing. Do not test against a previously installed jar.

## In-Game Checklist

1. Place and open a Dimensional Door facing north/south, then east/west.
   - Expected: the portal sheet sits on the block edge where the closed door leaf was, not in the block center.
   - Expected: a one-pixel inset is visible around the frame; the sheet never clips through the swung-open leaf.
   - Capture one face-on and one side-angle screenshot in `artifacts/`.
2. Obtain `pair`, `personal`, and `public` items with `/wormholes door type=<type>`.
   - Expected defaults: Oak pair doors, blue Warped personal door, red Crimson public door.
   - Expected: all three open by hand.
3. Put one Dimensional Door and one ordinary wooden/bamboo/crimson/warped door anywhere in a crafting table.
   - Expected: the output adopts the ordinary door material.
   - Expected: a paired endpoint remains linked to its original mate; a personal or public door returns to its original destination.
   - Break and replace the converted door once; expected skin and destination remain unchanged.
4. Attempt the same conversion with an Iron Door and a Copper Door as targets.
   - Expected: there is no crafting result.
5. If an old placed Iron Dimension Door exists, load its chunk.
   - Expected: it converts to Crimson in place and still opens the same public pocket.
6. Inspect `/wormholes help`, the portal menus, item names, and the startup console banner.
   - Expected: branded framing is black/dark gray and emphasis is gold; semantic red/green remains; no purple branding remains.
   - Purple magical portal particles are allowed because they are gameplay effects rather than product chrome.

## Return Artifacts

- Server log copied to `logs/latest.log`.
- Portal screenshots saved in `artifacts/`.
- Pass/fail notes recorded in `OBSERVATIONS.md`.
- Then run:

```bash
python3 /Users/brianfopiano/.codex/skills/qa-harness/scripts/qa_session_manager.py collect --session .qa/20260712-191856-validate-dimensional-door-portal --log-file .qa/20260712-191856-validate-dimensional-door-portal/logs/latest.log
python3 /Users/brianfopiano/.codex/skills/qa-harness/scripts/qa_session_manager.py summarize --session .qa/20260712-191856-validate-dimensional-door-portal
```
