# Inventory Sorter: why every sort uses the fewest clicks

killer560, 2026-11-01: "double check that any move you make is the most optimized method that there is no way to do it in
less clicks." This is the argument, and the exhaustive check that backs it. Code: `invsort/InvSortPlanner.java`; check:
`tools/invsort-optimality/InvSortOptimalityCheck.java`.

## The clicks

A sort only rearranges whole stacks. Two clicks move a whole stack without merging or splitting it:

- **Left click** (`PICKUP`) on a slot exchanges the cursor with that slot (pick up, put down, or swap).
- **Number key** (`SWAP`) over a slot, cursor empty, exchanges that slot with hotbar slot N. The vanilla client only
  sends it with an empty cursor, so the sorter never sends it otherwise.

So every click is one exchange (a transposition), and every exchange involves a **hub**: the cursor, or a hotbar slot.

## Fixed assignment: the minimum is a sum over cycles

Fix which stack ends in which slot. That is a permutation of the positions (the 36 slots plus the cursor, which is empty
at the start and the end), and it splits into cycles. Each exchange changes the number of cycles by exactly one (it joins
two or splits one), so a cycle of length L needs at least L - 1 exchanges.

A cycle with **no hub** in it needs at least L + 1. None of its positions can be touched except by an exchange with a hub,
and the hub is in another cycle, so the first exchange that touches it JOINS it to another cycle (cycle count down by
one); that join has to be undone by one more split. Each join involves at most one untouched hub-less cycle, so the extra
two clicks are paid once per hub-less cycle, never shared. A hub-less cycle holding an **empty slot** needs L: empties are
interchangeable, so the cursor's own emptiness stands in for that slot's and the cursor is in the cycle for free.

Per cycle, then: **L - 1** if a hotbar slot is in it, **L** if an empty slot is (and no hotbar slot), **L + 1** otherwise.
The planner reaches each bound exactly:

- hotbar in the cycle: L - 1 number-key swaps around that hotbar slot;
- empty slot in it: pick up the stack after the empty slot, click round the cycle, the last click drops into the empty
  slot (L clicks);
- neither: pick up, click round, click the first slot again (L + 1 clicks).

## Choosing the assignment

Copies of one item are interchangeable, slots the layout does not use accept anything, and empties are interchangeable,
so the planner also chooses the permutation:

- every wrong layout slot takes a copy of its item from: another wrong slot, a slot the layout does not use, or a slot
  already right whose copy is replaced by another (that last one is how a hotbar slot holding a matching copy brings a
  hub into a cycle that had none: `[A,-,B,A,-]` wanting slots 0=A, 2=A, 3=B is two swaps through slot 0, not three
  left clicks; the exhaustive check found it);
- the stack displaced from the last slot of a chain goes back to where the chain's first stack came from;
- every chain with no hub in it is spliced into a chain that has one, or into a hotbar slot the layout does not use, or,
  with neither, all such chains into one shared cycle (each splice saves one click: two hub-less cycles cost
  (a + 1) + (b + 1), joined they cost a + b + 1).

Which copy goes where is searched exhaustively (free slots of the same kind count once, so the search stays small); a
sort with very many copies of the same items can exhaust the search budget (20,000 choices in game), and the plan then is
still legal and at its own cycle bound but not proven the best choice of copies. The log says which
(`[InvSort] Plan ... proven minimal` / `search budget hit`).

When the layout wants more copies of an item than you have, the most slots possible are filled first, then the fewest
clicks.

## The exhaustive check

`InvSortOptimalityCheck` takes small inventories, every start and every layout, and runs a breadth-first search over
EVERY click a player can send, a superset of the two above:

- left click, including merging onto a matching stack;
- number-key swap with any hotbar slot;
- shift-click (merges into matching stacks of the other section first, then the first empty slot);
- double-click gather (pulls every matching stack onto the cursor).

One item (A) is stackable, so merges and gathers really happen. For every instance the planner's plan must be legal, fill
as many layout slots as any click sequence can, and be exactly as long as the search's minimum. Result
(2026-11-01, `killer560s-mod-logs/invsort-optimality-1101.txt`):

```
n=5 hotbar=2: 1048576 instances (790440 needing clicks, up to 4), every plan legal and exactly minimal
n=5 hotbar=1: 1048576 instances (790440 needing clicks, up to 5), every plan legal and exactly minimal
n=4 hotbar=0: 65536 instances (40056 needing clicks, up to 5), every plan legal and exactly minimal
n=4 hotbar=4: 65536 instances (40056 needing clicks, up to 3), every plan legal and exactly minimal
n=6 hotbar=2: 531441 instances (422534 needing clicks, up to 5), every plan legal and exactly minimal
n=6 hotbar=0: 531441 instances (422534 needing clicks, up to 7), every plan legal and exactly minimal
36 slots / 9 hotbar: 2000 random inventories, every plan legal and at its bound
ALL PASS: 3293106 instances
```

Run it again after any change to the planner:

```
javac -d <out> src/main/java/com/killer560/hub/invsort/InvSortPlanner.java tools/invsort-optimality/InvSortOptimalityCheck.java
java -cp <out> InvSortOptimalityCheck
```

## What the model leaves out, and why that is safe

- **Right-click splits and drags** split a stack. A layout records one item per slot and the sorter never changes how
  many items a stack holds, so they are not moves it may make. (A split could fill two slots of one item from one stack;
  that changes your stacks, which is not what a sort is for.)
- **Merges, shift-clicks and gathers** are in the exhaustive search, and none beat the plan on any instance.
- **The 2x2 crafting grid and armor slots** are extra slots no layout uses: positions like a free empty slot, which the
  search covers (free empty slots never helped).
- **The offhand** (number key 40) would be one more hub. Hypixel's server is 1.8, which has no offhand, so the sorter
  never uses it.
- **Locked slots** (Item Protect's Lock In Place) and the SkyBlock Menu are never clicked and never fill another slot.

## Instant (0-tick)

The plan is the same; Instant sends all of it in one client tick on the inventory menu without opening any screen (the
player's own inventory has no open packet). GrimAC 2.3.74's container checks were read from its classes before choosing
this: PacketOrderA (pickup and shift-click in one tick) applies only to 1.12.2 and older clients; MultiActionsC (a click
while the last input moves, sprints or sneaks) is why every run waits for a still input first; BadPacketsP and CrashD/F
check click values, which a plan's clicks always have right. Nothing in it counts clicks per tick. Hypixel's own checks are
not GrimAC's: see docs/FEATURES.md and the report for what is and is not evidenced.
