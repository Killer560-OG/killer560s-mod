#!/bin/sh
# Regression test for the Interactive Map's floor-wide etherwarp planner (WarpGraph). Exits non-zero if the warps,
# the success rate, the minimality against the every-landing reference or the warm click time got worse than
# tools/bench/floorbench-expect.txt allows, or if any planned hop does not land where the next one starts.
# Seeded, so the warp counts are the same on every run of the same code; only the times move.
set -e
cd "$(dirname "$0")/../.."
EXPECT=tools/bench/floorbench-expect.txt
echo "== whole floors: graph vs the old room-by-room planner"
sh tools/bench/floor.sh -Dfloors=3 -Dclicks=150 -Drefclicks=0 -Dcoldclicks=0 -Dlazyclicks=0 -Dselfclicks=40 \
  -Dthreads=4 -Dcheck=$EXPECT "$@" | grep -E "^  (old|new, warm)|SELF-CHECK|REGRESSION|did not"
echo "== small floors (3x2 cells): graph vs every landing with the full aim"
sh tools/bench/floor.sh -Dfloors=4 -Dclicks=100 -Drefclicks=100 -Dcoldclicks=0 -Dlazyclicks=0 -Dselfclicks=40 \
  -Dthreads=8 -Dsmallw=3 -Dsmallh=2 -Dreffull=true -Dold=false -Dcheck=$EXPECT "$@" \
  | grep -E "^  (ref|new on)|new - ref|SELF-CHECK|REGRESSION"
