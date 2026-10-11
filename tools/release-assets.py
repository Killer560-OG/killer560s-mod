"""Writes SHA256SUMS.txt and commit.txt for a full GitHub release, after checking the jars are fit to ship.

Auto Update (2026-10-11) installs a full release only when it carries both files: SHA256SUMS.txt (sha256sum format,
one line per jar) and commit.txt (the 40-hex commit the jars were built from). Full releases are made by hand, so this
is the step that makes them. dev-build.yml does the same for the Beta channel's Latest Dev Build.

Usage:  python tools/release-assets.py DIST_DIR

DIST_DIR holds the four jars, each built with -Prelease=true (see CLAUDE.md, "Releases"). The script refuses unless:
  * there is exactly one jar per Minecraft version and variant, named killer560smod-<mod>-<mc>-<cheat|legit>.jar,
    and none is a -testing jar;
  * every jar's killer560smod-build.properties says release=true and names the same 40-hex commit.
Then it writes DIST_DIR/SHA256SUMS.txt and DIST_DIR/commit.txt. Attach all six files to the release.
"""
import hashlib
import os
import re
import sys
import zipfile

NAME = re.compile(r"^killer560smod-[0-9A-Za-z.+]+-(\d+(?:\.\d+)+)-(cheat|legit)\.jar$")


def build_info(jar):
    with zipfile.ZipFile(jar) as z:
        text = z.read("killer560smod-build.properties").decode("utf-8")
    out = {}
    for line in text.splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def sha256(path):
    d = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            d.update(chunk)
    return d.hexdigest()


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    dist = os.path.abspath(sys.argv[1])
    jars = sorted(f for f in os.listdir(dist) if f.endswith(".jar"))
    if not jars:
        sys.exit("no jars in %s" % dist)
    seen = {}
    commits = set()
    for name in jars:
        m = NAME.match(name)
        if not m:
            sys.exit("%s is not named killer560smod-<mod>-<mc>-<cheat|legit>.jar (testing jars never ship)" % name)
        key = (m.group(1), m.group(2))
        if key in seen:
            sys.exit("two jars for %s %s: %s and %s" % (key[0], key[1], seen[key], name))
        seen[key] = name
        info = build_info(os.path.join(dist, name))
        if info.get("release") != "true":
            sys.exit("%s is not a release build - rebuild it with -Prelease=true" % name)
        if info.get("testing") == "true":
            sys.exit("%s is a testing build" % name)
        if info.get("minecraft") != key[0] or info.get("variant") != key[1]:
            sys.exit("%s says minecraft=%s variant=%s inside" % (name, info.get("minecraft"), info.get("variant")))
        commit = info.get("commit", "")
        if not re.fullmatch(r"[0-9a-f]{40}", commit):
            sys.exit("%s has no build commit (built without git?)" % name)
        commits.add(commit)
    if len(commits) != 1:
        sys.exit("the jars were built from different commits: %s" % ", ".join(sorted(commits)))
    commit = commits.pop()
    # Binary-mode sha256sum lines ("<hash>  <name>"), LF endings, so `sha256sum -c SHA256SUMS.txt` works too.
    with open(os.path.join(dist, "SHA256SUMS.txt"), "w", encoding="utf-8", newline="\n") as fh:
        for name in jars:
            fh.write("%s  %s\n" % (sha256(os.path.join(dist, name)), name))
    with open(os.path.join(dist, "commit.txt"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(commit + "\n")
    print("commit %s" % commit)
    for (mc, variant), name in sorted(seen.items()):
        print("  %-7s %-5s %s" % (mc, variant, name))
    print("wrote SHA256SUMS.txt and commit.txt in %s - attach them with the %d jar(s)" % (dist, len(jars)))


if __name__ == "__main__":
    main()
