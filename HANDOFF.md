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

4. **Secrets (Full Block)** - expanded interaction hitboxes for Levers/Buttons (Flat or Full Box
   choice)/Chests/Wither Essence (skulls), each its own toggle, under a new Dungeon -> Secrets tab. Deep
   dive per killer560's explicit request to cross-reference BOTH quoi and NoammAddons "very in depth."
   New `com.killer560.hub.secrets` package: `SecretsConfig`, `SecretsFeature` (server-gate: hypixel.net
   OR p3sim.net, per-type toggle checks, the 6 hardcoded button-face shapes ported from quoi), and 6
   mixins targeting `LeverBlock`/`ButtonBlock`/`ChestBlock`/`SkullBlock`/`WallSkullBlock`.getShape at HEAD
   (cancellable), plus a broad `BlockBehaviourMixin` on `BlockBehaviour` (parent of every block).
   **Real finding from the cross-reference** (both mods independently arrived at the same fix): a block
   with REAL solid collision (chests, skulls) needs its `getCollisionShape` separately pinned back to its
   ORIGINAL shape after `getShape` is expanded, or the player's own movement collision silently becomes a
   full solid cube too. Levers/Buttons don't need this (their real collision is hardcoded empty,
   independent of shape) - confirmed by neither reference mod bothering with it for those two.
   Deliberately simpler than NoammAddons in one way: no in-dungeon-room "is this a known secret"
   gate (needs a secrets-position database this mod doesn't have) or per-floor lever blacklist - just
   "toggle on + connected to hypixel.net/p3sim.net", documented as a known simplification.
   All real field/method names (`getShape`, `getCollisionShape`, `SHAPE`, `HALF_SHAPES`, `SHAPE_PIGLIN`,
   `SHAPES`, `FACE`, `FACING`, `getConnectedDirection`, `SkullBlock.Types.PIGLIN`) verified via javap
   against this project's own compiled MC 26.1.2 jar before writing any mixin, not just copied blind from
   the decompile. Build passed clean on the first try.

   **Real bug hit and fixed during boot-test**: the FIRST boot crashed at bootstrap with
   `IllegalClassLoadError: com.killer560.hub.secrets.mixin.OriginalCollisionShapeProvider is in a defined
   mixin package com.killer560.hub.secrets.mixin.* owned by killer560smod-secrets.mixins.json and cannot
   be referenced directly`. Root cause: the shared `OriginalCollisionShapeProvider` interface (implemented
   by ChestBlockMixin/SkullBlockMixin/WallSkullBlockMixin) was placed inside
   `com.killer560.hub.secrets.mixin` - the SAME package the mixin config's own `"package"` field claims -
   and Mixin's classloader reserves that entire package for actual `@Mixin` classes only, refusing to load
   a plain interface from it. quoi's own equivalent (`IOriginalCollisionShapeProvider`) already keeps this
   exact separation (its own dedicated `mixininterfaces` package, distinct from `mixins`) - missed porting
   that packaging detail on the first pass. **Fixed** by moving the interface to `com.killer560.hub.secrets`
   (alongside `SecretsConfig`/`SecretsFeature`, NOT the `.mixin` subpackage) and updating the 4 mixin
   classes' imports. Re-deployed and re-boot-testing now - **general lesson for any future mixin work in
   this codebase using a shared interface across multiple mixin classes: that interface must live OUTSIDE
   the `.mixin` subpackage declared in the mixins.json's own `"package"` field, always.**

   **DONE**: both bugs fixed, boot-tested clean (no mixin errors, no crash reports, reaches Title
   Screen), deployed to all 5 instances, legit built, `TESTING.md` updated with an extra-thorough
   checklist given the risk, committed `10dc3f9`, pushed. This is the highest-risk mixin set this session
   (broad `BlockBehaviour` target, core interaction/collision code) - it has NOT been verified with real
   in-game interaction yet (can't automate that), so treat it as boot-tested-only until killer560
   confirms live, especially the "every toggle OFF behaves like vanilla" and "chests still block movement
   normally when their hitbox toggle is on" checks in `TESTING.md`.

5. **Secrets follow-up** (commit `cb3a407`): killer560 asked for two more gates plus a "double check you
   missed nothing" pass. Added `com.killer560.hub.secrets.DungeonState` - real dungeon-floor detection
   via the sidebar scoreboard (mirrors this mod's own existing `LocationTracker` technique) and real
   boss-phase detection via the actual F7/M7 boss chat line (grounded in SkyHanni's `DungeonBossApi`
   reference, decompiled). New toggles: **Dungeons Only** (all 4 types) and **Boss Only**
   (Levers/Buttons only - restricts to the real F7/M7 boss fight, this mod's alternative to NoammAddons'
   per-floor lever blacklist for the same precision-puzzle-lever concern). Also found and fixed a real
   gap during the "double check" pass: Wither Essence is one specific player-head SKIN on a vanilla
   skull block, not a distinct block type - the original version expanded every skull's hitbox when
   Essence was on; now checks the real skin profile UUID first (ported from NoammAddons'
   `DungeonUtils.isSecret`). Boot-tested clean. **Not yet confirmed against a real dungeon run** - this
   is the first time this mod reads the sidebar scoreboard for dungeon/boss state, so the new
   `TESTING.md` checklist for this round deserves real verification before being trusted.

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

Termism, Copy Chat, and Secrets (Full Block) are all DONE (built, boot-tested, deployed to all 5,
committed, pushed). Continue working through `ROADMAP.md` simplest-to-most-complicated, referencing the
specific source mod for each item before building it - don't guess mechanics. Keep updating THIS document
regularly.

**Immediate next candidates**, roughly in order:
1. Finish the Slot Binds research (decompile `SlotBinds$1`/`$2`/`$3`/`$4` from Odin's jar - the actual
   bind-pairing/click-interception logic) before building it. Once grounded, it's a reasonable
   medium-complexity feature: per-profile slot-pair storage (mirror the Gson-config pattern every other
   feature here already uses) + a keybind-gated click interceptor (mirror `StorageOverlayContainerMixin`'s
   own click-redirect pattern) + hover-line rendering (mirror `TerminalSolverFeature`'s own
   `graphics.outline`/pose-transform usage for the visual side).
2. Re-scan `ROADMAP.md`'s "Dungeon / feature ideas" list fresh - most of it hasn't been decompile-checked
   at all yet this session. Chat Commands (Odin, `ChatCommands.class` - a big suite of `/coords`, `/ping`,
   `/dice`, `/8ball` etc. slash commands) is large overall but each individual command inside it is
   trivial once the dispatcher shell exists - could be a good next target since it's really many tiny
   features under one roof, not one complex one.
3. CFR decompiler jar note: re-download from
   `https://github.com/leibnitz27/cfr/releases/download/0.152/cfr-0.152.jar` to `/tmp/cfr.jar` (or
   wherever) each session - it doesn't persist between sessions (gets cleaned from temp). Reference mod
   jars live in `26.1.2 (Dungeons)`'s own `minecraft/mods/` folder - extract just the `.class` file(s) you
   need with `unzip`, then `java -jar cfr.jar SomeClass.class --outputdir <dir>` for readable pseudo-Java
   (much easier to read than raw `javap -c` bytecode for anything beyond a few methods).
4. **New general lesson from this session's Secrets work, apply to ANY future mixin with a shared
   interface**: that interface must live OUTSIDE the `.mixin` subpackage a mixins.json declares as its own
   `"package"` - Mixin's classloader refuses to load a plain (non-@Mixin) class from that package. Also:
   any mixin on a `getShape`/`getCollisionShape`/similar block-shape method can fire during
   `Blocks.<clinit>` bootstrap, before `Minecraft.getInstance()` exists - null-guard it.

## Open questions / things to flag to killer560 if this resumes cold

- Whether Termism's puzzle pools (hand-picked vanilla item names for Starts With / Select, not
  decompiled from Odin's real item roster) are good enough, or whether he wants them grounded in Odin's
  actual random item list instead.
- Whether Termism needs a "keep going / next puzzle after solving" auto-flow vs. the current manual "New
  Puzzle" button.
- Secrets (Full Block) needs killer560's own real in-game confirmation before it's fully trusted - see
  the TESTING.md checklist, especially "every toggle OFF behaves like vanilla" and chests still blocking
  movement normally when their toggle is on.
