"""Copies the freshly built jars into every Prism instance on a matching Minecraft version.

killer560 (2026-09-27): "make sure whenever you push a new mod update it goes to all instances on the
propper version [...] I still want it going there too" - including the Dungeons instance, which used to be
left alone deliberately.

Two rules it will not break:

  * It never writes over a RUNNING instance. Swapping a jar under a live game is how you get a corrupted
    mod list or a half-loaded class, so a running instance gets `<name>.jar.pending` instead and is named in
    the output for a manual swap after closing.
  * It keeps each instance on the variant it already had. Legit Test is a legit build and the rest are cheat
    builds, and quietly handing the legit instance a cheat jar would defeat the point of having it.

Usage:  python deploy-to-instances.py [--dry-run]
"""
import json
import os
import shutil
import subprocess
import sys

INSTANCES = r"C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances"
BUILD = r"C:/Users/Hunter/killer560s-mod/build/libs"
JAR_NAME = "killer560smod-1.1.0.jar"
CHEAT = os.path.join(BUILD, "killer560smod-1.1.0-cheat.jar")
LEGIT = os.path.join(BUILD, "killer560smod-1.1.0-legit.jar")

# The mod declares minecraft ~26.1, so only 26.1.x instances get it.
SUPPORTED_PREFIX = "26.1"


def running_instances():
    """Instance folder names that currently have a javaw running, read from the command line Prism used."""
    out = set()
    try:
        ps = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "Get-CimInstance Win32_Process -Filter \"Name='javaw.exe'\" | "
             "ForEach-Object { $_.CommandLine }"],
            capture_output=True, text=True, timeout=60).stdout
    except Exception:
        return out
    for name in os.listdir(INSTANCES):
        # Match the folder inside a real path, not as a bare substring: plain "26.1.2" is a substring of
        # "26.1.2 (Mod Only Test)", and matching loosely reported an idle instance as running.
        if name and ("instances\\" + name + "\\") in ps:
            out.add(name)
    return out


def mc_version(folder):
    pack = os.path.join(folder, "mmc-pack.json")
    if not os.path.exists(folder) or not os.path.exists(pack):
        return None
    try:
        for c in json.load(open(pack, encoding="utf-8")).get("components", []):
            if c.get("uid") == "net.minecraft":
                return c.get("version")
    except Exception:
        return None
    return None


def variant_of(jar):
    """Which build an instance already has, read out of the jar rather than guessed.

    The first version compared file sizes against the freshly built jars, and got it backwards: an instance
    carrying a jar from a week ago is closer in size to today's LEGIT build than to today's cheat build simply
    because the mod has grown since. javap reports the generated flag itself, which cannot drift."""
    if not os.path.exists(jar):
        return None
    try:
        out = subprocess.run(["javap", "-constants", "-cp", jar, "com.killer560.hub.BuildVariant"],
                             capture_output=True, text=True, timeout=120).stdout
    except Exception:
        return None
    for line in out.splitlines():
        if "CHEAT_FEATURES_ENABLED" in line:
            return "cheat" if "true" in line else "legit"
    return None


def main():
    dry = "--dry-run" in sys.argv
    for src in (CHEAT, LEGIT):
        if not os.path.exists(src):
            sys.exit("missing build output: %s - run ./gradlew build and build -PcheatBuild=true first" % src)

    live = running_instances()
    deployed, staged, skipped = [], [], []
    for name in sorted(os.listdir(INSTANCES)):
        folder = os.path.join(INSTANCES, name)
        mods = os.path.join(folder, "minecraft", "mods")
        if not os.path.isdir(mods):
            continue
        version = mc_version(folder)
        if not version or not version.startswith(SUPPORTED_PREFIX):
            skipped.append("%s (MC %s - the mod declares ~26.1)" % (name, version))
            continue
        target = os.path.join(mods, JAR_NAME)
        if not os.path.exists(target):
            skipped.append("%s (no mod jar installed - not adding one uninvited)" % name)
            continue
        which = variant_of(target)
        if which is None:
            skipped.append("%s (could not read its build variant - left alone rather than guessed)" % name)
            continue
        src = LEGIT if which == "legit" else CHEAT
        if name in live:
            dest = target + ".pending"
            staged.append("%s (%s) - RUNNING, staged as %s" % (name, which, os.path.basename(dest)))
        else:
            dest = target
            deployed.append("%s (%s)" % (name, which))
        if dest == target and os.path.exists(target)                 and os.path.getsize(target) == os.path.getsize(src):
            deployed.pop()
            skipped.append("%s (%s - already up to date)" % (name, which))
            continue
        if not dry:
            shutil.copyfile(src, dest)

    print("deployed to:" if deployed else "deployed to: none")
    for d in deployed:
        print("   ", d)
    if staged:
        print("STAGED, swap after closing the game:")
        for s in staged:
            print("   ", s)
    if skipped:
        print("skipped:")
        for s in skipped:
            print("   ", s)


if __name__ == "__main__":
    main()
