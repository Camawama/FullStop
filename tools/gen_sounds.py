"""Full Stop's procedural sounds (numpy + soundfile; no ffmpeg needed).

Run from the mod's folder:  python tools/gen_sounds.py
Writes src/main/resources/assets/fullstop/sounds/...

ship/shock_crack.ogg - ONE shock of a supersonic rotor's train (see ShipSonicBooms and
ShockTrainSounds). A rotor whose tips are past the speed of sound doesn't boom over and over: each
tip drags its shock round with it, so a listener is swept by one shock per tip per turn - a train of
cracks at the blade-passing rate, which at a few a second is the harsh buzzing roar of a supersonic
propeller (the XF-84H "Thunderscreech"). The client plays this one-shot once per shock, exactly at
the rotor's own cadence (turns a second times supersonic tips), so there is no loop and nothing to
pitch a loop by: the cadence IS the rotor's, and speeding up or slowing down is heard as the cracks
closing up or spreading out. One crack: the N-wave of a boom (pressure leaps up, falls straight
through zero, leaps up again), the crack the air makes of it, a low thump under it and a short
tail, through a hard edge for the harmonics that make it tear rather than thud. About 150 ms.
"""
import os

import numpy as np
import soundfile as sf

SR = 44100
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "fullstop", "sounds")


def save(rel, x, peak=0.9):
    path = os.path.join(OUT, rel + ".ogg")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    x = np.asarray(x, dtype=np.float32)
    m = float(np.max(np.abs(x))) or 1.0
    sf.write(path, x / m * peak, SR, format="OGG", subtype="VORBIS")
    print("wrote", os.path.normpath(path), "%.2f s" % (len(x) / SR))


def fft_band(x, lo=None, hi=None, slope=1.0):
    """Frequency-domain band filter with soft edges."""
    n = len(x)
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(n, 1 / SR)
    gain = np.ones_like(freqs)
    if lo:
        gain *= 1 / (1 + (lo / np.maximum(freqs, 1e-3)) ** (2 * slope))
    if hi:
        gain *= 1 / (1 + (freqs / hi) ** (2 * slope))
    return np.fft.irfft(spec * gain, n)


def brown(n, seed):
    x = np.cumsum(np.random.RandomState(seed).randn(n))
    x -= np.linspace(x[0], x[-1], n)
    return fft_band(x, lo=15)


def seamless(x, fade=0.25):
    """Crossfade the tail into the head so the clip loops without a click."""
    f = int(fade * SR)
    head = x[:f].copy()
    tail = x[-f:].copy()
    w = np.linspace(0, 1, f)
    x = x[:-f].copy()
    x[:f] = head * w + tail * (1 - w)
    return x


def add_wrapped(x, start, y):
    """Adds y into x from start, wrapping round the end: a pulse's tail crosses the loop's seam."""
    idx = (start + np.arange(len(y))) % len(x)
    np.add.at(x, idx, y)


def shock_crack(seconds=0.15, seed=7):
    """One shock: N-wave, crack, thump and a short tail; the whole clip fades to nothing."""
    n = int(SR * seconds)
    rng = np.random.RandomState(seed)
    x = np.zeros(n)
    # the N-wave: 5 ms, as a boom's
    L = int(0.005 * SR)
    x[:L] += np.linspace(1.0, -1.0, L)
    # the crack that comes with it: a burst of noise dying in 8 ms
    C = int(0.04 * SR)
    tc = np.arange(C) / SR
    crack = rng.randn(C) * np.exp(-tc / 0.008)
    x[:C] += fft_band(crack, lo=300, hi=6000) * 0.6
    # the thump under it: 48 Hz, dying over 50 ms
    T = min(n, int(0.12 * SR))
    tt = np.arange(T) / SR
    x[:T] += np.sin(2 * np.pi * 48.0 * tt) * np.exp(-tt / 0.05) * 0.9
    # the tail: the roar of the air behind the shock, dying over 40 ms
    tail = fft_band(rng.randn(n), lo=35, hi=700)
    tail /= float(np.max(np.abs(tail))) or 1.0
    x += 0.15 * tail * np.exp(-np.arange(n) / SR / 0.04)
    # a hard edge on everything: the harmonics that make it tearing rather than thudding
    x = np.tanh(1.8 * x)
    # to nothing at the end, so a crack that overlaps the next never clicks
    f = int(0.02 * SR)
    x[-f:] *= np.linspace(1.0, 0.0, f)
    return x


if __name__ == "__main__":
    save(os.path.join("ship", "shock_crack"), shock_crack())
