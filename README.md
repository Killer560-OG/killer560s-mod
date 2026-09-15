# Killer560's Mod

A Fabric client mod for Hypixel Skyblock (Minecraft 26.1.2). Built as a personal quality-of-life toolkit that grew into something friends can use too.

See the #roadmap channel in the [Discord server](https://discord.gg/hkQMF5fE84) for what's planned next.

[![Discord](https://img.shields.io/discord/1546630783970713665?label=Discord&logo=discord&color=9B59B6)](https://discord.gg/hkQMF5fE84)

Join the [Discord server](https://discord.gg/hkQMF5fE84) for releases, support, and to suggest features.

## Features

> **New tab:** every feature that's new or was just changed lives in the mod menu's **New** tab first, so it's easy to find what still needs testing. Once confirmed working it moves to its normal category tab below.

**Chat**
- Translate — auto-translates your chat messages into another language before sending
- Auto Correct — fixes common typos before you send
- Command Auto Correct — optional: fixes typos in command names you type ("/wardorbe" → "/wardrobe") against the server's real command list, never touching arguments
- Chat Emotes — shortcuts that turn into fun chat emotes
- Click Translate — click a chat message to see it translated
- Copy Chat — Shift+Click a message to copy the whole thing, or Shift+Right-Click to copy just one line of it
- Auto Meow — random cat noises
- Cringe — random one-liner messages for fun
- Spotify Mod — posts the lyrics of whatever you're playing on Spotify into chat, synced to the song
- Mod Chat — `/killer560 chat <message>` tags a message so other mod users get it as a highlighted overlay; sent over real Party/Guild chat, so it's not actually private from non-mod-users there (Hypixel doesn't offer a private client-only channel)
- Voice To Text — hold a push-to-talk key, speak, release to send the transcription to chat. Fully offline (Vosk), downloads its small speech model automatically on first use so there's no manual setup. **Untested with a real microphone** - see the New tab
- Proximity Voice — real peer-to-peer voice chat with distance-based volume falloff, no server required (uses a free public STUN server for NAT traversal + Party Chat to exchange connection info). No audio compression (raw PCM, to avoid a second unverified native-library dependency) and doesn't work through every home network (no relay server to fall back on for strict NATs). **Untested with a real microphone or a second player** - see the New tab

**Hud Elements**
- GIF Player — plays a GIF (with optional audio) as a HUD overlay
- DVD — the classic bouncing DVD logo screensaver on your screen
- YT Shorts — watch YouTube Shorts in a small 9:16 window pinned over Minecraft (Windows only; uses your installed Edge or Chrome in its own profile, log into YouTube once). Keybinds for show/hide, next, previous, play/pause and mute work without leaving the game. **Untested** - see the New tab

**Helpers**
- Quiver Display — shows the real "Arrows Remaining" count from any real arrow/quiver item anywhere in your inventory or off hand
- Player Stats HUD — reads real Health/Mana/Defense from the real action bar and shows them as their own always-on-screen HUD line, without touching the real action bar itself
- Experimentation Table Solver — solves Chronomatron, Ultrasequencer, and Superpairs in a highlight-only "Solver Only" mode (you click, it just shows you the answer). The **cheat** build additionally has a fully autonomous mode that clicks for you - see [Which jar do I download?](#installing) below.
- Experimentation Table Profit Tracker — logs every claimed experiment (game, clicks, rewards, XP, bits) with coin values and running totals, plus a max-clicks chat notice in both Solver Only and Autonomous modes
- Storage Overlay — see the contents of every Ender Chest page and Backpack live, right alongside your open inventory
- Screenshot Copy — automatically copies a screenshot to your clipboard the moment you take it
- No Fire — removes the on-screen fire overlay so burning doesn't block your view mid-fight
- Auto Join Skyblock — automatically runs `/skyblock` the moment you connect to Hypixel

**Dungeon**
- RNG Meter — tracks your Hypixel Skyblock RNG/pity progress on dungeon floors
- Chest Profit — shows the value of every reward, the cost, and the profit next to any open dungeon reward chest (end-of-run room or Croesus), and in a Croesus run view lists every chest's profit with the best one highlighted green. Uses the RNG Meter's live Bazaar/AH prices
- Croesus Profit Logger — logs every chest you actually claim (time, floor, chest, items with prices, cost, profit) to `config/killer560smod-croesus-log.json`, with session and all-time totals per floor, a Reset Totals button, and a chat summary after each claim
- Auto Croesus — **cheat build only.** Open Croesus yourself and it opens each unopened run and claims its single most profitable chest if it clears your Min Profit, with random Min/Max click delays; skips anything below the minimum, never rerolls or uses keys, and stops on any unexpected screen (close the menu to stop)
- Leap Message — sends a message when you leap to someone in dungeons
- Leap Order — pick the class you're playing, then place your party's names in the 4 leap menu spots; each class keeps its own layout, and the custom leap menu uses the one for the class the tab list says you're on
- Fast Leap / Auto Leap — **cheat build only.** QUOI's Auto Leap: left-click a Spirit Leap to leap to the right teammate for where you are (door opener, P1, predev, pads, PY healer, Storm death, P3 sections, middle, P5, relic), each with an optional Auto trigger; targets by name, class or whoever posted that position (posmsg); block inputs / fast mode / swap back
- Posmsg — position-message waypoints for real dungeon rooms (preloaded presets like Simon Says/EE2/EE3/Outpour/Recor/Necron's Platform, plus custom ones you add), each with its own radius, display, and once-vs-repeating toggles
- Ability Timers — a generic list of named countdown timers, each with its own keybind to start/restart it the moment you use that real ability
- Dungeon Info — a secrets-found HUD (from the tab list), run-time tracker, score-milestone messages, and mimic/prince/bat kill alerts (dungeons only)
- Dungeon Alerts — Shadow Assassin alert, secret-collected sound, F6/M6 Terracotta timers, Spring Boots height HUD, Ragnarock cast/buff alerts, dungeon class colors on nametags and tab, and room-entry alerts
- Mob ESP — highlights star-tier dungeon mobs by name filter (real vanilla Glowing); legit mode only glows what you can already see via a real raycast, the **cheat** build can glow through walls too
- Mapping — a data-gathering tool for building future map features ("Dump Held Map Now"); funny map/mimic highlight/class recolor are reserved settings that don't draw anything yet
- Etherwarp Waypoints — per-run-only reminders for secret etherwarp spots you've marked, never saved to disk
- Custom Leap Menu — replaces the Spirit Leap GUI (the real chest and your inventory are hidden) with 4 big name boxes in your Leap Order, colored by class; click a quarter of the screen or press 1-4 to leap
- Etherwarp Overlay — while holding a real Etherwarp item, highlights exactly where you'd land (green if safe, red if not) using a real voxel raycast. Never moves you — the real server still handles the actual warp
- Slot Binds — link two slots in your real inventory (one must be a hotbar slot); shift-click either one to swap it with its bound partner using the same real vanilla mechanic pressing a number key over a slot uses
- Chat Commands — replies to "!coords", "!ping", "!fps", "!time", "!holding", "!cf", "!8ball", and "!dice" from real party/guild/private/co-op chat. Never runs a party-management command from someone else's message
- Door Keys — highlights a real dropped Wither/Blood Key the moment it appears, with an optional tracer line
- Trajectories — predicts where a real bow shot or Ender Pearl throw would land using the real vanilla drag/gravity physics for each. Never fires anything
- Loadout Keybinds — on the real "(N/M) Loadout" screen, use number-row keys and left/right arrows to click a loadout slot or page instead of the mouse
- Ability Keybinds — bind any key to your real dungeon class Ability or Ultimate (the same real vanilla drop-item/drop-stack action Hypixel already reads), instead of the fixed Q/Ctrl+Q
- P4 Platform Highlight — highlights the real fixed 3x3 platform you need to mine after Goldor dies, before dropping into Necron's fight
- I Hate Diorite (cheat build only) — swaps Storm's real diorite pillars to see-through stained glass client-side during her fight
- Better Party Finder — shows a joining party member's real Catacombs level and secret count, with a clickable Kick button (no auto-kick)
- Command Keybinds — 8 individually-bindable keys for common Skyblock menu commands (pets/storage/armor/equipment/loadouts/stats/dungeon hub/potion bag)
- Revert Master Stars — shows Master Star items with the old all-red-stars look instead of Hypixel's numbered pip (cosmetic only)
- Inventory Search — Ctrl+F in any inventory-type screen to search and highlight matching items by name/lore
- Item Browser — a NEU-referenced searchable panel on the right of any inventory screen showing the real, complete Skyblock item catalog with real icons
- Item Rarity Backgrounds — colors the slot behind every Skyblock item by its rarity (Common through Divine/Special/Ultimate, pets included) in inventories and optionally the hotbar, with Square/Circle/Outline styles and adjustable opacity (visual only)
- Name Changer — client-side only: change how your own name shows, rename specific players, or randomize everyone else's names (chat, nametags, tab list, GUIs)
- Held Item Transform — resize, move and rotate the held item in each hand, plus No Swing, No Equip animation, No Hand Sway and swing speed
- Storage Item Search — search every cached ender chest page, backpack and your inventory by name, id or lore; click a result to open that storage with the slot outlined
- Waypoint Routes — per-area waypoint routes that advance as you reach each point, with keybinds to add/remove/skip, and clipboard import/export in ColeWeight and Skytils formats
- Custom Mage Beam — replaces the mage beam particles with a clean colored beam (color, width, duration, fade)
- Auto Quiz / Auto Three Weirdos — **cheat build only.** Clicks the solver's answer in Quiz and opens the right chest in Three Weirdos
- Auto Dialogue / Breaker Aura — **cheat build only.** Picks NPC dialogue options (never purchases or trades), and breaks blocks in your path with the Dungeonbreaker
- Dungeon Queue — re-queues the floor you just finished after a delay (leader/solo only, cancel key, skipped when someone types "dt"), plus a requeue key
- Inventory HUD — your main inventory drawn as a movable HUD panel (mini/normal, horizontal/vertical, background style, show always / hold key / toggle key)
- Skyblock Only — one toggle on the Home tab that pauses every feature outside Hypixel Skyblock and p3sim.net without changing any of your settings; rejoin Skyblock and everything is back on
- Motion Blur — smooth frame-blending blur for high-FPS recordings (strength slider, optional GUI blur); turns itself off with a chat notice if the shader can't load
- Window Layout — pick how many game windows share a monitor, then click a cell in the picker to snap this window there at the right size (per-instance memory, optional restore on launch, taskbar-aware)
- Real Time — a HUD clock from your computer's time (12h/24h, seconds, zone abbreviation) or any custom time zone
- Live Map rooms — multi-tile rooms (1x2 to 2x2 and L shapes) drawn and identified as one room, with room-state from the dungeon map and label styles (checkmarks, secrets, names)
- Custom Scoreboard — SkyHanni-style replacement sidebar (ported from SkyHanni and SkyBlock Custom Scoreboard): reorderable lines and events, alignment, background with rounded corners and border; unknown lines still show
- Profile Viewer — `/pv [name]`: NEU-style Skyblock profile viewer (skills, slayers, dungeons with floor times, inventories/ender chest/backpacks/wardrobe/accessories, pets) with a 3D skin preview
- Pack Disabler — stops Hypixel's forced Skyblock resource pack from loading, without getting kicked for required packs
- Terminal Solver — highlights the correct slot(s) to click on Floor 7 terminal puzzles, including Melody detection. The **cheat** build additionally has an Auto Terminals mode (with a Min/Max click delay, an input-block safety toggle, and a Melody Skip Mode for how aggressively it clicks ahead) that clicks for you - see [Which jar do I download?](#installing) below.
- Termism — a practice mode that generates fake terminal puzzles (every real type, including a real timed Melody puzzle) so you can drill them without a real dungeon run, with Auto Terminals also usable here on the cheat build to test it risk-free
- Full Block — **cheat build only.** Expands the clickable area of levers, buttons, chests, and Wither Essence blocks so real secrets are easier to click, with a master on/off toggle - see [Which jar do I download?](#installing) below.
- Simon Says — highlights the correct button(s) on the F7/M7 boss-fight Simon Says device with numbered, colored boxes (next = green, then orange, then red), tracks party members' progress from their chat announcements (compatible with Odin/QUOI's own progress messages too), and can block a real click on the wrong button (Prevent Misclicks - hold Shift to override). The **cheat** build additionally has a Trigger Bot, Auto Start for the real "skip" trick (configurable clicks, exact tick spacing), and Auto Solve with Rotate / No Rotate modes and a Timer Target (11-13s from the first start-button click, same as Hypixel's own device timer, with up to ±250ms variance), plus Auto Restart SS (restarts the device when it breaks), a Restart keybind, and a Trigger Bot delay slider (ms from aiming at the right button to the click) - see [Which jar do I download?](#installing) below.
- Tick Timers — real countdowns for Necron's drop, Goldor's Core opening, and Storm's pad/lightning/purple-pillar/crush windows, driven off the real boss chat lines
- Split Timers — per-segment time splits for every floor's real boss fight (Bonzo through Necron), announced in chat and shown on a HUD list
- Terminal Timers — Odin-style F7/M7 P3 timing: "Panes solved in 3.21s!" after each terminal you complete, (section | phase) times added to every terminal/device/lever completion line with a per-section summary when the core opens, and the Simon Says whole-device time
- Mask Invincibility Timers — automatic active/cooldown timers for Spirit Mask, Bonzo's Mask, and Phoenix Pet, detected off the real "saved your life" chat lines. The **cheat** build additionally has Auto Swap: right-clicks your other mask into place the moment the worn one procs, if it's off cooldown
- Sharp Shooter (i4) — a Solver that marks every hit target green and dots the between-column spots to aim at for predictions, plus always-on sensors for the F7/M7 i4 arrow device that log to latest.log. The **cheat** build adds Auto i4: shoots each lit target (Rotate / No Rotate, adjustable rotation speed), Terminator (with predictions) or Machine Gun Shortbow (aims each block, fires Rapid Fire at the start), auto swap to your bow, and Auto Mask with a Bonzo / Spirit / Phoenix order (in development)
- i4 Leap Out — **cheat build only**, in the Sharp Shooter (i4) tab. Leaps when your i4 device finishes (or on left-click at pre4) to a chosen class, player, or the Melody player with a backup target; Prevent Inputs blocks input during i4 and the leap
- 0 Ping Dungeon Breaker — **cheat build only.** While holding a real Dungeon Breaker item with charges left, insta-mines the exact block you're looking at the instant you start mining it, instead of waiting on your real connection's ping
- Live Map — a self-drawn room/door map for the current dungeon run, using the dungeon's real fixed 11x11 room grid, real door-type detection, and (new) real room NAMES from the same room database Secret Waypoints uses, plus live teammate positions (colored by their assigned class from the Leap Menu)
- Secret Waypoints — real, preloaded per-room secret positions (chests, items, wither skulls, bats, redstone keys) once a room is identified, downloaded from the same public room database NoammAddons itself uses. Includes real mimic-chest detection (an extra trapped chest beyond what a room should have)
- Auto Close Chest — instantly closes a real secret reward chest ("Chest"/"Large Chest"/"Trapped Chest") the moment it opens in a dungeon, before it's ever shown on screen
- Blood Camp — tracks the real F7 boss-fight Watcher and blood mobs by their real skull skins, predicting where each mob is about to resettle and showing a real countdown until it's vulnerable again. The **cheat** build additionally has a Trigger Bot (clicks once the countdown expires and you're looking at it, with auto ping-based or manual tick-offset timing) and an Aura that turns to face the predicted spot in advance
- Cheat Utilities — **cheat build only.** Wither ESP (F7 boss), Secret Aura (auto-clicks chests/levers/essence in reach), Auto GFS (sack refills), Auto Ult (Healer/Tank at the right boss moments), and Auto Chocolate Factory
- Boulder Solver — reads the real Boulder puzzle room's floor pattern and highlights the real solution tile(s) to click, ported from a known 8-pattern solution database. Never clicks for you
- Quiz Solver — reads the real Oruo the Omniscient trivia question and lettered answer options in chat, looks up the correct answer in a bundled real question database, and highlights that option's floor tile. Never answers for you
- Ice Fill Solver — identifies each of the real Ice Fill puzzle's 3 floor layouts and draws the real known-safe walking path across all of them. Never walks for you
- Weirdos Solver — reads the real Three Weirdos NPC dialogue lines and highlights the real correct chest (and, optionally, ruled-out ones) the moment a line gives it away
- Water Board Solver — identifies the real Water Board layout and shows a real live countdown above every remaining lever click, highlighting the soonest one. Never clicks anything for you
- Creeper Beams Solver — highlights real currently-connected Sea Lantern pairs with matching colors, updating live as panes are rotated. Never touches anything
- Blaze Solver — ranks real blazes in the Lower/Higher Blaze puzzle by HP and highlights the correct next few kill targets in order. Never attacks anything
- Livid Solver — identifies the real correct Livid on Floor 5 from the wool color clue and highlights it, plus a countdown for its opening invulnerability window. Never attacks anything

**Display**
- Borderless Fullscreen — F11 toggles between windowed and borderless fullscreen, never true exclusive fullscreen
- Fullbright — see clearly in dark areas without touching your real brightness setting

**Accounts**
- Account Switcher — swap between your saved Microsoft accounts from the main menu, with each one's Hypixel ban status shown; each account can also have its own SOCKS proxy assigned, applied automatically every time you swap to it
- Proxy Client — optionally route your connection through a SOCKS proxy

**Home**
- A HUD editor to drag and resize the mod's on-screen elements wherever you want them
- The mod menu remembers your scroll position, selected tab, and search text between opens

**Profiles**
- Save your current settings as a named profile, switch between profiles, and export one to a single `.zip` file to share with a friend (they drop it in `config/killer560smod-profiles/` and import it). Excludes real credentials (session login token, per-account proxies) and run caches — only actual feature settings travel with a profile. Switching applies immediately to disk; restart Minecraft for every feature to pick it up

## Requirements

- Minecraft `26.1.2`
- [Fabric Loader](https://fabricmc.net/use/) `>=0.19.3`
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java `25`+

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
