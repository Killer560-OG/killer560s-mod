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

3. Copy Chat - "Ctrl+Click to copy" roadmap item (general QoL, not dungeon-specific), grounded in quoi's
   own "Copy chat" module (decompiled via javap: "Copies chat on mouse click"). New
   `com.killer560.hub.copychat` package (`CopyChatConfig`, `CopyChatFeature` - GLFW live Ctrl-key check +
   a `Set-Clipboard` PowerShell shellout mirroring `ScreenshotCopyFeature`'s own AWT-headless workaround)
   plus a `CopyChatTab` (Chat folder, right after Click Translate). **Architecturally coupled to
   `ClickTranslateFeature`** - a chat line's `Style` can only carry one `ClickEvent`, so
   `ClickTranslateFeature.wrap()`'s gate now fires if EITHER feature is enabled, and `tryHandleClick()`
   checks `CopyChatFeature.isControlDown()` first before falling through to translate - if Click
   Translate ever acts up after this session, check that coupling first. Commit `7aa9883`, boot-tested
   clean, deployed to all 5 instances, `TESTING.md` updated.

**Researched but NOT built yet - see "Next steps" below for what to do with this:**
- **Slot Binds** (in Odin/NoammAddons/Devonian, all three - a genuinely established feature). Decompiled
  Odin's `SlotBinds.kt` via CFR (`java -jar` a redownloaded CFR jar - the one from earlier in the session
  is gone, re-fetch from `https://github.com/leibnitz27/cfr/releases/download/0.152/cfr-0.152.jar` if
  needed again). Real mechanic: "Bind slots together for quick access" - NOT a hotbar-key-to-item bind
  like the name suggests; it links TWO arbitrary inventory slots together (press a "set bind" keybind,
  click slot A, click slot B), persisted per-profile (6 profiles), and draws a colored line between bound
  slots on hover (or hover+shift, or never - a display-mode setting). The actual click/bind-creation logic
  lives in anonymous Kotlin lambda classes (`SlotBinds$1`/`$2`/`$3`/`$4` in the jar) that weren't
  decompiled yet - **that's the next research step before building this**, since the outer class alone
  only shows the settings/event registration, not the actual bind-pairing algorithm. More involved than
  it looks: needs a keybind-gated slot-click interceptor, per-profile persistent slot-pair storage, and
  hover-triggered line rendering across arbitrary screens - moderate complexity, not "simple."
- **Full Block** (quoi). Decompiled via CFR. Real mechanic: "Expands the hitboxes of buttons, chests,
  levers, mushrooms, and skulls" (dungeon secret-related blocks with narrow real interaction hitboxes) -
  a per-block-type toggle plus a hitbox-shape selector ("Expanded" vs "FullBlock"). This is a genuine
  interaction/collision-shape override (`getInteractionShape`/`getOutlineShape`-style mixin on specific
  Block subclasses: `ButtonBlock`, chest blocks, `LeverBlock`, mushroom blocks, skull/wall-skull blocks),
  not just a visual change - moderate-to-higher risk since it touches core block-interaction code across
  every instance of those block types, not a self-contained feature. Didn't find the actual shape-mixin
  class in quoi's own jar in the time available this session (only found the settings/toggle class) -
  would need another decompile pass targeting quoi's actual mixin/block-shape-override classes before
  building this safely.

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

Termism and Copy Chat are both DONE (built, boot-tested, deployed to all 5, committed, pushed). Continue
working through `ROADMAP.md` simplest-to-most-complicated, referencing the specific source mod for each
item before building it - don't guess mechanics. Keep updating THIS document regularly.

**Immediate next candidates**, roughly in order:
1. Finish the Slot Binds research (decompile `SlotBinds$1`/`$2`/`$3`/`$4` from Odin's jar - the actual
   bind-pairing/click-interception logic) before building it. Once grounded, it's a reasonable
   medium-complexity feature: per-profile slot-pair storage (mirror the Gson-config pattern every other
   feature here already uses) + a keybind-gated click interceptor (mirror `StorageOverlayContainerMixin`'s
   own click-redirect pattern) + hover-line rendering (mirror `TerminalSolverFeature`'s own
   `graphics.outline`/pose-transform usage for the visual side).
2. Full Block needs one more decompile pass (find quoi's actual hitbox-shape-override class, not just its
   settings class) before building - flag the real risk (core block-interaction code) to killer560 before
   starting, since a mistake here could affect way more than just dungeon secrets.
3. Beyond those two, re-scan `ROADMAP.md`'s "Dungeon / feature ideas" list fresh - most of the rest
   (ESP/aura/timer/auto-X features) haven't been decompile-checked at all yet this session. Chat Commands
   (Odin, `ChatCommands.class` - a big suite of `/coords`, `/ping`, `/dice`, `/8ball` etc. slash commands)
   is large but each individual command inside it is trivial once the dispatcher shell exists - could be
   a good next target since it's really many tiny features under one roof, not one complex one.
4. CFR decompiler jar note: re-download from
   `https://github.com/leibnitz27/cfr/releases/download/0.152/cfr-0.152.jar` to `/tmp/cfr.jar` (or
   wherever) each session - it doesn't persist between sessions (gets cleaned from temp). Reference mod
   jars live in `26.1.2 (Dungeons)`'s own `minecraft/mods/` folder - extract just the `.class` file(s) you
   need with `unzip`, then `java -jar cfr.jar SomeClass.class --outputdir <dir>` for readable pseudo-Java
   (much easier to read than raw `javap -c` bytecode for anything beyond a few methods).

## Open questions / things to flag to killer560 if this resumes cold

- Whether Termism's puzzle pools (hand-picked vanilla item names for Starts With / Select, not
  decompiled from Odin's real item roster) are good enough, or whether he wants them grounded in Odin's
  actual random item list instead.
- Whether Termism needs a "keep going / next puzzle after solving" auto-flow vs. the current manual "New
  Puzzle" button.
- Full Block's real risk (core block-interaction hitbox code, not a self-contained feature) is worth a
  quick sanity-check with killer560 before building, given the blast radius if it's ever subtly wrong.
