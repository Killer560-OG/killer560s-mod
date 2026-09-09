# Session Handoff

Living document, updated regularly during this session so a fresh chat can pick up with full context
without re-reading the whole transcript. Not committed to git if `.gitignore` excludes it - check before
relying on it surviving a `git status`; if it's tracked, treat it as scratch/internal, not a public doc.

## Where things stand right now

Session date: 2026-09-09. Working directory: `C:\Users\Hunter\killer560s-mod`.

**Just finished:** Terminal Solver polish rounds 6-9 (Dispenser stale-state bug, Melody full custom
redesign with role-based coloring, orange theming, 500% scale, bounding-box crop, no-pickup clicks,
load-flash fixes) - all committed and pushed (commits `1a4fd13` through `18d10df`), deployed to all 5
instances, `TESTING.md` updated with checklists for killer560 to confirm live.

**In progress right now:** Termism - a new terminal-practice feature killer560 asked for explicitly
before any roadmap work: `/termism` command or a Dungeon-tab settings button opens a menu with a
"Random" button (never picks Melody) plus one button per other terminal type (Panes, Rubix, Numbers,
Starts With, Select). Picking one generates a fake, fully local practice puzzle (no real Hypixel menu
involved) using the SAME real mechanics already ported from Odin for `TerminalSolverFeature`'s own
solvers. Renders the RAW puzzle with zero highlighting - the point is practicing reading it yourself.

Files added this round (not yet committed as of this handoff's last update - check `git status`):
- `src/main/java/com/killer560/hub/termism/TermismMenuScreen.java` - type picker
- `src/main/java/com/killer560/hub/termism/TermismPracticeScreen.java` - puzzle generator + practice UI
- `src/main/java/com/killer560/hub/gui/tab/TermismTab.java` - settings entry point
- `src/main/java/com/killer560/hub/Killer560ModClient.java` - added `/termism` command (modified, not new)
- `src/main/java/com/killer560/hub/gui/tab/DungeonTab.java` - added `TermismTab` to the folder (modified)

Build passed (`BUILD SUCCESSFUL`) for `-PcheatBuild=true`. Currently boot-testing on
`26.1.2 (Mod Only Test)` before deploying everywhere - **check the actual boot-test result before
trusting this is done**; if this handoff is being read because the session got cut off mid-test, the
next step is: check `latest.log` for mixin/exception errors, then manually open `/termism` in-game
(or via the Dungeon tab) and click through all 5 non-Melody types to confirm no crashes, then deploy to
the other 4 instances (`26.1.2 (Dungeons)`, `26.1.2 (Dungeons) (duo)`, `26.1.2 ALT`, `Taunahi`) with
MD5 + `unzip -t` verification, build the legit variant, update `TESTING.md`, commit + push.

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
