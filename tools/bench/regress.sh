#!/bin/sh
# Regression test for the Interactive Map's floor-wide etherwarp planner (WarpGraph). Exits non-zero if the warps,
# the success rate, the minimality against the every-landing reference or the warm click time got worse than
# tools/bench/floorbench-expect.txt allows, or if any planned hop does not land where the next one starts.
# Seeded, so the warp counts are the same on every run of the same code; only the times move.
#
# Each run's output goes to a file and its exit status is checked BEFORE the summary is grepped out of it. Piped
# straight into grep (as this was until 2026-10-04), the script's status was grep's: when the bench stopped
# compiling after the path-to-blood merge, regress.sh printed two javac errors and still exited 0.
set -e
cd "$(dirname "$0")/../.."
EXPECT=tools/bench/floorbench-expect.txt
OUT=build/regress-out.txt
mkdir -p build

run() {
  label="$1"; pattern="$2"; shift 2
  echo "== $label"
  if sh tools/bench/floor.sh "$@" > "$OUT" 2>&1; then
    grep -E "$pattern" "$OUT" || true
  else
    status=$?
    cat "$OUT"
    echo "REGRESSION: the bench did not run to the end (exit $status)"
    exit 1
  fi
  if ! grep -q "SELF-CHECK" "$OUT"; then
    echo "REGRESSION: no SELF-CHECK line - the bench did not get as far as checking anything"
    exit 1
  fi
}

run "whole floors: graph vs the old room-by-room planner" "^  (old|new, warm)|SELF-CHECK|REGRESSION|did not" \
  -Dfloors=3 -Dclicks=150 -Drefclicks=0 -Dcoldclicks=0 -Dlazyclicks=0 -Dselfclicks=40 \
  -Dthreads=4 -Dcheck=$EXPECT "$@"
run "small floors (3x2 cells): graph vs every landing with the full aim" "^  (ref|new on)|new - ref|SELF-CHECK|REGRESSION" \
  -Dfloors=4 -Dclicks=100 -Drefclicks=100 -Dcoldclicks=0 -Dlazyclicks=0 -Dselfclicks=40 \
  -Dthreads=8 -Dsmallw=3 -Dsmallh=2 -Dreffull=true -Dold=false -Dcheck=$EXPECT "$@"
echo "regress.sh: all checks passed"
