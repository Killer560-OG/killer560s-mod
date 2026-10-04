#!/bin/sh
# Times the Interactive Map's etherwarp search on a floor of the shipped room captures. No game needed.
#   tools/bench/run.sh                         flat-array grid (the search alone)
#   tools/bench/run.sh -Dsectioned=true        the game's section-table grid, sections already cached
#   tools/bench/run.sh -Dsectioned=true -Dcold=true   every section filled fresh each click (a lower bound)
set -e
cd "$(dirname "$0")/../.."
rm -rf build/bench
javac -d build/bench src/main/java/com/killer560/hub/livemap/autoclear/EtherSearch.java tools/bench/EtherSearchBench.java
java "$@" -cp build/bench EtherSearchBench src/main/resources/assets/killer560smod/rooms
