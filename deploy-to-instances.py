"""Copies freshly built jars into every Prism instance, matching each instance's Minecraft version and variant.

killer560 (2026-09-27): "make sure whenever you push a new mod update it goes to all instances on the
propper version [...] I still want it going there too" - including the Dungeons instance, which used to be
left alone deliberately.

Jars are named killer560smod-<mod>-<mc>-<cheat|legit>.jar (build.gradle's archiveClassifier), and one gradle
run produces only one Minecraft version's jars. So a deploy after building 26.1.2 updates the 26.1.2
instances, and the 26.2 instances are reported as "no build in <dir>" until a 26.2 build is pointed at with
--jars-dir (or lands in build/libs).

Rules it will not break:

  * It never writes over, or deletes from, a RUNNING instance. A running instance gets
    `<new jar name>.pending` instead and is named in the output for a manual swap after closing.
  * It keeps each instance on the variant it already has, read out of the installed jar with javap. An
    instance with no mod jar is skipped and reported, never given one uninvited: CLAUDE.md says which
    instances get the 26.2 build but not which variant, so there is nothing to read a variant from.
  * If it cannot tell which instances are running, it refuses to write anything.

Usage:  python deploy-to-instances.py [--dry-run] [--jars-dir DIR]

After `./gradlew build` for 26.2, the 26.1.2 jars are gone from build/libs; keep each version's jars in
their own folder and run once per folder.
"""
import argparse
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys
import zipfile

INSTANCES = r"C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances"
REPO = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(REPO, "build", "libs")
BUILD_VARIANT_CLASS = "com/killer560/hub/BuildVariant.class"

# First 8 hex of md5(BuildVariant.class), per CLAUDE.md's Quirks and lessons.
KNOWN_HASHES = {"c7d5d8f5": "cheat+dev", "1d821df5": "legit+dev", "f8fc20a1": "legit+release"}
EXPECTED = {"cheat": "c7d5d8f5", "legit": "1d821df5"}


def mod_version():
    with open(os.path.join(REPO, "gradle.properties"), encoding="utf-8") as fh:
        for line in fh:
            if line.strip().startswith("mod_version="):
                return line.split("=", 1)[1].strip()
    sys.exit("mod_version not found in gradle.properties")


def jar_name(mod, mc, variant):
    return "killer560smod-%s-%s-%s.jar" % (mod, mc, variant)


def _sha256(path):
    d = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            d.update(chunk)
    return d.digest()


def _same(a, b):
    """Byte-for-byte comparison. Size alone once said "already up to date" over a real update (2026-09-28)."""
    return _sha256(a) == _sha256(b)


def running_instances():
    """Instance folder names with a java/javaw whose command line runs inside them, or None if unknown.

    java.exe counts too: the gametest client runs as java.exe, and filtering on javaw alone once left every
    safeguard in run-scenario.ps1 inert."""
    try:
        res = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "Get-CimInstance Win32_Process -Filter \"Name='javaw.exe' OR Name='java.exe'\" | "
             "ForEach-Object { $_.CommandLine }"],
            capture_output=True, text=True, timeout=60)
    except Exception:
        return None
    if res.returncode != 0:
        return None
    ps = res.stdout
    out = set()
    for name in os.listdir(INSTANCES):
        # Match the folder inside a real path, not as a bare substring: plain "26.1.2" is a substring of
        # "26.1.2 (Mod Only Test)", and matching loosely reported an idle instance as running.
        if name and ("instances\\" + name + "\\") in ps:
            out.add(name)
    return out


def mc_version(folder):
    pack = os.path.join(folder, "mmc-pack.json")
    if not os.path.exists(pack):
        return None
    try:
        with open(pack, encoding="utf-8") as fh:
            for c in json.load(fh).get("components", []):
                if c.get("uid") == "net.minecraft":
                    return c.get("version")
    except Exception:
        return None
    return None


def variant_of(jar):
    """Which build a jar is, read with javap rather than guessed.

    The first version of this script compared file sizes and got it backwards: an old jar is closer in size
    to today's legit build simply because the mod has grown since. javap prints the generated flag itself."""
    try:
        out = subprocess.run(["javap", "-constants", "-cp", jar, "com.killer560.hub.BuildVariant"],
                             capture_output=True, text=True, timeout=120).stdout
    except Exception:
        return None
    for line in out.splitlines():
        if "CHEAT_FEATURES_ENABLED" in line:
            return "cheat" if "true" in line else "legit"
    return None


def variant_hash(jar):
    """First 8 hex of md5 of BuildVariant.class inside the jar (md5, not CRC32 - see CLAUDE.md)."""
    try:
        with zipfile.ZipFile(jar) as z:
            return hashlib.md5(z.read(BUILD_VARIANT_CLASS)).hexdigest()[:8]
    except Exception:
        return None


def describe_hash(jar, variant):
    h = variant_hash(jar)
    if h is None:
        return "md5 ?"
    tag = KNOWN_HASHES.get(h, "UNKNOWN")
    flag = "" if h == EXPECTED.get(variant) else "  <-- expected %s for %s" % (EXPECTED.get(variant), variant)
    return "md5 %s (%s)%s" % (h, tag, flag)


def main():
    global INSTANCES
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true", help="report what would change, change nothing")
    ap.add_argument("--jars-dir", default=BUILD, help="where the built jars are (default: build/libs)")
    ap.add_argument("--instances-dir", default=INSTANCES, help=argparse.SUPPRESS)  # for testing on a copy
    args = ap.parse_args()
    INSTANCES = args.instances_dir
    dry = args.dry_run
    jars_dir = os.path.abspath(args.jars_dir)
    if not os.path.isdir(jars_dir):
        sys.exit("jars dir does not exist: %s" % jars_dir)
    mod = mod_version()

    live = running_instances()
    if live is None:
        if not dry:
            sys.exit("could not list running java processes - refusing to write, since a running instance "
                     "could not be told apart from an idle one")
        print("(could not list running java processes; dry run treats every instance as idle)")
        live = set()

    lines = []  # (instance, action, detail)
    for name in sorted(os.listdir(INSTANCES)):
        folder = os.path.join(INSTANCES, name)
        mods = os.path.join(folder, "minecraft", "mods")
        if not os.path.isdir(mods):
            continue
        running = name in live
        mc = mc_version(folder)
        tag = "%s [MC %s%s]" % (name, mc, ", RUNNING" if running else "")
        installed = sorted(glob.glob(os.path.join(mods, "killer560smod*.jar")))

        if not installed:
            lines.append((tag, "skipped", "no mod jar installed - not adding one uninvited"))
            continue
        if not mc:
            lines.append((tag, "skipped", "could not read the Minecraft version from mmc-pack.json"))
            continue

        variants = {j: variant_of(j) for j in installed}
        if None in variants.values():
            bad = [os.path.basename(j) for j, v in variants.items() if v is None]
            lines.append((tag, "skipped", "could not read the build variant of %s - left alone" % ", ".join(bad)))
            continue
        if len(set(variants.values())) > 1:
            lines.append((tag, "skipped", "jars of BOTH variants installed (%s) - sort that out by hand"
                          % ", ".join(os.path.basename(j) for j in installed)))
            continue
        variant = next(iter(variants.values()))

        target_name = jar_name(mod, mc, variant)
        target = os.path.join(mods, target_name)
        src = os.path.join(jars_dir, target_name)
        others = [j for j in installed if os.path.normcase(j) != os.path.normcase(target)]
        now_jar, pending_jar = installed[0], None

        if not os.path.exists(src):
            lines.append((tag, "no build", "%s not in the jars dir; left on %s"
                          % (target_name, ", ".join(os.path.basename(j) for j in installed))))
        elif os.path.exists(target) and not others and _same(target, src):
            lines.append((tag, "up to date", target_name))
            now_jar = target
        elif running:
            pending = target + ".pending"
            pending_jar = src if dry else pending
            if os.path.exists(pending) and _same(pending, src):
                lines.append((tag, "staged", "%s.pending already current" % target_name))
            else:
                if not dry:
                    shutil.copyfile(src, pending)
                lines.append((tag, "staged", "%s.pending - swap after closing the game" % target_name))
            if others:
                lines.append((tag, "on swap", "also delete %s" % ", ".join(os.path.basename(j) for j in others)))
        else:
            if not dry:
                shutil.copyfile(src, target)
                for j in others:
                    os.remove(j)
            lines.append((tag, "installed", target_name))
            now_jar = src if dry else target
            for j in others:
                lines.append((tag, "removed", os.path.basename(j)))

        # Stale .pending: older than the jar it would replace. Never touched in a running instance.
        if running:
            for p in sorted(glob.glob(os.path.join(mods, "killer560smod*.pending"))):
                if os.path.normcase(p) != os.path.normcase(target + ".pending"):
                    lines.append((tag, "LEFT", "%s (instance running; rerun after closing)" % os.path.basename(p)))
        else:
            newest = max((os.path.getmtime(j) for j in glob.glob(os.path.join(mods, "killer560smod*.jar"))),
                         default=None)
            if dry and os.path.exists(src) and not os.path.exists(target):
                newest = os.path.getmtime(src)  # what the install above would have produced
            for p in sorted(glob.glob(os.path.join(mods, "killer560smod*.pending"))):
                if newest is not None and os.path.getmtime(p) < newest:
                    if not dry:
                        os.remove(p)
                    lines.append((tag, "removed", "stale %s" % os.path.basename(p)))
                else:
                    lines.append((tag, "LEFT", "%s is newer than the installed jar - promote it by hand"
                                  % os.path.basename(p)))

        # Hash of what is on disk now (in a dry run, what would be), plus any staged jar.
        lines.append((tag, "hash", "%s: %s" % (os.path.basename(now_jar), describe_hash(now_jar, variant))))
        if pending_jar:
            lines.append((tag, "hash", "pending: %s" % describe_hash(pending_jar, variant)))

    if dry:
        print("DRY RUN - nothing written")
    print("jars dir: %s\n" % jars_dir)
    last = None
    for inst, action, detail in lines:
        if inst != last:
            print(inst)
            last = inst
        print("    %-10s %s" % (action, detail))


if __name__ == "__main__":
    main()
