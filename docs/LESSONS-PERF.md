# Performance lessons

How to measure the mod's frame and tick cost, and the per-call waste that measuring found. Part of the feature lessons:
[LESSONS.md](LESSONS.md). Rendering cost (fills, draw calls, text layout) is in [LESSONS-GUI.md](LESSONS-GUI.md).

## Measuring

- Measure FPS work with the testkit's `95-fps-bench` (sim F7, ON/OFF alternated, frame and tick CPU time, JFR dumps;
  `tools/fps-jfr.py` attributes samples to mod code). Compare the ON-OFF DELTA within one run: absolute numbers
  drifted ~0.06 ms between identical runs, which is larger than most single fixes. 95 is a dungeon; a hub (80-entry
  tab list, ~180 entities, chat, an open chest, an Ender Chest page) is `403-perf-hub` (testkit docs/fps-bench.md).
  Per FEATURE, use `95-perf-features` (the debug-only `perfprobe/PerfProbe` times every mod listener and HUD element)
  and gate a change with `95-perf-gate`.
- On a machine running other work, wall time per callback carries a ~1.5 us per-call floor and +-15 us of noise per key
  between phases, and whole-frame CPU means swing +-0.3 ms, so a single feature's cost is only trustworthy as the mean of
  long ALL ON phases or as ALLOCATION, which is deterministic (the first "each feature alone" table, 2026-10-15, ranked
  switches by noise until it compared only their own keys).
- **PerfProbe cannot see a mixin; JFR can, under the VANILLA class's name.** A handler is merged into its target, so its
  frame is `net.minecraft...handler$abc000$killer560smod$name` and a `com.killer560.` filter misses all of them (the
  testkit's `tools/fps-jfr.py` counts them since 2026-10-15). Per-call cost and allocation of the hooks into vanilla hot
  paths are testkit `95-mixin-bench` (vs a `-NoMod` run) and `95-mixin-fixes`.
- A mixin's `CallbackInfo(Returnable)` is 24 B per call only when the JIT leaves the handler out of line. JFR of real
  frames (hub, menu, 2026-10-15) showed almost none, but the same jar measured 0 or 24 B for `DataComponentHolder.get` in
  two runs of a tight test loop. Keep hot handlers tiny and judge allocation tests with a budget above 24 B.

## What it found

- The mod-wide per-call waste was not in the mixin hooks' own guards but in what they called - a title, name or address
  turned into a String on every call (Storage Overlay 432 B and RNG Meter 184 B per frame in every container, Revert
  Master Stars 920 B per `getHoverName`, the protect star 312 B per slot, Secrets 80 B per `getShape`, Click Sound 80 B
  per sound), and an identity-hash lookup per glyph in `ChromaWave` because the Enchant Colors spec keeps its map
  non-empty.
- **That ~1.5 us per tick handler is real, not the probe.** With every switch off, the testkit's unwrapped
  `harness/EventSpan` (no probe) read START+END ~570-620 us a tick, the same as the probe's total, and a no-probe JFR
  put 14% of render-thread samples under the tick invokers, the trivial handlers' samples landing on static-field loads
  like `XConfig.getInstance`'s `instance == null`: cold cache misses, one handler at a time (fps-idle-1015). A dispatcher
  that skips switched-off features cannot be exact as the code stands: only 2 of 105 method-ref tick handlers begin with
  a bare `if (!cfg.isEnabled()) return;` - the rest reset edge state, watch for a level change or tick a helper first.
- **A switched-off listener can be skipped for one boolean read, if the skip is provably the same** (fps-idle-2-1016):
  `FeatureGuard.start/end(name, bit, handler)` and `FeatureGuard.afterFeatures/afterTerrain(bit, listener)` read a
  `util/FeatureBits` bit whose gate reads only STORED switches (`isEnabledRaw()`, never `SkyblockGate.allows()` or the
  dungeon state), recomputed only after `FeatureBits.changed()`. So every setter of a gated field and the config's
  `load()` (wrapped as `load(){ load0(); changed(); }`, which covers profile apply) must call it; a gated tick wrapper
  still runs the handler once on its first switched-off tick, so an off branch that resets state does so exactly once.
  (`afterFeaturesFlush` does the same for a render listener that clears its last frame: Blood Camp.)
  Gate only a handler whose off branch is a bare return or a reset nothing else writes while off; a handler that ticks a
  helper, watches the world or checks a key first is not gated. A forgotten `changed()` is caught by a 1-in-64 re-check
  that logs ERROR and turns the bit on; testkit 95-perf-bits drives every bit through its setters and `load()`.
- **Every chat line was ~52 KB of garbage with every switch off** (95-perf-idle): each of ~55 ChatObserver subscribers
  and ~20 raw listeners walked the component with `getString()` again and built its own `Matcher`s (Matcher + groups +
  `IntHashSet[]`, ~200 B each, ~60 a line). `ChatObserver.raw`/`strip(Component)` now hand every reader of one delivered
  line the same String (inside a ChatObserver dispatch or between the open/close phases it adds to Fabric's CHAT, GAME
  and MODIFY_GAME), and the always-on handlers test a literal their patterns require before `matcher()`. 15.5 KB a line
  is left, ~14 KB of it vanilla's (ICU Bidi, wrapping). A new chat handler: read the text through ChatObserver, and put
  a `startsWith`/`contains` the pattern needs in front of it.
- **Server-sent text can be memoized by object identity.** A tab entry's display name, a team's prefix/suffix and an
  entry's display are REPLACED with a new `Component` when the server updates them, never edited, so "same object (and
  same `Language`), same string" is exact. `util/TabText` and `DungeonState.readSidebarText` key on that (2026-10-07):
  a dozen tab readers had each re-flattened and regex-stripped all ~80 entries, and the sidebar was rebuilt every tick
  in every world. In a weak-keyed cache never store the key itself as its value (Name Changer stores `NO_MATCH`): a
  strong value that names its key keeps the entry alive forever.
- A cache that is never clean costs the whole save every time: `PlayerNameCache.put` treated a newer timestamp as a
  change, so the 500 ms tab-list scan made it dirty on every pass and the whole name file was rewritten every two
  seconds in any lobby (a sighting now refreshes an unchanged name at most once per ten minutes).
