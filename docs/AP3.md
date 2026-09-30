# AP3 - quirks and lessons

Route nodes, the align planners and their measured physics. Split out of the project `CLAUDE.md` on
2026-09-29 when it passed 300 lines.

- Interaction features must tick on `ClientTickEvents.START_CLIENT_TICK`, not `END_CLIENT_TICK`: END runs
  after the player's own movement packet, and GrimAC flags every resulting interaction as `Post`. Measured
  2026-09-27 — Breaker Aura drew 808 violations on END and zero on START; Secret Triggerbot 17 and 17.
  Fixed for Breaker Aura in `825f319`. Audited properly 2026-09-29: of 111 END registrations, exactly
  **three** reach a block/item/container packet — `Ap3Feature:95`, `AutoRoutesFeature:87`, `FastLeapFeature:105`
  — and about 17 more send only chat or a server command. The "about twenty" figure counted those. Separately,
  three features click from a RENDER FRAME, which is also after the movement packet: Goldor Triggerbot, Arrow
  Align and Auto I4. `ActionGate` does not help — `tryAct` returns immediately and the caller sends
  synchronously, so it arbitrates who acts and never changes ordering.
- An automated click must aim at a point on the block's real **surface**, from the eye, not at
  `Vec3.atCenterOf(pos)` with a fixed `Direction`. The centre is a point *inside* the block and no raycast
  produces it; GrimAC raised `PositionPlace` on every such click even at a distance the server accepted
  (2026-09-28). Use `util/BlockHits.surface`, and prefer skipping a tick to sending an impossible hit. Entity
  clicks are the same: aim at a point on the entity's box, which Arrow Align and Terminal Aura already do.
- AP3's align planners solve the YAW freely, so two entries in an action set differ only by the SIZE of the
  push and what they leave for the next tick (sprint, crouch) - a key pointing elsewhere is the same action at
  another yaw. All eighteen real key combinations produce just five sizes: 0, 0.13377 (non-sprinting straight
  key), 0.13650 (non-sprinting diagonal), 0.17390 (W) and 0.17745 (W+A), and only `fw > 0` sprints. Searching a
  near-duplicate costs |ACTS| to the power of the press count for nothing.
- **Align nodes are designed for 550-600 speed** on the Hypixel scale (killer560, 2026-09-28: "they should
  still align at lower speeds but the time isn't important"). So tune and benchmark at 550-600, and treat low
  speed as a CORRECTNESS check only - it must still land, it may take as long as it likes. This matters because
  every push the planner prices comes off the movement-speed attribute: at 550-600 Fast Align lands 100% of
  cases in 3 or 4 ticks, while at 100-450 it lands 89% with a tail out to 7. A constant tuned at walking pace
  is not tuned. The sim can be set to any Hypixel speed with `TestMap.speed(550)`.
- Align tick counts are bound by STOPPING, not by travel or by the solver. You must arrive under vanilla's 0.003
  zeroing line or the next tick slides you off the point, and friction alone takes ~8 ticks from top speed. A
  floor that charges the stop sits at 4.41 ticks against the planner's 4.52 (measured 2026-09-28), and 3 ticks
  is impossible for 72% of aligns at any tolerance. Tolerance is nearly free: Caleb's 3e-8 costs 0.09 of a tick
  over 1e-4. Do not accept a "make the align faster" task without re-deriving that floor first.
- **A node's angle was being quantised on every save.** `Ap3Store.writeNode` wrote yaw and pitch through
  `round(v, 1)`, so a restart moved every node's angle to the nearest tenth of a degree and the loss compounded
  (killer560, 2026-09-29: "it feels like the angles and stuff gets truncated whenever I close and restart").
  `Ap3EditScreen.fmt` did the same at two decimals, and it is read back on Save, so opening an editor and
  saving damaged the node - as did "Look from me". Store at 5-6 decimals, and print fields at full precision
  with the zeros trimmed.
- `Ap3Store` wrote `useItemId` inside `case LEAP`, so a USE node's recorded item was NEVER saved while the load
  path read it unconditionally. It worked until the game closed and then became "use whatever is in my hand".
  A field shared by several node types belongs outside the type switch.
- A step machine whose every phase sets the next step and RETURNS costs one client tick per phase whether or
  not it had anything to wait for. AP3's USE node spent four ticks before the click, six or seven with a swap
  ("my use item nodes come out like half a second late"). Only two waits are real: the server must see the new
  rotation before the use, and it must have acknowledged a hotbar change. Let the rest fall through in one tick.
