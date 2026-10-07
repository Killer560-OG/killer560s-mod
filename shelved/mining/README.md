# Shelved: mining features (until after the 2.0 release)

On 2026-10-07 killer560 parked every mining feature until after the 2.0 dungeons release: "for all of the current
mining features please make it so they no longer show on the menu and their code is no longer shipped. It should
just be hidden in a sense somewhere so that way you can reference it again after the 2.0 release ... I don't plan
on fully working on any of that until after this main release."

Everything here is in the repo but outside `src/`, so Gradle/Loom never compiles or packages it (build.gradle only
adds `src/main/java`, the generated BuildVariant dir and one `src/mc26_x/java` compat dir). Players' saved mining
config files are left on disk untouched; the mod simply no longer reads them.

Shelved by commit **aa09fd82** on branch `shelve-mining` ("Shelve all mining features until after 2.0").
`git log --follow` on any file below shows its full history.

## What was shelved

- Crystal Hollows Map and Interactive Crystal Hollows Map (structure discovery, per-lobby structure sharing over the
  relay as data key `ch.v1.find`, waypoints)
- Profit Per Hour Tracker (Mining Profit) and its `/profit mining` screen
- Nucleus Run Profit Tracker and its `/profit nucleus` screen
- Metal Detector solver (never registered; dead code already)
- The "Mining (WIP)" menu category, including its Planned page

## Moved files (original path -> path here, under `shelved/mining/`)

All 20 Java files keep their package; only the root moved from `src/main/java/` to `shelved/mining/src/main/java/`.

```
src/main/java/com/killer560/hub/mining/chmap/ChDiscovery.java
src/main/java/com/killer560/hub/mining/chmap/ChFind.java
src/main/java/com/killer560/hub/mining/chmap/ChShare.java
src/main/java/com/killer560/hub/mining/chmap/ChStructure.java
src/main/java/com/killer560/hub/mining/chmap/CrystalHollowsMapConfig.java
src/main/java/com/killer560/hub/mining/chmap/CrystalHollowsMapScreen.java
src/main/java/com/killer560/hub/mining/chmap/CrystalHollowsWaypoint.java
src/main/java/com/killer560/hub/mining/metaldetector/MetalDetectorData.java
src/main/java/com/killer560/hub/mining/metaldetector/MetalDetectorSolver.java
src/main/java/com/killer560/hub/mining/nucleus/NucleusRunProfitConfig.java
src/main/java/com/killer560/hub/mining/nucleus/NucleusRunProfitTracker.java
src/main/java/com/killer560/hub/mining/profit/MiningItemPricer.java
src/main/java/com/killer560/hub/mining/profit/MiningProfitConfig.java
src/main/java/com/killer560/hub/mining/profit/MiningProfitTracker.java
src/main/java/com/killer560/hub/gui/tab/MiningWipTab.java          (the "Mining (WIP)" category + Planned page)
src/main/java/com/killer560/hub/gui/tab/MiningProfitTab.java
src/main/java/com/killer560/hub/gui/tab/NucleusRunProfitTab.java
src/main/java/com/killer560/hub/gui/tab/CrystalHollowsMapTab.java
src/main/java/com/killer560/hub/gui/profit/MiningProfitScreen.java
src/main/java/com/killer560/hub/gui/profit/NucleusProfitScreen.java
docs/MINING-PLAN.md         -> docs/MINING-PLAN.md
docs/CRYSTAL-HOLLOWS.md     -> docs/CRYSTAL-HOLLOWS.md
```

`docs/FEATURES-section.md` is the "Mining (WIP)" section exactly as it stood in docs/FEATURES.md. There were no mining
mixins, resources (assets/data json) or relay-server message types; the relay only forwards data packets by key.

## References removed from the compiled code

`references.patch` (in this folder) is the exact diff of every edited file outside the moved ones. In words:

- `Killer560ModClient.onInitializeClient`: the four `register()` calls `MiningProfitTracker`, `NucleusRunProfitTracker`,
  `ChDiscovery`, `ChShare` (replaced by a comment).
- `gui/ModScreen.init`: `tabs.add(new MiningWipTab());` (after `NewTab`) and its import.
- `gui/profit/ProfitTracker`: enum constants `MINING("Mining Profit", ...)` and `NUCLEUS("Nucleus Runs", ...)`, their
  `miningSummary()` / `nucleusSummary()` methods and four imports. `/profit` now lists croesus/dungeon, etable/experiments.
- `modchat/ModChatFeature.tick`: `chNeedsLobby = ChShare.wantsLobbyRoom()`, its part of the `on` condition and the
  `else if (chNeedsLobby) mode = LOBBY` branch. Older clients still sending `ch.v1.find` into a room we share are
  ignored: the only other data listener (`partydata/PartyDataFeature`) switches on its own keys and only in a dungeon.
- `profiles/ProfileManager`: the `CrystalHollowsMapConfig::load`, `NucleusRunProfitConfig::load`,
  `MiningProfitConfig::load` entries of the profile-switch reload list.
- `gui/SettingTooltipsData`: the `mining(d)` call and method (12 entries keyed `mining (wip)/...` and
  `crystal hollows map/...`) and the `"mining - planned (not in 1.3)"` entry.
- Comments only: `relay/HypixelLocation.lobbyKey` javadoc, `gui/profit/ProfitTrackerScreen` class doc.
- Docs: README's "Mining (WIP)" list line, docs/FEATURES.md's Mining section and its /profit wording, two
  docs/LESSONS.md mentions annotated, one pointer line in CLAUDE.md.

Kept on purpose: `util/ModPaths`' three rows (`mining-profit`, `nucleus-profit`, `chmap` -> `skyblock/...`). They name
folders only; dropping them would make `migrateAll` move an old root mining file into `other/`, where the restored
features would not find it. Also kept (not mining features): the RNG meter's Crystal Nucleus category, the scoreboard's
Crystal Hollows/Dwarven Mines lines, `IslandDetector`, `autoroutes/ItemIdentity`, and the profile viewer's Mining page.

## Restoring after 2.0

1. `git mv` every file above back to its original path (the Java tree: `git mv shelved/mining/src/main/java/com/killer560/hub/mining src/main/java/com/killer560/hub/mining`, then the four tabs and two screens one by one; the two docs back to `docs/`).
2. Re-apply the references: `git apply -R --3way shelved/mining/references.patch`. Hunks that no longer apply (the files
   will have moved on) are listed in the bullet list above; put each back by hand. Skip the README/FEATURES/LESSONS/CLAUDE
   hunks if the docs are being rewritten anyway, and put `docs/FEATURES-section.md` back into docs/FEATURES.md.
3. Decide where the tabs go: the "Mining (WIP)" category, or a final Mining category. Check `SettingTooltipsData` keys
   match the tab names (lookup is `"<sub-tab>/<label>"`), and that ModChatFeature still has a single place deciding the
   relay room (CH sharing needs LOBBY).
4. Build both variants and both Minecraft versions (see CLAUDE.md); the mining code was last compiled on 26.1.2 and 26.2
   at the shelving commit, so check `McCompat` use against anything that has changed since.
5. Testkit: un-shelve `139-hx-mining-nucleus-runs` (HxHudCases), put the shelved fixtures back into
   `src/gametest/resources/testkit-fixtures/` (see the testkit's `shelved/mining/README.md`), restore the mining rows in
   `ui/ProfitCases` and `allow-screens.json`, regenerate `pattern-catalog.json`, and retire `411-ui-mining-shelved`.
6. Delete this folder.
