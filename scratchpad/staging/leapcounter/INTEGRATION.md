# Leap Counter - integration notes

Staging root: `C:\Users\Hunter\killer560s-mod\scratchpad\staging\leapcounter\`

Standalone, informational feature: counts party members that spirit-leap TO you in the F7/M7 boss and alerts when
the expected number (per P3 section) have arrived. killer560, 2026-09-16: "This should also be its own mod
section... that will alert me when X amount of people have leaped to me. For S2 it should be 4, S3 it should only
be 3, and S4 it should be 4 again." Default OFF, no mixin, no new coordinates, ships on BOTH jars.

## Files to copy into the repo (paths are already package-shaped)

| Staging file | Repo destination |
|---|---|
| `src/main/java/com/killer560/hub/leapcounter/LeapTracker.java` | `src/main/java/com/killer560/hub/leapcounter/` |
| `src/main/java/com/killer560/hub/leapcounter/LeapCounterConfig.java` | `src/main/java/com/killer560/hub/leapcounter/` |
| `src/main/java/com/killer560/hub/leapcounter/LeapCounterFeature.java` | `src/main/java/com/killer560/hub/leapcounter/` |
| `src/main/java/com/killer560/hub/gui/tab/LeapCounterTab.java` | `src/main/java/com/killer560/hub/gui/tab/` |
| `tooltips.txt` | paste the `d.put(...)` lines into `gui/SettingTooltipsData.java` (next to the F7 Spots block) |

`args.txt` is the javac args file used for the compile gate (output went to the scratchpad `gate-leapcounter`
folder; nothing under `src/` was touched). Do not copy it.

All four files compiled with JDK 25 javac against the real 26.1.2 jar + Fabric API + the live `src/main/java`
(including the just-landed `hud/HudVisibility` and `HudElement.isRelevantNow()`), zero errors.

## Wiring the main session must do

### 1. `Killer560ModClient#onInitializeClient` - register + HUD element

Next to the F7 Spots lines (~100 / ~109). Both builds - it is NOT cheat-only, so it does not go in any
`BuildVariant.CHEAT_FEATURES_ENABLED` block:

```java
        com.killer560.hub.leapcounter.LeapCounterFeature.register();
        HudElementRegistry.register(com.killer560.hub.leapcounter.LeapCounterFeature.HUD);
```

The feature draws its own element through Fabric's HUD API (same as F7 Spots' CrushHud), so its id
(`leap_counter`) must NOT be added to `HudInGameRenderer.UNDRAWN_ELEMENT_IDS` or it draws twice.

### 2. Tab - `gui/tab/NewTab.java`, the legit list (this repo's convention until killer560 confirms it in a run)

```java
                new F7SpotsTab(),
                new LeapCounterTab(),
```

When confirmed, its natural home is the Dungeon tab next to Leap Menu.

### 3. `profiles/ProfileManager#reloadAllConfigs` - loaders array

```java
                com.killer560.hub.leapcounter.LeapCounterConfig::load,
```

`killer560smod-leapcounter.json` holds only settings, so it belongs in profiles - nothing to add to
`EXCLUDED_FILES`.

### 4. `gui/SettingTooltipsData.java`

Paste `tooltips.txt`. Every label was checked against the existing keys; "Alert Sound" and "HUD" were already
taken, so the tab uses "Leap Alert Sound" and "Counter HUD" instead - nothing needs a `new/` scope.

### 5. README Features section

Per the standing rule, add a Leap Counter bullet (legit, both builds).

## Public API (names fixed - AP3's leap-detector node will call these)

```java
LeapTracker.count()               // teammates counted as having leapt to you at the current spot
LeapTracker.target()              // min(alive teammates excluding you, configured count for this section), 0 = not countable here
LeapTracker.isComplete()          // true from the tick the target was reached until the next reset
LeapTracker.reset()               // forget the counted teammates at this spot (anchor re-follows you)
LeapTracker.countSince(long ms)   // teammates counted at/after a System.currentTimeMillis() stamp
LeapTracker.section()             // S1..S4, CORE, RELIC or null (extra, not required by AP3)
```

`LeapTracker.tick` runs from `LeapCounterFeature` only while the master toggle is on. If AP3 wants the counts with
the HUD/alert feature switched off, either have AP3 flip nothing and require the toggle (simplest - document it in
the AP3 tab), or add a second "driver" flag later; the tracker itself has no dependency on the config beyond the
per-section counts, radius and jump distance.

## Design notes worth knowing before integrating

- **No leap-spot coordinates.** NoammAddons' seven boxes and Devonian's eight points are those mods' numbers.
  The rule here: you are in a countable section (`Floor7Tracker.getPhaseAt()`/`getStageAt()`, chat stage as
  fallback: P3 S1-S4, S5 = inside the core after "The Core entrance is opening!", P5 = relic) AND not walking
  (own movement < 0.2 blocks that tick). Your position when the first leap lands becomes the anchor; moving
  more than the radius from it resets.
- **Leap vs walk-in** (NoammAddons' packet check, re-expressed for per-tick polling): a teammate counts when
  they are within the radius, not yet counted, and either covered `jumpDistance` (default 8) blocks over the
  last 5 ticks from a sample that was outside the radius, or just appeared in `level.players()`. 5 ticks, not
  1, because the client interpolates a remote player's teleport over 3 ticks (verified with javap on the 26.1.2
  jar: `InterpolationHandler(Entity)` = 3 steps, `handleEntityPositionSync` only snaps past 64 blocks).
- **Own leap** (Devonian's trick): Hypixel's `^You have teleported to \w{1,16}!$` line suppresses counting for
  3 s and resets - otherwise everyone already standing at the spot you landed on is counted. Fed through
  `util/ChatObserver`, so it survives another mod cancelling and re-adding the line.
- **Devonian's squared/unsquared bug is not reproduced** - every distance compares squared against squared.
- **Warm-up:** for the first 10 ticks after entering a section a "just appeared" arrival is ignored (on P3 start
  / a world load into boss the whole party pops in at once and nobody leapt).
- **Dead teammates** never count (a ghost's spectator flight is fast enough to look like a leap).
- **Target cap:** `min(alive teammates excluding you, configured)`; with no party known at all (p3sim.net or the
  tab list not parsed yet) the configured number is used as-is.
- Every tick and chat path is wrapped; the first tick exception is logged once, none escape.

## What killer560 should confirm

1. **S1 = 3** is inferred (NoammAddons' Simon Says entry) - he gave numbers only for S2/S3/S4.
2. **Core = 4 and Relic = 4** are NoammAddons' values (its in-core and relic boxes). Set to 0 to turn off.
3. **Jump distance default 8.** NoammAddons has a separate "in core" region 6 blocks inside the core-entrance
   spot; a 6-block hop into the core will NOT count at 8 - lower the slider to 5-6 if that leap matters.
4. **Radius default 3** - the loosest of Devonian's P3 spots. Tighten to 1.5-2 if someone running past you
   ever gets counted.
5. **Whether p3sim.net prints "You have teleported to X!"** - if it doesn't, the 3 s self-leap suppression never
   fires there and the people already at the spot you land on get counted (the 10-tick warm-up still covers
   section entry).
6. The HUD line shows the section it is counting in ("2/4 Leaped (S2)"); drop the suffix if he finds it noisy.
