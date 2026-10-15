"""Generates the bundled menu click sounds (assets/killer560smod/sounds/click_*.ogg).

Every sound is synthesized from scratch with numpy (sine/noise envelopes), so there is no sampled audio and no
third-party license: they are original works of this project, released as CC0 (public domain). Run:

    python tools/gen-click-sounds.py

needs: numpy, soundfile (libsndfile with Ogg Vorbis). Output: mono, 44.1 kHz, 60-220 ms, peak about -6 dBFS.
"""
import os
import numpy as np
import soundfile as sf

SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets",
                   "killer560smod", "sounds")


def t(ms):
    return np.arange(int(SR * ms / 1000)) / SR


def env(n, attack_ms, decay_tau_ms):
    x = np.arange(n) / SR
    a = np.minimum(1.0, x / (attack_ms / 1000.0))
    return a * np.exp(-x / (decay_tau_ms / 1000.0))


def tone(freq, ms, tau_ms, attack_ms=0.8, harm=()):
    x = t(ms)
    y = np.sin(2 * np.pi * freq * x)
    for mult, amp in harm:
        y += amp * np.sin(2 * np.pi * freq * mult * x)
    return y * env(len(x), attack_ms, tau_ms)


def noise(ms, tau_ms, seed, lowpass=0.0):
    rng = np.random.default_rng(seed)
    y = rng.standard_normal(int(SR * ms / 1000))
    if lowpass > 0:
        k = max(1, int(lowpass))
        y = np.convolve(y, np.ones(k) / k, mode="same")
    return y * env(len(y), 0.3, tau_ms)


def sweep(f0, f1, ms, tau_ms):
    x = t(ms)
    f = f0 + (f1 - f0) * (x / x[-1])
    phase = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(phase) * env(len(x), 0.8, tau_ms)


def finish(y, peak=0.5):
    y = y / (np.max(np.abs(y)) + 1e-9) * peak
    fade = int(SR * 0.004)
    y[-fade:] *= np.linspace(1, 0, fade)
    return y.astype(np.float32)


def mix(*parts):
    n = max(len(p) for p in parts)
    y = np.zeros(n)
    for p in parts:
        y[:len(p)] += p
    return y


SOUNDS = {
    # a soft rounded tap
    "soft": finish(mix(tone(260, 110, 22, 1.5, [(2, 0.25)]), noise(25, 6, 1, 6) * 0.15)),
    # a dry mechanical tick
    "tick": finish(mix(noise(40, 5, 2, 2) * 0.6, tone(1900, 40, 5, 0.3), tone(3100, 30, 4, 0.3) * 0.4)),
    # a bubble pop, pitch falling
    "pop": finish(mix(sweep(900, 380, 85, 18), noise(10, 2, 3, 3) * 0.2)),
    # a wooden knock
    "wood": finish(mix(tone(430, 120, 16, 0.4, [(2.4, 0.5), (4.1, 0.25)]), noise(18, 3, 4, 4) * 0.4)),
    # a short glassy chime
    "chime": finish(mix(tone(1320, 220, 55, 0.6, [(2.0, 0.3), (3.0, 0.12)]), tone(1980, 160, 38, 0.6) * 0.35)),
    # a two-step electronic bleep
    "bleep": finish(np.concatenate([tone(880, 45, 40, 0.5, [(2, 0.1)]), tone(1320, 70, 30, 0.5, [(2, 0.1)])])),
}

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, data in SOUNDS.items():
        path = os.path.join(OUT, "click_%s.ogg" % name)
        sf.write(path, data, SR, format="OGG", subtype="VORBIS")
        print("%s: %d ms, %d bytes" % (name, int(len(data) * 1000 / SR), os.path.getsize(path)))
