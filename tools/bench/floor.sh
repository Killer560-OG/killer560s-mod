#!/bin/sh
# The Interactive Map's etherwarp planner on whole sim-style floors: old vs new vs a breadth-first reference over
# every landing. No game needed. See FloorBench.java for the method and the -D options.
#   tools/bench/floor.sh                                    5 floors x 300 clicks, reference on 100 a floor
#   tools/bench/floor.sh -Dcheck=tools/bench/floorbench-expect.txt   regression: exits 1 if warps/success worse
set -e
cd "$(dirname "$0")/../.."
G="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
GSON=$(find "$G/com.google.code.gson" -name 'gson-2*.jar' ! -name '*sources*' | sort | tail -1)
SLF=$(find "$G/org.slf4j/slf4j-api" -name 'slf4j-api-2*.jar' ! -name '*sources*' | sort | tail -1)
SEP=":"
case "$(uname -s)" in
  *NT*|*MINGW*|*MSYS*) SEP=";"; GSON=$(cygpath -w "$GSON"); SLF=$(cygpath -w "$SLF") ;;
esac
rm -rf build/floorbench
javac -nowarn -proc:none -cp "$GSON$SEP$SLF" -d build/floorbench \
  $(find tools/layoutsim/stubs -name '*.java') \
  src/main/java/com/killer560/hub/roomsim/SimFloorLayout.java \
  src/main/java/com/killer560/hub/roomsim/RoomDoors.java \
  src/main/java/com/killer560/hub/roomsim/SimWitherDoors.java tools/layoutsim/LayoutSim.java \
  src/main/java/com/killer560/hub/livemap/autoclear/EtherSearch.java \
  src/main/java/com/killer560/hub/livemap/autoclear/WarpGraph.java \
  src/main/java/com/killer560/hub/livemap/autoclear/FloorGraphs.java \
  tools/bench/EtherSearchBench.java tools/bench/FloorBench.java
java -Xmx4g "$@" -cp "build/floorbench$SEP$GSON$SEP$SLF" FloorBench src/main/resources/assets/killer560smod/rooms
