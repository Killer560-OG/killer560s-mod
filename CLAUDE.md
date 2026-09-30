# killer560s-mod

A Fabric client mod for Hypixel Skyblock, mostly Catacombs dungeons: solvers, HUDs, timers, a dungeon map,
and (in one of the two build variants) automation. Personal toolkit that friends also use.

## Build and run

Java 25 and a JDK on `JAVA_HOME`. Gradle wrapper, Fabric Loom.

```
./gradlew build                     # legit variant  -> build/libs/killer560smod-1.1.0-legit.jar
./gradlew build -PcheatBuild=true   # cheat variant  -> build/libs/killer560smod-1.1.0-cheat.jar
./gradlew build -Prelease=true      # official release: compiles dev tooling OUT
```

Both variants come from the same source. `build.gradle` generates
`com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED` from `-PcheatBuild`, and `DEV_TOOLS` from
`-Prelease`. Because they are `static final boolean`, javac folds every `CHEAT_FEATURES_ENABLED && x` down
to `false` in a legit build and deletes the branch, which is the point.

Versions, all from `gradle.properties` (check there, not here, if something looks off): Minecraft 26.1.2,
Fabric Loader 0.19.3, Fabric API 0.155.2+26.1.2, Loom 1.17-SNAPSHOT, Mod Menu 18.0.0, mod version 1.1.0,
Netty 4.2.7.Final (bundled Jar-in-Jar for the Proxy Client feature; must match what MC 26.1.2 ships).
`fabric.mod.json` declares `minecraft ~26.1`, `java >=25`, entrypoints `client` and `modmenu`.

## Testing

Boot-test only in the Prism instance **26.1.2 (Mod Only Test)**, and close the game afterwards. The
instance **26.1.2 (Dungeons)** is him actually playing — never install a test jar there, never boot it, and
never draw a conclusion from its log.

**Never use `prismlauncher.exe --launch`.** On 2026-09-30 `--launch "26.2 mod only"` was given to an
already-running Prism that did not yet know that folder, and instead of failing it started **`26.2`, one of
his play instances** (17:35:35 to 17:39:54 in its own log). A new instance folder is invisible to a running
Prism until it rescans, and `--launch` does not report a miss. Boot-test through the gametest harness, which
starts its own client and needs no launcher, or ask him to launch it. killer560, the same day: "make sure you
are only ever boot testing on the mod only variant."

Never swap a jar over a running game; stage it as `.jar.pending`. Never blanket `taskkill javaw` — kill
only the PID that was launched, and if the log shows a server join, he took the window, so leave it.

Deploying updates **Mod Only Test does not reach the instances he plays on.** As of 2026-09-30 SIX
instances run the cheat variant - `26.1.2 (Dungeons)`, `26.1.2`, `26.1.2 ALT`, `Map Logger`,
`Ashfall rooms` and `26.1.2 (Mod Only Test)` - and `26.1.2 (Legit Test)` runs a legit build. After a session's work lands, check
every one by md5 and promote or delete any leftover `.jar.pending`: on 2026-09-29 four instances were two
builds behind with a stale `.pending` nothing had ever promoted.

**Enumerate the instances directory, never trust this list.** It has been wrong twice in one day:
`AP3 Competition Instance` was missing from it, then that instance disappeared and two new ones showed up.

**`26.2` and `26.2 ALT` get the 26.2 build, not the 26.1.2 one.** killer560 lifted the old do-not-deploy rule on 2026-09-30 ("you can deploy the proper version of the mod to the proper instance") when 26.2 became a supported target: 26.1.2 stays the main release because most people play it, and a good few play 26.2. Four jars now ship - legit and cheat for each - and the Minecraft version is in every jar's name so `*-legit.jar` cannot match two different builds. The 26.1.2 jar still will not load on 26.2 and vice versa, because each declares its own `minecraft` range; that is the point, not a bug to widen away.

There is an anticheat harness at `C:\Users\Hunter\killer560s-mod-testkit` that runs features against a real
GrimAC on a real dedicated server. See its own `CLAUDE.md`.

## Layout

Source under `src/main/java/com/killer560/hub/`, one package per feature area (`ap3`, `dungeonextras`,
`secrets`, `livemap`, `autopuzzles`, `terminals`, `leveraura`, `gui/tab`, `util`, …). Mixins live in a
`mixin` subpackage of the feature that owns them. Docs: `docs/FEATURES.md` holds the full text for every
feature; `README.md` holds the name list.

Shared pieces worth knowing: `util/ActionGate` is the mod-wide one-automated-interaction-per-tick arbiter
with actor priority; `util/SkyblockGate` is the "Skyblock Only" gate; `secrets/DungeonState` owns floor and
boss-phase detection; `util/ViewFreeze` holds the camera still while something rotates the real player.

## Conventions

Loggers come from `util/ModLog.get("killer560smod-…")`, never from `LoggerFactory` directly. In a dev or
cheat build that hands back the real SLF4J logger; in a release (`-Prelease=true`, `DEV_TOOLS == false`) it
hands back one that drops TRACE/DEBUG/INFO/WARN and forwards only ERROR, so a release jar is quiet without
a thousand call sites being guarded. `roomsim/` and `bazaarflip/` were the last two packages still calling
`LoggerFactory` directly, and so the last two still logging in a release; both went through `ModLog` on
2026-09-30, so a release jar is now quiet everywhere.

Every setting must survive a restart: add the field, load it, save it, and expose a getter and setter.
A new feature gets its name in the README list and its full text in `docs/FEATURES.md`, then the features
Google Doc is regenerated. Sharing and receiving settings default ON. The GUI is orange-themed. Never carry
his typos into a command, label or alias.

Never track a `HANDOFF.md` in the repo; handoff notes live only in
`C:\Users\Hunter\.claude\killer560s-mod-HANDOFF-PRIVATE.txt`. The Discord bot token lives at
`C:\Users\Hunter\.claude\secrets\killer560smod-discord-bot-token.txt` and is never printed or committed.
The features Google Doc **edit** link is never published; only the `/e/2PACX-…` published link is public.

## Safety rules for automation

These are his, they are about getting banned, and they are not negotiable. Never write position or velocity
directly (`setPos`, `setDeltaMovement`) and never use fractional movement input — discrete key presses only,
and abort on any server correction. Never clamp or wrap simulated yaw at 0-360; Hypixel treats an uncapped
running rotation value as the normal signal. Pitch is always kept within -90..+90. Anticheat *deception*
features (blink, inventory walk) were declined in September 2026 and stay declined.

## Quirks and lessons

Two topics have their own files, because they had grown to half this one:
**[docs/SIM.md](docs/SIM.md)** for the dungeon sim (`roomsim/`) - room captures, floor generation,
secret placement, doors and altitude - and **[docs/AP3.md](docs/AP3.md)** for AP3's nodes and align
physics. Read the relevant one before touching either area.

- `LOGGER.debug` never reaches his log. Minecraft's root log4j2 level is INFO, and a real client log
  (`26.1.2 (Dungeons)`, 29,596 lines, 2026-09-29) contains zero DEBUG lines. So a `.debug` call is not the
  spam and deleting one buys nothing; when hunting log noise, hunt `.info`. That is where it all was: the
  2026-09-29 trim cut INFO call sites from 783 to 273 and left WARN (276) and ERROR (86) alone.
- A reach check belongs at the one place the interaction is SENT, not in each caller. Simon Says had four
  callers and a check in one of them; three paths sent clicks from up to 30 blocks away for months.
- `"^(?:.*something.*|...)$"` is NOT anchored. The leading `^` buys nothing when the alternative starts with
  `.*`, and the Blood Key split read that way for months. Anchor on the real line shape, with a
  `[A-Za-z0-9_]{1,16}` name group, and check with `matches()`.
- A comparator must not call the RNG. `SimFloorLayout` sorted candidates by a key containing
  `rng.nextDouble()`; TimSort noticed and threw "Comparison method violates its general contract!". Draw the
  jitter once per element and store it.
- Switching variants needs `build/classes` cleared too, not just the generated `BuildVariant` source. After a
  cheat compile, deleting only `build/generated/sources/buildVariant` and compiling legit produced a wall of
  `error: cannot access Ap3FreezeState` / `Ap3EditScreen` / `Ap3RouteCache` - stale class files, not a real
  break. The same source compiled clean once `build/classes` went too (2026-09-29). A "cannot access <a class
  in this repo>" error is almost always this, not a missing dependency.
- Gradle does NOT always regenerate `BuildVariant` when only `-Prelease`/`-PcheatBuild` changes. After
  building a release jar, a plain `./gradlew build` left the legit jar still carrying `DEV_TOOLS=false`.
  Delete `build/generated/sources/buildVariant` between variants, and check the class hash - **`md5sum` of
  `com/killer560/hub/BuildVariant.class` unzipped from the jar, first 8 hex characters**, which a 2026-09-30
  sweep wasted time on by reaching for CRC32 and finding it did not reproduce:
  `c7d5d8f5`=cheat+dev, `1d821df5`=legit+dev, `f8fc20a1`=legit+release. `javap -constants` on that class is
  the unambiguous check if a hash ever disagrees - it prints the two booleans directly.
- Mixin config uses `defaultRequire: 0`, so a wrong target signature fails **silently** and the feature
  just never runs. Verify targets with `javap` against the mapped jar in `.gradle/loom-cache/` before
  trusting a new mixin. A probe that silently counts nothing reports zeroes that read as findings.
- **Two Minecraft versions, one source tree.** `build.gradle` derives a compat directory from
  `minecraft_version` and puts exactly ONE of `src/mc26_1/java` / `src/mc26_2/java` on the source path (it
  prints "compatibility layer mcXX_Y" every build and throws for a version with no directory). Version-specific
  API lives in `com.killer560.hub.compat` - `McCompat`, `McBlocks`, `McItems`, `McEntities`, `McRender` - and
  **every version's copy must have identical public signatures**, or one version stops compiling. Nothing under
  `src/main/java` may touch an API that differs between versions. Build 26.2 with
  `-Pminecraft_version=26.2 -Pfabric_api_version=0.160.0+26.2 -Pmodmenu_version=20.0.2`.
  A mixin is the one thing the facade cannot cover, because `@Mixin(X.class)` is an annotation constant: a
  mixin whose target **class** moved gets one copy per version directory under the same name (eight do), while
  one where only the **method** name changed just lists both - `method = {"renderFire", "submitFire"}`. Mixin
  accepts a handler taking only `CallbackInfo` for any target (checked in `CallbackInjector$Callback.
  checkDescriptor`: the full descriptor OR `(LCallbackInfo;)V`, all-or-nothing, never a prefix), which is how a
  single mixin survives a target whose argument TYPES changed.
- Run gradle through Bash, not PowerShell: PowerShell wraps native stderr in ErrorRecords and splits compiler
  messages mid-line, so error counts and file paths become unreadable. Gradle also prints compiler output
  TWICE, so `grep -c "error:"` is double - the `N errors` line javac prints is the authoritative number.
- **Cancelling a block break makes `AttackBlockCallback` fire every TICK, not once per click.**
  `MultiPlayerGameMode.continueDestroyBlock` only continues an existing break when `isDestroying` is set, and
  that field is set inside `startDestroyBlock` - which is where the callback lives and which a cancel returns
  from first. So holding the button re-enters the callback twenty times a second (verified by `javap -c`:
  `continueDestroyBlock` calls `startDestroyBlock` on its fallback path). The sim's Dungeon Breaker spent its
  whole twenty-charge bar in one second this way. Anything that consumes that callback needs its own
  edge-detection - track the block and clear it when `keyAttack` comes up.
- A class placed inside a mixin-owned package throws `IllegalClassLoadError` and crashes the game at boot.
  Keep helper classes out of `mixin` packages.
- `RenderSystem.setShaderColor` does not exist in 26.1.2, so there is no global colour multiplier and items
  cannot be tinted per-item. The inventory HUD's Opacity now dims items with a translucent quad drawn over the
  panel after the item loop instead: 0 hides the panel outright, and the darkening is capped at 80% so no
  setting turns it into an unreadable black box.
- Forwarding a self-registered client command name to the server recurses through Fabric's command API and
  StackOverflows. Send below the dispatcher via `util/ServerCommands.toServer`.
- When sweeping for features that tick on the wrong event, resolve the **called classes**, not per-file: a
  feature is often ticked from a lambda in another class entirely (`CheatUtils` ticks Secret Aura, Auto Ult and
  Chocolate Factory; `PathfindingFeature` ticks the soul runner and pearl hopper). A per-file grep missed seven
  of them and the gap only surfaced as `Post` violations in a later test.
- Reach must be measured to the block's **box**, not its centre — the centre reads up to half a block
  further and makes a module look out of range when it is not.
- The gametest client runs as **java.exe**, not javaw.exe. `run-scenario.ps1` filtered on javaw only, so every
  safeguard in it was inert — the freeze watcher never saw an unresponsive client and the deadline cleanup
  killed nothing, while the script reported success. That is why "it still doesn't close out on freeze"
  survived two rounds of fixes to the watching logic.
- **A throw in a RAW chat listener disconnects him from Hypixel.** `ClientReceiveMessageEvents` runs on the
  packet path, and `ClientCommonPacketListenerImpl.onPacketError` logs "Failed to handle packet, disconnecting"
  and drops the connection. `util/ChatObserver` catches per listener; the raw events do not. On 2026-09-29 the
  Simon Says party tracker matched `SS (\d+)/(\d+)` with unbounded digits straight into `Integer.parseInt`, so
  ANY player typing `SS 99999999999/5` in party, guild or all chat ended his run - and that tracker is on by
  default. Reproduced and fixed, with scenario 86 as the control: on the broken jar it disconnects with
  "Network Protocol Error"; on the fixed one it survives. Bound every quantifier that parses another player's
  text, and wrap the parse anyway.
- A block scan over a room's volume is almost always a chunk-section scan in disguise. Secret Waypoints read
  44,649 blocks for a 1x1 room (173,000 for a 1x4) every second looking for levers; asking each section's
  palette first (`section.maybeHas(...)`, plus `hasOnlyAir()`) does the same job in 0.7% of the reads -
  10,240 against 1,527,209 over a whole floor, verified equivalent by scenario 77 which runs BOTH algorithms
  and requires identical results. Also hoist `isLoaded`/`getChunk` out of the y loop: they depend only on x,z.
- **A `HudElement`'s `render()` is not always where it draws.** For `croesus_start_button`,
  `experiments_start_button`, `rng_meter_ranking`, `storage_overlay`, `inventory_hud` and `custom_scoreboard`,
  `render()` is ONLY the HUD editor's preview and the real pixels come from a container-screen or Fabric HUD
  layer elsewhere in the feature; `etherwarp_waypoints` never draws at all (`isVisible()` is hardcoded false).
  So anything that needs to know "was this on screen" must be placed at each feature's own draw site, not on
  the interface method. That is what `hud/HudSeen` does, and why `isRelevantNow` was split into
  `isEnabledInSettings()` (the toggle) plus the draw stamp on 2026-09-30 - the old single predicate had
  already drifted from the render path it mirrored in four places (Split Timers' tested `isInDungeon()` and
  its `render` did not).
- `Map.getOrDefault` EVALUATES its default eagerly. `HudConfig.getPosition` allocated a throwaway `int[2]` on
  every call even when a saved value existed - about 110 allocations a frame across the HUD.
- An early-out that reads "not enabled AND no key bound" is not an early-out when the key has a DEFAULT
  binding. Breaker Aura's render callback ran in every world, including the lobby, because its select key
  defaults to semicolon. Gate on the state the feature needs (in a dungeon), not on whether it is configured.
- **Anchor every chat pattern that gates an ACTION.** Other players' messages arrive on the same listeners as
  the server's. An unanchored `find()` on Maxor's opening line let anyone set the boss phase for a whole run
  (2026-09-29), and `contains("has obtained Wither Key")` let anyone make the client right-click a door. Even
  an anchored `^(.{1,16}) completed a terminal!` is forgeable, because `[VIP] Bob: a` is sixteen characters -
  a name group must be `[A-Za-z0-9_]{1,16}`, which no chat prefix can be.
- Instant Transmission is 8 blocks on Aspect of the End, Aspect of the Void AND the Etherwarp Conduit alike.
  What changes the range is the item's own `tuned_transmission` tag - a Transmission Tuner adds a block, four
  maximum - so a fully tuned one of any of them goes 12, and killer560 plays fully tuned ("nearly no one plays
  with less"). AOTV is NOT 12 by nature; assuming that got AOTE and AOTV wrongly split in the route matcher
  once already. `EtherwarpHopper` reads the same tag for the 57-block etherwarp.
- `ItemIdentity.of()` is shared by Auto Sell, the Inventory Sorter, Armour Dye and the mining profit tracker.
  Widening it to make two items equal makes "sell my Hyperion" sell an Astraea. Loose matching belongs in
  `matches()`, which only a route's USE_ITEM node reaches.
- `/f7`, `/m7` and the rest are THIS MOD'S client-side shortcuts, not Hypixel commands - they expand to
  `/joininstance catacombs_floor_seven` etc. in `CommandShortcutsFeature.Shortcut`. So automation must send the
  `joininstance` form: `ServerCommands.toServer` deliberately sends below the client dispatcher, so it handed
  Hypixel the literal "/f7" and the Room Recorder sat waiting for a dungeon that was never queued (2026-09-28).
  `/dh` and `/skyblock` ARE real Hypixel commands. Read the id off the enum rather than writing it out again.
- An "any key stops it" guard must ignore keys while `client.screen != null`. Otherwise the Return that submits
  the command starting the feature, and the Escape that closes the settings tab starting it, each stop it
  immediately - which reads as "I turn it on and it auto turns off". Name the key in the stop message too; "key
  pressed" cannot tell a walk from the feature killing itself.
- `setBreakerAuraCooldownTicks` clamped to a minimum of 1 while the field defaults to 0, so the default
  could never be restored once the setter ran. Fixed 2026-09-27; the SLIDER driving it still mapped onto
  1-20 and was fixed 2026-09-30. A clamp has two halves - the setter and whatever widget feeds it - and a
  slider whose start position computes negative (`(0 - 1) / 19`) is the tell. Every other numeric setter in
  the repo was swept on 2026-09-30 and has its field default inside its clamp.
- **A fix applied to one of a set is the thing to go looking for.** The 2026-09-30 audit found four, all of
  the same shape: the Wither Key line was anchored and the Blood Key line beside it left on `contains()`;
  `Floor7Tracker` and `LeverAura` got the `[A-Za-z0-9_]{1,16}` name group and `Ap3Feature`'s copy of the same
  line kept `(.{1,16})`; three reach CONSTANTS were tightened to 4.5/3.0 and the MEASURES under them left on
  centre/feet (the javadoc describing the fault sat directly above the code still doing it); the cooldown
  setter above. When fixing one instance, grep the sibling call sites and fix the set, and update the comment
  that claims they match - two of these said "the same regex as X" after they had stopped being that.
- `RouteExecutor.stop()` is already a real cancel - it drops the step machine, `releaseKeys()` zeroes the
  want-flags the input mixin reads, `RouteRotation.clear()` releases the camera, and every per-node buildup
  (breaker queue, boom snapshot, swap/await state) is rebuilt by `beginAction`. BOOM and BREAKER send their
  START and ABORT in the same tick, so nothing is left open server-side either. What it does NOT clear is
  `stoppedByUser`/`justFinished`, and both make `AutoRoutesFeature` latch instead of arming *while the player
  stands inside a node* - which is exactly where a map warp puts him. Clear them whenever something other than
  the player cancels a route.
- **Moving a setting to a different sub-tab silently orphans its scoped tooltip.** `SettingTooltips.describe`
  looks up `"<sub-tab name>/<label>"` first and falls back to the bare label, so a `d.put("experiments/set", ...)`
  entry stops being found the moment that button is built by a different tab - no error, the hover text just
  changes or disappears. Re-key the entry in `SettingTooltipsData` in the same session as the move.
- **A HUD element's `width()` must be in the registry's unit, and the HUD editor saves on a zero-pixel
  click.** `HudElementRegistry` defines an element's on-screen size as `width() * HudConfig` scale, but the
  Storage Overlay's `defaultX()` centred against `width() * its own slider scale` and its render pose used a
  third combination, so the clamp, the editor box and the drawn panel measured three different panels
  (2026-09-30). A clamp also cannot rescue a panel *wider* than the screen - it only picks which columns to
  hide - so `gridWidthLocal()` now drops columns until the grid fits. Separately, `HudEditorScreen.
  mouseReleased` persists a position for any press-release on a box, drag or not: one click in the editor
  while the window was briefly 854x480 froze `storage_overlay` at the clamped `x:0` and it stayed there at
  2560x1441, which is what "the storage overlay is no longer centered" turned out to be. A saved position is
  never re-clamped, so the cure is deleting the element's entry from `killer560smod-hud.json`.
- **Hypixel's Bazaar summaries are named the opposite of how they read.** In
  `api.hypixel.net/v2/skyblock/bazaar`, `buy_summary` is the book you INSTANT-BUY OUT OF and `sell_summary` is
  the one you instant-sell into. Verified on `VIBRANT_CORAL` (2026-09-29): `quick_status.buyPrice` 3324220.9
  matches `buy_summary[0].pricePerUnit` and `sellPrice` 221605.8 matches `sell_summary[0]`. Reading them the
  other way round produced a fake 1.6-billion-coin flip. `quick_status.buyPrice` is also a weighted AVERAGE,
  matching the exact top of book on only 628 of 1833 products, so anything sizing a real purchase must walk the
  levels. Scale check for the Bazaar-to-NPC flipper: of 819 products with an `npc_sell_price` only ~46 profit at
  all and the worthwhile margins are 0.4%-1.5% - a result far outside that band means the book is backwards.
