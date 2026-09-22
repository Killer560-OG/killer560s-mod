# Killer560's Mod

A Fabric client mod for Hypixel Skyblock (Minecraft 26.1.2). Built as a personal quality-of-life toolkit that grew into something friends can use too.

See the #roadmap channel in the [Discord server](https://discord.gg/hkQMF5fE84) for what's planned next.

[![Discord](https://img.shields.io/discord/1546630783970713665?label=Discord&logo=discord&color=9B59B6)](https://discord.gg/hkQMF5fE84)

Join the [Discord server](https://discord.gg/hkQMF5fE84) for releases, support, and to suggest features.

## Features

**Full descriptions of every feature: [Killer560's Mod - Features](https://docs.google.com/document/d/e/2PACX-1vQsLEy_hlz-Dv518bH7AgYjyoGgdNl2jSAF4T8vzQHawmkg0f56q6Yrp67Zqncc8h9N-pdAvFLC5_D-/pub)**

New or changed features sit in the mod menu's **New** tab until they're confirmed working. Open the menu in game with `/killer560`. Features marked † have extra automation or through-walls options in the cheat build.

**Chat** - Auto Correct, Auto Meow, Chat Commands, Chat Emotes, Chat Keybinds, Click Translate, Command Auto Correct, Command Shortcuts, Copy Chat, Cringe, Mod Chat, Party Commands, Spotify Mod, Translate, Voice To Text

**Social & Supporters** - Best Friends, Friends List, Name Changer, Profile Viewer, Supporter Names

**Party Data & Cross-Mod** - Cross-Mod Bridge, Mod conflict warnings, Party Dungeon Data, Party Interop, Team Melody HUD, Teammate Highlight †, Teammate rooms on the map

**Items, Inventory & Trading** - Armour Recolour, Auction House Browser, Bazaar Browser, Custom Enchant Colours, Experimentation Table Profit Tracker, Experimentation Table Solver †, Inventory HUD, Inventory Search, Inventory Theme, Item Browser, Item Protection, Item Rarity Backgrounds, Listing Helper, Loadout Keybinds, Pet Wheel, Revert Master Stars, Scrollable Tooltips, Slot Binds, Storage Item Search, Storage Overlay

**HUDs & Helpers** - Ability Cooldowns, Ability Keybinds, Ability Timers, Advanced Position, Auto Join Skyblock, Custom Scoreboard, Discord Rich Presence, DVD, Etherwarp Overlay, Etherwarp Waypoints, GIF Player, Lag Display, No Fire, Object Hider, Pathfinding †, Player Stats HUD, Quiver Display, Real Time, Screenshot Copy, Trail, Trajectories, Waypoint Routes, Video Browser

**Dungeon: Solvers & Secrets** - Architect's First Draft †, Arrow Align †, Auto Close Chest, Blaze Solver, Boulder Solver, Creeper Beams Solver, Door Keys †, Ice Fill Solver, Ice Path Solver, Livid Solver, Mapping, Quiz Solver, Secret Waypoints, Simon Says †, Solver Highlights, Teleport Maze Solver, Terminal Solver †, Termism †, Tic Tac Toe Solver, Water Board Solver, Weirdos Solver

**Dungeon: Map, Leap & Party** - Chunk Cache, Custom Leap Menu, Dungeon Map, Dungeon Queue, Leap Message, Leap Order, Posmsg

**Dungeon: Timers, Score & Boss** - Blessings, Blood Camp †, Chest Profit, Croesus Profit Logger, Custom Mage Beam, Dungeon Alerts, Dungeon Run Summary, Dungeon Score Calculator, F7 Spots (F7/M7), Goldor Frenzy Timer, M7 King Relics, M7 Wither Dragons, Mask Invincibility Timers †, Maxor's Crystals (F7/M7 P1), Mob ESP †, P3 Nav (F7/M7) †, P4 Platform Highlight, Rag Axe, RNG Meter, Run HUDs, Sharp Shooter (i4) †, Split Timers, Starred Mob Hitboxes, Terminal Timers, Thorn (F4/M4) †, Tick Timers, Wither Highlight †

**Display & Menus** - Borderless Fullscreen, Fullbright, Held Item Transform, Motion Blur, Skyblock Only, Themed Main Menu, Window Layout

**Accounts, Home & Profiles** - Account Switcher, HUD Editor, Menu Memory, Profiles, Proxy Client

**Cheat Build Only** - 0 Ping Dungeon Breaker, AP3 (F7/M7 P3 automation), Auto Croesus, Auto Dialogue / Breaker Aura, Auto Puzzles (QUOI port), Auto Quiz / Auto Three Weirdos, Auto Routes, Cheat Utilities, Door Helpers, Fast/Auto Leap, Freeze State, Full Block, I Hate Diorite, i4 Leap Out, Interactive Map, Lever Aura, Secret Triggerbot, Terminal Aura, Terminal Triggerbot

## Latest dev build

Every push to `main` is built automatically and published as the **Latest Dev Build** pre-release on the [Releases page](https://github.com/Killer560-OG/killer560s-mod/releases/tag/dev-latest) - download the `-legit.jar` or `-cheat.jar` from there to get the newest commit without building it yourself. It is replaced on every push and is not an official release.

## Installing

1. Install Fabric Loader for `26.1.2`.
2. Drop Fabric API into your `mods` folder.
3. Grab the latest release jar from the [Releases](../../releases) page and drop it in `mods` too. Two jars are published per release:
   - **`-legit.jar`** — everything in the feature list above except autonomous auto-clicking and Full Block. This is the one almost everyone wants.
   - **`-cheat.jar`** — same mod, plus the Experimentation Table's fully autonomous mode, Auto Terminals (auto-clicks Floor 7 terminals for you), and Full Block (expanded secret hitboxes). These are real macros/exploits against Hypixel's rules - download at your own risk.
4. Launch with the Fabric profile — configure everything from the in-game mod menu (works standalone or through [Mod Menu](https://modrinth.com/mod/modmenu)).

Config is created automatically on first launch - nothing else to set up.

## Building from source

```bash
./gradlew build                    # legit build (default)
./gradlew build -PcheatBuild=true  # cheat build
```

Output jar lands in `build/libs/`.

## License

MIT — see [LICENSE](LICENSE). Third-party notices in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
