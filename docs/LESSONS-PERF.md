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

## Frame times and stutters (stutter-1026)

- **The FPS line cannot show a stutter.** `Minecraft.getFps()` is frames counted in the last second, so one 300 ms hitch
  in a 120 fps second reads ~85 and is gone the next second. `lagdisplay/FrameStats` times each `Minecraft.runTick`
  (HEAD to HEAD) and its phases; the Frame line and the stutter test use that minus the FPS limiter's sleep (the
  unfocused-window cap is not lag).
- **A frame's wall time splits into the game's phases, the swap and "outside".** Outside every phase the render thread
  runs only small vanilla work (sound, toasts, mouse), so a large outside share with little render-thread CPU means the
  thread was not running: the PC (testkit 1026-stutter-outside sleeps 200 ms in a toast's update and reads "likely your
  PC"). A daemon thread that only sleeps 10 ms tells a whole-PC stall from a busy render thread; it stalls in a GC pause
  too (188 ms of a 193 ms System.gc(), 1026-stutter-gc), so GC is judged first. Windows counts thread CPU in ~15.6 ms
  steps: a CPU figure is only meaningful for frames far longer than that.
- **Cost:** with measuring on, the hooks are ~13 `System.nanoTime()` pairs and three GC counter reads a frame plus a pair
  per FeatureGuard tick handler, no allocation: 95-perf-stutter (2026-10-26) measured frameStart 0.54 us, a phase
  begin+end 0.045 us, a callback add 0.044 us, all 0 B, so 1.1 us a frame + 8.3 us a tick (189 handlers) = 2.3 us a frame at
  144 fps / 20 tps. Its whole-frame ON/OFF rounds swung -68..+504 us with other test clients running, i.e. noise far above
  that; off, each hook is one static boolean.
- In a gametest client quiet frames reached 51.5 ms in 200 ticks (1026-stutter-hooks, 2026-10-26, other clients running),
  so the "nothing fires on quiet frames" check uses a 500 ms threshold.


## fps-1104 (everything fps tested)

- Predictive Rooms' ray sweep was the largest cost at defaults in a dungeon (95-perf-features: 61 us/frame@144, 220 KB a
  tick; 95-perf-predict alone: 909 us and 227 KB a tick), nearly all inside vanilla `level.clip`. `predict/PredictClip`
  walks the ray as `BlockGetter.traverseBlocks` does (javap: identical on 26.2 and 26.3), skips air, reads each chunk
  once per column and stops once the ray has left the target tiles' box: 579 us and 174 KB a tick, and testkit
  95-perf-predict re-casts every ray with vanilla's clip under `RoomPredictor.VERIFY_CLIP` (115,688 rays, 0 different).
  Any change to PredictClip reruns that check.
- Only `HudInGameRenderer` opened the `HudTextCache` scope, so every other mod HUD layer (Hoppity Eggs, Inventory HUD,
  alerts...) ran ICU Bidi over its String lines every frame. `BazaarHud.gate` now opens it around every layer whose
  class is ours (Hoppity Eggs' HUD: 48 -> 38 us and 32 -> 15 KB a frame). Other mods' layers stay outside it.
- With everything OFF the mod still costs ~200 us/frame@144 in the sim, spread thin: the HUD layer's per-element
  position lookups (`resolvePosition`, ~2.5% of an ALL ON frame), FeatureGuard wrappers, Mining QoL's 14 sub-ticks
  (0.2% in JFR). No single fix there is worth its risk; a per-frame position cache would be the next step.
- `bench.sh` runs need the jar as `C:/Users/...`: run-scenario.ps1 reports "Mod under test not found" for `/c/Users/...`.
- Round 2 (fps-1104): the HUD position path is already memoized end to end (`memoisedClamp`, HudConfig lookups are
  plain map gets); the ~2 us per drawn element left is mostly the dev-only PerfProbe around it, so a further position
  cache bought nothing measurable and was not built. Wrong Armor Alert's `defaultX()` measured its constant text every
  frame (0.35 -> 0.01 KB/frame once cached). An identity memo of the tab list's text in `ScoreboardData.readTab` saved
  nothing in the sim (SimTabList hands out new Components each refresh), so it was dropped; recheck on Hypixel before
  retrying. Read-only Skyblock id helpers now use `ItemNbt.view(CustomData)`, never `copyTag()`.
- Same-run us figures moved +30-50% between two runs on a busy machine (MsMpEng 180%); compare KB per frame, which is
  stable, and treat us as a direction only.

- Memory over a long session (scanleak-1104, 2026-11-04): all ~600 static collections were scanned (`killer560s-mod-logs/leakscan-1104.py`)
  and almost all are capped, cleared on reset, weak/identity-keyed or keyed by finite data. Joining and leaving a full F7 sim 30
  times (testkit `1104-leak-sim-hops`) plateaus at ~+30 MB after GC (vanilla registry/biome reload per world; no mod class and no
  mod collection grows). The one real unbounded map was Auto Routes' `RouteExecutor.lastDugAt` (one entry per breaker dig, never
  removed; 1500 of 1500 kept on the old jar, 60 on the fixed one); `PlayerHeadRenderer` (RESOLVED/FETCH_AT) and
  `PlayerNames.NAME_FAILED_AT` were capped too. A cache keyed by a player or a dug block needs a size check at its put, not only a reset hook.
- Startup cost (scanboot-1104, 2026-11-04): the client entrypoint takes about 3.2 s in a dev run and its profile is flat; JFR shows it is Mixin/ASM class transformation of ~300 feature classes, not config or asset reads. `util/BootTimer` (dev builds, `-Dkiller560.boottime=true`, add `.exit=true` to quit at the title screen) logs entrypoint start/total and the title-screen time. Run-to-run spread on a loaded PC is seconds, so judge a startup fix by the per-step timer (a temporary mark after each statement of onInitializeClient), not by total time. JFR via JAVA_TOOL_OPTIONS also starts in the Gradle JVMs: give the file `%p` in its name.
- A chat-hider rule written as `(?i).*a.*b.*` with matches() is quadratic: ObjectHider ran two of them on every chat line on the packet path (~0.4 s for 10k chars). Write it as a one-pass indexOf function (single line, ASCII case-insensitive) and compare it with the old regex in a testkit case (scanaudit-1104, 1104-logic-scanaudit). Bound every digit group of a chat pattern that feeds parseInt/parseLong to {1,9}. A static scan for unused assets or tooltip keys reports false positives for names built at run time; do not delete from it alone.
- Derived-structure caches (rushcache-1104): Blood Rush Helper rebuilt its rush path (a link list per room, BFS, sets) every other tick, 1.7 MB/min of garbage per room state on the sim. It now keys on the layout object (DungeonLayout.current returns a new one whenever rooms, names, locks or the player's room change), HeldMap.answersVersion() (map item or sim stand-in version) and its own change counter, compared field by field; hashing the 121 cells or asking HeldMap per cell would itself allocate in the sim stand-in. Testkit 1104-bloodrush-pathcache compares the cached path with a from-scratch one after every room that appears. Measure allocation per call over 20000 calls: getThreadAllocatedBytes moves in TLAB-sized steps (a 600-call loop read 0 or 360000 B at random).
- Per-frame and per-tick garbage (rushalloc-1105): with its path cached, Blood Rush Helper still made 30.5 MB/min standing in an 8-box sim room. Metered inside the mod (`allocMeterForTest`: getCurrentThreadAllocatedBytes around its own tick and render; the on/off difference of the whole client thread swung from 6 to 850 MB/min run to run, and JFR's allocation samples were too sparse to rank sites): render (new box/color arrays, a clone, a Vec3 and a lambda per label, every frame) and the starred-mob scan (`MobEspFeature.starredMobs` reads every stand's name, ~22 KB a call, every other tick) about equally, then build() (~4 MB/min, left: it runs at 10 Hz and its inputs have no single version). Now render rebuilds its arrays and label sequences only when build() hands it a different list (build returns the old list when equal), the scan runs when an armor stand loads (`ClientEntityEvents.ENTITY_LOAD`) or every 20 ticks, and learned-spot boxes are cached per room on `BloodRushSpots.version()`: 30.5 -> 4-14 MB/min (it moves with the frame rate), and testkit 1104-bloodrush-pathcache fails over 20. Cache the ThreadMXBean itself: `ManagementFactory.getThreadMXBean()` allocates on every call, and asking it inside the meter added ~4-5 MB/min to the first readings (34-36).
