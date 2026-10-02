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


def raised_cosine(n):
    """0 to 1 over n samples, with no corner at either end."""
    return 0.5 - 0.5 * np.cos(np.pi * np.arange(n) / max(1, n - 1))


def shock_crack(seconds=0.17, seed=7):
    """One shock: N-wave, crack, thump and a short tail.

    Clicks: the clip used to start on a full-height sample (the N-wave's leap) and stop after a 20 ms straight
    fade, so every crack began with a step from silence - heard as a click on top of the crack, one per shock, a
    rattle at the train's rate. Now it rises over 4 ms and dies over 60 ms on raised-cosine curves (no corner where
    they meet the silence), has no DC offset (a constant offset clicks where the clip starts and stops), and sits
    between 2 ms of silence before and 6 ms after, so the decoder never starts or ends on anything but zero.
    """
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
    # no DC: a constant offset is a step at the clip's start and end
    x = fft_band(x - np.mean(x), lo=20)
    # the envelope: up over 4 ms, down over the last 60 ms, both raised cosines
    up = int(0.004 * SR)
    down = int(0.06 * SR)
    x[:up] *= raised_cosine(up)
    x[-down:] *= raised_cosine(down)[::-1]
    x -= np.linspace(x[0], x[-1], n)  # exactly zero at both ends
    return np.concatenate([np.zeros(int(0.002 * SR)), x, np.zeros(int(0.006 * SR))])


def edges(x, ms=2.0):
    """How the clip meets silence: its first and last samples, and its largest sample-to-sample step in the first
    and last few ms against the largest anywhere (a step at an edge that is large next to the body's is a click)."""
    k = int(ms / 1000.0 * SR)
    d = np.abs(np.diff(x))
    return x[0], x[-1], float(d[:k].max()), float(d[-k:].max()), float(d.max())


if __name__ == "__main__":
    crack = shock_crack()
    first, last, head, tail, body = edges(crack / (np.max(np.abs(crack)) or 1.0))
    print("shock_crack edges: first %.5f last %.5f, largest step in the first 2 ms %.4f, last 2 ms %.4f, anywhere %.4f"
          % (first, last, head, tail, body))
    save(os.path.join("ship", "shock_crack"), crack)
