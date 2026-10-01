# Sim + puzzle solvers — 2026-10-01 round

Working notes for the Discord post and the features Doc. `docs/SIM.md` has the long version of every item.

---

## The headline

**One line in `SimBuilder` broke nine of the eleven puzzle solvers in the sim.**

It published each room's clay corner (computed for the *database* rotation) next to the *paste* rotation. Those
two differ by the capture's own quarter turn in 88 of the 122 identifiable rooms, so every solver got a correct
corner with a rotation a quarter or a half turn off it — and every relative coordinate came out spun about that
corner.

Decoded off the captures:

| Room | Capture turn | Was published as |
|---|---|---|
| Water Board | 270 | 0 |
| Creeper Beams | 0 | 0 |
| Tic Tac Toe | 180 | 0 |
| Boulder | 270 | 0 |
| Quiz | 180 | 0 |
| Three Weirdos | 270 | 0 |
| Ice Fill | 270 | 0 |
| Ice Path | 180 | 0 |
| Teleport Maze | 0 | 0 |
| Higher Blaze | 270 | 0 |
| Lower Blaze | 90 | 0 |

Only Creeper Beams and Teleport Maze — the two canonical captures — could ever have worked. Every one of those
turns independently matches what `bestAnchor` measured against the pasted blocks, and Secret Waypoints always
computed it correctly, which is why the *waypoints* landed while the solvers did not.

---

## Discord post (ready to paste)

> **Dungeon Sim — puzzle solvers now actually work in there**
>
> Found the root cause of "none of my solvers work in the sim": the sim was telling the live map each room's
> corner with the wrong rotation. Nine of the eleven puzzle rooms were affected — only Creeper Beams and
> Teleport Maze happened to be unaffected. Fixed, and the map now also follows a puzzle's own furniture when the
> recovered capture turn disagrees with it.
>
> On top of that, this round:
> • **Auto puzzles** — the sim's Terminator was missing the one lore line (`Shortbow: Instantly shoots!`) that
>   Auto Creeper Beams, Auto Ice Path and Auto Blaze gate their shot on. All three aimed and then declined to
>   fire; that's why Auto Blaze just looked at the middle blaze.
> • **Auto Creeper Beams** also needed the elder-guardian hit sound the real room reports progress with, and its
>   "am I on the platform" check was a hardcoded y 75.
> • **Creeper Beams** — holding the first lantern of a pair turned it to prismarine, which your own solver reads
>   as *the failure state*. A correct first shot was painting the pair red. The hold is drawn now instead.
> • **Quiz / Three Weirdos** — the question was being announced from up to 22 blocks away, i.e. from the
>   corridor. The solver armed on it, then the room change wiped it. Gated on the live map naming the room,
>   held steady for three ticks. Auto Quiz now presses a pillar *button* instead of the pillar.
> • **Teleport Maze** — the start pad was the only pad nothing indexed, and it's the only way into a sealed
>   maze. Teleports also land at the half-block height your solver recognises.
> • **Tic Tac Toe** — marks were painted on the wall behind the buttons, which is the one place the solver never
>   looks. They're on the cell now, and an unplayed cell is its button.
> • **Boulder** — a boulder is a three-block plank column; the pattern write and the push both treated it as one
>   block. `BoulderSolverFeature` was also reading the floor at Hypixel's height.
> • **Ice Fill** — that capture sits exactly one block low (244/244 path tiles land on ice at −1, 0/244 at 0).
>   The sim measured that already; it now publishes it so the solver's line lands on the ice.
> • **Interactive map etherwarp** — stopped it pathing onto the roof of the dungeon, and a room click now goes
>   anywhere in the clicked tile that's fastest instead of to one exact block.
> • **Auto Routes** — a route recorded in the sim stored a shifted height as if it were relative, so it was
>   wrong on Hypixel *and* wrong in the sim's own next build. Both directions carry the shift now.

---

## Google Doc — needs regenerating by you

`docs/FEATURES.md` is updated (Dungeon Sim state paragraph, Quiz Solver, Auto Puzzles). The published Doc
**Killer560's Mod - Features** was last modified **2026-09-28**, so it is behind by this round and the two
before it.

I could not regenerate it from the cloud session: the Drive tooling available here can change a file's title
and folder but not its contents. Regenerate it the way you normally do, from the current `docs/FEATURES.md`.

(Reminder, from CLAUDE.md: only the `/e/2PACX-…` published link is public — the edit link is never published.)

## Discord bot — needs running by you

The bot token lives on your machine only (see CLAUDE.md for where). A cloud session cannot read it and should
not, so posting the block above is yours to do.
