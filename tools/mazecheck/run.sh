#!/bin/sh
# Offline checks for the sim's Teleport Maze, no game needed.
#   MazeCheck: draws N pad pairings with TeleportMazeLinks (and with the pre-2026-10-04 random pairing, for
#   comparison) and counts closed loops, structural faults, and landings from which following diagonals never
#   reaches the centre. walkcheck.py: on the shipped capture, whether every pad-to-pad walk inside a chamber has a
#   way round (MazeWalk's rules) and how many a straight walk would run into a wall.
#   tools/mazecheck/run.sh [N]        default 100000
set -e
cd "$(dirname "$0")/../.."
rm -rf build/mazecheck
javac -nowarn -d build/mazecheck src/main/java/com/killer560/hub/roomsim/puzzles/TeleportMazeLinks.java \
  tools/mazecheck/MazeCheck.java
java -cp build/mazecheck MazeCheck "${1:-100000}"
python tools/mazecheck/walkcheck.py
