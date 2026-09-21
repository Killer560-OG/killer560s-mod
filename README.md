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
- Mod Chat — a genuinely private chat channel between mod users. Messages travel over the mod's own relay server rather than Hypixel's party or guild chat, so players who don't run the mod never see them. Send with `/killer560 chat <message>`; the tab shows who else from your party is connected. Falls back to nothing if the relay is unreachable — it will never quietly post your message in public chat
- Voice To Text — hold a push-to-talk key, speak, release to send the transcription to chat. Fully offline (Vosk), downloads its small speech model automatically on first use so there's no manual setup. **Untested with a real microphone** - see the New tab

**Hud Elements**
- GIF Player — plays a GIF (with optional audio) as a HUD overlay
- DVD — the classic bouncing DVD logo screensaver on your screen
- YT Shorts — watch YouTube Shorts in a small 9:16 window pinned over Minecraft (Windows only; uses your installed Edge or Chrome in its own profile, log into YouTube once). Keybinds for show/hide, next, previous, play/pause and mute work without leaving the game. Dark / Light / System theme option. **Untested** - see the New tab

**Helpers**
- Quiver Display — shows the real "Arrows Remaining" count from any real arrow/quiver item anywhere in your inventory or off hand
- Player Stats HUD — reads real Health/Mana/Defense from the real action bar and shows them as their own always-on-screen HUD line, without touching the real action bar itself
- Experimentation Table Solver — solves Chronomatron, Ultrasequencer, and Superpairs in a highlight-only "Solver Only" mode (you click, it just shows you the answer). The **cheat** build additionally has a fully autonomous mode that clicks for you - see [Which jar do I download?](#installing) below.
- Experimentation Table Profit Tracker — logs every claimed experiment (game, clicks, rewards, XP, bits) with coin values and running totals, plus a max-clicks chat notice in both Solver Only and Autonomous modes
- Storage Overlay — see the contents of every Ender Chest page and Backpack live, right alongside your open inventory
- Screenshot Copy — automatically copies a screenshot to your clipboard the moment you take it
- No Fire — removes the on-screen fire overlay so burning doesn't block your view mid-fight
- Discord Rich Presence — shows "Playing Killer560's Mod" on your Discord profile, with your island/area, dungeon floor and boss phase, and an elapsed timer. Off by default; a Hide Details mode shows only the mod name
- Object Hider — client-side render suppressors for M7/F7: healer fairy, power orbs (with a keep-nearby radius), soulweaver skulls, archer bone meal, sheep, wither cloak creepers, shield hearts, grounded arrows, blindness, death animations, block-break/explosion/smoke particles, dying P5 dragons, the Wither King model, boss damage splashes, and a Clean End mode. Nothing is removed from the world — it only stops rendering
- Ability Cooldowns — automatic cooldown HUD: detects the ability you used (its sound, the mana action-bar line, or chat) and counts down the real cooldown from a 41-item table ported from SkyHanni, with a dungeon-only filter and a click window so a teammate's ability can't start your timer
- Lag Display — how long ago the last server tick arrived, plus ping, FPS and a clicks-per-second counter, in one movable HUD element
- Goldor Frenzy Timer — a HUD countdown to Goldor's 3-second P3 damage tick, plus the Storm-to-Goldor gap and an optional cumulative P3 timer
- Auto Join Skyblock — automatically runs `/skyblock` the moment you connect to Hypixel

**Dungeon**
- RNG Meter — tracks your Hypixel Skyblock RNG/pity progress on dungeon floors
- Chest Profit — shows the value of every reward, the cost, and the profit next to any open dungeon reward chest (end-of-run room or Croesus), and in a Croesus run view lists every chest's profit with the best one highlighted green. Uses the RNG Meter's live Bazaar/AH prices
- Croesus Profit Logger — logs every chest you actually claim (time, floor, chest, items with prices, cost, profit) to `config/killer560smod-croesus-log.json`, with session and all-time totals per floor, a Reset Totals button, and a chat summary after each claim
- Auto Croesus — **cheat build only.** Open Croesus yourself and it opens each unopened run and claims its single most profitable chest if it clears your Min Profit, with random Min/Max click delays; skips anything below the minimum, never rerolls or uses keys, and stops on any unexpected screen (close the menu to stop)
- Leap Message — sends a message when you leap to someone in dungeons
- Leap Order — pick the class you're playing, then place your party's names in the 4 leap menu spots; each class keeps its own layout, and the custom leap menu uses the one for the class the tab list says you're on
- Auto Routes — **cheat build only, work in progress — not part of the current release.** Record a route through a dungeon room (`/ar start record`) and play it back when you etherwarp onto its start node. Recording captures your actual path; playback follows it rather than replaying key presses blind, so a lag spike doesn't desync the rest of the route, and every etherwarp / item use / block break waits for real confirmation before moving on. Node types for etherwarp, walk, use item, dungeon breaker, superboom and waits, a `/ar` command for each with an assignable keybind, per-node-type colours, and an edit mode for picking a breaker's blocks. Routes live in one shareable, hand-editable JSON with an Open Routes Folder button and `/ar reload`.
- Scrollable Tooltips — the mouse wheel scrolls an item tooltip that is taller than the screen, with an optional hold-a-key modifier. Short tooltips are untouched and the wheel still works normally on them.
- Custom Enchant Colours — recolours enchantment names in item lore so the ones that matter stand out, per enchant, with ~110 sensible defaults you can edit. Only acts on items Hypixel actually tags with enchantment data.
- Armour Recolour — client-side only: give any armour piece your own dye colour, an armour skin or a trim. Nothing is sent to the server and the real item never changes.
- Terminal Aura — **cheat build only.** Opens P3 terminals by itself when one comes within range (range, delay, ground-only, and an after-leap delay so you don't steal the terminal the person you leapt to was walking into). Opening only — Auto Terminals is what solves them — and it never rotates your camera.
- Terminal Triggerbot — **cheat build only.** Same shape as Secret Triggerbot: opens the P3 terminal your crosshair is already on, after a configurable delay, with a cooldown and a reach limit. Never moves your camera.
- Fast/Auto Leap — **cheat build only.** (its own tab, separate from Leap Menu) QUOI's Auto Leap: left-click a Spirit Leap to leap to the right teammate for where you are (door opener, P1, predev, pads, PY healer, Storm death, P3 sections, middle, P5, relic), each with an optional Auto trigger; targets by name, class or whoever posted that position (posmsg); block inputs / fast mode / swap back. The leap menu never shows on screen during a fast/auto leap. Each leap is a row in a list with an Edit button that opens all of that leap's settings on their own page. Includes a temporary "Test" leap (always leaps to a chosen class wherever you are) for testing, to be removed later.
- Lever Aura — **cheat build only.** F7/M7 P3 Section 2: flicks the Lights device levers for you (the 4 corners and 2 middle before S2 opens, then one finishing/activating flick once S1 is done), optional S2 section levers; every lever is clicked at most once, no camera turn
- Thorn (F4/M4) — Spirit Bear HUD (kills toward the bear out of 25/30, spawn countdown, overkill count), Spirit Bear / spirit mob / Spirit Bow ESP (through walls is **cheat build only**), and your own stun-spot waypoints
- Dungeon Score Calculator — live estimated score and rank (S+/S/A...) from the tab list, with an optional Skill/Explore/Speed/Bonus breakdown, secrets still needed for S+, crypts/deaths, mimic/prince, and optional 270/300 titles and party messages (party messages off by default)
- M7 Wither Dragons — Phase 5 helper: per-dragon spawn timers in-world and on a HUD, spawn boxes, health, a "which dragon is yours" title using the power/class split rules, and ice-spray / arrow counters
- M7 King Relics — relic spawn countdown, highlight of the cauldron matching the relic you're carrying, your placement time, and a party placement summary
- Chunk Cache — every chunk you've loaded stays readable in memory after the game would unload it, so the Interactive Map, its pathfinder, secret waypoints and the solvers still see rooms you've already visited; memory limit with oldest-first eviction, a Clear Cache button and a live cached-chunk readout. Per world, cleared on every world change; nothing extra is rendered, nothing in a cached chunk ticks, and collision/"is it loaded" stay exactly as they are without the feature
- Pathfinding — shows the fastest route to anywhere on the island (SkyHanni-style island graphs, downloaded at runtime) with `/k560path`, plus Fairy Souls: missing-soul waypoints, a whole-island route, a per-profile found log that syncs from Hypixel's own Fairy Souls menu, and a found/total HUD. **Cheat build only:** Auto Walk Path / Auto Fairy Souls with Walk, Etherwarp and Fast Etherwarp modes
- F7 Spots (F7/M7) — your own walk-to waypoints per phase (box, beam, label, distance), a Storm crush timer HUD with purple-pad highlight, and Last Breath aim-spot markers filtered to your class (plus NoammAddons' arrow-stack points and Devonian's Last Breath waypoints for the five P5 dragons)
- P3 Nav (F7/M7) — highlights the Phase 3 section gate until it's actually destroyed, and boxes every unfinished terminal, lever and device in your section, each box vanishing the moment it's completed (through walls is **cheat build only**)
- Rag Axe — Ragnarock Axe helper: cast/cancel alerts, separate channel (3s), buff (10s) and cooldown (20s) countdowns, the Strength gained message with an optional party announce, and built-in "rag now" prompts with per-prompt lead times for M7 P5 dragons (line + per-dragon spawn), Necron's drop-down, Storm, and F5/M5 Livid
- Blessings — tracks each dungeon blessing your run has (Power, Time, Wisdom, Stone, Life) from the tab list on a movable HUD, with optional Roman numerals, a chat announcement when one levels up, and an optional once-per-run party message. Levels only — no stat estimates
- Maxor's Crystals (F7/M7 P1) — Energy Crystal respawn countdown, how long you took to place yours (with a saved personal best, wiped only on a two-click confirm), a warning while you're still holding an unplaced crystal, an active-crystals counter, and an optional highlight of the crystals
- Dungeon Run Summary — one saved record per finished run (floor, total time, splits, device/terminal times, secrets, crypts, deaths, failed puzzles, the mod's estimated score next to Hypixel's real Team Score, class and party size), a history browser, per-floor personal bests, and an optional compact chat summary (off by default so it doesn't repeat Split Timers)
- Posmsg — position-message waypoints for the F7/M7 boss fight: each one draws a ring on the ground and types its own message into party chat ("at hee2" — no coordinates, so teammates need no mod to read it) the moment you walk inside it. Completely inert outside the boss. Presets for the real spots (Simon Says/EE2/EE3/Outcore/Recore/Necron's Platform/P5) ship pre-positioned with real coordinates, plus custom ones you add; each has its own message, radius slider, ring toggle, colour, label text scale and height, and an optional "only send once per run". Every waypoint starts off — turn on the ones you want
- Ability Timers — a generic list of named countdown timers, each with its own keybind to start/restart it the moment you use that real ability
- Dungeon Info — a secrets-found HUD (from the tab list), run-time tracker, score-milestone messages, and mimic/prince/bat kill alerts (dungeons only)
- Dungeon Alerts — Shadow Assassin alert, secret-collected sound, F6/M6 Terracotta timers, Spring Boots height HUD, dungeon class colors on nametags and tab, and room-entry alerts
- Party Interop — one place for what the whole party knows about the run. Works out what it can from what Hypixel already sends every client (the action bar's per-room secret count, terminal/device/lever lines, blood door and Watcher lines, the tab list's totals), reads the party-chat announcements other dungeon mods make (NoammAddons, Odin, Devonian, QUOI, Skytils markers) so you benefit without installing any of them, and leaves a seam for the mod's own relay. Every fact records where it came from and the most trustworthy source wins. Optional read-only bridge to NoammAddons/Odin if you happen to run them. Off by default; dungeons only
- Teammate Highlight — boxes your dungeon party in the world coloured by their class, with optional name and distance labels, a self toggle and a dead-teammate skip; legit mode only shows a teammate you can actually see, the **cheat** build can box through walls
- Mob ESP — highlights star-tier dungeon mobs by name filter (real vanilla Glowing); legit mode only glows what you can already see via a real raycast, the **cheat** build can glow through walls too
- Mapping — a data-gathering tool for building future map features ("Dump Held Map Now"); funny map/mimic highlight/class recolor are reserved settings that don't draw anything yet
- Etherwarp Waypoints — per-run-only reminders for secret etherwarp spots you've marked, never saved to disk
- Custom Leap Menu — replaces the Spirit Leap GUI (the real chest and your inventory are hidden) with 4 big name boxes in your Leap Order, colored by class; click a quarter of the screen or press 1-4 to leap (scale 50-400%)
- Etherwarp Overlay — while holding a real Etherwarp item, highlights exactly where you'd land (green if safe, red if not by default - both colours and Outline / Filled / Filled+Outline style are configurable) using a real voxel raycast. Never moves you — the real server still handles the actual warp
- Item Protection — lock inventory slots, protect named or starred items (with an optional padlock icon on every protected slot), and block the drop key so a Hyperion can't be dropped, sold, salvaged, traded or anvil-fed by accident; every block says so in chat
- Slot Binds — link two slots in your real inventory (one must be a hotbar slot); shift-click either one to swap it with its bound partner using the same real vanilla mechanic pressing a number key over a slot uses
- Chat Commands — replies to "!coords", "!ping", "!fps", "!time", "!holding", "!cf", "!8ball", and "!dice" from real party/guild/private/co-op chat. Never runs a party-management command from someone else's message
- Party Commands — Odin's party commands (!warp, !wt, !allinvite, !ptme, !invite, !kick, !promote, !demote, !boop, !dt, !f1-!t5, !help) plus !reinv (!reinvite), which kicks the teammate who asked and invites them back 5 seconds later to fix a stuck party or instance — but only a player who is actually in your party or your current dungeon run can trigger any of them — guild chat, DMs and anyone outside the party are ignored, and only chat that really came from the server counts. Off by default, one toggle per command, with a separate switch for the destructive ones (warp, warp + transfer, kick, reinvite, demote, floor queue). Rate limited per player and overall, and every command a teammate runs is printed in your own chat
- Door Keys — highlights a real dropped Wither/Blood Key the moment it appears, with an optional tracer line (adjustable thickness) and, in the cheat build, an ESP option that shows it through walls
- Trajectories — predicts where a real bow shot or Ender Pearl throw would land using the real vanilla drag/gravity physics for each. Never fires anything
- Loadout Keybinds — on the real "(N/M) Loadout" screen, use number-row keys and left/right arrows to click a loadout slot or page instead of the mouse
- Ability Keybinds — bind any key or mouse button to your real dungeon class Ability or Ultimate (the same real vanilla drop-stack/drop-item action Hypixel already reads), instead of the fixed Ctrl+Q/Q
- P4 Platform Highlight — highlights the real fixed 3x3 platform you need to mine after Goldor dies, before dropping into Necron's fight, and hides itself again once the platform has been mined out
- I Hate Diorite (cheat build only) — swaps Storm's real diorite pillars to see-through stained glass client-side during her fight
- Chat Keybinds — bind any key or mouse button (middle click included) to a command or chat message you type in yourself; the old 8 menu binds are migrated automatically
- Revert Master Stars — shows Master Star items with the old all-red-stars look instead of Hypixel's numbered pip (cosmetic only)
- Inventory Search — Ctrl+F, or a click into the search bar, to search and highlight matching items by name/lore in any inventory-type screen; adjustable bar scale, Ctrl+Backspace word delete, and correct highlighting inside the Storage Overlay's own grid
- Item Browser — a NEU-style full-height panel listing the complete Skyblock item catalog with real icons beside any inventory screen; adjustable column count (3-20), scale, horizontal/vertical fill order and left/center/right anchoring, hover lore with tier/category/NPC sell price, and a left-click craft and obtain popup. Shares its search box with Inventory Search
- Item Rarity Backgrounds — colors the slot behind every Skyblock item by its rarity (Common through Divine/Special/Ultimate, pets included) in inventories and optionally the hotbar, with Square/Circle/Outline styles and adjustable opacity (visual only)
- Name Changer — client-side only: change how your own name shows, rename specific players, or randomize everyone else's names (chat, nametags, tab list, GUIs), each with its own colour picker
- Held Item Transform — resize, move and rotate the held item in each hand, plus No Swing, No Equip animation, No Hand Sway and swing speed (0.05x-4x)
- Storage Item Search — search every cached ender chest page, backpack and your inventory by name, id or lore; click a result to open that storage with the slot outlined
- Waypoint Routes — per-area waypoint routes that advance as you reach each point, with keybinds to add/remove/skip, and clipboard import/export in ColeWeight and Skytils formats
- Custom Mage Beam — replaces the mage beam particles with a clean colored beam (color, real thickness up to a full block, duration, fade)
- Auto Quiz / Auto Three Weirdos — **cheat build only.** Clicks the solver's answer in Quiz and opens the right chest in Three Weirdos
- Auto Puzzles (QUOI port) — **cheat build only.** An auto for every other puzzle, each needing its solver on: Auto Blaze, Auto Creeper Beams and Auto Ice Path shoot your shortbow (shared Shoot/Miss cooldowns), Auto Boulder / Auto Water Board / Auto Tic Tac Toe click the right buttons, levers and cells, Auto Teleport Maze faces and walks to the right pad, Auto Ice Fill steps the path with your AOTV, plus an optional Etherwarp Reposition that warps you to each puzzle's standing spots
- Auto Dialogue / Breaker Aura — **cheat build only.** Picks NPC dialogue options (never purchases or trades; dungeons only unless "Outside Dungeons" is on), and breaks blocks in your path with the Dungeonbreaker
- Dungeon Queue — Auto Requeue (sends /instancerequeue at the end of a run after a delay, skipped if a party member leaves or is kicked) and Party Finder Overlay (green/red joinable highlight, member count, and tooltip stats: Catacombs level, secrets, PB for the floor, missing classes)
- Inventory HUD — your main inventory drawn as a movable HUD panel (mini/normal, horizontal/vertical, background style, show always / hold key / toggle key)
- Skyblock Only — one toggle on the Home tab that pauses every feature outside Hypixel Skyblock and p3sim.net without changing any of your settings; rejoin Skyblock and everything is back on
- Motion Blur — smooth frame-blending blur for high-FPS recordings (strength slider, optional GUI blur); turns itself off with a chat notice if the shader can't load
- Window Layout — pick how many game windows share a monitor, then click a cell in the picker to snap this window there at the right size (per-instance memory, optional restore on launch, taskbar-aware)
- Real Time — a HUD clock from your computer's time (12h/24h, seconds, zone abbreviation) or any custom time zone
- Custom Scoreboard — SkyHanni-style replacement sidebar (ported from SkyHanni and SkyBlock Custom Scoreboard): reorderable lines and events, alignment, background with rounded corners and border; unknown lines still show; optional background blur, unclaimed bits, powder totals, exact SkyBlock minutes, Perkpocalypse mayor, and min size/margin options
- Profile Viewer — `/pv [name]`: NEU-style Skyblock profile viewer (skills, slayers, dungeons with floor times, inventories/ender chest/backpacks/wardrobe/accessories, pets) with a 3D skin preview; a Weight page with Senither and Lily weight (skill/slayer/dungeon breakdown with overflow); source defaults to Auto, a recent-views sidebar (your own head pinned first, click a head or type a name to switch), Ender Chest and Backpacks tiled several pages at once, click-to-select Bestiary categories, and overflow levels on capped skills
- Themed Main Menu — the title screen in the mod's black/orange theme: themed buttons, dark animated background with drifting embers, a cleaner button column (no Realms/language/accessibility buttons, Swap Accounts in the column), and the same theme on every other menu: multiplayer, options, world select, mod list and other mods' screens, with themed buttons, text fields, sliders, checkboxes, tabs, lists, scrollbars and tooltips (one toggle in Display; chests and inventories stay normal)
- Arrow Align — F7/M7 third device solver: clicks-needed numbers on each frame, Prevent Misclicks (crouch to override); **cheat build:** Trigger Bot with delay and Aura
- Secret Triggerbot — **cheat build only.** Clicks a secret (chest, lever, redstone key, wither essence) when you look at it, after a delay; never re-clicks a looted secret; optional slot swap
- Starred Mob Hitboxes (named Dungeon ESP on the cheat build) — boxes or glow on starred mobs and secret bats, each with its own colour, line-of-sight only; Room Scoped highlights every starred mob in the room you are standing in (plus an adjustable margin for anything that wandered just outside) instead of using a flat range
- Wither Highlight — the real F7/M7 wither bosses (Maxor, Storm, Goldor, Necron) at any range with no range limit, as a glow hitbox or a filled hitbox, still line-of-sight only; the cheat build adds a red ESP section with Through Walls for the mobs and a separate Wither ESP section that draws the withers through walls
- Door Helpers — **cheat build only.** Auto Door Opener (QUOI: aura or triggerbot on locked wither/blood doors) and Look At Door (smoothly turns to the next locked door on a key or when you pick up a key)
- Interactive Map — **cheat build only.** The full-screen map: open it on a key, click a room to teleport-path to it or start its secret route, hover for type, secrets, crypts and who cleared it, right-click to toggle that room's secret waypoints, scroll and drag to zoom and pan
- Terminal Solver — highlights the correct slot(s) to click on Floor 7 terminal puzzles, including Melody detection (with an optional "Send Mel Coords On Open" that types your position into party chat when the Melody terminal opens). The **cheat** build additionally has an Auto Terminals mode (with a Min/Max click delay, an input-block safety toggle, and a Melody Skip Mode for how aggressively it clicks ahead) that clicks for you - see [Which jar do I download?](#installing) below. Every overlay colour is customisable (per-terminal highlights, Numbers' next/after-next/3rd tiers, Rubix left- vs right-click, Melody's board palette, panel background/border and label text) with a one-click Reset Colours.
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
- Dungeon Map — a HUD copy of the real held dungeon map: rooms coloured by type (brown normal, magenta puzzle, orange trap, yellow miniboss, pink fairy, red blood, green entrance, grey unexplored), corridors flush against the rooms, merged multi-tile rooms, sprite checkmarks, and rotated arrows or player heads for you and your party. It shows only what the dungeon map in your hotbar has already revealed — unopened rooms stay grey and unnamed. Every colour is a picker, with a one-click reset back to the real map's own colours
- Secret Waypoints — real, preloaded per-room secret positions (chests, items, wither skulls, bats, redstone keys) once a room is identified, downloaded from the same public room database NoammAddons itself uses. Drawn through walls (toggleable), as either a full block or the secret's own hitbox, with a render-distance slider so only the secrets near you are built and drawn
- Auto Close Chest — instantly closes a real secret reward chest ("Chest"/"Large Chest"/"Trapped Chest") the moment it opens in a dungeon, before it's ever shown on screen
- Blood Camp — tracks the real F7 boss-fight Watcher and blood mobs by their real skull skins, predicting where each mob is about to resettle and showing a real countdown until it's vulnerable again. The **cheat** build additionally has a Trigger Bot (clicks once the countdown expires and you're looking at it, with auto ping-based or manual tick-offset timing) and an Aura that turns to face the predicted spot in advance
- Cheat Utilities — **cheat build only.** Wither ESP (F7 boss), Secret Aura (auto-clicks chests/levers/essence in reach), Auto GFS (sack refills), Auto Ult (Healer/Tank at the right boss moments), and Auto Chocolate Factory
- Solver Highlights — one shared switch that draws every puzzle and boss solver's highlight through blocks (ESP-style) instead of only when you have line of sight
- Boulder Solver — reads the real Boulder puzzle room's floor pattern and highlights the real stone button to press next, ported from a known 8-pattern solution database. Never clicks for you
- Quiz Solver — reads the real Oruo the Omniscient trivia question and lettered answer options in chat, looks up the correct answer in a bundled real question database, and highlights that option's floor tile. Never answers for you
- Ice Fill Solver — identifies each of the real Ice Fill puzzle's 3 floor layouts and draws the real known-safe walking path across all of them. Never walks for you
- Weirdos Solver — reads the real Three Weirdos NPC dialogue lines and highlights the real correct chest (and, optionally, ruled-out ones) the moment a line gives it away
- Water Board Solver — identifies the real Water Board layout and shows a real live countdown above every remaining lever click, highlighting the soonest one. Never clicks anything for you
- Creeper Beams Solver — highlights real currently-connected Sea Lantern pairs with matching colors, updating live as panes are rotated. Never touches anything
- Blaze Solver — ranks real blazes in the Lower/Higher Blaze puzzle by HP and highlights the correct next few kill targets in order. Never attacks anything
- Tic Tac Toe Solver — reads the map item frames on the Tic Tac Toe board and outlines the best move on your turn (minimax), with an optional prediction of your next move. Never clicks for you
- Teleport Maze Solver — tracks the pads you've used and narrows down the real exit pad from where each teleport makes you face (green = the one, gold = candidates), with a tracer to the best next pad. Never moves you
- Ice Path Solver — reads the silverfish Ice Path board and draws the shortest push path to the exit, outlining the silverfish's next stop. Never hits the silverfish
- Livid Solver — identifies the real correct Livid on Floor 5 from the wool color clue, re-checking it twice a second, and highlights it in that Livid's own color with an optional line to it, plus a countdown for its opening invulnerability window. Never attacks anything

**Display**
- Borderless Fullscreen — F11 toggles between windowed and borderless fullscreen, never true exclusive fullscreen
- Fullbright — see clearly in dark areas without touching your real brightness setting

**Accounts**
- Account Switcher — swap between your saved Microsoft accounts from the main menu, with each one's Hypixel ban status shown; each account can also have its own SOCKS proxy assigned, applied automatically every time you swap to it
- Proxy Client — optionally route your connection through a SOCKS proxy; set it from Swap Accounts on the main menu, per instance, or turn on a Universal proxy that every instance uses no matter what

**Home**
- A HUD editor to drag and resize the mod's on-screen elements wherever you want them. It lists only the elements that apply to where you are right now (dungeon timers in a dungeon, the RNG Meter ranking outside one, and so on) - flip Show All to arrange everything at once. Every element starts fully on screen at any GUI scale or window size, and opening chat never hides the HUD (real menus still do)
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
