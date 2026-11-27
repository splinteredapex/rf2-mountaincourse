"""Make synthetic logged runs with a known true road profile, to test fuse_drives.py.

Writes runs in the same layout import_drive.py produces: <out>/<run>/track.csv and raw/sensors.csv.gz.
The error model is deliberately pessimistic for a phone: metres of slowly wandering GPS height error,
barometer drift, per-run offsets and a small barometer lag.
"""
from __future__ import annotations
import sys
from pathlib import Path
import numpy as np
import pandas as pd
from pyproj import Transformer

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "pipeline"))
from fuse_drives import Centreline  # noqa: E402

TO_LONLAT = Transformer.from_crs("EPSG:27700", "EPSG:4326", always_xy=True)
GEOID = 54.6  # ellipsoid minus sea level, roughly, for the Isle of Man


def true_profile(s: np.ndarray, base: np.ndarray) -> np.ndarray:
    """Open-data profile plus features it cannot see: two crests, a dip and a bridge hump."""
    z = base.copy()
    for centre, height, width in [(3600, 1.8, 35), (4400, -2.5, 60), (5200, 1.2, 20), (6300, 0.9, 8), (7000, -1.5, 45)]:
        z += height * np.exp(-0.5 * ((s - centre) / width) ** 2)
    return z + 4.0 * np.sin(s / 900.0)  # and a long-wavelength error in the open data itself


def slow_noise(rng, n, dt, sigma, tau):
    a = np.exp(-dt / tau)
    x = np.empty(n)
    x[0] = rng.normal(0, sigma)
    e = rng.normal(0, sigma * np.sqrt(1 - a * a), n)
    for i in range(1, n):
        x[i] = a * x[i - 1] + e[i]
    return x


def make_run(out: Path, name: str, cl: Centreline, s_grid, z_true, s_from, s_to, speed, seed, baro=True):
    rng = np.random.default_rng(seed)
    dur = abs(s_to - s_from) / speed
    t = np.arange(0, dur, 1.0)
    s = s_from + np.sign(s_to - s_from) * speed * t
    i = np.searchsorted(cl.s, s).clip(0, len(cl.s) - 1)
    e = cl.e[i] + slow_noise(rng, len(t), 1.0, 2.5, 30)
    n = cl.n[i] + slow_noise(rng, len(t), 1.0, 2.5, 30)
    lon, lat = TO_LONLAT.transform(e, n)
    h = np.interp(s, s_grid, z_true) + GEOID
    gps_h = h + slow_noise(rng, len(t), 1.0, 3.0, 120) + rng.normal(0, 1.0, len(t)) + rng.normal(0, 2.0)
    t_ns = (t * 1e9).astype("int64") + 5_000_000_000
    d = out / name
    (d / "raw").mkdir(parents=True, exist_ok=True)
    pd.DataFrame({"t_ns": t_ns, "utc_ms": 1.8e12 + t * 1000, "lat": lat, "lon": lon, "h_ellipsoid_m": gps_h,
                  "h_acc_m": 4.0, "v_acc_m": 6.0, "speed_mps": speed, "quality": 1, "source": "phone"}
                 ).to_csv(d / "track.csv", index=False)
    if baro:
        tb = np.arange(0, dur, 1 / 25)
        lag = 0.4  # seconds
        sb = s_from + np.sign(s_to - s_from) * speed * np.clip(tb - lag, 0, None)
        hb = np.interp(sb, s_grid, z_true) + rng.normal(0, 8.0) + 0.002 * tb + slow_noise(rng, len(tb), 1 / 25, 0.25, 20)
        hb += rng.normal(0, 0.12, len(tb))
        p = 1013.25 * (1 - hb / 44330.0) ** 5.255
        pd.DataFrame({"t_ns": (tb * 1e9).astype("int64") + 5_000_000_000, "sensor": "BARO", "x": p, "y": "", "z": "", "w": ""}
                     ).to_csv(d / "raw" / "sensors.csv.gz", index=False)
