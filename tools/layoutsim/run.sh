#!/bin/sh
# Runs the sim's real floor layout (SimFloorLayout + RoomDoors) outside the game. See LayoutSim.java.
#   tools/layoutsim/run.sh -Droomdata=.../killer560smod-roomdata/rooms-modern.json [-Drooms=DIR]
#                          [-Drecent=.../killer560smod-sim-recent.json] [-Dnorecency=true] [-Dcorrupt=A,B] [floors]
set -e
cd "$(dirname "$0")/../.."
G="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
GSON=$(find "$G/com.google.code.gson" -name 'gson-2*.jar' ! -name '*sources*' | sort | tail -1)
SLF=$(find "$G/org.slf4j/slf4j-api" -name 'slf4j-api-2*.jar' ! -name '*sources*' | sort | tail -1)
SEP=":"
case "$(uname -s)" in
  *NT*|*MINGW*|*MSYS*) SEP=";"; GSON=$(cygpath -w "$GSON"); SLF=$(cygpath -w "$SLF") ;;
esac
FLOORS=500
for a in "$@"; do
  shift
  case "$a" in
    -D*) set -- "$@" "$a" ;;   # kept, quoting intact (paths with spaces)
    *) FLOORS="$a" ;;
  esac
done
rm -rf build/layoutsim
javac -nowarn -proc:none -cp "$GSON$SEP$SLF" -d build/layoutsim \
  $(find tools/layoutsim/stubs -name '*.java') \
  src/main/java/com/killer560/hub/roomsim/SimFloorLayout.java \
  src/main/java/com/killer560/hub/roomsim/RoomDoors.java tools/layoutsim/LayoutSim.java
java "$@" -cp "build/layoutsim$SEP$GSON$SEP$SLF" LayoutSim src/main/resources/assets/killer560smod/rooms "$FLOORS"
