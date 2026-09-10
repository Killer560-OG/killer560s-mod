# Killer560's Mod

A Fabric client mod for Hypixel Skyblock (Minecraft 26.1.2). Built as a personal quality-of-life toolkit that grew into something friends can use too.

See the #roadmap channel in the [Discord server](https://discord.gg/hkQMF5fE84) for what's planned next.

[![Discord](https://img.shields.io/discord/1546630783970713665?label=Discord&logo=discord&color=9B59B6)](https://discord.gg/hkQMF5fE84)

Join the [Discord server](https://discord.gg/hkQMF5fE84) for releases, support, and to suggest features.

## Features

**Chat**
- Translate — auto-translates your chat messages into another language before sending
- Auto Correct — fixes common typos before you send
- Chat Emotes — shortcuts that turn into fun chat emotes
- Click Translate — click a chat message to see it translated
- Copy Chat — Shift+Click a message to copy the whole thing, or Shift+Right-Click to copy just one line of it
- Auto Meow — random cat noises
- Cringe — random one-liner messages for fun
- Spotify Mod — posts the lyrics of whatever you're playing on Spotify into chat, synced to the song

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
