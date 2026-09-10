# Session Handoff

Living document, updated regularly so a fresh chat can pick up with full context without re-reading the
whole transcript. This file IS tracked/pushed to the public GitHub repo - never put secrets (tokens,
real identity info) in it, only paths to where secrets live. Treat it as informal internal notes, not
polished public docs.

## Where things stand right now

Session date: 2026-09-10. Working directory: `C:\Users\Hunter\killer560s-mod`. **Current released
version: v1.1.0** (bumped from 1.0.0 this session - `gradle.properties`' `mod_version`). GitHub Release
is live at https://github.com/Killer560-OG/killer560s-mod/releases/tag/v1.1.0 with both jars attached.

The major feature built this session was **Auto Terminals** - real auto-clicking of Floor 7 terminal
puzzles (Panes, Rubix, Numbers, Starts With, Select, Melody), cheat build only. Everything below covers
its build-out, the real bugs found and fixed in it, a bunch of Termism (practice mode) puzzle-generation
tuning, a new per-account-proxy feature on Account Switcher, moving Full Block to cheat-only, and
publishing v1.1.0 (GitHub Release + Discord updates).

## Build variant gating - what's cheat-only right now

`com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED` is a compile-time-generated `boolean` (see
`build.gradle`'s `generateBuildVariant` task) - `true` for `-PcheatBuild=true`, `false` otherwise. Gate the
CONFIG GETTER itself (not just the UI), e.g. `isXEnabled() { return CHEAT_FEATURES_ENABLED && rawField; }`
- this is the established pattern so a legit build can never run the feature even from a copied
config.json. As of v1.1.0, exactly three things are cheat-gated, confirmed directly by killer560
("cheat variant should have hitbox's auto etable and auto terms"):

1. **Auto ETable Autonomous Mode** - `ExperimentsConfig#isAutonomousMode`.
2. **Auto Terminals** - `TerminalSolverConfig#isAutoTerminalsEnabled`. Its own tab (`AutoTerminalTab`) is
   only added to `DungeonTab`'s list when `CHEAT_FEATURES_ENABLED` - legit build has no tab at all.
3. **Full Block (hitboxes)** - `SecretsConfig#isMasterEnabled` (moved to cheat-only in v1.1.0). Its tab
   (`SecretsTab`) is likewise only added to `DungeonTab` on the cheat build now.

**Fullbright was explicitly considered and rejected** for cheat-gating (killer560's call, 2026-09-10) -
it stays on both builds. Don't re-propose it without new context.

## Auto Terminals architecture

- `TerminalSolverFeature.java` - `tickAutoClick()` (the 5 non-Melody types, reusing the same
  `currentHighlights` the Custom GUI overlay already computes every frame), `tickMelodyAutoClick()`
  (Melody is fully separate - no solved/correct set, real-time lime/target-marker tracking instead),
  `pickAutoClickTarget()` (public, parameterized - reused by Termism), `sendTerminalClick()` (uses
  `SlotClickInvoker` + `ContainerInput.CLONE`, matching the Custom GUI click-redirect's own real click
  path; Rubix uses real `PICKUP` left/right instead).
- `TerminalSolverConfig.java` - per-type toggles, `autoClickMinDelayMs`/`autoClickMaxDelayMs`
  (`rollAutoClickDelayMs()` picks a fresh random value per click), `blockInputWhileAutoClicking` (default
  ON), `melodyLookaheadClicks` (0-4), `melodySkipMode` (`MelodySkipMode.EDGES` default / `ALL`).
- `AutoTerminalTab.java` - its own tab under Dungeon, cheat-only (see above).
- `TerminalAutoClickInputBlockMixin` - swallows real input while auto-clicking; Escape is explicitly
  exempted (matches NoammAddons' own real precedent) so the menu can always be closed manually.
- Also works in **Termism** (`TermismPracticeScreen`) - reuses the exact same `pickAutoClickTarget`
  decision logic against a locally-generated puzzle, only active when Termism's own Custom GUI is on.

### Real bugs found and fixed in Auto Terminals (chronological, each one a real lesson)

1. **Melody target-marker color** - only recognized `MAGENTA_STAINED_GLASS_PANE`; this class's OWN
   Melody rendering code already knew (from a much earlier round) that a real board's marker can be
   PURPLE or MAGENTA. Fixed by reusing the same `paneDyeColor`/`isMelodyEndpointColor` classification the
   rendering path already used, instead of a separate narrower check. A real, legitimate fix - just not
   the dominant cause of the reported symptom (see #2).
2. **THE Melody bug**: `correctColumn` was computed as `targetSlot - 1` - the marker's RAW slot index
   across the whole board (up to 54 slots) - while `currentColumn` was correctly reduced to a same-row
   column via `limeSlot % 9 - 1`. The two could only match by coincidence (row 0's absolute slots happen
   to already be small). This is why "only row 0 ever clicks" was the exact symptom reported. Fixed by
   applying the same `% 9 - 1` reduction to `targetSlot`. **Lesson: when two values are compared for
   equality, verify both sides actually got reduced to the same unit before assuming a color/detection
   bug.**
3. **Same-slot re-click guard had no timeout** - if a single click packet ever silently didn't register
   (real network hiccup, or Hypixel dropping/throttling one), the guard blocking re-clicks on that exact
   slot deadlocked FOREVER (confirmed in a real log: 46 seconds straight of "waiting for it to clear").
   Fixed with `SAME_SLOT_RETRY_TIMEOUT_MS = 1500` - past that, retry instead of waiting forever.
4. **First-click race against Hypixel's own screen-reopen** - a real log showed Hypixel sending TWO
   `Screen opened` packets back to back the instant a terminal first activates (confirmed for both Melody,
   which reopens continuously as it animates, AND regular terminals, which do it once on activation). The
   very first auto-click could land in that split second and get silently dropped. Fixed with a 500ms
   settle window (`stabilizedAtMs`/`INITIAL_CLICK_SETTLE_MS`) before the first click on a freshly-opened
   terminal - lets the real reopen finish before racing it. The #3 retry timeout stays as a backstop for
   genuine click loss elsewhere in a terminal's life.

**The established debugging pattern that found all of #2-#4**: add throttled `LOGGER.info` diagnostic
lines (once/sec max) explaining WHY a decision was/wasn't made, ask killer560 to reproduce and grab
`logs/latest.log`, then read the REAL log rather than guessing at a fix. This worked every single time it
was tried this session; guessing without a log did not (round 28's purple/magenta fix, while real, missed
the actual dominant bug). **If a future Auto Terminals report comes in without a log, ask for one before
touching code again.**

## Termism puzzle-generation tuning - use SIMULATION, not hand math

Termism (`TermismPracticeScreen.java`) generates practice puzzles for Panes/Rubix/Numbers/Starts
With/Select/Melody. This session tuned how many "correct answer" cells each of Panes/Starts With/Select
shows, across several rounds of killer560 giving quantitative targets ("favor higher", "max available",
"about 50% higher/lower"). **Hand-calculating the resulting mean got it wrong at least once** (round 35's
pool expansion accidentally converged 11 of 19 letters to exactly 3 items each, verified only after
writing a standalone Python simulation of the actual algorithm). Every round after that, the working
pattern was: **write a quick Python simulation of the exact formula BEFORE touching Java code**, tune
parameters against it until the target mean is hit, then implement. Do this again for any future
"raise/lower the average by X%" request rather than reasoning about `Math.max`/`Math.min` distributions by
hand.

Current tuning state (all still keep their FULL original range reachable, including the max - a `Math.min`
formula just changed which end of the range is weighted):
- **Panes** (`generatePanes`, range 4-15): single plain roll, no bias. Mean ~9.5.
- **Select** (`generateSelect`, range 2-28): `Math.min` of two rolls (biased low). Mean ~10.5.
- **Starts With** (`generateStartsWith`, range 2-21, `STARTS_WITH_POOL` now ~93 items): `matchCount`
  capped by `matches.size()` (how many pool items share the picked letter) with a max-of-two-rolls bias;
  letter SELECTION itself also biased toward richer letters via `CANDIDATE_COUNT = 5` (draws 5 candidate
  items, keeps whichever one's letter has the most matches). Mean ~9.3.

## Per-account proxy (Account Switcher)

New this session: `AccountProxyProfile` + `AccountProxyStore` (persists to
`killer560smod-account-proxies.json`, keyed by account uuid) + `AccountProxyConfigScreen` (opened via a
"Set Proxy"/"Proxy ✓" button on each row of `AccountSwitcherScreen`). On a successful account swap,
`ProxyConfig.getInstance().applyAccountProfile(AccountProxyStore.get(account.uuid()))` runs - **explicit
design choice from killer560: an account with NO saved proxy explicitly DISABLES the active proxy**,
rather than leaving whatever the previous account had active. Don't change this without asking again.

## Release process (no `gh` CLI installed in this environment)

No GitHub CLI available. Releases are created via the raw REST API:
```bash
CRED=$(printf 'protocol=https\nhost=github.com\n' | git credential fill)
GH_TOKEN=$(echo "$CRED" | grep '^password=' | cut -d= -f2-)
# POST https://api.github.com/repos/Killer560-OG/killer560s-mod/releases  (tag_name, name, body, target_commitish: main)
# then POST to the returned upload_url (?name=<jar>) with the jar as the body, Content-Type: application/java-archive
```
`git credential fill` reuses the SAME credential already trusted for `git push` to this exact repo - no
separate token needed. Never print `$GH_TOKEN`'s value into chat/output.

## Discord (bot-only - browser navigation to Discord was explicitly rejected by killer560)

Bot token lives at `C:\Users\Hunter\.claude\secrets\killer560smod-discord-bot-token.txt` (outside the
repo, gitignored path irrelevant since it's not even in the repo - NEVER move it into the repo or print
its value). Use it as `Authorization: Bot <token>` against `https://discord.com/api/v10/...`. Guild ID:
`1546630783970713665` ("killer560's server").

Key channel IDs (Info category):
- `📢-announcements` = 1546637235657379850 (currently empty)
- `⚙️-how-to-install` = 1546637234210345031 (generic/evergreen, no version-specific content, doesn't need
  updates when a version ships)
- `📖-mod-features` = 1546637232578760734 (3 messages, split across the 2000-char limit - **must be kept
  current with each release**; rewritten this session to describe v1.1.0 instead of stale v1.0.0/
  "unreleased" framing)
- `🚀-releases` = 1546637237213470740 (post a short release announcement here each version, pointing to
  the GitHub release and the changelog channel - NOT the full changelog text itself)
- `🐙-github-updates` = 1546637239172210778 (auto-posts via Discord's own GitHub integration, not manually
  maintained)
- `📋-changelog` = 1547494054294458378 (**new channel, created this session**, right after
  github-updates - post a detailed "what changed" writeup here each release, ending with a pointer to
  `#releases` for downloads, NOT a raw GitHub link duplicated in both places - killer560 explicitly asked
  for the split: full description in changelog, downloads only in releases)

**Pattern for a new release announcement**: (1) post the detailed changelog in `#changelog` ending with
"Grab the jars in <#1546637237213470740>", (2) post a short announcement in `#releases` pointing to
`<#1547494054294458378>` for the full writeup, (3) update `#mod-features` to describe the new current
release if the feature list changed.

## Standing rules for this project (see CLAUDE.md / memory for full detail)

- Every code change: build both `-PcheatBuild=true` and `-PcheatBuild=false`, deploy the cheat jar to all
  5 instances (MD5 + `unzip -t` verified), boot-test on `26.1.2 (Mod Only Test)` via PrismLauncher CLI
  when touching risky/new code (check `latest.log` for mixin errors), update `TESTING.md`, commit with
  `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` trailer, push, report back concisely.
- **Always check for a running `javaw.exe`/game process before overwriting a mod jar** - if killer560 is
  actively playing on one of the 5 instances, skip deploying to that one and say so rather than disrupting
  his session; deploy to the rest and note which instance still needs a restart.
- Memory says the standing default is to `taskkill` the boot-test instance after a clean log (reverted
  from "leave running" back on 2026-09-09) - but this whole session left every boot-test instance running
  without objection. Use judgment; ask if genuinely unsure.
- All new features ship with `enabled = false` by default. killer560's real identity must NEVER appear
  publicly - only the "killer560" persona.
- Any dungeon solver/ESP/timer feature must also work on **p3sim.net**, not just real Hypixel.
- The 5 PrismLauncher test instances (all normally kept on the CHEAT build for testing): `26.1.2
  (Dungeons)`, `26.1.2 (Dungeons) (duo)`, `26.1.2 (Mod Only Test)`, `26.1.2 ALT`, `Taunahi`.

## Open questions / things to flag if this resumes cold

- Auto Terminals has been live-tested by killer560 across several real dungeon sessions this round and is
  in a good state, but keep the "ask for a log if unclear" pattern for any new report - don't guess.
- Termism's puzzle-count tuning may get further adjustment requests - simulate first, always.
- No other channels/docs were found stale during this session's sweep beyond `#mod-features` (already
  fixed) - but worth a periodic re-check after future releases.
