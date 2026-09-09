# Session Handoff

Living document, updated regularly during this session so a fresh chat can pick up with full context
without re-reading the whole transcript. Not committed to git if `.gitignore` excludes it - check before
relying on it surviving a `git status`; if it's tracked, treat it as scratch/internal, not a public doc.

## Where things stand right now

Session date: 2026-09-09. Working directory: `C:\Users\Hunter\killer560s-mod`.

**Done, committed, pushed, deployed to all 5 instances:**
1. Terminal Solver polish rounds 6-9 (Dispenser stale-state bug, Melody full custom redesign with
   role-based coloring, orange theming, 500% scale, bounding-box crop, no-pickup clicks, load-flash
   fixes) - commits `1a4fd13` through `18d10df`.
2. Termism - a terminal-practice feature (`/termism` command or Dungeon tab -> Termism): Random (never
   Melody) + a button per other terminal type, generates a fake local practice puzzle reusing
   `TerminalSolverFeature`'s real Odin-derived mechanics, zero highlighting on purpose. Commit `dc8c08e`.
   Boot-tested clean.

**In progress right now:** Copy Chat - "Ctrl+Click to copy" roadmap item (general QoL, not
dungeon-specific), grounded in quoi's own "Copy chat" module (decompiled via javap, 2026-09-09: "Copies
chat on mouse click"). New `com.killer560.hub.copychat` package (`CopyChatConfig`, `CopyChatFeature` -
GLFW live Ctrl-key check + a `Set-Clipboard` PowerShell shellout mirroring `ScreenshotCopyFeature`'s own
established AWT-headless workaround) plus a `CopyChatTab` (added to the Chat folder, right after Click
Translate). **Architecturally coupled to `ClickTranslateFeature`** - a chat line's `Style` can only carry
one `ClickEvent` at a time, so `ClickTranslateFeature.wrap()`'s gate now fires if EITHER Translate or
Copy is enabled, and `tryHandleClick()` checks `CopyChatFeature.isControlDown()` first before falling
through to its own translate logic - modified, not just added to, so double-check this still works
correctly if anything about Click Translate acts up after this.

Build passed (`BUILD SUCCESSFUL`) for `-PcheatBuild=true` after one fix (`Window.getWindow()` doesn't
exist - it's `Window.handle()`, matching `WindowModeFeature`'s own established usage). Currently
boot-testing on `26.1.2 (Mod Only Test)` - **check the actual result before trusting this is done**; if
resuming cold here, check `latest.log` for errors, then in-game confirm: a plain click on a chat message
still translates (if Click Translate is on), and Ctrl+Click copies to clipboard (with Copy Chat on) -
then deploy to the other 4 instances, build legit, update `TESTING.md`, commit + push.

**Note on `taskkill` policy:** Hunter reverted the "leave Minecraft running after boot-test" preference
mid this session (2026-09-09) - back to taskkilling after a clean boot-test log by default now (memory
updated: `feedback_killer560s_mod_boot_test_leave_open.md`). Don't leave instances running between
rounds unless he says so again.

## Standing rules for this project (see CLAUDE.md / memory for full detail - this is a quick reference)

- Every code change: build both `-PcheatBuild=true` and `-PcheatBuild=false`, deploy the cheat jar to
  all 5 instances (MD5 + `unzip -t` verified), boot-test on `26.1.2 (Mod Only Test)` via PrismLauncher
  CLI when touching risky/new mixins (check `latest.log` for mixin errors), update `TESTING.md`, commit
  with `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` trailer, push, report back concisely.
- After a clean boot-test log, leave Minecraft running instead of taskkilling it.
- All new features ship with `enabled = false` by default (persisted config toggle) - **note**: Termism
  is an on-demand tool (command/screen), not a passive background feature, so it has no `enabled` flag,
  matching how "Edit HUD Positions" also has none. If that judgment call is wrong, add one.
  killer560's real identity must NEVER appear publicly - only the "killer560" persona.
- Reference-mod note: NoammAddons, quoi, Odin, and Devonian are all installed in the live-play instances
  (`26.1.2 (Dungeons)`'s mods folder) specifically for decompiling before building anything from the
  roadmap list - don't guess a mechanic from scratch when a reference mod has it. CFR decompiler was
  used earlier in the session (jar has since been deleted from temp) - redownload if needed.
- Any dungeon solver/ESP/timer feature must also work on **p3sim.net**, not just real Hypixel - gate on
  both `hypixel.net` and `p3sim.net` server IPs (see `AutoJoinSkyblockFeature`'s own check for the
  pattern).

## Next steps (in the order killer560 asked for)

1. **Finish Termism** (in progress - see above): confirm boot-test clean, deploy everywhere, commit.
2. **Then work through `ROADMAP.md`, simplest to most complicated**, referencing the specific source mod
   for each item (NoammAddons/quoi/Odin/Devonian per the roadmap's own "Reference-mod note") before
   building it - don't guess mechanics. Keep updating THIS document regularly (killer560's explicit
   instruction: "update the handoff document extremely regularly") so a fresh session can resume cleanly
   if this one runs out of room.
3. Suggested rough simplicity ordering to start from (not exhaustive - re-scan `ROADMAP.md` for the
   full list, this is just a starting point): "Ctrl+Click to copy" (general QoL) and "Slot Binds" look
   like the simplest self-contained items; most of the rest (ESP/aura/timer/auto-X features) need a real
   decompile pass against the specific reference mod first since none of their mechanics are grounded
   yet in this codebase.

## Open questions / things to flag to killer560 if this resumes cold

- Whether Termism's puzzle pools (hand-picked vanilla item names for Starts With / Select, not
  decompiled from Odin's real item roster) are good enough, or whether he wants them grounded in Odin's
  actual random item list instead.
- Whether Termism needs a "keep going / next puzzle after solving" auto-flow vs. the current manual "New
  Puzzle" button.
