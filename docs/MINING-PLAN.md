# Crystal Hollows Map and Auto Nucleus Run

killer560's spec, 2026-09-30, written down so it can be corrected before any of it is built. **Both features
are deliberately excluded from the newest release** - he said so when giving the spec.

Names in this document are the ones I believe he meant; his message was dictated, so where I have corrected
a word the original is in brackets. **Anything marked OPEN is a question for him.** The game facts are being
checked against hypixelskyblock.minecraft.wiki separately and land in `CRYSTAL-HOLLOWS.md`; where the two
disagree, that document wins on facts and this one wins on what he asked for.

## What already exists

`mining/chmap/` has a Crystal Hollows map screen, a config with saved waypoints, and a waypoint record - 477
lines. It is a manual waypoint list with a map screen, not a structure map. Everything below is on top of it.

---

## 1. Crystal Hollows Map

### 1a. The legit map

Shows the map "just like other mods". What is new is **structures**:

- When he finds an area - his example was the **Mines of Divan** ["minds of Devon"] - the map draws that
  area's **border**.
- A per-area option to show a **waypoint** for it as well.
- The legit map learns a structure **only by him actually being inside it**. It never scans. That is the
  whole distinction from the cheat map below.

### 1b. Sharing

- Waypoints he acquires are **shared with other people running this mod**, over the mod's own relay (see
  `relay/`, and `partydata/` for the existing share/receive pattern).
- A sharing toggle **under the map specifically**, not the mod-wide one: "if I don't want to share waypoints
  then it won't".
- NOTE: the project rule is that sharing and receiving settings default ON. That rule and "give it its own
  switch" are compatible - its own switch, defaulting on.
- OPEN: does receiving another player's discovered structure also draw its border, or only its waypoint?

### 1c. The cheat map

- A separate **"illegal map" button**.
- It **scans for structures around him** - everything in range, recording whether each is there or not,
  without him having to enter it.
- Cheat-build gated and red-headered, like every other cheat-only control.
- OPEN: scan radius, and whether it re-scans continuously or on demand.

---

## 2. Auto Nucleus Run

**Cheat only.** A setting called "Auto Nucleus Run" with several tabs under it. The first tab is
**Auto Pathfind**, which has two modes:

1. **Commissions** - pathfind to whatever the current commission needs. Specified in 2e.
2. **Speed** - specified below.

### 2a. Speed mode: getting started

- Uses **the same etherwarp pathfinding the Interactive Map uses**, but over a Crystal Hollows map rather
  than a dungeon one. See `pathfinding/` and the interactive map's travel code for the existing logic.
- On first entering the lobby, if he does **not** have `/warp crystal nucleus`, it pathfinds to the Crystal
  Nucleus first.
- It scans the map as it goes, moving closer to the nucleus to load more of it, and keeps scanning until the
  **whole map is scanned**.
- When the macro first starts it **tries `/warp crystal nucleus` once**. If that fails, the command is
  **blacklisted until the macro is started again** - so it never spams a command he has not unlocked.

### 2b. Route between crystals

- After collecting a crystal: warp to the nucleus if he has the warp, otherwise pathfind.
- **Whether to warp at all is my call**: if pathfinding straight from this crystal to the next is faster than
  going via the nucleus, do that instead.
- The order of the crystals is **also my call** - he listed them in the order he happened to remember.

### 2c. Each crystal

**Bal ["Val"] - Topaz**
- Pathfind to Bal, then **etherwarp on top of the crystal**.
- Bow choice, in order: **Terminator** first, then **any shortbow**. If he has neither, send a message saying
  to get a shortbow and stop.
- Standing on top: use the **Wither Cloak sword**, then shoot the bow, and **keep shooting until Bal dies**.
- Collect the crystal.
- He notes other mods have alerts for when Bal dies - use that as the completion signal rather than a timer.

**Amethyst - Jungle Temple**
- Walk to the **key guardian**, give him a key, do the **parkour**, collect the crystal.
- If he has no etherwarp, **flick the lever at the back** to be teleported out.
- Then run **out of range of the temple** so a warp can be used again.
- **He will need to do a true scan of the Jungle Temple for me.** ACTION FOR HIM.

**Sapphire - Lost Precursor City**
- Etherwarp in. There is an **NPC** who takes the **Precursor Apparatus**; hand it over, teleport in, grab
  the crystal.

**Amber - Goblin Queen's Den**
- Etherwarp to the **King**, talk to him, give **one goblin egg**, then etherwarp **into the Queen** and grab
  that crystal.

**Jade - Mines of Divan**
- Needs the **auto metal detector** (below). Once it has the metal detector parts, talk to each NPC and grab
  **the crystal in the middle**.

### 2e. Commissions mode

The route driver changes, nothing else: **it pathfinds to whatever is next for the commission, rather than
to whatever is fastest.** Everything about how each crystal is actually collected (2c) is unchanged.

**Claiming a commission** splits on one thing, which the mod must **DETECT rather than ask**:

- **Has the Royal Pigeon** - use it to claim commissions in place.
- **Does not have it** - teleport back to the Crystal Nucleus and talk to the NPC to collect.

**Which commissions it will take: only two kinds.**

- **Crystal commissions** - the ones that ask for a crystal. Handled by 2c.
- **Corleone** - a boss that spawns randomly. Find him; if he is alive, kill him **by melee** ["by Malay"].
  If he is not there, **wait for him to spawn**.

Everything else is out of scope.
- OPEN: what happens to a commission that is neither of those - skipped, abandoned, or does it just idle
  until one of the two comes up?
- OPEN: is there a time limit on waiting for Corleone before it gives up and does something else?

### 2f. Lobby Swapper

A setting of its own, called **Lobby Swapper**, and the thing Auto Nucleus Run falls back on: **if the run
cannot find everything it needs in the current lobby, it swaps lobby.**

**The swap cycle, for the nucleus run specifically:**
1. Warp to the **hub**.
2. Warp to the **Crystal Nucleus**. If he does not have that warp, warp to the **Crystal Hollows** instead.
3. Pathfind around and **scan the whole map**.
4. If the lobby does not have everything needed, **swap again** and repeat.

**Its own settings, usable outside the nucleus run too:**
- **Structures to scan for** - he picks **any number of them**, from **every structure the scanner can
  detect**. A lobby only passes if all the selected ones are present.
- **Day count** - a lobby passes only when its **day is equal to or lower than** the number he sets. (The
  Crystal Hollows lobby day; a low day means a lobby that has not been mined out.)
  - OPEN: where the client reads the lobby day from - scoreboard, chat on join, or the `/profile`-style
    line. The research pass should confirm this.

- OPEN: a cap on how many swaps it will make before giving up and telling him, so a bad filter cannot leave
  it swapping forever.
- OPEN: does the day filter apply on its own, or only alongside the structure filter?

### 2d. Finish

Once it has **all** the crystals: **place all the crystals**, then carry on running.

---

## 3. Metal Detector

Two halves, and the legit one is the foundation:

- **Legit setting**: does the calculation and draws a **waypoint with a line to it**. Nothing else - no
  movement, no interaction. This ships in both builds.
- **The macro** uses the same calculation to find the treasure itself. If **several candidate points** come
  out of it, **teleport to one as a guess; if it is not there, teleport to the next**.

Reference other mods for the maths - NoammAddons, Devonian, QUOI and SkyHanni all have local source or jars
on this machine, and the research pass is reading them.

---

## Open questions for killer560

1. **Commissions mode** - specified 2026-09-30, see 2e. Remaining gaps: what to do with a commission that
   is neither a crystal nor Corleone, and whether waiting for Corleone has a time limit.
2. **Crystal order** - he left it to me, but if he has a preferred route, it is cheaper to be told than
   derived.
3. **The Jungle Temple scan** - he needs to do a true scan for the parkour route.
4. Does a received waypoint from another player draw that structure's border too, or only the waypoint?
5. Cheat map scan radius, and on-demand versus continuous.
6. What should the macro do when it is missing a prerequisite mid-run - no goblin egg, no Precursor
   Apparatus, no key? Stop with a message, skip that crystal, or keep going round?
