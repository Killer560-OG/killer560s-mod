#!/usr/bin/env python3
"""Scan built jars (or folders, or a git repo's objects) for secrets before they ship.

    python tools/verify-artifact.py build/libs/*.jar
    python tools/verify-artifact.py --git C:/path/to/repo        # every object in the repo, reachable or not

What it looks for, in every file of every jar (nested Jar-in-Jar jars included):

  * secret SHAPES: PEM private keys, Discord bot tokens and webhooks, GitHub / Anthropic / OpenAI / Slack / AWS
    keys, Microsoft refresh/access tokens next to a token field, Minecraft session ids, connection strings with
    a password, a Hypixel key next to an API-Key mention;
  * the owner's REAL secrets, by fingerprint only: every file in ~/.claude/secrets (or --secrets-dir) is read,
    reduced to sha256 + length + shape in memory, and candidates of that shape in the artifact are hashed and
    compared. A fingerprint file (--fingerprints, JSON from --write-fingerprints) works the same way without
    the secret files. No secret value is ever printed: a hit shows its first 4 characters and its length;
  * information leaks: absolute user paths (C:\\Users\\..., /home/..., /Users/...), .java sources, files
    that should never ship (.env, .dev.vars, *.pem, *token*.txt ...);
  * debug data, reported only: how many classes carry a LocalVariableTable.

Exit status 1 on any secret, fingerprint, absolute-path or forbidden-file hit, 0 otherwise. Missing inputs are
an error too (exit 2), so a deploy step cannot pass by scanning nothing.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

# --------------------------------------------------------------------------------------------- patterns

SECRET_PATTERNS: list[tuple[str, re.Pattern[bytes]]] = [
    ("private-key", re.compile(rb"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED |PGP )?PRIVATE KEY(?: BLOCK)?-----\s*[A-Za-z0-9+/=]{40}")),
    ("discord-bot-token", re.compile(rb"([MNO][A-Za-z0-9_-]{23,27}\.[A-Za-z0-9_-]{6}\.[A-Za-z0-9_-]{27,40})(?![A-Za-z0-9_-])")),
    ("discord-webhook", re.compile(rb"https://(?:ptb\.|canary\.)?discord(?:app)?\.com/api/webhooks/[0-9]{15,22}/[A-Za-z0-9_-]{50,80}")),
    ("github-token", re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{60,})")),
    ("anthropic-key", re.compile(rb"\bsk-ant-[A-Za-z0-9_-]{20,}")),
    ("openai-key", re.compile(rb"\bsk-(?:proj-)?[A-Za-z0-9_-]{40,}")),
    ("slack-token", re.compile(rb"\bxox[abprs]-[A-Za-z0-9-]{10,}")),
    ("aws-access-key", re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("google-api-key", re.compile(rb"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("ms-refresh-token", re.compile(rb"\bM\.(?:C|R)[0-9]{1,3}_[A-Za-z0-9!*$_.-]{40,}")),
    ("ms-token-field", re.compile(rb"(?i)(?:access_?token|refresh_?token|bearer)[\"':= ]{1,4}(?:EwA|eyJ)[A-Za-z0-9+/_=.-]{100,}")),
    ("minecraft-session-id", re.compile(rb"token:[A-Za-z0-9._-]{30,}:[0-9a-f]{32}")),
    ("connection-string", re.compile(rb"(?i)\b(?:mongodb(?:\+srv)?|postgres(?:ql)?|mysql|mariadb|rediss?|amqps?|mssql|sqlserver)://[^:/\s'\"]+:[^@\s'\"]{3,}@[^\s'\"]+")),
    ("hypixel-key-in-context", re.compile(rb"(?i)(?:api[-_ ]?key|x-api-key|apikey)[\"':= ]{1,4}[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
    ("spotify-secret-in-context", re.compile(rb"(?i)client_?secret[\"':= ]{1,4}[0-9a-f]{32}\b")),
]

PATH_PATTERNS: list[tuple[str, re.Pattern[bytes]]] = [
    ("windows-user-path", re.compile(rb"(?i)[A-Z]:(?:\\\\|\\|/)+Users(?:\\\\|\\|/)+[A-Za-z0-9._ -]{2,}")),
    ("unix-home-path", re.compile(rb"/home/[a-z][a-z0-9_-]{1,31}/")),
    ("mac-user-path", re.compile(rb"(?<![A-Za-z0-9])/Users/[A-Za-z][A-Za-z0-9._-]{1,31}/")),
]

# An obfuscated key cannot be matched by shape or hash, but the code that carries one has a recognisable look:
# v1.0.0 and v1.1.0 shipped a Hypixel key as two XOR'd int arrays in rngmeter/HypixelApiKeyProvider.
SUSPECT_CLASS_STRINGS = re.compile(rb"(?i)ApiKeyProvider|BUILTIN_KEY|EMBEDDED_KEY|CIPHER_PART|OBFUSCATED_KEY|SECRET_PAD")

FORBIDDEN_NAMES = re.compile(
    r"(?i)(^|/)(\.env(\..*)?|\.dev\.vars|.*\.pem|.*\.p12|.*\.pfx|.*\.keystore|.*\.jks|id_rsa.*|id_ed25519.*"
    r"|.*token.*\.txt|.*secret.*\.txt|.*api-?key.*\.txt|credentials(\.json)?|killer560smod-accounts.*)$")

# Shapes a fingerprinted secret can take. Each is the regex its candidates are pulled from, and the
# normalisation applied to both the secret and the candidate before hashing.
SHAPES = {
    "uuid": (re.compile(rb"[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}"),
             lambda b: b.replace(b"-", b"").lower()),
    "discord": (re.compile(rb"[A-Za-z0-9_-]{20,30}\.[A-Za-z0-9_-]{4,10}\.[A-Za-z0-9_-]{20,45}"), lambda b: b),
    "hex": (re.compile(rb"[0-9a-fA-F]{16,}"), lambda b: b.lower()),
    "b64url": (re.compile(rb"[A-Za-z0-9_-]{16,}"), lambda b: b),
    "b64": (re.compile(rb"[A-Za-z0-9+/]{16,}={0,2}"), lambda b: b),
    "any": (re.compile(rb"[\x21-\x7e]{8,}"), lambda b: b),
}
SHAPE_ORDER = ["uuid", "discord", "hex", "b64url", "b64", "any"]


def classify(value: bytes) -> str:
    for shape in SHAPE_ORDER:
        rx, _ = SHAPES[shape]
        if rx.fullmatch(value):
            return shape
    return "any"


def fp(value: bytes) -> str:
    """Redacted fingerprint: first 4 characters and the length. Never more."""
    return f"{value[:4].decode('ascii', 'replace')}... (len {len(value)})"


class Fingerprints:
    """sha256 fingerprints of the owner's real secrets, grouped by shape and normalised length."""

    def __init__(self) -> None:
        self.by_shape: dict[str, dict[int, dict[str, str]]] = {}
        self.count = 0

    def add(self, name: str, digest: str, length: int, shape: str) -> None:
        self.by_shape.setdefault(shape, {}).setdefault(length, {})[digest] = name
        self.count += 1

    def add_value(self, name: str, value: bytes) -> None:
        shape = classify(value)
        norm = SHAPES[shape][1](value)
        self.add(name, hashlib.sha256(norm).hexdigest(), len(norm), shape)

    @classmethod
    def load(cls, secrets_dir: Path | None, fp_file: Path | None) -> "Fingerprints":
        f = cls()
        if secrets_dir and secrets_dir.is_dir():
            for p in sorted(secrets_dir.iterdir()):
                if not p.is_file():
                    continue
                try:
                    raw = p.read_bytes()
                except OSError:
                    continue
                for line in raw.splitlines():
                    v = line.strip()
                    if len(v) >= 16 and b" " not in v:
                        f.add_value(p.name, v)
        if fp_file and fp_file.is_file():
            for e in json.loads(fp_file.read_text(encoding="utf-8")):
                f.add(e["name"], e["sha256"], int(e["length"]), e["shape"])
        return f

    def dump(self) -> list[dict]:
        out = []
        for shape, by_len in self.by_shape.items():
            for length, digests in by_len.items():
                for d, name in digests.items():
                    out.append({"name": name, "sha256": d, "length": length, "shape": shape})
        return out

    def scan(self, data: bytes):
        """Yields (secret name, matched bytes) for every candidate whose hash is a known secret."""
        for shape, by_len in self.by_shape.items():
            rx, norm = SHAPES[shape]
            for m in rx.finditer(data):
                cand = m.group(0)
                for length, digests in by_len.items():
                    n = norm(cand)
                    if len(n) == length:
                        name = digests.get(hashlib.sha256(n).hexdigest())
                        if name:
                            yield name, cand
                    elif len(n) > length and shape != "uuid" and len(n) < 8192:
                        # A secret glued to neighbouring characters of the same class: try every window.
                        for i in range(0, len(n) - length + 1):
                            name = digests.get(hashlib.sha256(n[i:i + length]).hexdigest())
                            if name:
                                yield name, n[i:i + length]


# --------------------------------------------------------------------------------------------- scanning

class Report:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.warnings: list[str] = []
        self.files = 0
        self.classes = 0
        self.lvt_classes = 0
        self.bytes = 0

    def fail(self, msg: str) -> None:
        self.failures.append(msg)

    def warn(self, msg: str) -> None:
        self.warnings.append(msg)


def line_of(data: bytes, pos: int) -> int:
    return data.count(b"\n", 0, pos) + 1


NATIVE_SUFFIXES = (".so", ".a", ".dll", ".dylib", ".jnilib", ".exe")


def scan_blob(where: str, data: bytes, fps: Fingerprints, rep: Report, check_paths: bool = True) -> None:
    rep.files += 1
    rep.bytes += len(data)
    for kind, rx in SECRET_PATTERNS:
        for m in rx.finditer(data):
            rep.fail(f"SECRET {kind}: {where}:{line_of(data, m.start())} {fp(m.group(m.lastindex or 0))}")
    for name, cand in fps.scan(data):
        rep.fail(f"OWNER SECRET ({name}): {where} {fp(cand)}")
    if check_paths:
        native = where.lower().endswith(NATIVE_SUFFIXES)
        for kind, rx in PATH_PATTERNS:
            for m in rx.finditer(data):
                (rep.warn if native else rep.fail)(f"PATH {kind}: {where}:{line_of(data, m.start())} {m.group(0)[:48].decode('utf-8', 'replace')}")


def scan_zip(where: str, data: bytes, fps: Fingerprints, rep: Report, depth: int = 0) -> None:
    try:
        zf = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        rep.fail(f"UNREADABLE zip: {where}")
        return
    for info in zf.infolist():
        if info.is_dir():
            continue
        name = info.filename
        inner = f"{where}!/{name}"
        if FORBIDDEN_NAMES.search(name):
            rep.fail(f"FORBIDDEN file in artifact: {inner}")
        body = zf.read(info)
        if name.endswith(".java"):
            rep.warn(f"SOURCE file shipped: {inner}")
        if name.endswith(".class"):
            rep.classes += 1
            if b"LocalVariableTable" in body:
                rep.lvt_classes += 1
            m = SUSPECT_CLASS_STRINGS.search(body)
            if m:
                rep.fail(f"SUSPECT embedded-key code: {inner} ({m.group(0).decode()})")
        if name.endswith(".jar") and depth < 4:
            scan_zip(inner, body, fps, rep, depth + 1)
            continue
        scan_blob(inner, body, fps, rep)


def scan_path(p: Path, fps: Fingerprints, rep: Report) -> None:
    if p.is_dir():
        for q in sorted(p.rglob("*")):
            if q.is_file() and "node_modules" not in q.parts and ".git" not in q.parts:
                scan_path(q, fps, rep)
        return
    data = p.read_bytes()
    if data[:4] == b"PK\x03\x04" or p.suffix in (".jar", ".zip"):
        scan_zip(str(p), data, fps, rep)
    else:
        if FORBIDDEN_NAMES.search(p.name):
            rep.warn(f"secret-looking file name: {p}")
        scan_blob(str(p), data, fps, rep, check_paths=False)


def scan_git(repo: Path, fps: Fingerprints, rep: Report) -> None:
    """Every blob in the object store, reachable or not, so deleted history and stashes are covered."""
    lst = subprocess.run(["git", "-C", str(repo), "cat-file", "--batch-all-objects", "--batch-check"],
                         capture_output=True, check=True, stdin=subprocess.DEVNULL).stdout.decode().split("\n")
    blobs = [l.split()[0] for l in lst if l.endswith(tuple("0123456789")) and " blob " in l]
    proc = subprocess.Popen(["git", "-C", str(repo), "cat-file", "--batch"], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE)
    assert proc.stdin and proc.stdout
    import threading

    def feed() -> None:
        for b in blobs:
            proc.stdin.write((b + "\n").encode())
        proc.stdin.close()

    threading.Thread(target=feed, daemon=True).start()
    for _ in blobs:
        header = proc.stdout.readline().split()
        size = int(header[2])
        body = proc.stdout.read(size)
        proc.stdout.read(1)
        where = f"{repo.name}:blob {header[0].decode()[:10]}"
        if body[:4] == b"PK\x03\x04":
            scan_zip(where, body, fps, rep)
        else:
            scan_blob(where, body, fps, rep, check_paths=False)
    proc.wait()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*", type=Path, help="jars, zips, folders or files to scan")
    ap.add_argument("--git", type=Path, action="append", default=[], help="scan every blob of this repo")
    ap.add_argument("--secrets-dir", type=Path, default=Path.home() / ".claude" / "secrets",
                    help="folder of the owner's secret files, fingerprinted in memory (default ~/.claude/secrets)")
    ap.add_argument("--fingerprints", type=Path, default=Path.home() / ".claude" / "secret-fingerprints.json",
                    help="JSON fingerprint file (sha256/length/shape, no values; default ~/.claude/secret-fingerprints.json)")
    ap.add_argument("--write-fingerprints", type=Path, help="write the fingerprints of --secrets-dir and exit")
    ap.add_argument("--require-fingerprints", action="store_true",
                    help="fail if no owner fingerprint could be loaded (use on the owner's machine)")
    ap.add_argument("-q", "--quiet", action="store_true", help="print only failures and the summary")
    a = ap.parse_args()

    fps = Fingerprints.load(a.secrets_dir, a.fingerprints)
    if a.write_fingerprints:
        a.write_fingerprints.write_text(json.dumps(fps.dump(), indent=1), encoding="utf-8")
        print(f"wrote {fps.count} fingerprints (hashes only) to {a.write_fingerprints}")
        return 0
    if a.require_fingerprints and fps.count == 0:
        print("verify-artifact: no owner fingerprints loaded and --require-fingerprints given", file=sys.stderr)
        return 2
    if not a.paths and not a.git:
        ap.error("nothing to scan")

    rep = Report()
    for p in a.paths:
        if not p.exists():
            print(f"verify-artifact: missing input {p}", file=sys.stderr)
            return 2
        scan_path(p, fps, rep)
    for g in a.git:
        scan_git(g, fps, rep)
    if rep.files == 0:
        print("verify-artifact: scanned zero files", file=sys.stderr)
        return 2

    for f in rep.failures:
        print("FAIL", f)
    if not a.quiet:
        for w in rep.warnings:
            print("WARN", w)
    print(f"verify-artifact: {rep.files} files, {rep.bytes // 1024} KiB, {rep.classes} classes "
          f"({rep.lvt_classes} with LocalVariableTable), {fps.count} owner fingerprints, "
          f"{len(rep.failures)} failures, {len(rep.warnings)} warnings")
    return 1 if rep.failures else 0


if __name__ == "__main__":
    sys.exit(main())
