#!/bin/sh
# SUPER LONG Interactive Map trips: the floor graph against the exact search (LongBench.java for the -D options).
#   tools/bench/long.sh -Dfloors=4 -Dtrips=6
set -e
cd "$(dirname "$0")/../.."
G="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
GSON=$(find "$G/com.google.code.gson" -name 'gson-2*.jar' ! -name '*sources*' | sort | tail -1)
SLF=$(find "$G/org.slf4j/slf4j-api" -name 'slf4j-api-2*.jar' ! -name '*sources*' | sort | tail -1)
SEP=":"
case "$(uname -s)" in
  *NT*|*MINGW*|*MSYS*) SEP=";"; GSON=$(cygpath -w "$GSON"); SLF=$(cygpath -w "$SLF") ;;
esac
rm -rf build/longbench
javac -nowarn -proc:none -cp "$GSON$SEP$SLF" -d build/longbench \
  $(find tools/layoutsim/stubs -name '*.java') \
  src/main/java/com/killer560/hub/roomsim/SimFloorLayout.java \
  src/main/java/com/killer560/hub/roomsim/RoomDoors.java \
  src/main/java/com/killer560/hub/roomsim/SimWitherDoors.java tools/layoutsim/LayoutSim.java \
  src/main/java/com/killer560/hub/livemap/autoclear/EtherSearch.java \
  src/main/java/com/killer560/hub/livemap/autoclear/WarpGraph.java \
  src/main/java/com/killer560/hub/livemap/autoclear/FloorGraphs.java \
  src/main/java/com/killer560/hub/livemap/autoclear/ExactWarpSearch.java \
  tools/bench/EtherSearchBench.java tools/bench/FloorBench.java tools/bench/ExactBench.java tools/bench/LongBench.java
java -Xmx6g "$@" -cp "build/longbench$SEP$GSON$SEP$SLF" LongBench src/main/resources/assets/killer560smod/rooms
