#!/bin/sh
# Runs the real RoomCaptureRotation and RoomTileAudit over the shipped captures, outside the game, and prints
# every room's roof-marker, lapis and secret evidence with the rotation chosen. See RotationAudit.java.
#   tools/layoutsim/rotation.sh -Droomdata=.../killer560smod-roomdata/rooms-modern.json [-Dverbose=true]
set -e
cd "$(dirname "$0")/../.."
G="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
GSON=$(find "$G/com.google.code.gson" -name 'gson-2*.jar' ! -name '*sources*' | sort | tail -1)
SLF=$(find "$G/org.slf4j/slf4j-api" -name 'slf4j-api-2*.jar' ! -name '*sources*' | sort | tail -1)
SEP=":"
case "$(uname -s)" in
  *NT*|*MINGW*|*MSYS*) SEP=";"; GSON=$(cygpath -w "$GSON"); SLF=$(cygpath -w "$SLF") ;;
esac
rm -rf build/rotationaudit
javac -nowarn -proc:none -cp "$GSON$SEP$SLF" -d build/rotationaudit \
  $(find tools/layoutsim/stubs tools/layoutsim/rotstubs -name '*.java') \
  src/main/java/com/killer560/hub/roomdatabase/RoomEntry.java \
  src/main/java/com/killer560/hub/roomsim/RoomCaptureRotation.java \
  src/main/java/com/killer560/hub/roomsim/RoomTileAudit.java \
  src/main/java/com/killer560/hub/roomsim/SimFloorLayout.java \
  src/main/java/com/killer560/hub/roomsim/RoomDoors.java \
  src/main/java/com/killer560/hub/roomsim/SimWitherDoors.java \
  tools/layoutsim/LayoutSim.java tools/layoutsim/RotationAudit.java
java "$@" -cp "build/rotationaudit$SEP$GSON$SEP$SLF" RotationAudit src/main/resources/assets/killer560smod/rooms
