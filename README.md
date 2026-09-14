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
- Jumpscare — randomly plays a full-screen image with sound to startle you (just for fun)

**Helpers**
- Experimentation Table Solver — solves Chronomatron, Ultrasequencer, and Superpairs in a highlight-only "Solver Only" mode (you click, it just shows you the answer). The **cheat** build additionally has a fully autonomous mode that clicks for you - see [Which jar do I download?](#installing) below.
- Storage Overlay — see the contents of every Ender Chest page and Backpack live, right alongside your open inventory
- Screenshot Copy — automatically copies a screenshot to your clipboard the moment you take it
- No Fire — removes the on-screen fire overlay so burning doesn't block your view mid-fight
- Auto Join Skyblock — automatically runs `/skyblock` the moment you connect to Hypixel

**Dungeon**
- RNG Meter — tracks your Hypixel Skyblock RNG/pity progress on dungeon floors
- Leap Message — sends a message when you leap to someone in dungeons
- Terminal Solver — highlights the correct slot(s) to click on Floor 7 terminal puzzles, including Melody detection. The **cheat** build additionally has an Auto Terminals mode (with a Min/Max click delay, an input-block safety toggle, and a Melody Skip Mode for how aggressively it clicks ahead) that clicks for you - see [Which jar do I download?](#installing) below.
- Termism — a practice mode that generates fake terminal puzzles (every real type, including a real timed Melody puzzle) so you can drill them without a real dungeon run, with Auto Terminals also usable here on the cheat build to test it risk-free
- Full Block — **cheat build only.** Expands the clickable area of levers, buttons, chests, and Wither Essence blocks so real secrets are easier to click, with a master on/off toggle - see [Which jar do I download?](#installing) below.
- Simon Says — highlights the correct button(s) on the F7/M7 boss-fight Simon Says device with numbered, colored boxes (next = green, then orange, then red), tracks party members' progress from their chat announcements (compatible with Odin/QUOI's own progress messages too), and can block a real click on the wrong button (Prevent Misclicks - hold Shift to override). The **cheat** build additionally has a no-rotate Trigger Bot, Auto Solve, and an Auto Start mode for the real "skip" trick with configurable click counts and timer-based pacing - see [Which jar do I download?](#installing) below.
- Tick Timers — real countdowns for Necron's drop, Goldor's Core opening, and Storm's pad/lightning/purple-pillar/crush windows, driven off the real boss chat lines
- Split Timers — per-segment time splits for every floor's real boss fight (Bonzo through Necron), announced in chat and shown on a HUD list
- Mask Invincibility Timers — automatic active/cooldown timers for Spirit Mask, Bonzo's Mask, and Phoenix Pet, detected off the real "saved your life" chat lines. The **cheat** build additionally has Auto Swap: right-clicks your other mask into place the moment the worn one procs, if it's off cooldown
- I4 Sensors — a diagnostic block-state logger for the real "Pre4" area (right before Necron's P4 starts), for gathering the real data an i4 solver would need
- Auto Leap Out — **cheat build only.** Automatically opens Spirit Leap and jumps to a configured player (or "Mel") on real F7 triggers: the i4 device completing, Storm's death, the middle/P4 approach, relic pickup, and pad crushes
- 0 Ping Dungeon Breaker — **cheat build only.** While holding a real Dungeon Breaker item with charges left, insta-mines the exact block you're looking at the instant you start mining it, instead of waiting on your real connection's ping
- Live Map — a self-drawn room/door map for the current dungeon run, using the dungeon's real fixed 11x11 room grid, real door-type detection, and (new) real room NAMES from the same room database Secret Waypoints uses, plus live teammate positions (colored by their assigned class from the Leap Menu)
- Secret Waypoints — real, preloaded per-room secret positions (chests, items, wither skulls, bats, redstone keys) once a room is identified, downloaded from the same public room database NoammAddons itself uses. Includes real mimic-chest detection (an extra trapped chest beyond what a room should have)
- Auto Close Chest — instantly closes a real secret reward chest ("Chest"/"Large Chest"/"Trapped Chest") the moment it opens in a dungeon, before it's ever shown on screen
- Blood Camp — tracks the real F7 boss-fight Watcher and blood mobs by their real skull skins, predicting where each mob is about to resettle and showing a real countdown until it's vulnerable again. The **cheat** build additionally has a Trigger Bot (clicks once the countdown expires and you're looking at it, with auto ping-based or manual tick-offset timing) and an Aura that turns to face the predicted spot in advance
- Boulder Solver — reads the real Boulder puzzle room's floor pattern and highlights the real solution tile(s) to click, ported from a known 8-pattern solution database. Never clicks for you
- Quiz Solver — reads the real Oruo the Omniscient trivia question and lettered answer options in chat, looks up the correct answer in a bundled real question database, and highlights that option's floor tile. Never answers for you
- Ice Fill Solver — identifies each of the real Ice Fill puzzle's 3 floor layouts and draws the real known-safe walking path across all of them. Never walks for you
- Weirdos Solver — reads the real Three Weirdos NPC dialogue lines and highlights the real correct chest (and, optionally, ruled-out ones) the moment a line gives it away
- Water Board Solver — identifies the real Water Board layout and shows a real live countdown above every remaining lever click, highlighting the soonest one. Never clicks anything for you

**Display**
- Borderless Fullscreen — F11 toggles between windowed and borderless fullscreen, never true exclusive fullscreen
- Fullbright — see clearly in dark areas without touching your real brightness setting

**Accounts**
- Account Switcher — swap between your saved Microsoft accounts from the main menu, with each one's Hypixel ban status shown; each account can also have its own SOCKS proxy assigned, applied automatically every time you swap to it
- Proxy Client — optionally route your connection through a SOCKS proxy

**Home**
- A HUD editor to drag and resize the mod's on-screen elements wherever you want them

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
