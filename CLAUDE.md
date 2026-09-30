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

Never swap a jar over a running game; stage it as `.jar.pending`. Never blanket `taskkill javaw` — kill
only the PID that was launched, and if the log shows a server join, he took the window, so leave it.

Deploying updates **Mod Only Test does not reach the instances he plays on.** SIX instances run the cheat
variant — `26.1.2 (Dungeons)`, `26.1.2`, `26.1.2 ALT`, `Map Logger`, `AP3 Competition Instance` and
`26.1.2 (Mod Only Test)` — and `26.1.2 (Legit Test)` runs a legit build. (`AP3 Competition Instance` was
missing from this list until 2026-09-29; enumerate the instances directory rather than trusting the names
written here.) After a session's work lands, check every one of them by md5, and
promote or delete any leftover `.jar.pending`: on 2026-09-29 four instances were still two builds behind
with a stale `.pending` from the night before that nothing had ever promoted.

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

- A reach check belongs at the one place the interaction is SENT, not in each caller. Simon Says had four
  callers and a check in one of them; three paths sent clicks from up to 30 blocks away for months.
- `"^(?:.*something.*|...)$"` is NOT anchored. The leading `^` buys nothing when the alternative starts with
  `.*`, and the Blood Key split read that way for months. Anchor on the real line shape, with a
  `[A-Za-z0-9_]{1,16}` name group, and check with `matches()`.
- A comparator must not call the RNG. `SimFloorLayout` sorted candidates by a key containing
  `rng.nextDouble()`; TimSort noticed and threw "Comparison method violates its general contract!". Draw the
  jitter once per element and store it.
- Gradle does NOT always regenerate `BuildVariant` when only `-Prelease`/`-PcheatBuild` changes. After
  building a release jar, a plain `./gradlew build` left the legit jar still carrying `DEV_TOOLS=false`.
  Delete `build/generated/sources/buildVariant` between variants, and check the class hash:
  `c7d5d8f5`=cheat+dev, `1d821df5`=legit+dev, `f8fc20a1`=legit+release.
- Mixin config uses `defaultRequire: 0`, so a wrong target signature fails **silently** and the feature
  just never runs. Verify targets with `javap` against the mapped jar in `.gradle/loom-cache/` before
  trusting a new mixin. A probe that silently counts nothing reports zeroes that read as findings.
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
  could never be restored once the setter ran. Fixed 2026-09-27. Worth checking other setters for the same
  mismatch between setter clamp and field default.
- `RouteExecutor.stop()` is already a real cancel - it drops the step machine, `releaseKeys()` zeroes the
  want-flags the input mixin reads, `RouteRotation.clear()` releases the camera, and every per-node buildup
  (breaker queue, boom snapshot, swap/await state) is rebuilt by `beginAction`. BOOM and BREAKER send their
  START and ABORT in the same tick, so nothing is left open server-side either. What it does NOT clear is
  `stoppedByUser`/`justFinished`, and both make `AutoRoutesFeature` latch instead of arming *while the player
  stands inside a node* - which is exactly where a map warp puts him. Clear them whenever something other than
  the player cancels a route.
- **Hypixel's Bazaar summaries are named the opposite of how they read.** In
  `api.hypixel.net/v2/skyblock/bazaar`, `buy_summary` is the book you INSTANT-BUY OUT OF and `sell_summary` is
  the one you instant-sell into. Verified on `VIBRANT_CORAL` (2026-09-29): `quick_status.buyPrice` 3324220.9
  matches `buy_summary[0].pricePerUnit` and `sellPrice` 221605.8 matches `sell_summary[0]`. Reading them the
  other way round produced a fake 1.6-billion-coin flip. `quick_status.buyPrice` is also a weighted AVERAGE,
  matching the exact top of book on only 628 of 1833 products, so anything sizing a real purchase must walk the
  levels. Scale check for the Bazaar-to-NPC flipper: of 819 products with an `npc_sell_price` only ~46 profit at
  all and the worthwhile margins are 0.4%-1.5% - a result far outside that band means the book is backwards.
