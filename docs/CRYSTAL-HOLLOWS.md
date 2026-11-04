# Crystal Hollows — game facts for the map and commission features

Research only. Nothing here is a design decision; it is what the wiki and the reference mods actually say,
so the two Crystal Hollows features can be built without baking in a guess.

**Source rule.** Every wiki citation below is to **hypixelskyblock.minecraft.wiki**, per killer560's standing
instruction. `wiki.hypixel.net` and the Fandom wiki were not used. Where that wiki is silent or hedges, this
document says so in those words rather than filling the gap — the gaps are the important part of this file.

Anything labelled **UNVERIFIED** or **SPECULATION** is exactly that and must not be treated as fact in code.

---

## 0. What killer560 said, and what was actually garbled

His message was dictated, so several names came through wrong. Corrected against the wiki:

| He said | Actually | Note |
|---|---|---|
| "Val" / "bowel" | **Bal** | Correct guess. Boss in Khazad-dûm, drops the Topaz Crystal. |
| "minds of Devon" | **Mines of Divan** | Correct guess. |
| "key guardian" | **Key Guardian** *and* **Kalhuiki Door Guardian** | Two different things — see §1.2. He merged them. |
| "precursor apparatus" | **Precursor Apparatus** | Exactly right, and it is a real single item, not just shorthand for the six parts. |
| "the king" / "the queen" | **King Yolkar** / **Goblin Queen's Den** | Right shape, see §1.4 for what he got wrong about the sequence. |
| "/warp crystal nucleus" | **`/warp nucleus`** | See §5. There is no `/warp crystal nucleus`. |

---

## 1. The five crystals

All five are placed at statues in the Crystal Nucleus; once all five are placed a **Crystal Nucleus Loot
Bundle** appears, and *"Crystals will remain placed if you leave the game. They cannot be picked up once
placed."*
<https://hypixelskyblock.minecraft.wiki/w/Crystal_Nucleus>

The crystals *"aren't actual items, but rather achievements that can be tracked"* in the Heart of the
Mountain menu.
<https://hypixelskyblock.minecraft.wiki/w/Gemstone_Crystals>

Summary table (all from <https://hypixelskyblock.minecraft.wiki/w/Gemstone_Crystals> and
<https://hypixelskyblock.minecraft.wiki/w/Crystal_Hollows>):

| Crystal | Biome | Structure | Gate |
|---|---|---|---|
| **Amethyst Crystal** | Jungle | Jungle Temple | Jungle Key → Kalhuiki Door Guardian → parkour |
| **Jade Crystal** | Mithril Deposits | Mines of Divan | 4 Scavenged Items → 4 Keepers of Divan |
| **Amber Crystal** | Goblin Holdout | Goblin Queen's Den | Goblin Egg → King Yolkar → King's Scent |
| **Sapphire Crystal** | Precursor Remnants | Lost Precursor City | 6 Automaton Parts *or* 1 Precursor Apparatus → Professor Robot |
| **Topaz Crystal** | Magma Fields | Khazad-dûm | Kill Bal |

### 1.1 Topaz Crystal — Khazad-dûm, boss **Bal**

**The boss is `Bal`.** Level 100, 1,000,000-scale health is *not* what the wiki says — it lists **200 health
and 100 damage**, and notes *"Bal's health is displayed as ???"*.
<https://hypixelskyblock.minecraft.wiki/w/Bal>

- *"Bal spawns every minute while a player is within Khazad-dûm"* (same page).
- He hops toward players, summons **Fire Bats** and **Bald Blazes**, and shoots fireballs.
- *"After the boss sinks into the ground, the Topaz Crystal will spawn close-by and become claimable."*
  <https://hypixelskyblock.minecraft.wiki/w/Khazad-d%C3%BBm>
- *"Only players who contributed to the boss's elimination may claim it"*, and it must be claimed before he
  respawns. <https://hypixelskyblock.minecraft.wiki/w/Gemstone_Crystals>

Khazad-dûm sits in the **Magma Fields**. Discovery message: *"Deep below the Misty Mountains lies the shadow
and flame guarding ancient treasure..."*

**Confirmed:** boss name `Bal`, area `Khazad-dûm`, crystal `Topaz Crystal`. His guess was right on all three.

### 1.2 Amethyst Crystal — Jungle Temple

**There are two different "guardians" and killer560 merged them.**

- **Key Guardian** — a *mob*, level 100, that spawns in *"two Granite towers in the Jungle"* with *"a 1m 30s
  cooldown between spawning"*. It **drops the Jungle Key**.
  <https://hypixelskyblock.minecraft.wiki/w/Key_Guardian>
  (The two towers are named structures: **Key Guardian Tower** and **Key Guardian Tower 2** —
  <https://hypixelskyblock.minecraft.wiki/w/Crystal_Hollows/Special_Locations>)
- **Kalhuiki Door Guardian** — an *NPC* at the temple entrance who *"Allows the player to access the Jungle
  Temple parkour by presenting a Jungle Key."*
  <https://hypixelskyblock.minecraft.wiki/w/Jungle_Temple>

So: the "key guardian" gives you nothing; it *drops* the key. The NPC that *takes* the key is the Kalhuiki
Door Guardian. A mod that conflates them will look for the wrong entity.

Second source for the key: the **Jungle Key can also be bought from the NPC `Odawa`** in the Jungle, who
trades for Sludge Juice and assorted items (he also sells Kalhuiki Mask, Jungle Amulet, Tribal Spear, Jungle
Pickaxe, Rough Amethyst Gemstone). <https://hypixelskyblock.minecraft.wiki/w/Odawa>,
<https://hypixelskyblock.minecraft.wiki/w/Amethyst_Crystal>

Sequence:
1. Get a Jungle Key (kill a Key Guardian, or buy from Odawa).
2. Give it to the Kalhuiki Door Guardian. **One key is consumed per run.**
3. Complete *"a series of 8 simple parkours with traps"* inside the main building.
4. At the end: **2 Loot Chests** (higher chance of a Jungle Heart) and the **Amethyst Crystal**.

Mod-relevant mechanics on that page:
- **Touching lava teleports you back to the entrance.**
- **Jump Boost and Speed bonuses are removed inside the temple.** (This kills any speed-assumption in a
  movement routine.)
- Claiming without a key gives the chat line: *"You haven't earned this Crystal! Come back when you have >:-)"*
- *"Claiming the Amethyst Crystal prevents the player from claiming it again as well as the surrounding Loot
  Chests, unless another Jungle Key is given"*.

**Is there a parkour?** Yes — 8 sections.
**Is there a "key guardian" NPC?** Only in the sense above; the *NPC* is the Kalhuiki Door Guardian.
**Is there a lever at the back that teleports you out?** **The wiki does not mention one.** Not a lever, not
a button, not a pressure plate, not a teleporter. That is an open question — see §7.

### 1.3 Sapphire Crystal — Lost Precursor City, **Professor Robot**

NPC: **Professor Robot**. He *"will unlock the door adjacent to him"* giving access to the crystal.
<https://hypixelskyblock.minecraft.wiki/w/Lost_Precursor_City>

He accepts **either**:
- all **6 Automaton Parts** — exact item names: `Electron Transmitter`, `FTX 3070`, `Robotron Reflector`,
  `Superlite Motor`, `Control Switch`, `Synthetic Heart`; **or**
- **1 `Precursor Apparatus`** (EPIC), which is crafted from one of each of those six pieces and is
  *"given to Professor Robot to complete the Sapphire Crystal quest in the Lost Precursor City."*
  <https://hypixelskyblock.minecraft.wiki/w/Precursor_Apparatus>

The parts drop from **Automaton** mobs in the Precursor Remnants (the same page also lists `Rough Sapphire
Gemstone` from them). <https://hypixelskyblock.minecraft.wiki/w/Precursor_Remnants>

**Confirmed:** area is **Lost Precursor City** (inside the **Precursor Remnants** biome), the NPC is
**Professor Robot**, and the item name **Precursor Apparatus** is exactly right. He got this one entirely
correct; the only addition is that the six loose parts are an equally valid input.

### 1.4 Amber Crystal — Goblin Queen's Den, **King Yolkar**

- Give **1 Goblin Egg of any type** to **King Yolkar** (an NPC in the Goblin Holdout whose *location is
  fixed*, i.e. he does not wander). <https://hypixelskyblock.minecraft.wiki/w/King_Yolkar>
- Requirement dropped from three eggs to one in August 2024 (same page).
- Egg variants: `Green Goblin Egg`, `Blue Goblin Egg`, `Red Goblin Egg`, `Yellow Goblin Egg`, obtained from
  Treasure Chests in the Goblin Holdout. <https://hypixelskyblock.minecraft.wiki/w/Goblin_Egg>
- He grants **`King's Scent I`**, duration **20:00**, *"Grants the stench of the Goblin King"*. Extended +40%
  by a Parrot Pet, or by Alchemy. <https://hypixelskyblock.minecraft.wiki/w/King%27s_Scent>
- **The effect dissipates if you enter Water.** (Mod-relevant: a route that swims loses the run.)
- With King's Scent active, enter the **Goblin Queen's Den** and take the **Amber Crystal**.
  *"Claiming the Amber Crystal now removes King's Scent"* (August 2024).
  <https://hypixelskyblock.minecraft.wiki/w/Goblin_Queen%27s_Den>

**What he got wrong:** "give one goblin egg, then into the queen" is right, but "etherwarp to the king" is
his own travel method, not a game mechanic — and the wiki documents **no "being caught" mechanic**. There is
no stated stealth timer or guard-detection rule; the wording is only that King's Scent lets you *"sneak past
guards"* (<https://hypixelskyblock.minecraft.wiki/w/King_Yolkar>). Whether anything actually catches you is
**not settled by the wiki**.

Also useful: *"Using a Wishing Compass while King's Scent is active points to the Goblin Queen's Den"* —
see §4.2.

### 1.5 Jade Crystal — Mines of Divan, the four **Keepers of Divan**

Four NPCs: **Keeper of Diamond**, **Keeper of Emerald**, **Keeper of Gold**, **Keeper of Lapis**.
<https://hypixelskyblock.minecraft.wiki/w/Mines_of_Divan>

The Metal Detector is **obtained from the Keepers themselves** (*"obtained from Keeper of Gold, Keeper of
Emerald, Keeper of Diamond, Keeper of Lapis"* — <https://hypixelskyblock.minecraft.wiki/w/Metal_Detector>).

Sequence:
1. Get the Metal Detector from a Keeper.
2. Inside the Mines of Divan, use it to find buried chests — *"Metal Detector Chests are exclusively found
   among the Pure Gold coating the floor of the Mines of Divan using a Metal Detector."*
   <https://hypixelskyblock.minecraft.wiki/w/Crystal_Hollows>
3. Each chest has a chance of a **Scavenged Item**: `Scavenged Lapis Sword`, `Scavenged Golden Hammer`,
   `Scavenged Diamond Axe`, `Scavenged Emerald Hammer`.
4. Give each to its matching Keeper — Lapis Sword→Keeper of Lapis, Golden Hammer→Keeper of Gold, Diamond
   Axe→Keeper of Diamond, Emerald Hammer→Keeper of Emerald.
   <https://hypixelskyblock.minecraft.wiki/w/Scavenged_Lapis_Sword>
5. *"The Jade Crystal will appear in the center after delivering each Scavenged Item to their respective
   keepers."*

Two exact in-game lines, useful for chat matching
(<https://hypixelskyblock.minecraft.wiki/w/Keepers_of_Divan>):

- *"Talking to the first Keeper will give the player a Metal Detector."* — dialogue:
  **"Take this Metal Detector to scavenge the ground for lost items!"**
- On completion: **"You found all of the items! Behold... the Jade Crystal!"**

SkyHanni's chat filter gives the wrapping format for all Keeper lines (§7.4):
`§e\[NPC\] §6Keeper of (?<keepertype>.*)§f: §r(?<message>.*)`.

Chest loot table (<https://hypixelskyblock.minecraft.wiki/w/Metal_Detector>): Rough Gemstones 4/8/16/32/64x
**68%**, one of four Scavenged Items **18%**, Mithril Powder **8%**, Gemstone Powder **6%**, Pickonimbus 2000
**0.06%**, 2x Fine Gemstones **0.01%**. The Scavenged chance can be raised by up to 25% with a **Mole Pet**,
for a final 45% (<https://hypixelskyblock.minecraft.wiki/w/Scavenged_Lapis_Sword>) — note that 18% and 45%
are inconsistent across the two pages and the wiki does not reconcile them.

**His description was right.** "Talk to each of the NPCs and grab the crystal in the middle" is accurate:
four Keepers, crystal spawns in the centre. The only thing he left out is that the Metal Detector comes
*from* the Keepers and that the Scavenged Items come out of chests at ~18–45%, so this is a grind, not a
fixed four-pickup route.

---

## 2. Named areas and structures

### 2.1 The five biomes

*"There are 5 main locations in the Crystal Hollows, being the Jungle, Mithril Deposits, Precursor Remnants,
Goblin Holdout, and Magma Fields."* <https://hypixelskyblock.minecraft.wiki/w/Crystal_Hollows>

| Biome | Notes | Source |
|---|---|---|
| **Jungle** | Sludges, Kalhuiki Tribe Members, Thysts; Abyssal Miners fishable. Contains Jungle Temple, Jungle Village, both Key Guardian Towers, Odawa. | [Jungle](https://hypixelskyblock.minecraft.wiki/w/Jungle) |
| **Mithril Deposits** | *"Farmland is used as the ground, and minecart tracks are often found running along the pathways."* Crypt Undead, Grunts, three Executives (Wendy, Sebastian, Viper), **Boss Corleone**. Mines of Divan, the Forger NPC, the 4 Keepers. | [Mithril Deposits](https://hypixelskyblock.minecraft.wiki/w/Mithril_Deposits) |
| **Goblin Holdout** | *"Home of Goblins"*, *"Rich with Amber Gemstones."* Goblin Queen's Den; NPCs King Yolkar and Xalx. Water Worms. | [Goblin Holdout](https://hypixelskyblock.minecraft.wiki/w/Goblin_Holdout) |
| **Precursor Remnants** | Automatons; Lost Precursor City; NPCs Chunk (sells Superboom TNT for 2,500 Coins) and Professor Robot. Discovery message: *"Mine Sapphire Gemstones. Look out for Automatons!"* | [Precursor Remnants](https://hypixelskyblock.minecraft.wiki/w/Precursor_Remnants) |
| **Magma Fields** | **Not a quadrant — a floor layer.** See §3. Khazad-dûm; Yog, Bal, Lava Blaze, Lava Pigman, Fire Bat, Bald Blaze. Heat mechanic. | [Magma Fields](https://hypixelskyblock.minecraft.wiki/w/Magma_Fields) |

Magma Fields heat warnings, in order, verbatim: *"You are heating up!"* → *"You are starting to burn!"* →
*"You are melting!"* → *"Time to leave!"*; +100 Heat kills you.

### 2.2 The Crystal Nucleus

The only structure with a **fixed position in every lobby**.
<https://hypixelskyblock.minecraft.wiki/w/Crystal_Nucleus>

- Location coordinate: **`512 106 512`**
- Extent, verbatim: *"The center is positioned at [513, Y, 513] and roughly encloses a space from
  [465, 64, 465] to [561, 188, 561]"*
- NPCs inside: **Emissary Sisko** `495.5, 106, 556.5`; **Gemma** `475.5, 106, 513.5`;
  **Geonathan Greatforge** `530.5, 106, 556.5`
- First-entry task: *"Place all Crystals at the statues."*

> ⚠ **Existing-code discrepancy.** `CrystalHollowsMapScreen.java` lines 51–53 define
> `NUCLEUS_X/Y/Z = 495.5, 106.0, 556.5` and the class doc calls that *"the Crystal Nucleus's real, fixed
> world coordinate"*. Per the wiki that is **Emissary Sisko's** position, not the Nucleus. The Nucleus centre
> is `512, 106, 512` (the page also writes `513, Y, 513` for the centre — the wiki is internally
> inconsistent by one block). The whole map is plotted relative to that constant, so it is currently offset
> by roughly (−16.5, 0, +44.5). Worth fixing, but it is a behaviour change to an existing feature, so it is
> an open question rather than something to silently correct.

### 2.3 The five guaranteed structures

One per region, *"5 Main Guaranteed Structures"*:
Jungle Temple (Jungle), Mines of Divan (Mithril Deposits), Goblin Queen's Den (Goblin Holdout), Lost
Precursor City (Precursor Remnants), Khazad-dûm (Magma Fields).
<https://hypixelskyblock.minecraft.wiki/w/Crystal_Hollows/Special_Locations>

Also **always spawn**, per server: **Goblin King Tower**, **Jungle Village**, and **3 Fairy Grottos**.

Two more that the existing `ChStructure` enum already lists, and what the wiki says about them:

- **Fairy Grotto** — *"a Location that rarely spawns in the Crystal Hollows"*; *"Up to 3 Fairy Grottos can
  spawn in a Crystal Hollows lobby."* Butterflies; Hard Stone, Jasper and Jasper Crystals; Fairy Souls
  fishable at 1/200, twice per location. Precise location *"Varies"*.
  <https://hypixelskyblock.minecraft.wiki/w/Fairy_Grotto>
  (Note the wiki is slightly inconsistent — the Special Locations page lists 3 Fairy Grottos as *always*
  spawning, the Fairy Grotto page says *"rarely spawns"*, up to 3.)
- **Dragon's Lair** — *"the unofficial name of a location in the Crystal Hollows where players can buy the
  Golden Dragon Egg"*; *"It rarely spawns in the Mithril Deposits."* Contains the **Golden Dragon** NPC, who
  *"Sells the Golden Dragon Pet"* — a merchant, not a boss. *"Precise Location: Varies between lobbies"*.
  <https://hypixelskyblock.minecraft.wiki/w/Dragon's_Lair_(Crystal_Hollows)>
  Both QUOI and Odin have a `GOLDEN_DRAGON` block signature with offset `(0, −3, 5)` for exactly this.

### 2.4 Full special-structure list

From <https://hypixelskyblock.minecraft.wiki/w/Crystal_Hollows/Special_Locations>. The page states plainly:
*"Within the Crystal Hollows, special structures spawn in random locations. The structures do not keep their
coordinates between lobbies."*

**Anywhere:** Trapped Throne · Dragon Skull · Tavern · Caravan · Tiny Hut · Dark Auction Room ·
Underground Spring · Underground Office · Water Hall · Colosseum · Spider's Den · Abandoned Lift · Ruins ·
Ruby Minetrack · Ruby Bridge · Red Puzzle Tower · Canvas Room

**Jungle:** Jungle Village · Key Guardian Tower · Key Guardian Tower 2 · Jungle Lagoon Pillars · Purple Tree ·
Miniature Jungle Temple · Small Temple · Jungle Lagoon Island · Jungle Lagoon Temple · Stuck Sludge ·
Crystal Tree · Jungle Bridges · Kalhuiki Lair · Large Jungle Cavern · Jungle Lagoon Spiral · Jungle Gorge ·
Tiny Pond · Jungle Luxuriant Cavern · Jungle Valley · Sludge Cavern

**Mithril Deposits:** Dragon's Lair · Miniature Forge · Ancient Dwarf · **Corleone Hideout** · Granite Walkway ·
**Jade Pedestal** · **Corleone Lakefront** · Team Treasurite Rail Crossing · Team Treasurite Waterfall ·
Crystal Train · Treasure Deposits · Layered Mineshaft · Ruined Forge · Boulder Room · Minecart Storage ·
Crashed Minecart · Double Railways

**Goblin Holdout:** The 3 Bears Fireplace · Goblin King Tower · Deep Goblin Lair · Goblin Gold Stash ·
Goblin Sewer Camp · Goblin Wide Pit · Goblin Lava Spiral Ramp · Giant Campfire · Hanging Drill ·
Goblin Mineshaft · Xalx's Lair · Goblin Lava Pit · Goblin Treasure Tower · Goblin Campfire Pillar ·
Goblin Tent · Goblin Red Pillar Bridge

**Precursor Remnants:** Big Automaton · Precursor Lava Orb · Wooden Door Passage · Precursor Tower ·
Precursor Stone Arch · Colored Skull Puzzle · Precursor Trap Labyrinth · Precursor Trapped Stairs ·
Precursor Tall Pillars · Precursor Throne Hall · Precursor Spiral Cave · Precursor Stone Bridge ·
Precursor Diorite Pillars · Precursor Square Hall · Precursor Diorite Corridor · Precursor Trapped Pillars ·
Precursor Trapped Dungeon · Precursor Pitfall Corridor · Precursor Tripwire Chamber · Giant Diorite Bars

**Magma Fields:** Magma Temple · Magma Pools · Magma Mineshaft · Magma Lavafalls · Magma Spiral Cavern ·
Magma Spiral Steps · Magma Flows · Magma Lake of the Rings · Magma Plank Bridge · Magma Wide Staircase ·
Magma Ravine · Magma Stone Bridge · Magma High Railway · Magma Pit Cave · Magma Crescent Pool ·
Magma Ring-shaped Pool · Magma Gully · Magma Creek

**Fairy Grotto (interior variants):** Shrine · Arch · Mansion · Hall · Pillars · Palace · Remnants ·
Aqueduct · Waterfalls · Overgrown · Pedestal · Spiral · Pavilion · Ruins · Square · Puddle · Bridge

Note the two **Corleone** structures (Corleone Hideout, Corleone Lakefront) and **Jade Pedestal** — these are
the named Mithril Deposits structures a Corleone-hunting or Jade route would care about.

**For drawing borders:** the wiki gives **no extent, size or shape for any structure except the Crystal
Nucleus**, and no mod hardcodes one either — all five learn positions per lobby. The existing
`ChFind`/`ChDiscovery` design (a growing AABB per lobby, learned from where the player has stood) is
therefore sound, and the class docs in `src/main/java/com/killer560/hub/mining/chmap/ChStructure.java`
already reason about this correctly.

What the other mods add on top is not a *shape* but better ways to learn the *position*: Skyblocker's NPC
chat triggers and Wishing Compass triangulation (§7.7) learn it without entering, and QUOI's chunk scanner
learns it from render distance. None of them knows a structure's extent — they all draw a point or an icon,
not a border. **So the growing-AABB border is our own idea and has no prior art to check it against.**

---

## 3. The coordinate space

### 3.1 What the wiki actually states

Only one number, and it is in the changelog section:

> *"Changed size of the Crystal Hollows from 1024 x 256 x 1024 to 621 x 157 x 621."*
> <https://hypixelskyblock.minecraft.wiki/w/Crystal_Hollows>

**The wiki gives no min/max X/Y/Z for the world as a whole.** It also gives **no coordinate ranges for any of
the four surface biomes.**

### 3.2 What can be derived, and how confident to be

The commonly cited community bounds are **X 202→823, Y 31→188, Z 202→823**. Four independent checks:

- `823 − 202 = 621` and `188 − 31 = 157`, which reproduces the wiki's stated `621 x 157 x 621` **exactly**.
- The midpoint of `202..823` is `512.5`, and the wiki puts the Crystal Nucleus at `512, 106, 512` with its
  centre written as `513, Y, 513` — dead centre either way.
- The Nucleus's stated box `[465, 64, 465] → [561, 188, 561]` has its top at **Y 188**, matching the derived
  world ceiling.
- **QUOI hardcodes exactly these X/Z bounds** and builds its whole minimap on them (§7.3):
  `X_MIN = 202`, `X_MAX = 823`, `Z_MIN = 202`, `Z_MAX = 823`, `MAP_SIZE = 621`.
- **Skyblocker hardcodes all six numbers in real source** (`CrystalsLocationsManager.checkInCrystals`):

```java
protected static boolean checkInCrystals(BlockPos pos) {
    //checks if a location is inside crystal hollows bounds
    return pos.getX() >= 202 && pos.getX() <= 823
            && pos.getZ() >= 202 && pos.getZ() <= 823
            && pos.getY() >= 31  && pos.getY() <= 188;
}
```

That last one settles it. Two independent mods hardcode the same X/Z, Skyblocker hardcodes the Y too, and
the numbers reproduce the wiki's stated `621 x 157 x 621` exactly. **Use `202/31/202 → 823/188/823`.** It is
still not a wiki statement, so cite it as "confirmed by Skyblocker and QUOI, consistent with the wiki's
stated dimensions" rather than to the wiki.

Note SkyHanni uses a *different*, looser box (`x,z ∈ 0..1024`, `y ∈ 65..190`) because it only draws warning
walls and never needs a tight fit. Don't take SkyHanni's numbers as the world bounds.

### 3.3 Biome layout — quadrants

The wiki says Magma Fields *"span any location in the Crystal Hollows at or below X, 64, Z"* — i.e. the
**bottom layer under the whole footprint**, not a quadrant. (Changed from Y=63 to Y=64 in a 2024 update.)
<https://hypixelskyblock.minecraft.wiki/w/Magma_Fields>. The Locations index agrees: Magma Fields
*"appearing throughout the entire area at low Y levels"*
(<https://hypixelskyblock.minecraft.wiki/w/Locations/Crystal_Hollows>).

So the model is **four surface quadrants above Y 64, one floor layer below it**, plus the Nucleus column in
the middle.

Which quadrant is which is **not stated on the wiki**, but it is now settled by five independent pieces of
evidence that all agree:

- Wiki: Goblin Holdout has a water pond at **`358, 167, 534`** → X < 512, Z > 512.
  <https://hypixelskyblock.minecraft.wiki/w/Goblin_Holdout>
- Wiki: Precursor Remnants gives **`797.5, 128.5, 708.5`** (the NPC Chunk) → X > 512, Z > 512.
  <https://hypixelskyblock.minecraft.wiki/w/Precursor_Remnants>
- **SkyHanni** hardcodes signpost labels inside the Nucleus that name each quadrant by direction
  (`CrystalHollowsNamesInCore`, §7.3): `(550,116,550) → Precursor Remnants`, `(552,116,474) → Mithril
  Deposits`, `(477,116,476) → Jungle`, `(474,116,554) → Goblin Holdout`.
- **QUOI**'s `CrystalHollowsQuarter` enum and **Odin**'s `WorldScanner$Quarter` enum both encode the same
  assignment.

| Quadrant | X | Z | Confidence |
|---|---|---|---|
| Jungle | low (≤ ~512) | low (≤ ~512) | Three mods agree; no wiki coordinate |
| Mithril Deposits | high (> ~512) | low (≤ ~512) | Three mods agree; no wiki coordinate |
| Goblin Holdout | low (≤ ~512) | high (> ~512) | Three mods **and** the wiki pond coordinate |
| Precursor Remnants | high (> ~512) | high (> ~512) | Three mods **and** the wiki Chunk coordinate |
| Magma Fields | whole footprint | whole footprint | Wiki: the layer **at or below Y 64** |

**The mods disagree on where the dividing line is, and on the Magma Fields floor.** This matters:

| | Split line | Magma floor | Nucleus |
|---|---|---|---|
| Wiki | — | **Y ≤ 64** | box `465,64,465 → 561,188,561` |
| **Skyblocker** | **512 / 513**, hard AABBs | **Y 30…64** | AABB `462,63,461 → 564,181,565` |
| SkyHanni | **513**, a hard plane | **Y < 65** | AABB `463,65,460 → 560,190,563` |
| QUOI | fuzzy: `≤ 576` / `> 448`, i.e. a ±64 overlap band around 512 | **Y < 80** | n/a |
| Odin | same fuzzy band as QUOI | **Y < 80** | `449..576` on both axes |

Skyblocker's version is the most explicit — six named AABBs, from `WishingCompassSolver.java`:

```java
private static final Map<Zone, AABB> ZONE_BOUNDING_BOXES = Map.of(
        Zone.CRYSTAL_NUCLEUS,     new AABB(462, 63, 461, 564, 181, 565),
        Zone.JUNGLE,              new AABB(201, 63, 201, 513, 189, 513),
        Zone.MITHRIL_DEPOSITS,    new AABB(512, 63, 201, 824, 189, 513),
        Zone.GOBLIN_HOLDOUT,      new AABB(201, 63, 512, 513, 189, 824),
        Zone.PRECURSOR_REMNANTS,  new AABB(512, 63, 512, 824, 189, 824),
        Zone.MAGMA_FIELDS,        new AABB(201, 30, 201, 824, 64, 824)
);
```

Two things to notice. It uses `201/824` here while `checkInCrystals` uses `202/823` — **Skyblocker is
internally inconsistent by one block**, so don't assume either set is exact. And the four surface zones
overlap by a block at 512/513, so a position exactly on the line matches two zones.

Skyblocker also plants the same four quadrant labels SkyHanni does, at slightly different coordinates
(`NucleusWaypoints.java`): `(551,116,551) Precursor Remnants`, `(551,116,475) Mithril Deposits`,
`(475,116,551) Goblin Holdout`, `(475,116,475) Jungle`, `(513,106,524) Nucleus`. Compare SkyHanni's
`(550,116,550) / (552,116,474) / (474,116,554) / (477,116,476)` — same four corners, hand-measured
independently, differing by a block or two. **The quadrant assignment is now confirmed four times over.**

QUOI's comment makes the intent explicit: the overlapping band is *"used only to reject impossible structure
matches, not as a hard partition."* That is the honest reading — **the biome boundary is cave terrain, not a
plane**, and the ±64 band is an admission that nobody knows exactly where it is. SkyHanni's hard 513 split is
fine for drawing warning walls but would be wrong for classifying a position near the middle.

**The reliable runtime signal is the sidebar, not arithmetic.** `IslandDetector.scoreboardArea()` already
reads the sidebar area line, and `ChStructure.fromAreaName()` already matches structures off it. The sidebar
names the biome you are in directly, so a mod never has to infer the biome from coordinates — it can *learn*
the quadrant boundaries from where the sidebar changes, which would be genuinely correct per lobby rather
than derived from another mod's guess. Use coordinates only as a rejection filter, the way QUOI does.

### 3.4 Lobby age — the "day" number

The Lobby Swapper filters on *"day is equal to or lower than N"*, so it needs a per-lobby age. Two different
things are called "day" and only one is useful:

- **The SkyBlock calendar date** (e.g. *"Late Summer 22nd"*) on the scoreboard. This is the same on every
  lobby at a given moment, so it cannot distinguish a fresh lobby from a picked-over one. **Not the one.**
- **The lobby's own age in days.** This is what matters — an old Crystal Hollows lobby has had its crystals
  claimed and its chests opened.

The only thing the approved wiki says about lobby age: **on Day 35 (the 36th day) a lobby force-starts
shutting down regardless of player count**. <https://hypixelskyblock.minecraft.wiki/w/Crystal_Hollows>

**Answered — and it is not parsed from any text.** QUOI reads it from the **vanilla client world time**.
`src/main/kotlin/quoi/utils/WorldUtils.kt`, last line of the object:

```kotlin
val ClientLevel.day get() = this.dayTime / 24000
```

`dayTime` is the vanilla world time delivered by `ClientboundSetTimePacket`. On a Hypixel SkyBlock instance
it starts near zero when the instance boots and advances monotonically, so `dayTime / 24000` **is** the
per-lobby age in Minecraft days — exactly the "old lobby, crystals already taken" signal. Nothing is read
from the scoreboard, tab list, chat, `/locraw` or an HTTP API.

QUOI's own lobby swapper uses it — `src/main/kotlin/quoi/api/commands/QuoiCommand.kt`, `/quoi findlobby`:

```kotlin
fun isMet(): Boolean = when (criteria) {
    "day"    -> mc.level!!.day <= intValue!!
    "server" -> Location.currentServer.equals(value, true)
    "player" -> WorldUtils.players.any { it.profile.name.equals(value, true) }
    else     -> false
}
```

driven by a warp cycle (`QuoiCommand.kt:45-49`) that alternates `/warp ch` and `/warp hub` on an 80-tick
period until `isMet() && currentArea.isArea(island)`, and **cancels if the player moves**
(`if (mc.player!!.isMoving) { modMessage("Cancelling, you moved!"); it.cancel() }`).

**Do not confuse this with SkyBlock calendar time.** SkyHanni's `SkyBlockTime` computes the in-game date
purely from wall-clock milliseconds (`SKYBLOCK_EPOCH_START_MILLIS = 1559829300000L`,
`SKYBLOCK_DAY_MILLIS = 1200000L`, i.e. 20 real minutes per SkyBlock day, with `Early`/`Late` month prefixes).
That value is identical in every lobby at the same instant and is useless as a filter. SkyHanni's
`ServerTime` reads the same `ClientboundSetTimePacket` QUOI uses but only as a monotonic tick clock for
timers — it never divides it into days. **Neither SkyHanni nor Odin has a lobby swapper or a day filter.**

The closest SkyHanni gets to lobby age is a countdown, not a count
(`features/misc/ServerRestartTitle`, repo key `features.misc.serverrestart`):

```
time    §cServer closing: (?<minutes>\d+):(?<seconds>\d+) ?§8.*
greedy  §cServer closing.*
```
plus the scoreboard literal `§cServer closing soon!`. Its lobby *identity* comes from
`lobbyTypePattern = (?<lobbyType>.*lobby)\d+` and `/locraw` JSON keys `server, gametype, mode, map,
lobbyname` — an ID like `mini123X`, not an age.

### 3.5 Crystal status in the tab list

From <https://hypixelskyblock.minecraft.wiki/w/Crystal_Hollows>, verbatim:

> *"You can see the status of Gemstone Crystals in the tablist: a Crystal can either be 'Not Found', 'Not
> Placed', or 'Placed'."*

This is the one reliable, client-readable, wiki-confirmed per-crystal state signal, and **Skyblocker gives
the exact strings** (`WishingCompassSolver.java`, real source — it tests for the *negative* case):

```java
case JUNGLE             -> displayNameStream.noneMatch(entry -> entry.equals("Amethyst: ✖ Not Found"));
case MITHRIL_DEPOSITS   -> displayNameStream.noneMatch(entry -> entry.equals("Jade: ✖ Not Found"));
case GOBLIN_HOLDOUT     -> displayNameStream.noneMatch(entry -> entry.equals("Amber: ✖ Not Found"));
case PRECURSOR_REMNANTS -> displayNameStream.noneMatch(entry -> entry.equals("Sapphire: ✖ Not Found"));
case MAGMA_FIELDS       -> displayNameStream.noneMatch(entry -> entry.equals("Topaz: ✖ Not Found"));
```

So the line format is **`<Crystal>: ✖ Not Found`** under a `Crystals:` header entry, matched on the tab-list
display name with **exact string equality** (no regex, no colour codes in the compared string). Skyblocker
also matches `entry.equals("Crystals:")`, `entry.startsWith("Active Effects:")` and
`entry.startsWith("King's Scent")` in the same tab list.

Note it only ever checks for `✖ Not Found` — the wiki's other two states (`Not Placed`, `Placed`) are
inferred as "anything else". The exact glyph and spacing for those two are **not** confirmed by any source
here; capture them in game.

---

## 4. The Metal Detector

### 4.1 What the wiki says, verbatim

<https://hypixelskyblock.minecraft.wiki/w/Metal_Detector> and
<https://hypixelskyblock.minecraft.wiki/w/Mines_of_Divan>:

- *"The Metal Detector is a SPECIAL Quest item that allows you to find Scavenged Items buried in the gold of
  the Mines of Divan."* Visually an **Iron Hoe**. Not tradeable, not sellable, not auctionable.
- *"In the player's actionbar (above the hotbar), TREASURE will be displayed along with a meters counter,
  which shows you how far away you are from the treasure."*
- *"A beeping sound will play if you hold the Metal Detector, and the closer to treasure you are, the higher
  pitch and frequency of each beep."*
- *"While holding the Metal Detector, the player's distance from the treasure is shown, as well as
  increasingly louder/higher pitched beeps when nearing treasure."*

### 4.2 The exact string — confirmed twice, independently

The wiki never quotes the action-bar line. Two mods do, and **they agree**.

**SkyHanni** (compiled string constant, RepoPattern `mining.crystalnucleus.metaldetector.treasure`):

```
.*§3§lTREASURE: §b(?<distance>.*)m
```

**Skyblocker** (real source, `MetalDetector.java`):

```java
private static final Pattern TREASURE_PATTERN = Pattern.compile("(§3§lTREASURE: §b)(\\d+\\.?\\d?)m");
```

Skyblocker's is the more informative of the two: `\d+\.?\d?` says the value is **an integer or a single
decimal place** — so the readout is at most one decimal, confirming SkyHanni's `roundTo(1)`. Both read the
**action bar / overlay message**, not chat; Skyblocker gates on `if (!overlay || ...) return`.

The success line, also confirmed twice. SkyHanni
(`mining.crystalnucleus.metaldetector.treasurefound`):

```
§aYou found .*with your §r§cMetal Detector§r§a!
```

Skyblocker matches the same event far more loosely, on the non-overlay chat message:

```java
if (text.getString().startsWith("You found")) { newTreasure = true; possibleBlocks = new ArrayList<>(); }
```

### 4.3 What the maths actually is — and it is NOT trilateration

The obvious design is trilateration: treat each reading `(px, py, pz, d)` as a sphere and intersect three or
more. That is a reasonable *fallback*, but **SkyHanni — the only mod in this set that implements a metal
detector at all — does something completely different and much better**, and the approach is worth copying:

> **Candidate elimination against a known list of chest spawn offsets, anchored on a block landmark.**

Chest spawn positions in the Mines of Divan are **not random** — they are a fixed finite set relative to the
structure. So:

1. **Find the anchor.** Search a `±50` box in X and Z and `+30 … −30` in Y around the player for a position
   `P` where `P` is `QUARTZ_STAIRS` **and** `P + (0,13,0)` is `BARRIER`. Then walk that barrier blob to its
   maximal `+X/+Y/+Z` corner. That corner is the structure's anchor, `base`. Re-searched at most every 15 s
   and reset on world swap.
2. **Enumerate candidates.** A data file supplies a list of chest offsets `c`. Each candidate world position
   is `P_candidate = base − c`.
3. **Filter by the one reading.** Keep every candidate where
   `round(|player − (P_candidate + (0,1,0))|, 1) == distanceFromActionBar`.
4. If exactly one survives → that is the chest, draw the waypoint. If several → tell the player to move a few
   blocks and it re-filters on the next action-bar tick. If none → tell them to move.

**It does not accumulate readings across positions** — the candidate list is cleared and rebuilt from scratch
every action-bar tick. Because the readout has a decimal place and the candidate set is finite and sparse,
**one reading is usually enough**; two from different spots in the worst case.

Full reconstructed source is in §7.3.

**Why this is better than trilateration:** trilateration needs 3–4 well-separated, non-collinear samples,
degrades badly when the readout is rounded, and gives you a point in empty space that you then have to search
around. Candidate elimination gives an exact block position from one reading, and is immune to rounding
because rounding is applied to both sides of the comparison.

**What it costs:** it needs the chest-offset list, which SkyHanni fetches from its own online repo and does
not bundle. We do not have that list from SkyHanni.

**Skyblocker solves the same problem differently, and better for us** (`MetalDetector.java`, real source).
Same family — candidate elimination — but with three changes that matter:

1. **The chest offsets are in the source file**, 43 of them, as `Vec3i` relative to `minesCenter`. So the
   dataset exists in readable form rather than behind an API. (Licence still applies — see §7.7.)
2. **The anchor is found from Keeper armour-stand nametags**, not a block pattern:

```java
private static final Pattern KEEPER_PATTERN = Pattern.compile("Keeper of (\\w+)");
private static final Map<String, Vec3i> keeperOffsets = Map.of(
        "Diamond", new Vec3i(33, 0, 3),   "Lapis",   new Vec3i(-33, 0, -3),
        "Emerald", new Vec3i(-3, 0, 33),  "Gold",    new Vec3i(3, 0, -33));
```

   It scans `player.getBoundingBox().inflate(500d)` for named `ArmorStand`s, matches the name, and applies
   the offset. **One Keeper is enough.** This is more robust than SkyHanni's QUARTZ_STAIRS+BARRIER block
   search and does not depend on the block palette surviving a Hypixel rebuild — an NPC's nametag is far
   more stable than its floor.
3. **There is a working fallback when the anchor is unknown** — a brute-force square at the player's own Y:

```java
if (minesCenter != null) {                 // fast path: 43 known offsets
    for (Vec3i knownOffset : knownChestOffsets) {
        Vec3i checkPos = minesCenter.offset(knownOffset).offset(0, 1, 0);
        if (Math.abs(playerPos.distanceTo(Vec3.atLowerCornerOf(checkPos)) - distance) < 0.25)
            possibleBlocks.add(checkPos);
    }
} else {                                   // slow path: no dataset needed at all
    for (int x = (int) -distance; x <= distance; x++)
        for (int z = (int) -distance; z <= distance; z++) {
            Vec3i checkPos = new Vec3i((int) playerPos.x + x, (int) playerPos.y, (int) playerPos.z + z);
            if (Math.abs(playerPos.distanceTo(Vec3.atLowerCornerOf(checkPos)) - distance) < 0.25)
                possibleBlocks.add(checkPos);
        }
}
```

   and on every later reading it simply intersects:

```java
possibleBlocks.removeIf(location ->
        Math.abs(playerPos.distanceTo(Vec3.atLowerCornerOf(location)) - distance) >= 0.25);
```

**The slow path is the important one: it needs no dataset whatsoever.** It generates every block at the
player's own Y at the reported distance, then narrows by intersection across readings until one survives.
That is a complete, working metal detector we could build without any borrowed data — at the cost of
assuming the chest is at the player's Y level, which it does assume (`(int) playerPos.y`, no Y search).

Two more Skyblocker details worth copying:

- **Tolerance `0.25` blocks**, compared against the block's **lower corner**, not its centre.
- **A reading is only consumed when the player is standing still**, gated on the distance *and* the position
  being identical to the previous tick:

```java
if (distance == previousDistance && playerPos.equals(previousPlayerPos)) {
    updatePossibleBlocks(distance, playerPos);
}
```

  Hence its own tip string: *"Stand still in multiple places until the solver has narrowed down possible
  locations to one"*. This is the honest way to handle a rounded readout taken while moving — **do not
  sample mid-stride**.

- Output: if exactly one candidate, a yellow waypoint at `block.offset(0, -1, 0)` (its comment: *"the block
  you are taken to is one block above the chest"*), plus a guide line from the crosshair. If 2–8, white
  "Possible" waypoints. Above 8, nothing is drawn.

**So the two implementations disagree on method, and Skyblocker's is the better model here** — the anchor is
an NPC nametag rather than a block pattern, and there is a no-dataset fallback.

**The trilateration fallback**, if neither candidate approach is wanted. For samples `i` against reference
`0`, subtracting the sphere equations cancels the quadratic terms and leaves a **linear** system:

```
2(xi - x0)·x + 2(yi - y0)·y + 2(zi - z0)·z
    = d0² - di² + (xi² + yi² + zi²) - (x0² + y0² + z0²)
```

With ≥ 4 samples this is over-determined; solve by least squares. Devonian's `MathUtils` has the primitives,
and its `BurrowGuesser` RANSAC pattern is the right way to reject a reading taken mid-jump or one tick stale.

**Still unsettled:**

1. **Is the distance 3D or horizontal?** The wiki does not say, and **the two mods imply different answers**.
   SkyHanni compares against a full 3D `distanceToPlayer`. Skyblocker's slow path generates candidates only
   at the player's own Y (`(int) playerPos.y`) but then measures a full 3D `distanceTo` — which works only
   if the chest really is near your Y, or if the distance is effectively horizontal. Its fast path uses real
   3D offsets with `-22`-ish Y components, so *that* path is unambiguously 3D. The likeliest reading is that
   **the value is 3D and Skyblocker's slow path is simply an approximation that works because you walk on
   the gold floor the chests are buried in** — but that is inference. Ten-second in-game check: stand
   directly above a known treasure and see whether the counter reads ~0 or reads the vertical offset.
2. **Do the anchors still exist on MC 26.1.2?** Skyblocker's local jar is `6.4.1+1.21.11` and SkyHanni's is
   `7.22.0-mc1.21.11`. Both target 1.21.11, not 26.1.2. The Keeper nametags are very likely unchanged; the
   QUARTZ_STAIRS+BARRIER block pattern is the more fragile of the two. And the 43 chest offsets are only
   valid if Hypixel has not moved them.

**SPECULATION, clearly labelled:** the beep pitch/frequency is almost certainly a function of the same
distance value and carries no extra information, so it can be ignored. SkyHanni agrees implicitly — its
`MetalDetectorMute` feature just silences `block.note_block.harp` while the detector is held.

### 4.4 The adjacent tool: the Wishing Compass

Not part of the Metal Detector, but the closest thing the game gives to a directional signal, and directly
useful for locating the five structures on a map.
<https://hypixelskyblock.minecraft.wiki/w/Wishing_Compass>

- Found in treasure chests in the Crystal Hollows (3 per lobby). **Consumed after one use** (was three uses
  until August 2023). Uncommon, tradeable, not auctionable.
- On use it *"displays a line of green particles pointing toward"* a target that depends on where you are and
  what you are carrying:

| In | Points to | Unless |
|---|---|---|
| Jungle | Odawa | Jungle Key in inventory → **Jungle Temple** |
| Mithril Deposits | **Mines of Divan** | — |
| Goblin Holdout | King Yolkar | King's Scent active → **Goblin Queen's Den** |
| Precursor Remnants | **Lost Precursor City** | — |
| Magma Fields | **Khazad-dûm** | — |
| anywhere | **Crystal Nucleus**, if all 5 crystals found but not placed | — |

- If the structure failed to generate: *"The Wishing Compass can't seem to locate anything!"* and it is
  **not consumed**.

**A particle line is a ray, not a point.** Two compass uses from different positions give two rays whose
intersection is the structure — the same idea as trilateration but with bearings instead of ranges.

**Skyblocker implements exactly this** (`WishingCompassSolver.java`, real source), and it is the single most
useful reference for putting a structure on the map without walking into it. It intercepts
**`HAPPY_VILLAGER` particle packets** from the server, averages 25 particle directions into a unit vector,
does that twice from two positions at least 8 blocks apart, then intersects the two lines:

```java
private static final long   PARTICLES_PER_LINE      = 25;
private static final long   PARTICLES_MAX_DELAY     = 500;
private static final double PARTICLES_MAX_DISTANCE  = 0.9;
private static final long   DISTANCE_BETWEEN_USES   = 8;
private static final double DISTANCE_TOLERANCE      = 5.0;
private static final Vec3   JUNGLE_TEMPLE_DOOR_OFFSET = new Vec3(-57, 36, -21);
```

It identifies *which* structure it just found purely from the zone you were standing in plus two state
checks — no block scanning at all:

```java
case JUNGLE             -> isKeyInInventory()   ? JUNGLE_TEMPLE      : ODAWA;
case MITHRIL_DEPOSITS   -> MINES_OF_DIVAN;
case GOBLIN_HOLDOUT     -> isKingsScentPresent() ? GOBLIN_QUEENS_DEN : KING_YOLKAR;
case PRECURSOR_REMNANTS -> LOST_PRECURSOR_CITY;
case MAGMA_FIELDS       -> KHAZAD_DUM;
```

`isKeyInInventory()` checks item id `"JUNGLE_KEY"`; `isKingsScentPresent()` reads the tab list for
`Active Effects:` / `King's Scent`; the held item is checked for `"WISHING_COMPASS"`. The failure case is
matched on the exact wiki-quoted string `"The Wishing Compass can't seem to locate anything!"`.

**This maps one-to-one onto the wiki's table above**, which is a good cross-check: the wiki and Skyblocker
independently describe the same conditional targeting.

> **There is a real bug on Skyblocker's master branch** — the intersection averages `close` with itself, so
> the second reading is discarded:
> ```java
> Vec3 c1 = new Vec3(close.getX(), close.getY(), close.getZ());
> Vec3 c2 = new Vec3(close.getX(), close.getY(), close.getZ());   // should be closeTwo
> intersection = c1.add(c2).scale(0.5);
> ```
> Do not copy that line.

This is a **legit** technique — it reads particles the server already sent and an item the player already
used — and it places a structure far more precisely than walking into it. It is the obvious legit-build
counterpart to QUOI's cheat-build chunk scanner.

---

## 5. `/warp nucleus` and detecting access

**There is no `/warp crystal nucleus`.** From <https://hypixelskyblock.minecraft.wiki/w/Fast_Travel>:

| Destination | Command | Aliases | Requirement |
|---|---|---|---|
| Crystal Hollows entrance | `/warp crystals` | `/warp hollows`, `/warp ch` | **Hollows Pass** |
| **Crystal Nucleus** | **`/warp nucleus`** | `/warp cn`, `/warp nuc` | **Hollows Pass** *or* **Travel Scroll** |

Two separate unlocks feed that:

- **Hollows Pass / island access.** *"Talking to Gwendolyn allows you to purchase a pass to the Crystal
  Hollows for 6h for 10,000 Coins: you gain permanent, unrestricted access to the Crystal Hollows after
  helping Dulin."* Entry also needs **Mining XII (12)** and **Heart of the Mountain Tier 4**.
  <https://hypixelskyblock.minecraft.wiki/w/Crystal_Hollows>. The Dulin quest requires **HOTM VII**, and runs
  Gwendolyn → Dulin (Hanging Court) → forge a **Secret Railroad Pass** → give to a Ticket/Station Master.
- **`Travel Scroll to the Crystal Nucleus`** — RARE. Granted once at **Commission Milestone VI (750
  commissions)**, then repurchasable from **Rusty** for 100,000 Coins.
  <https://hypixelskyblock.minecraft.wiki/w/Travel_Scroll_to_the_Crystal_Nucleus>

### 5.1 How a client can tell

**The wiki documents no client-visible flag for any of this.** What a client can *actually* check, in rough
order of reliability:

1. **Scan for the travel scroll item.** It is a real inventory/sack item with a stable Skyblock item id.
   This is a direct, honest check and the mod already has item-id scanning for the inventory sorter and the
   value tooltip. **The wiki does not give the exact id** for this scroll (it does give `ROYAL_PIGEON` and
   `ROYAL_COMPASS` for the two commission items), so the id must be captured in game or read from the API.
2. **Try the command and read the failure.** Robust but it is a side effect, and the wiki quotes no failure
   message, so the string would have to be captured.
3. **Read the SkyBlock fast-travel menu.** Not documented on the wiki.

**Recommendation as a fact, not a design call:** there is no passive, wiki-documented signal. Anything the
mod does here is an inference, and the feature should be written to degrade gracefully if it guesses wrong
rather than to block on the check.

---

## 6. Commissions, Royal Pigeon, and Boss Corleone

### 6.1 Where commissions are claimed

Commissions come from *"the King and his Emissaries"*; **Emissaries unlock at Commissions Milestone I**.
<https://hypixelskyblock.minecraft.wiki/w/Commissions>, <https://hypixelskyblock.minecraft.wiki/w/Emissaries>

Two matter here:

- **Emissary Braum** — at the Crystal Hollows entrance in the Dwarven Mines, `89/198/-92`. He introduces the
  CH set. Verbatim dialogue: *"Commissions inside the Crystal Hollows are different from the ones in the
  Dwarven Mines."* / *"Click me again to recieve your first set of Crystal Hollows commissions."* (sic —
  that is the wiki's transcription of the in-game line) / *"Once you complete them, come back to me!"*
- **Emissary Sisko** — **at the Crystal Nucleus**, `495.5, 106, 556.5`, *"Allows the player to access their
  Commissions."* This is the in-Hollows claim NPC.

**Slots:** 2 initially → 3 at 100 commissions completed → 4 with the **Core of the Mountain** HOTM perk
(HOTM V). Slots 1 & 3 and 2 & 4 share quest pools without duplicates.

**Milestones:** I/5 (Emissaries + Royal Compass) · II/25 · III/100 (+1 slot) · IV/250 (Travel Scroll to
Dwarven Mines) · V/500 (**Royal Pigeon**) · VI/750 (**Travel Scroll to the Crystal Nucleus**).

### 6.2 Crystal Hollows commission list

From the Commissions page's Crystal Hollows table, verbatim:

*Non-crystal:*
- `[Type] Gemstone Collector` — Collect 1,000 [type] Gemstones.
- `Automaton Slayer` — Slay 13 Automatons in the Precursor Remnant.
- `Sludge Slayer` — Slay 25 Sludges in the Jungle.
- `Team Treasurite Member Slayer` — Slay 13 Team Treasurite Members in the Mithril Deposits.
- `Goblin Slayer` — Slay 13 Goblins in the Goblin Holdout.
- `Yog Slayer` — Slay 13 Yogs in the Magma Fields.
- `Chest Looter` — Open 3 chests.
- `Thyst Slayer` — Slay 5 Thysts in the Jungle.
- `Hard Stone Miner` — Mine 1,000 Hard Stone.

*Crystal:*
- `[Gemstone] Crystal Hunter` — Find a [gemstone] Crystal in the [area].
- `Boss Corleone Slayer` — Slay 1 Boss Corleone in the Mithril Deposits. **The wiki marks this row with a
  `{{confirm}}` template — i.e. the wiki itself flags it as unverified.**

`[area]` values for Crystal Hunter: Jade→Mines of Divan, Amber→Goblin Queen's Den, Amethyst→Jungle Temple,
Sapphire→Lost Precursor City, Topaz→Khazad-dûm.
<https://hypixelskyblock.minecraft.wiki/w/Gemstone_Crystals>

**Matching hazard:** `Goblin Slayer` exists in **both** the Dwarven Mines and the Crystal Hollows lists with
different targets. A matcher keyed on the commission name alone cannot tell them apart — it needs the island
as well.

**The wiki gap is real** — the Commissions page has **no Interface section** (the Royal Pigeon page even
links to a `#Interface` anchor that does not exist), describes no GUI layout, no scoreboard line, no tab-list
entry, and quotes no completion chat message. **But Skyblocker fills it.**

### 6.3 How to actually read commissions — from Skyblocker

**They are in the TAB LIST, not the scoreboard.** `CommissionLabels.tick()` runs every 20 ticks, walks
`PlayerListManager.getPlayerList()`, finds the entry whose display name `startsWith("Commissions")`, then
consumes the following entries **while they begin with a single leading space**, stripping that space:

```java
} else if (string.startsWith("Commissions")) {
    foundCommissions = true;
}
...
if (!string.startsWith(" ")) break;
string = string.substring(1);
Matcher matcher = CommsWidget.COMM_PATTERN.matcher(string);
if (matcher.matches()) {
    String name = matcher.group("name");
    String progress = matcher.group("progress");
    newCommissionDone |= "DONE".equals(progress);
    newCommissions.add(name);
}
```

The pattern (`skyblock/tabhud/widget/CommsWidget.java`):

```java
// group 1: comm name, group 2: comm progress (without "%" for comms that show a percentage)
public static final Pattern COMM_PATTERN = Pattern.compile("(?<name>.*): (?<progress>.*)%?");
```

So: **`<name>: <progress>`**, and a finished commission has progress exactly **`DONE`** — that is the
completion signal, and it is in the tab list, not in chat. This answers the "how does the macro know a
commission is finished" question completely.

Skyblocker matches a commission to a location with a plain `commission.contains(<location display name>)`,
and flips colour on `commission.contains("Titanium")`. A `contains` match is what makes the
Dwarven-vs-Hollows `Goblin Slayer` collision above a real problem.

Two related Skyblocker pieces:

- `CommissionHighlight` — a container solver keyed on the GUI title regex `^Commissions$`, greening any slot
  whose lore contains `COMPLETED`. So the menu title is literally `Commissions` and the lore marker is
  `COMPLETED` (not `DONE` — the two differ between tab list and GUI).
- `CallMismyla` — matches the chat line
  `^([\w' ]+) Commission Complete! Visit the King to claim your rewards!$`.
  **That is the completion chat message the wiki does not document.** Note the leading group is the
  commission name, and "Visit the King" is why the Royal Pigeon / Mismyla contact exists.
- `PowderWidget` parses tab lines `Mithril: ([\d,]+)`, `Gemstone: ([\d,]+)`, `Glacite: ([\d,]+)`.

### 6.4 Royal Pigeon

<https://hypixelskyblock.minecraft.wiki/w/Royal_Pigeon>

It is an **item**, not a pet, not an accessory, not a HOTM perk. Infobox: **LEGENDARY**, `id = ROYAL_PIGEON`,
`tradeable = no`, `auctionable = no`, `soulbound = Co-op`, `museum = yes` (category `special`),
`source = Commission Milestone 5`.

Effect, verbatim: *"Every 5 seconds, the player can use the Royal Pigeon to access the Commissions menu
remotely from within the Dwarven Mines, Crystal Hollows, Glacite Tunnels or Glacite Mineshafts."*

So it is **remote access to the menu**, not an auto-claimer and not a notifier. Two exact in-game strings the
page does give:

- `This ability is on cooldown for #s.`
- `The Royal Pigeon can't find the King from here.`

**Detection — and this is where the design has to change.** The wiki does **not** state where the item lives
(no accessory or equipment infobox field; museum category `special`), so "inventory scan for `ROYAL_PIGEON`"
is a reasonable but undocumented assumption. Worse:

> **A player who took the Abiphone route no longer has the item at all.**
> **Queen Mismyla** (<https://hypixelskyblock.minecraft.wiki/w/Queen_Mismyla>) gives her Abiphone contact —
> the *same* remote-commission function, from *anywhere* — in exchange for the Royal Pigeon. Her dialogue:
> *"✆ If you return my Royal Pigeon, I'll give you my contact."*, handed over via a `[GIVE ITEM]` option.

So an inventory scan for `ROYAL_PIGEON` returns **false for a player who has strictly more capability than
one it returns true for**. Detecting the capability reliably means detecting *either* the item *or* the
Mismyla Abiphone contact, and the wiki gives no client-readable signal for the latter.

**Plainly: item-presence detection is not a sound proxy for "can claim commissions remotely."** That is a
finding, not a preference.

Do not confuse it with the **Royal Compass** (`ROYAL_COMPASS`, Rare, Milestone I) — that points green
particles at the nearest Emissary, **Dwarven Mines only**, and is unrelated.
<https://hypixelskyblock.minecraft.wiki/w/Royal_Compass>

### 6.5 Boss Corleone

There is no standalone article. `Boss_Corleone` is a redirect to
<https://hypixelskyblock.minecraft.wiki/w/Team_Treasurite> §`Boss_Corleone`; `/w/Corleone` is not an article.

- **Name:** the wiki writes **"Boss Corleone"** (prose sometimes shortens to "Corleone"). The commission row
  also uses "Boss Corleone".
- **Stats:** Level 200, Health **1,000,000**, Damage **4,000**.
- **Where:** Mithril Deposits. Verbatim: he *"has a small chance to spawn in place of a regular Grunt, but it
  will always spawn repeatedly in a special structure within the Mithril Deposits."* (The named structures
  are **Corleone Hideout** and **Corleone Lakefront** — §2.4.)
- **Spawn timing:** *"Corleone seemingly spawns every 60 or 120 seconds alternately in these structures."*
  and *"In order for Corleone to spawn, the player must be moving near his spawn point when his spawn timer
  is off cooldown."* First entry to the spawn area on a server spawns him immediately regardless of timer.
  Note the wiki's own hedge — **"seemingly"**. The timer is not established.
- **Mechanics:** throws a ball that summons **Smog**, a small Wither with 10k health dealing 1,500 damage,
  which explodes after several seconds for area damage; he can summon more. When these mobs throw a ball,
  white text appears above their head reading `[mob] go!` — combat text, not a spawn broadcast.
- **Drops:** Rough Ruby Gemstone 15–27x (100%), Rough Jade Gemstone 18–41x (100%), **Corleonite** 0–1x (25%,
  *"100% loot share drop chance"* — *"everyone who attacked receives the drop"*), 50 coins, +150 Combat XP,
  50 experience orbs. **So a melee-only macro does not need the kill, only a hit.**
- **No damage cap and no immunity is documented.** The wiki is silent, which is not the same as there being
  none.
- The other Team Treasurite mobs, exact names: **Grunt** (Level 50), **Executive Sebastian**,
  **Executive Wendy**, **Executive Viper** (all Level 100). Worth knowing so a "find Corleone" scan does not
  latch onto an Executive.

**The critical answer for the macro: the wiki documents no spawn broadcast, no boss bar, no scoreboard line,
and no nametag/armor-stand text for Boss Corleone.** Team Treasurite, the redirect, Mithril Deposits, Crystal
Hollows and a site-scoped search all turn up nothing. The common `[Lv200] Boss Corleone` nametag form is
**UNVERIFIED** — it is not on this wiki in any form.

**And none of the five mods detects him either.** This was checked in all of them:

- **Skyblocker** — the string `Corleone` appears in exactly two classes, and it is
  `CORLEONE("Corleone", Color.WHITE, null)` — a map marker with a **null linked chat message**. It can only
  be placed by the scoreboard area line saying "Corleone", a coordinate someone pasted in chat, a manual
  `/skyblocker crystalWaypoints add <pos> Corleone`, or another player's client publishing it. No boss bar
  parse, no entity scan, no health bar.
- **QUOI and Odin** — they find `CORLEONE_DOCK` and `CORLEONE_HOLE`, his **spawn structures**, by block
  signature. Never the entity.
- **SkyHanni** — nothing.

So "is Corleone alive in this lobby right now?" has **no documented text to match and no prior art**. The
only route is entity detection — scanning loaded entities for his nametag — and the exact nametag string
must be captured in game before a regex is written. That also means detection is limited to render/tracking
range, so "wait for him to spawn" can only mean "wait near a Corleone structure", which fits the wiki's own
statement that the player must be *moving near his spawn point* for him to spawn at all. **Finding the
structure is the solved half; detecting the entity is not.**

---

## 7. What the reference mods actually do

Six mods were read. In one line each:

| Mod | CH map | Structure detection | Metal detector | Commissions | Corleone |
|---|---|---|---|---|---|
| **Skyblocker** (real source) | yes, static image + 62px transform | **five mechanisms**, incl. compass triangulation and a WebSocket | **yes, two paths** | **yes, tab list** | no |
| **QUOI** (real source) | yes, 1px/block, 621² | **cheat chunk scan**, block signatures | no | no | structures only |
| **SkyHanni** (bytecode) | walls only | area strings only | **yes, repo dataset** | no | no |
| **Odin** (bytecode) | no | same cheat chunk scan as QUOI | no | no | structures only |
| **NoammAddons / Devonian** | no | none | no | no | no |

**Skyblocker is the one to study for the legit feature; QUOI is the one to study for the cheat feature.**

### 7.1 NoammAddons — `C:\Users\Hunter\noammaddonsmod`

**No Crystal Hollows feature of any kind.** An exhaustive search of the tree (and its `build/` output) for
every Crystal Hollows term turns up exactly one line:

- `C:\Users\Hunter\noammaddonsmod\src\main\kotlin\com\github\noamm9\utils\location\WorldType.kt:7` —
  `CrystalHollows("Crystal Hollows"),`, one enum constant in an area-name map. Nothing consumes it.

No metal detector, no map, no structure bounds, no nucleus-warp detection.

**Location detection** — `...\utils\location\LocationUtils.kt:46-51`: reads the **tab list**
(`ClientboundPlayerInfoUpdatePacket`), finds the entry whose display name starts with `"Area: "` or
`"Dungeon: "`, strips the prefix and matches the rest against `WorldType.tabName`. Skyblock detection at
line 67 is the scoreboard objective name `== "SBScoreboard"`. **No `/locraw` anywhere.**

**Transferable:** the world→map-pixel projection in
`...\utils\dungeons\map\utils\MapUtils.kt`. It *calibrates* rather than hardcoding — defaults `startCorner =
Pair(5, 5)`, `mapRoomSize = 16`, `coordMultiplier = 0.625` (lines 14–16), with

```kotlin
fun coordsToMap(vec: Vec3): Pair<Float, Float> {
    val x = ((vec.x - DungeonScanner.startX + 15) * coordMultiplier + startCorner.first).toFloat()
    val z = ((vec.z - DungeonScanner.startZ + 15) * coordMultiplier + startCorner.second).toFloat()
    return Pair(x, z)
}
```

### 7.2 Devonian — `C:\Users\Hunter\UsersHunterdevonian`

**No Crystal Hollows feature either.** The config `Categories.kt` has no MINING or CRYSTAL_HOLLOWS category
at all. The only near-hits are a Garden powder regex and item gemstone *slots* for pricing — neither is CH.

**Location detection** — `...\api\Location.kt`:

```kotlin
val areaRegex = "^(?:Area|Dungeon): ([\w ']+)$".toRegex()   // line 8, from TabUpdateEvent
val subAreaRegex = "^([⏣ф]) ".toRegex()                      // line 9, from ScoreboardEvent
```

Area from the tab list; **sub-area from the scoreboard line starting `⏣` or `ф`**, first 3 chars dropped
(line 50). That is exactly the mechanism that yields `⏣ Crystal Hollows` / `⏣ Jungle` / `⏣ Mithril Deposits`
— nothing in the repo uses it for CH, but it is the same signal `IslandDetector` already reads here.

**Closest analogue to a "solve a hidden coordinate from samples" problem** —
`...\features\diana\BurrowGuesser.kt`. It is **not** trilateration: particle-chain RANSAC plus cubic
polynomial regression extrapolation. Constants at lines 67–69: `MIN_CHAIN_LENGTH = 6`,
`MAX_CHAIN_DISTANCE_ERROR = 0.5`, `RANSAC_ITERS_PER = 30`. The useful part for us is the supporting maths in
`...\utils\math\MathUtils.kt` (`polyRegression`, `toPolynomial`, `convergeHalfInterval`, `rescale`) — the
primitives a least-squares sphere solver needs — and the RANSAC-style outlier rejection, which is the right
pattern for metal-detector samples where one reading may be stale or taken mid-jump.

It also has the only hardcoded world-bounds sanity filter in either repo (BurrowGuesser lines 88–90, hub
world), which is the pattern to copy for rejecting a CH solution outside `202..823 / 31..188 / 202..823`.

**Best structural template for the CH coordinate space** — `...\api\dungeon\Coordinates.kt`: a dedicated
world↔grid coordinate-space type pair with hardcoded corners (`cornerStart`, `cornerEnd`), `toComponent()`
/ `toWorld()` inverses, and explicit in-bounds checks. That is the shape a `CrystalHollowsCoords` type should
take.

### 7.3 QUOI — `C:\Users\Hunter\Downloads\quoi-1.1.0.zip` (real Kotlin source)

**This is the cheat Crystal Hollows map, and the answer to "how does it detect a structure it is not
standing in" is: it scans loaded chunks for hardcoded vertical block-column signatures.** No entity names, no
scoreboard, no network. Files (paths relative to the extracted zip root `quoi-1.1.0/`):

- `src/main/kotlin/quoi/module/impl/mining/CrystalHollowsMap.kt` (246 lines)
- `src/main/kotlin/quoi/module/impl/mining/CrystalHollowsScanner.kt` (175 lines)
- `src/main/kotlin/quoi/module/impl/mining/enums/Structure.kt` (519 lines — the signature table)
- `src/main/kotlin/quoi/module/impl/mining/enums/CrystalHollowsQuarter.kt`
- `src/main/kotlin/quoi/module/impl/mining/enums/StructureType.kt`
- `src/main/kotlin/quoi/utils/WorldUtils.kt` (`worldToMap`)
- `src/main/resources/assets/quoi/crystalhollowsmap.png` — **2613 × 2613 px**

**Map model** (`CrystalHollowsMap.kt:63-74, 80-82`), verbatim:

```kotlin
const val X_MIN = 202
const val X_MAX = 823
const val Z_MIN = 202
const val Z_MAX = 823
const val MAP_SIZE = 621

private val Number.mapX get() = worldToMap(this, X_MIN, X_MAX, 0, MAP_SIZE).toFloat()
private val Number.mapZ get() = worldToMap(this, Z_MIN, Z_MAX, 0, MAP_SIZE).toFloat()

private const val GRID_SIZE = 64
private const val CHUNK_OFFSET = X_MIN shr 4      // = 12
```

`worldToMap` (`WorldUtils.kt:42`) is a plain linear remap. Since `X_MAX − X_MIN == 621 == MAP_SIZE`, this is
**1 world block = 1 map pixel**; the 2613 px PNG is downscaled to 621×621 on draw. Player icons rotate by
`yHeadRot − 180f`; out-of-range players fall back to a white marker after 60 000 ms.

**Quadrants** (`CrystalHollowsQuarter.kt`, the whole file):

```kotlin
enum class CrystalHollowsQuarter(val predicate: (BlockPos) -> Boolean) {
    JUNGLE({ it.x <= 576 && it.z <= 576 }),
    PRECURSOR_REMNANTS({ it.x > 448 && it.z > 448 }),
    GOBLIN_HOLDOUT({ it.x <= 576 && it.z > 448 }),
    MITHRIL_DEPOSITS({ it.x > 448 && it.z <= 576 }),
    MAGMA_FIELDS({ it.y < 80 }),
    ANY({ true });

    fun test(pos: BlockPos) = predicate(pos)
}
```

Note the deliberate ±64 overlap around 512 — see §3.3.

**The scanner** (`CrystalHollowsScanner.kt`), credited in a header comment to GumTuneClient's
`WorldScanner.java`. On `WorldEvent.Chunk.Load`, off-thread on `Dispatchers.IO`:

```kotlin
val chunkX = chunk.pos.x shl 4
val chunkZ = chunk.pos.z shl 4
if (chunkX !in X_MIN..X_MAX || chunkZ !in Z_MIN..Z_MAX) return@launch
```

Y scan range (`handleChunk`, lines 112-113):

```kotlin
val fromY = if (structureScanner && !routeScanner) 30 else 0
val toY   = if (routeScanner && !structureScanner) 70 else 180
```

Matching is a **vertical block-column signature**: for a candidate `(x, y, z)` it walks `structure.blocks`
upward (`null` entries are wildcards), bailing if `startY + blocks.size >= 180`. On a hit, the reported
waypoint is `pos.offset(xOffset, yOffset, zOffset)` — a hand-measured offset from the fingerprint block to
where you actually want the marker — and it prints
`"Found ${structure.displayName} at ${pos.x}, ${pos.y}, ${pos.z}"` to chat.

Dedup: each chunk is scanned exactly once (`scannedChunks: HashSet<Long>` keyed on `ChunkPos.toLong()`,
cleared on `WorldEvent.Change`); structures flagged `canBeMultiple` must be more than 4 chunks apart
(`isWithinChunks(pos, 4)`); the rest stop after the first hit in the world.

**Cost:** 256 columns per chunk × up to 151 Y values × 30 structures. That is why it runs on
`Dispatchers.IO` and why the quadrant predicate is checked *before* the column compare — it is the cheap
rejection that makes the rest affordable.

**The key property, and the reason this is the cheat map:** it works on **any chunk the client has loaded**,
including ones the player merely rendered past. It does not require standing in the structure — but it does
require the server to have sent the chunk, so it is bounded by render distance. QUOI draws which chunks have
been scanned on the HUD map (greedy-meshed rectangles, `rebuildMeshedChunks()`) precisely because coverage is
partial.

One latent bug worth not copying: in `renderMap` the chunk overlay uses
`rect(x = (chunk.z shl 4).mapZ, y = (chunk.x shl 4).mapX, …)` — axes swapped relative to the route blocks,
which use `x = pos.x.mapX, y = pos.z.mapZ`.

**Signature table** (`Structure.kt`), the ones that matter:

| Enum | Display | Quarter | Offset | Signature, bottom → up |
|---|---|---|---|---|
| `QUEEN` | Goblin Queen | GOBLIN_HOLDOUT | 0, 5, 0 | STONE, ACACIA_LOG×3, CAULDRON, FIRE |
| `DIVAN` | Mines of Divan | MITHRIL_DEPOSITS | 0, 5, 0 | QUARTZ_PILLAR, QUARTZ_STAIRS, STONE_BRICK_STAIRS, CHISELED_STONE_BRICKS |
| `CITY` | Precursor City | PRECURSOR_REMNANTS | **24, 0, −17** | COBBLESTONE×4, COBBLESTONE_STAIRS, POLISHED_ANDESITE×2, DARK_OAK_STAIRS |
| `TEMPLE` | Jungle Temple | JUNGLE | **−45, 47, −18** | BEDROCK, BEDROCK, CLAY, CLAY |
| `KING` | Goblin King | GOBLIN_HOLDOUT | 1, −1, 2 | RED_WOOL, DARK_OAK_STAIRS×3 |
| `BAL` | Bal | MAGMA_FIELDS | 0, 1, 0 | LAVA, then BARRIER×10 |
| `FAIRY_GROTTO` | Fairy Grotto | ANY (multi) | 0,0,0 | MAGENTA_STAINED_GLASS |
| `CORLEONE_DOCK` | Corleone Dock | MITHRIL_DEPOSITS | **23, 11, 17** | POLISHED_GRANITE, WATER, WATER … FIRE |
| `CORLEONE_HOLE` | Corleone Hole | MITHRIL_DEPOSITS | **0, −3, 34** | SMOOTH_STONE_SLAB … |
| `GOLDEN_DRAGON` | Golden Dragon | ANY | **0, −3, 5** | STONE, RED_TERRACOTTA×3, SKELETON_SKULL, RED_WOOL |

Plus grotto variants (`RUINS_GROTTO_1/2/3`, `SHRINE_GROTTO`, `SPIRAL_GROTTO`, `WATERFALL_GROTTO`) and mob-spot
structures (`GOBLIN_HALL`, `GOBLIN_RING`, `GOBLIN_HOLE_CAMP`, `GRUNT_BRIDGE`, `GRUNT_RAILS_1`,
`GRUNT_HERO_STATUE`, `SMALL_GRUNT_BRIDGE`, `KEY_GUARDIAN_SPIRAL`, `SLUDGE_WATERFALLS`, `SLUDGE_BRIDGES`,
`ODAWA`, `MINI_JUNGLE_TEMPLE`, `YOG_BRIDGE`, `PRECURSOR_TRIPWIRE_CHAMBER`, `PRECURSOR_TALL_PILLARS`).
`StructureType` = `FAIRY_GROTTO, CH_CRYSTALS, CH_MOB_SPOTS, WORM_FISHING, GOLDEN_DRAGON`.

**CH detection:** `Island.CrystalHollows("Crystal Hollows", "ch")`, from the tab-list `"Area: "` entry in
`ClientboundPlayerInfoUpdatePacket`; sub-area from `ClientboundSetPlayerTeamPacket` prefix+suffix matching
`^ ([⏣ф]) .*`. **Same signal `IslandDetector` already uses here.**

**QUOI has no metal detector and no nucleus-warp detection.** A grep for `metal|nucleus|treasure|keeper of`
across its Kotlin returns only an unrelated `"Treasure Talisman"` in `Dungeon.kt:524`.

### 7.4 SkyHanni 7.22.0 — `C:\Users\Hunter\Downloads\SkyHanni-7.22.0-mc1.21.11.jar`

Compiled, Fabric-intermediary. **Everything below is reconstructed from `javap -p -c` bytecode except the
quoted string and numeric constants, which are exact (read from the constant pool).**

Relevant classes, all under `at/hannibal2/skyhanni/`:
`features/mining/crystalhollows/{CrystalHollowsWalls, CrystalHollowsNamesInCore, NucleusBarriersBox,
MetalDetectorSolver, MetalDetectorMute, MetalDetectorAllToolsAlert, HighHeatSound, CrystalNucleusApi,
CrystalNucleusTracker, CrystalNucleusProfitPer}`, `features/chat/CrystalNucleusChatFilter`,
`features/misc/JoinCrystalHollows`, `data/MiningApi`, `data/IslandType`,
`data/jsonobjects/repo/MetalDetectorChestsJson`.

**World model** — `CrystalHollowsWalls`, exact `ConstantValue` attributes:

```java
private static final int    EXPAND_TIMES = 20;
private static final double HEAT_HEIGHT  = 65.0d;
private static final double MAX_HEIGHT   = 190.0d;
private static final double MIN_X = 0.0d, MIDDLE_X = 513.0d, MAX_X = 1024.0d;
private static final double MIN_Z = 0.0d, MIDDLE_Z = 513.0d, MAX_Z = 1024.0d;

nucleusBB = new AABB(463.0, 65.0, 460.0,  560.0, 190.0, 563.0);
```

Quadrant dispatch is a hard split at 513, with Magma as `y < 65` and the Nucleus as the AABB — see the
comparison table in §3.3.

**Quadrant name labels inside the Nucleus** — `CrystalHollowsNamesInCore`, exact map:

```kotlin
private val coreLocations = mapOf(
    LorenzVec(550, 116, 550) to "§8Precursor Remnants",
    LorenzVec(552, 116, 474) to "§bMithril Deposits",
    LorenzVec(477, 116, 476) to "§aJungle",
    LorenzVec(474, 116, 554) to "§6Goblin Holdout",
)
```

Gated on `GraphAreaChangeEvent.area == "Crystal Nucleus"` and `playerLocation().y > 65.0`.

**Nucleus crystal-slot boxes** — `NucleusBarriersBox$Crystal`, exact corner pairs (each 11×13×11, Y 111…124):

| Crystal | Corner A | Corner B |
|---|---|---|
| AMBER | (474, 124, 524) | (485, 111, 535) |
| AMETHYST | (474, 124, 492) | (485, 111, 503) |
| TOPAZ | (508, 124, 473) | (519, 111, 484) |
| JADE | (542, 124, 492) | (553, 111, 503) |
| SAPPHIRE | (542, 124, 524) | (553, 111, 535) |

**This is directly useful**: it is a concrete, per-crystal, fixed-coordinate box inside the Nucleus, which is
more than the wiki gives.

**`MetalDetectorSolver`** — the core, reconstructed:

```kotlin
private fun findBaseCoordinates() {
    if (lastSearchedForBase.passedSince() < 15.seconds) return
    lastSearchedForBase = SimpleTimeMark.now()
    val player = LocationUtils.playerLocation().roundToBlock()
    for (x in -50..49) {
        for (y in 30 downTo -30) {
            for (z in -50..49) {
                val location = player.add(x, y, z).roundToBlock()
                val above    = location.add(0, 13, 0)
                if (location.getBlockAt() == Blocks.QUARTZ_STAIRS &&
                    above.getBlockAt()    == Blocks.BARRIER) {
                    baseCoordinates = getBaseCoordinates(above)
                    return
                }
            }
        }
    }
}

private fun getBaseCoordinates(start: LorenzVec): LorenzVec {
    var moved = true
    var pos = start
    while (moved) {
        moved = false
        if (pos.add(1,0,0).getBlockAt() == Blocks.BARRIER) { moved = true; pos = pos.add(1,0,0) }
        if (pos.add(0,1,0).getBlockAt() == Blocks.BARRIER) { moved = true; pos = pos.add(0,1,0) }
        if (pos.add(0,0,1).getBlockAt() == Blocks.BARRIER) { moved = true; pos = pos.add(0,0,1) }
    }
    return pos
}
```

and the per-action-bar-tick solve:

```kotlin
metalDetectorDistancePattern.matchMatcher(event.actionBar) {
    val distance = group("distance").formatDoubleOrNull() ?: return
    if (baseCoordinates == null) findBaseCoordinates()
    val base = baseCoordinates ?: return

    predictedChestLocations.clear()
    for (chest in chestLocations) {                    // from the SkyHanni repo
        val possible = base + chest.negated()          // base - chest
        if (possible == ignoreLocation) { ignoreLocation = null; return }
        if (LocationUtils.distanceToPlayer(possible.add(0, 1, 0)).roundTo(1) == distance) {
            predictedChestLocations.add(possible)
        }
    }
    // 1 survivor -> solved; >1 -> "please try standing still in a different spot"; 0 -> same
}
```

Chest offsets come from `event.getConstant<MetalDetectorChestsJson>("MetalDetectorChests")`, GSON field
`locations : List<LorenzVec>` — a runtime repo download, **not bundled in the jar**.

Output rendering: gold block colour, a line to the crosshair (width 3), a filled beacon waypoint in red, and
a label with the literal format `Treasure: §e<n>m`. Housekeeping: once solved and within 5 blocks
(`distanceSq <= 25.0`) the location is moved to `ignoreLocation` and cleared, and `ignoreLocation` is
forgotten again past 10 blocks (`distanceSq > 100.0`). Everything resets on world swap.

Gate: `fun isEnabled() = MiningApi.inMinesOfDivan() && config.metalDetectorSolver`.

**CH detection** — `MiningApi`:

```java
public final boolean inCrystalHollows() { return IslandType.CRYSTAL_HOLLOWS.isInIsland(); }
public final boolean inMinesOfDivan() {
    return inCrystalHollows() && RegexUtils.matches(minesOfDivanPattern, HypixelData.getSkyBlockArea());
}
```

`IslandType.CRYSTAL_HOLLOWS` = `IslandType("CRYSTAL_HOLLOWS", 11, "Crystal Hollows")`; `minesOfDivanPattern`
defaults to the literal `Mines of Divan`, matched against the scoreboard/tab `⏣ …` sub-area. `MiningApi` also
parses `heatDisplay` from the scoreboard with an `IMMUNE` sentinel.

**Exact in-game chat/regex literals** — `CrystalNucleusChatFilter`. These are ground truth for message
formats the wiki does not document, and several are directly relevant to the crystal features:

```
npc.keeper                 §e\[NPC\] §6Keeper of (?<keepertype>.*)§f: §r(?<message>.*)
crystal found (counter)    §f *§r§5§l❉ CRYSTAL FOUND §r§7\((?<count>\d)§r§7/5§r§7\)
crystal found (which one)  §f *§r(?<crystal>.* Crystal) *
crystal.placed             §5§l⚑ §r§dYou placed the §r(?<crystal>.* Crystal)§r§d!
run.completed              §5Crystal Nucleus Run complete§d!
component.list.preamble    §rThat's not one of the components I need! Bring me one of the missing components:
component.list              {2}§r§9(?<component>.*)
precursor.submitted        (?:Wait a minute. This will work just fine.|You've brought me all|me the (?<component>.*)§r! Bring me (?<remaining>\d|one) more).*
divan.scavenge             §aYou found §r(?<loot>.*) §r§awith your §r§cMetal Detector§r§a!
goblin.guard.exit          §8§oWhew! That was a close one, better get out of here\.{3}|§cThe Goblin King's §r§afoul stench §r§chas dissipated!
loot.start                  \s*§r§5§lCRYSTAL NUCLEUS LOOT BUNDLE.*
loot.end                   §3§l❉{64}
```

Other exact literals:
`§e[NPC] §6King Yolkar§f: §rBring me a §9Goblin Egg §rof any type.`,
`§e[NPC] §6King Yolkar§f: §2King's Scent§r applied.`,
`Bring me back a §9Goblin Egg`, `*rumble* *rumble*`, `This egg will help me stomach my pain.`,
`§e[NPC] Professor Robot§f: §rAll components submitted.`, `§e[NPC] §5Gwendolyn`,
`§7Pick it up near the §r§5Nucleus Vault§r§7!`, `§6§lPICK IT UP!`, `§cScavenged`, `§c[GUARD]`,
`  §r§dKeep exploring the §r§5Crystal Hollows §r§dto find the rest!`.

Filter message-type keys: `npc_divan_keeper, npc_goblin_guard, npc_king_yolkar, npc_prof_robot,
crystal_collected, crystal_placed, run_completed, non_tool_scavenge`.

`CrystalNucleusTracker` also carries a Bal-pet drop announcement pattern:
`(?:(?:§.)*\[.*(?:§.)*\+*(?:§.)*\] )?(?<player>.*)§r§f §r§ehas obtained §r§a§r§7\[Lvl 1\] §r§(?<raritycolor>[65])Bal§r§e!`
and the command `shresetcrystalnucleustracker`.

> These §-codes were read from bytecode twice with minor disagreements between the passes — see the
> confidence note at the end of §8. Capture the real lines before writing matchers.

> **Two of these answer open questions from the wiki research.** `goblin.guard.exit` contains
> `§c[GUARD]` and *"Whew! That was a close one, better get out of here..."* — so there **is** a guard
> mechanic in the Goblin Queen's Den after all, which the wiki does not document (see open question 10).
> And `crystal.collected.id` gives the exact `CRYSTAL FOUND (n/5)` counter format.

**`/warp nucleus` unlock detection: not present.** The only warp logic is `JoinCrystalHollows`, which handles
the **Crystal Hollows Pass** prompt only — `§cYou do not have an active Crystal Hollows pass!`,
`Buy a §2Crystal Hollows Pass §efrom §5Gwendolyn`, and rewriting messages into clickable `/warp ch` and
`/warp mines`. A jar-wide grep finds no `warp crystal` and no nucleus-warp tracking.

### 7.5 Odin client 0.2.3 — `C:\Users\Hunter\Downloads\odin-client-0.2.3-r1+26.1.jar`

Compiled, Mojang-mapped; reconstructed from bytecode. Only CH content is
`foo/starred/odinclient/features/impl/cheats/WorldScanner` — *"Scans and highlights structures in Crystal
Hollows"*. **No metal detector, no map, no nucleus feature.**

`WorldScanner$Quarter.test`:

```kotlin
NUCLEUS   -> x in 449..576 && z in 449..576
JUNGLE    -> x <= 576 && z <= 576
PRECURSOR -> x >  448 && z >  448
GOBLIN    -> x <= 576 && z >  448
MITHRIL   -> x >  448 && z <= 576
MAGMA     -> y < 80
ANY       -> true
```

Identical to QUOI's plus a `NUCLEUS` entry — **QUOI is a descendant of this** (QUOI's `Location.kt` header
credits OdinFabric). Same block-signature scanner technique, chunk-load driven, `y 0 until 171`. Its structure
list adds `KEY_GUARDIAN_TOWER`, `XALX`, `PETE` and `ODAWA` over QUOI's, and agrees on the hand-measured
offsets `CITY (24,0,−17)`, `TEMPLE (−45,47,−18)`, `GOLDEN_DRAGON (0,−3,5)` exactly.

Worm-fishing corner, reconstructed: `y > 63 && ((x >= 564 && z >= 513) || (x >= 513 && z >= 564))`.

### 7.6 Skyblocker — real source, and the closest thing to what we want

Read from `raw.githubusercontent.com/SkyblockerMod/Skyblocker/master/...` and cross-checked against the local
jar `C:\Users\Hunter\Downloads\skyblocker-6.4.1+1.21.11.jar`. Everything is in package
`de.hysky.skyblocker.skyblock.dwarven`. **This is the most directly relevant reference of the five** — it is
a legit-build Fabric mod with a Crystal Hollows map, structure waypoints, a metal detector and commission
reading, all of which we want.

Key files: `CrystalsHudWidget.java`, `CrystalsLocationsManager.java`, `MiningLocationLabel.java`,
`WishingCompassSolver.java`, `NucleusWaypoints.java`, `MetalDetector.java`, `CommissionLabels.java`,
`CrystalsChestHighlighter.java`, plus `utils/ws/SkyblockerWebSocket.java` and
`skyblock/tabhud/widget/CommsWidget.java`.

**Map.** World→pixel, real source:

```java
protected static Vector2ic transformLocation(double x, double z) {
    int transformedX = (int) ((x - 202) / 621 * 62);
    int transformedY = (int) ((z - 202) / 621 * 62);
    transformedX = Math.clamp(transformedX, 0, 62);
    transformedY = Math.clamp(transformedY, 0, 62);
    return new Vector2i(transformedX, transformedY);
}
```

Asset `assets/skyblocker/textures/gui/crystals_map.png`, measured **621 × 621 px**, blitted into a 62×62
area (`blit(..., 0, 0, 0, 0, 62, 62, 62, 62)`); widget size `62 * mining.crystalsHud.mapScaling`. **Note the
off-by-one**: the clamp upper bound is 62 but valid indices in a 62-pixel image are 0..61. Don't copy that.

Player marker: vanilla `minecraft:textures/map/decorations/player.png`, offset `(-2,-3)`, scale `0.75`,
pivot `(2.5f, 3.5f)`, yaw snapped to 16 cardinal steps by `yaw2Cardinal`. These locations draw at half size:

```java
private static final List<String> SMALL_LOCATIONS =
        List.of("Fairy Grotto", "King Yolkar", "Corleone", "Odawa", "Key Guardian", "Xalx", "Unknown");
```

**The biomes are not modelled in the HUD at all** — the map image is a static pre-rendered picture and the
zone AABBs live in the compass solver (§3.3). That is a design worth noting: Skyblocker draws art, not
computed borders.

**Structure detection — five independent mechanisms**, and this is the part to study:

1. **Scoreboard area line.** Every 40 ticks it reads `Utils.getIslandArea().substring(2)` and, if the name
   is a known category, drops a waypoint **at the player's current position**. `getIslandArea()` scans the
   sidebar for the line containing the area icon char `'\uE067'` (`SkyBlockIcons.AREA`). *This is the same
   mechanism `ChDiscovery` already uses here.*
2. **Chat NPC triggers.** A waypoint is placed at the player's feet and marked verified when a chat line
   *starts with* a linked string. Real source, `MiningLocationLabel.CrystalHollowsLocationsCategory`
   (`CRYSTALS_SPACER` is exactly 32 spaces):

```java
private static final String CRYSTALS_SPACER = "                                ";

UNKNOWN("Unknown", Color.WHITE, null),
JUNGLE_TEMPLE("Jungle Temple", new Color(DyeColor.PURPLE.getTextColor()), "[NPC] Kalhuiki Door Guardian:"),
MINES_OF_DIVAN("Mines of Divan", Color.GREEN, CRYSTALS_SPACER + "Jade Crystal"),
GOBLIN_QUEENS_DEN("Goblin Queen's Den", new Color(DyeColor.ORANGE.getTextColor()), CRYSTALS_SPACER + "Amber Crystal"),
LOST_PRECURSOR_CITY("Lost Precursor City", Color.CYAN, CRYSTALS_SPACER + "Sapphire Crystal"),
KHAZAD_DUM("Khazad-dûm", Color.YELLOW, CRYSTALS_SPACER + "Topaz Crystal"),
FAIRY_GROTTO("Fairy Grotto", Color.PINK, null),
DRAGONS_LAIR("Dragon's Lair", new Color(TextColor.GOLD.getValue()), "[NPC] Golden Dragon:"),
CORLEONE("Corleone", Color.WHITE, null),
KING_YOLKAR("King Yolkar", Color.RED, "[NPC] King Yolkar:"),
ODAWA("Odawa", Color.MAGENTA, "[NPC] Odawa:"),
KEY_GUARDIAN("Key Guardian", Color.LIGHT_GRAY, null),
XALX("Xalx", Color.GREEN, "[NPC] Xalx:");
```

   **Fairy Grotto, Corleone and Key Guardian have no linked message** — nothing announces them.

3. **Coordinates scraped from other players' chat:**

```java
static final Pattern TEXT_CWORDS_PATTERN = Pattern.compile(
        "\\Dx?(\\d{3})(?=[, ]),? ?y?(\\d{2,3})(?=[, ]),? ?z?(\\d{3})\\D?(?!\\d)");
```

   Applied to `text.split(":", 2)[1]`, validated with `checkInCrystals`, then matched against location names
   word-by-word; if nothing matches it offers the player a clickable labelling menu. It shares back out as
   plain chat: `<prefix> <place>: <x>, <y>, <z>`.

4. **Wishing Compass particle triangulation** — see §4.4.

5. **Cross-player sharing over a WebSocket.** `utils/ws/SkyblockerWebSocket.java`:
   `private static final String WS_URL = "wss://ws.hysky.de";`, authenticated via
   `de.hysky.skyblocker.utils.ApiAuthentication`. Services: `CRYSTAL_WAYPOINTS`, `DUNGEON_SECRETS`,
   `EGG_WAYPOINTS`. Payload is `{"name": <enum constant>, "coordinates": <BlockPos>}`; inbound handles a
   single `RESPONSE` and an `INITIAL_MESSAGE` backlog. **Lobbies are keyed by a predicted close timestamp**
   — see below. Fairy Grotto is opt-out-able (`mining.crystalsWaypoints.shareFairyGrotto`); everything else
   is shared unconditionally. *This is directly comparable to our own relay, and the sharding key is the
   interesting part.*

**Lobby day — and where `26 days` comes from.** Skyblocker uses the day count only to key the WebSocket
lobby, by predicting when the lobby will close. In 6.4.1 (bytecode):

```
timestamp = System.currentTimeMillis()/1000 + (624000L - world.getDayTime()) / 20
```

with a field `TWENTY_SIX_DAYS`. `624000` ticks = 26 MC days × 24000. On master, rewritten onto the 26.x
timeline API:

```java
/// Crystal Hollows lobbies close after 26 Minecraft days.
private static final long MAX_LOBBY_LIFETIME = 26;
private static final long MILLIS_PER_MINECRAFT_DAY = Duration.ofMinutes(20).toMillis();
...
int dayCount = timeline.get().value().getPeriodCount(clockManager);   // Timelines.OVERWORLD_DAY
if (dayCount >= 0 && dayCount < MAX_LOBBY_LIFETIME) {
    long closeTime = System.currentTimeMillis() + ((MAX_LOBBY_LIFETIME - dayCount) * MILLIS_PER_MINECRAFT_DAY);
    return new CrystalsWaypointSubscribeMessage(closeTime / 1000);
}
```

> **Note the conflict with the wiki.** The wiki says a lobby force-shuts-down on **day 35**; Skyblocker's
> code comment says *"Crystal Hollows lobbies close after 26 Minecraft days"* and its range check is
> `dayCount < 26`. One of the two is out of date. **Unresolved** — and it matters for the Lobby Swapper's
> sensible filter range.

Note also **`Timelines.OVERWORLD_DAY.getPeriodCount()` is the 26.x-native way to read the day**, which is
better for us than QUOI's `dayTime / 24000` since we are on 26.1.2.

**Metal detector, commissions, Corleone** — see §4.3, §6.3 and §6.5 respectively.

**Other CH pieces worth knowing.** `CrystalsChestHighlighter` triggers on the exact chat line
`"You uncovered a treasure chest!"`, then watches block-state updates for `Blocks.CHEST` within 10 blocks
(`distToCenterSqr > 100` rejects), and drives a lock-pick overlay from `CRIT` particles (250 ms lifetime,
0.8-block radius) plus three sound packets — `EXPERIENCE_ORB_PICKUP` at pitch exactly `1` = lock picked,
`VILLAGER_NO` = fail, `CHEST_OPEN` = done, with `neededLockCount = Math.min(currentLockCount, 5)`.

### 7.7 The two techniques, side by side

**The cheat one.** QUOI ← Odin ← GumTuneClient all use the same approach, and it is the only one that finds
a structure the player has never been near:

> A **vertical block-column fingerprint**, scanned per loaded chunk off the main thread, with `null`
> wildcards, a quadrant predicate as a cheap rejection filter, one-find-per-world dedup, and a hand-measured
> `(dx, dy, dz)` offset from the fingerprint block to the waypoint.

No entity nametags, no scoreboard, no chat, no network sharing. It works because Hypixel pastes the same
prefab schematic in every lobby — only its position changes. **That also means it is detectable in principle
as a chunk-scan pattern, and it is unambiguously a cheat-build feature.**

**The legit one.** Skyblocker never scans a block for a structure. It layers four weaker signals that are
each individually legitimate, and lets them reinforce each other:

> 1. the sidebar area name, recorded at the player's own position;
> 2. a chat line from the structure's own NPC (`[NPC] King Yolkar:`, `[NPC] Odawa:`, …);
> 3. **Wishing Compass particle triangulation** — two `HAPPY_VILLAGER` rays from ≥8 blocks apart, intersected;
> 4. coordinates other players paste in chat, or push over a shared WebSocket.

(3) is the interesting one: it locates a structure *before* you reach it, from data the server volunteered
because the player used an item. It is the legit answer to the same question the chunk scanner answers, and
it is the technique worth building here.

**Neither approach is what the existing `ChDiscovery` does.** That currently learns only from (1) — standing
in the place. Adding (2) and (3) would make the legit map genuinely useful without touching the cheat gate.

### 7.8 Licensing note

Three different situations here, and they are not equivalent:

- **NoammAddons** is CC0 — no restriction.
- **QUOI** is real source in a zip the user downloaded; **Skyblocker** is public source on GitHub. Both are
  readable, but both carry a licence that has not been checked in this pass.
- **SkyHanni** and **Odin** were read as **decompiled/disassembled bytecode**. That is a weaker basis in
  every sense: the reconstruction may be wrong (see the confidence note in §8), and reading bytecode is not
  the same as being offered source.

Reading any of them for *approach* is one thing. Transcribing a class, or lifting a dataset — Skyblocker's
43 chest offsets and its Keeper offsets, or SkyHanni's repo chest list — is another, and neither should
happen without checking the specific project's licence first. **`THIRD-PARTY-NOTICES.md` in this repo is
where that would have to be recorded.**

This document deliberately records *approaches* and *in-game string constants*. The strings are Hypixel's
text, not the mods' authorship, so quoting them here is a description of the game, not a copy of anyone's
work. The algorithms are described so they can be reimplemented, not pasted.

### 7.9 What already exists in killer560s-mod

There is already a `mining/chmap` package, written 2026-09-30:

- `src/main/java/com/killer560/hub/mining/chmap/ChStructure.java` — the enum of named structures
  (Crystal Nucleus, Jungle Temple, Mines of Divan, Goblin Queen's Den, Lost Precursor City, Khazad-dûm,
  Fairy Grotto, Dragon's Lair, Corleone) with a `fromAreaName()` that matches the sidebar area string on the
  *distinctive* part of each name, deliberately so that the Jungle **biome** never matches the Jungle
  **Temple**. That reasoning is correct and matches the wiki.
- `src/main/java/com/killer560/hub/mining/chmap/ChFind.java` — a growing AABB per structure, with a
  `Source` of `VISITED` / `SCANNED` / `SHARED`.
- `src/main/java/com/killer560/hub/mining/chmap/ChDiscovery.java` — per-lobby discovery, discarded when the
  lobby id changes, driven off `IslandDetector.graphIsland() == "CRYSTAL_HOLLOWS"` and
  `IslandDetector.scoreboardArea()`.
- `src/main/java/com/killer560/hub/mining/chmap/CrystalHollowsMapScreen.java` — the map screen.
- `src/main/java/com/killer560/hub/pathfinding/IslandDetector.java:49` — `"crystal hollows"` →
  `"CRYSTAL_HOLLOWS"`, read from the sidebar.

The existing class docs correctly refuse to draw fixed zone outlines on the grounds that structure placement
differs per lobby. **The one factual error found is the Nucleus constant — see the boxed note in §2.2.**

---

## 8. Open questions — only killer560 can settle these

1. **The Nucleus constant.** `CrystalHollowsMapScreen.NUCLEUS_X/Y/Z = 495.5, 106, 556.5` is Emissary Sisko's
   position, not the Nucleus's (`512, 106, 512`). Every position on the existing map is plotted relative to
   it. Fix it and the map shifts by ~(−16.5, 0, +44.5) — including any waypoints he has already saved. Fix,
   leave, or migrate saved waypoints?
2. **Which metal-detector approach?** Three real options, in increasing order of borrowed data (§4.3):
   (a) **Skyblocker's slow path** — brute-force every block at the player's Y at the reported distance, then
   intersect across readings. Needs **no dataset at all**, works today, costs a few extra readings.
   (b) **Skyblocker's fast path** — anchor on a Keeper armour-stand nametag, then test 43 known chest
   offsets. Near-instant, but the 43 offsets are Skyblocker's data (licence question, §7.8).
   (c) Build our own offset list by logging finds over a few runs, then use (b)'s method with our own data.
   Recommendation as a fact rather than a preference: **(a) is the only one that is certainly available to
   us, and it degrades into (b) automatically if we ever collect the offsets.** His call.
3. **Do the anchors survive on MC 26.1.2?** Both jars target 1.21.11. Skyblocker's Keeper-nametag anchor is
   the more robust; SkyHanni's QUARTZ_STAIRS+BARRIER block pattern is the fragile one. And any borrowed
   chest offsets are only valid if Hypixel has not moved them. One in-game check settles all three.
4. **Is the distance 3D or horizontal?** The two mods imply different things (§4.3) — SkyHanni measures 3D,
   Skyblocker's slow path generates candidates only at the player's own Y. Likeliest answer is 3D, but it is
   an inference. Ten-second check: stand directly above a known treasure and see whether the counter reads
   ~0 or reads the vertical offset.
5. **What is Boss Corleone's exact nametag?** No approved source has it — not the wiki, and none of the five
   mods read one (Skyblocker has a map marker with no trigger; QUOI and Odin find his structures by block
   signature, never the entity). Without it there is no
   "is he alive right now" detection at all, and `[Lv200] Boss Corleone` is a guess. One screenshot settles
   it. Note the fallback the mods imply: find **Corleone Hideout / Corleone Lakefront** by structure and
   park there, which matches the wiki's own statement that the player must be moving near his spawn point.
6. **Commission text — ANSWERED.** Skyblocker reads commissions from the **tab list** as
   `<name>: <progress>`, with a finished one reading progress `DONE`; the GUI title is `Commissions` and its
   lore marker is `COMPLETED`; the chat line is
   `^([\w' ]+) Commission Complete! Visit the King to claim your rewards!$`. See §6.3. The only thing left
   is confirming those still hold on 26.1.2. Left in the list so it is clear it was asked and settled.
7. **Lobby "day" — ANSWERED on the mechanism, one number still open.** It is the vanilla world day count:
   `dayTime / 24000` (QUOI), or `Timelines.OVERWORLD_DAY.getPeriodCount()` on 26.x (Skyblocker) — the
   per-lobby age, not the SkyBlock calendar date, and not parsed from any text. See §3.4 and §7.6.
   **Still open:** the wiki says a lobby force-closes on **day 35**, Skyblocker's source comment says
   **26 days**. One of the two is stale, and it sets the sensible range for the filter. Which is right?
8. **Royal Pigeon detection.** Item-presence is not a sound proxy for the capability, because trading the
   Royal Pigeon to Queen Mismyla for her Abiphone contact *upgrades* the player while removing the item.
   Does he want: (a) scan for `ROYAL_PIGEON` only and accept a false negative for Abiphone users, (b) also
   detect the Abiphone contact somehow, or (c) drop the detection and just try the action and read the
   failure? His stated requirement was "detect rather than ask" — but (a) will be wrong for some players,
   and (c) is the only option that is actually correct for everyone.
9. **Is there a lever at the back of the Jungle Temple that teleports you out?** He described one; the wiki
   mentions no lever, button, plate or teleporter anywhere on the Jungle Temple page, and none of the five
   mods reference one. Either the wiki is incomplete or he is thinking of something else. If a route is
   built around it, it needs confirming.
10. **How hard a quadrant boundary does he want?** Which quadrant is which is now settled four times over
    (§3.3), but the mods disagree on where the line is: Skyblocker and SkyHanni use hard planes at 512/513,
    QUOI and Odin use an overlapping ±64 band and treat it only as a rejection filter. And Skyblocker draws
    no computed biome border at all — it ships a pre-rendered map image instead. Hard rectangles, a fuzzy
    band, a map image, or no drawn biome boundary (letting the sidebar name it, as the current code does)?
11. **World bounds — effectively settled, confirm the intent.** `202/31/202 → 823/188/823` is hardcoded in
    Skyblocker's `checkInCrystals` and QUOI's map, and reproduces the wiki's stated `621 x 157 x 621`. Fine
    to hardcode, citing the mods rather than the wiki? (Skyblocker itself is inconsistent by one block —
    its zone AABBs use 201/824 — and SkyHanni uses a looser 0..1024, so "confirmed" means two sources, not
    unanimity.)
12. **Does anything actually "catch" you in the Goblin Queen's Den?** The wiki describes no such mechanic,
    but **SkyHanni's chat filter says otherwise**: it matches `§c[GUARD]` messages and the exit line
    *"Whew! That was a close one, better get out of here..."* (§7.4). So there **is** a guard mechanic the
    wiki does not document. What actually happens when a guard catches you — teleport out, lose King's
    Scent, damage? Needs describing before a route assumes there is no fail state.
13. **Scavenged Item drop rate.** The Metal Detector page says 18% and the Scavenged Items page says up to
    45% with a Mole Pet. The two are not reconciled on the wiki. Only matters if a tracker quotes an expected
    run length.

### A note on confidence

Two independent passes were made over the SkyHanni bytecode and they **disagreed on a few of the colour-code
glyphs and on which RepoPattern key held which regex** in `CrystalNucleusChatFilter` (§7.4) — e.g. one pass
read `§5§l?{64}` where the other read `§5§l❉{64}`, and the `crystal.collected.id` / `crystal.collected.count`
key names were swapped between them. The *shapes* of the patterns agreed in both passes; the key names and
the exact special characters did not. **Treat every §-code string in this document as a strong hint, not as
something to paste into a regex.** Capture the real line in game before writing a matcher against it.

Nothing in this document has been tested against a running game. It is a reading of the wiki and of six
other mods: Skyblocker and QUOI as real source (the two that matter), NoammAddons and Devonian as real
source (both turned out to have no Crystal Hollows code at all), and SkyHanni and Odin from disassembled
bytecode.
