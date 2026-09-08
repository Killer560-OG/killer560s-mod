# Handoff: Killer560's Mod — Experimentation Table Automation

Paste this into a new chat to resume work with full context, without needing the long prior conversation.

## Project basics

- **Location:** `C:\Users\killer560\killer560s-mod` (NOT a git repo — plain folder, no version control).
- **Mod:** Fabric mod, id `killer560smod`, package base `com.killer560.hub`, targets **Minecraft 26.1.2**, Java 25, Mojang mappings.
- **This started as killer560's personal combined mod** ("Killer 560s Mod") — merges several previously-separate mods (Account Switcher, Proxy, Spotify Lyrics, RNG Meter, Experiments automation, etc.) into one. As of 2026-09-08 it's public on GitHub with a legit/cheat build split (see build.gradle's `cheatBuild` property) - the legit build strips Autonomous auto-clicking out entirely.
- There's a **separate, older, unrelated mod** at `C:\Users\killer560\killer560smod` (no hyphen) — the mining macro mod, renamed to "Killer560's Macro Mod." Do not confuse the two folders. This handoff is entirely about the hyphenated `killer560s-mod` project.

## Mandatory workflow for every code change

This has been followed rigidly all session — keep doing it:

1. Make the code change.
2. `cd "/c/Users/killer560/killer560s-mod" && ./gradlew build --console=plain` — must say BUILD SUCCESSFUL.
3. Deploy the built jar (`build/libs/killer560smod-1.0.0.jar`) to **all 4 Prism Launcher instances**:
   - `C:\Users\killer560\AppData\Roaming\PrismLauncher\instances\26.1.2 (Dungeons)\minecraft\mods\`
   - `C:\Users\killer560\AppData\Roaming\PrismLauncher\instances\26.1.2 (Dungeons) (duo)\minecraft\mods\`
   - `C:\Users\killer560\AppData\Roaming\PrismLauncher\instances\26.1.2 ALT\minecraft\mods\`
   - `C:\Users\killer560\AppData\Roaming\PrismLauncher\instances\Taunahi\minecraft\mods\`
   - For each: `rm -f` the old jar, `cp` the new one, `md5sum` both source and dest and confirm MATCH, `unzip -t` the dest to confirm ZIP OK.
4. **Known gotcha:** if Minecraft is currently running on the `26.1.2 (Dungeons)` instance (the one killer560 live-tests on), `rm` will fail with "Device or resource busy." The `cp` usually still succeeds and MD5 still matches, but the **already-running game keeps using the OLD in-memory code** until killer560 fully closes and relaunches it. Always flag this explicitly when it happens — don't just report "deployed" silently.
5. Update the project memory file at `C:\Users\killer560\.claude\projects\C--Users-killer560\memory\project_killer560s_consolidation.md` — `wc -l` it first, then `Edit` to insert a new dated entry (root cause, real evidence, fix, build md5) right before the stable "Mod menu reorganized into 5 top-level tabs (2026-09-03)" anchor entry near the end. This file is huge and append-only; don't try to read the whole thing, just insert near that anchor.
6. Report back to killer560 what was fixed, the md5, and whether all 4 instances deployed cleanly.

## Debugging discipline established this session (keep following it)

- **Read killer560's actual Minecraft logs directly** rather than guessing from his verbal description. Logs live at `C:\Users\killer560\AppData\Roaming\PrismLauncher\instances\<instance>\minecraft\logs\` — `latest.log` for the current/most recent session, `YYYY-MM-DD-N.log.gz` for rotated ones (use `zcat file.gz | grep ...`). This repeatedly found the *real* bug instead of a plausible-sounding wrong one. Always check file mtimes/timestamps to make sure you're reading the log that actually corresponds to the test he's describing — multiple short launches in one evening is common, and it's easy to grab a stale log.
- **Never guess Hypixel's exact tooltip/lore text.** Every text-matching bug this session came from an earlier assumption about exact wording that turned out to be wrong once real lore was logged. When adding new text-matching logic, either get a real log/screenshot first, or add diagnostic logging and wait for the next real test rather than guessing twice.
- **Verify unfamiliar Minecraft/Mojang-mapped APIs via `javap`** against the real decompiled jar before relying on them, rather than trusting training-era assumptions — this MC version (26.1.2) has diverged significantly from older/vanilla APIs in several places (see below). Real jar for javap:
  `C:\Users\killer560\killer560s-mod\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-043a8b3edf\26.1.2\minecraft-merged-043a8b3edf-26.1.2.jar`
  Extract a class with `unzip -o jar "path/to/Class.class" -d dir`, then `javap -p [-c] dir/path/to/Class.class`.
- **Prefer hard, unconditional gates over precise attribution logic** when parsing Hypixel lore — e.g. checking for the literal "Cannot afford this!" line is more robust than trying to compute affordability from cost/balance lines that may or may not be present.
- **A Google "AI Overview" on a technical protocol question was checked this session and found to have fabricated a nonexistent class name** (`ClientboundTickPacket`) — worth being skeptical of those on anything protocol/API-specific; verify against the real jar instead.

## The feature under active work: Experimentation Table autonomous automation

This is a bot that plays Hypixel Skyblock's Experimentation Table (Chronomatron, Ultrasequencer, Superpairs) and manages Renew Experiments / Titanic Experience Bottle purchases, either fully autonomously (real clicks) or in "Solver Only" mode (highlight-only, never clicks — for a human to play manually with guidance).

**Core files** (all in `src/main/java/com/killer560/hub/experiments/`):
- `ExperimentsFeature.java` — the client-tick-driven orchestrator. Reads whichever container screen is open, decides autonomous-click vs. solver-only-highlight, handles the emergency-cancel keybind, claim-reward detection, jittered action scheduling (`scheduleAction`/`scheduleClick`), and the two render hooks (see z-order gotcha below).
- `ExperimentNavigator.java` — drives menu-to-menu navigation: picks tiers, clicks into games, manages Renew Experiments (with its own configured daily cap `autoRenewCount`), buys Titanic Experience Bottles when XP is short, and now detects when the whole run is genuinely done for the day (`DONE_SIGNAL`).
- `ExperimentSolver.java` — the actual puzzle-solving logic for all three minigames (ported from the real, working `astrail-experiment` mod, cross-checked against SkyHanni's real source for lock text / threshold math). Tracks known Superpairs tile identities across the whole board permanently (not just currently-visible ones).
- `GuardianPetSwapper.java` — swaps to the Guardian pet (for the Superpairs "ultra rare book" chance bonus) before a run starts, without despawning an already-active Guardian.
- `mixin/ExperimentsGuiMixin.java` / `mixin/ExperimentsContainerRenderMixin.java` — the two render hooks; see the z-order note below, this distinction matters a lot.

## Current build state

Last deployed build md5: **`f8afa2fbb8a2341b6b0d0182b46ad178`**, deployed clean to all 4 instances. This is the build killer560 is about to test next — see "Pending test" below.

## What's fixed and confirmed working this session (don't re-investigate these)

- **Titanic Experience Bottle purchases now capped at 1 per visit** to Bottles of Enchanting (previously bought unlimited times in a loop if affordable).
- **Emergency-cancel keybind now actually stops mid-puzzle clicking** — it used to only gate the menu-navigation logic, not the actual Chronomatron/Ultrasequencer/Superpairs auto-click loop, which was gated on "Autonomous Mode is on" alone, independent of whether the run was armed.
- **`DONE_SIGNAL` mechanism** — the navigator now recognizes two genuine stopping conditions and fully unarms + shows a green overlay message explaining why, instead of looping or sitting idle forever: (1) all three games done for the run AND Renew Experiments exhausted for the day (config cap reached / disabled / blocked by Bits / blocked by XP with Titanic disabled), (2) a Titanic Experience Bottle purchase is genuinely unaffordable (including a **real Bazaar-coin-balance shortfall**, detected via the literal "You don't have enough Coins!" lore line — distinct from just exceeding killer560's configured budget). Both confirmed working in the field.
- **Escape now unbinds the emergency-cancel keybind** instead of literally binding Escape to it (matches vanilla's own keybind-menu convention). Verified `InputConstants.KEY_ESCAPE` via javap.
- **Real z-order rendering bug, found and fixed, affecting ALL THREE highlight overlays** (Chronomatron/Ultrasequencer/Superpairs labels): they were being drawn from the *early* `Gui`-level HUD render pass (`ExperimentsGuiMixin`), which runs **before** the container screen paints its own darkened background — so every highlight tint was drawing, then immediately getting covered/hidden, invisible the whole time despite correct underlying logic. This is the exact same bug already found and fixed once before for the "Start ETable" button. Fixed by moving the highlight draw call into `renderStartButtonOverContainer` (the correct LATE pass, `ExperimentsContainerRenderMixin`, hooked to `AbstractContainerScreen.extractRenderState` at TAIL). **If you ever add a new overlay/highlight to this feature, draw it from `renderStartButtonOverContainer`, never from `renderOverlay`/`ExperimentsGuiMixin` — that early pass is a trap.**
- **Chronomatron/Ultrasequencer highlight redesigned**: shows only the very next slot to click (green) and the one after it (orange) — not the whole accumulated sequence with numbers.
- **Superpairs stuck-forever bug fixed**: the click-pacing mechanism (see below) had no timeout, so a single dropped/unconfirmed click could permanently halt all further clicking for the rest of the round. Added a 3-second timeout fallback.
- **Superpairs "long stall then click extremely fast" bug fixed**: the confirm-check was comparing a tile's *entire* visible state (itemId/count/foil/name/lore) for equality, and something cosmetic (likely lore or count) was flickering on the covering item even without a click — causing false-positive early "confirmed" clears most of the time (bursts) and, on whichever tick the noise happened to coincidentally re-match, a full timeout wait (long stalls). Narrowed the check to just `itemId` + `empty()` — the only two things that define a tile's real identity.
- **Superpairs click pacing redesigned from a fixed delay to real state confirmation**: instead of waiting a guessed number of milliseconds between clicks, the solver now snapshots what a slot looked like the instant it decides to click it, and won't issue another click until it actually observes that slot's state change in a real snapshot (or the 3s timeout above fires). This is deliberately not tick/TPS-based — investigated deeply (see below) and confirmed no better signal exists; watching the actual effect land is already the most precise signal available.
- **Superpairs highlight now shows the real item name on every known tile** (not just confirmed pairs) — gold for confirmed matches, blue for known-but-unmatched singles, both with a short label (color codes stripped, truncated ~8 chars). Green suggestion tile shown when nothing at all is known yet.

## Pending / not yet field-tested

killer560 has not yet confirmed in-game whether the **z-order render fix** (highlights actually becoming visible) and the **narrowed Superpairs confirm-check** (steady pacing instead of stall-then-burst) actually work. This is the very next thing to verify. If either is still broken, **get a real log** (see logging conventions below) before proposing another fix — don't guess a third time on the same mechanism.

## Two things explicitly closed off, do not revisit unless killer560 brings them up again

- **Tick-time-based pacing was investigated in depth and rejected.** Confirmed via `javap` bytecode inspection that `ClientLevel.tickTime()` is a client-local free-running counter (`dayTime + 1` every client tick, unconditional) that does *not* reflect real server-tick progress between periodic server corrections — so it would be functionally identical to using milliseconds. There is no generic "a new server tick just arrived" packet/event in the protocol (confirmed via jar inspection: no `ClientboundTickPacket` exists; the only real "Ticking*" packets are vanilla's admin `/tick freeze`/`/tick step`/`/tick rate` debug tools, irrelevant here and not something Hypixel would send anyway). Even if such a signal existed, a tick boundary isn't the same thing as "my specific click was processed" — a category mismatch, not a precision problem. **Conclusion: watching the actual click's effect land (current design) is already the best available signal. Don't reopen this.**
- Real measured ping (`PlayerInfo.getLatency()`, confirmed accessible via `Minecraft.getInstance().getConnection().getPlayerInfo(uuid)`) was identified as a legitimate real signal that *could* make the Superpairs 3-second timeout adaptive instead of a flat constant — **offered to killer560, not yet built, no decision made**. Pick this up only if he asks for it.

## Logging conventions in this feature (useful for reading future logs)

- Logger names: `killer560smod-experiments` (ExperimentsFeature), `killer560smod-experiments-nav` (ExperimentNavigator), `killer560smod-experiments-solver` (ExperimentSolver, added this session).
- Key log lines to grep for when diagnosing: `"Navigator sees screen"`, `"Navigator clicking slot"`, `"Clicking slot ... (mode="`, `"cannotAffordRenew"`, `"isRenewBlockedByXp"`, `"Titanic Experience Bottle lore="`, `"Autonomous run finished on its own"`, `"never confirmed within"`.
