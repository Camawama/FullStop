"""Full Stop's procedural sounds (numpy + soundfile; no ffmpeg needed).

Run from the mod's folder:  python tools/gen_sounds.py
Writes src/main/resources/assets/fullstop/sounds/...

ship/shock_train.ogg - the continuous shock train of a supersonic rotor (see ShipSonicBooms and
ShockTrainSounds). A rotor whose tips are past the speed of sound doesn't boom over and over: each
tip drags its shock round with it, so a listener is swept by one shock per tip per turn - a train of
booms at the blade-passing rate, which at a few a second is the harsh buzzing roar of a supersonic
propeller (the XF-84H "Thunderscreech"). One second, eight pulses, seamless: the client pitches the
loop to the rotor's own pulse rate (pulses per second over BASE_RATE). Each pulse is the N-wave of a
boom (pressure leaps up, falls straight through zero, leaps up again), with the crack the air makes
of it and a low thump under it, over a low roar between the shocks, all through a hard edge for the
harmonics that make it tear rather than thud.
"""
import os

import numpy as np
import soundfile as sf

SR = 44100
BASE_RATE = 8.0  # pulses per second in the loop; ShockTrainSounds.BASE_RATE must match
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


def shock_train(rate=BASE_RATE, seconds=1.0, seed=7):
    n = int(SR * seconds)
    rng = np.random.RandomState(seed)
    x = np.zeros(n)
    pulses = int(round(rate * seconds))
    period = n / pulses
    # the N-wave: 5 ms, as a boom's
    L = int(0.005 * SR)
    nwave = np.linspace(1.0, -1.0, L)
    # the crack that comes with it: a burst of noise dying in 8 ms
    C = int(0.04 * SR)
    tc = np.arange(C) / SR
    # the thump under it: 48 Hz, dying over 70 ms
    T = int(0.25 * SR)
    tt = np.arange(T) / SR
    for k in range(pulses):
        s = int(round(k * period))
        add_wrapped(x, s, nwave)
        crack = rng.randn(C) * np.exp(-tc / 0.008)
        add_wrapped(x, s, fft_band(crack, lo=300, hi=6000) * 0.6)
        thump = np.sin(2 * np.pi * 48.0 * tt) * np.exp(-tt / 0.07)
        add_wrapped(x, s, thump * 0.9)
    # the roar between the shocks: a low band of brown noise, seamless
    bed = seamless(fft_band(brown(int(n * 1.25), seed + 1), lo=35, hi=700))[:n]
    bed /= float(np.max(np.abs(bed))) or 1.0
    x += 0.15 * bed
    # a hard edge on everything: the harmonics that make it tearing rather than thudding
    x = np.tanh(1.8 * x)
    return x


if __name__ == "__main__":
    save(os.path.join("ship", "shock_train"), shock_train())
