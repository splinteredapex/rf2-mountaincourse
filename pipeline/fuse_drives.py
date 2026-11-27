"""Turn logged drives into one road height profile.

    python pipeline/fuse_drives.py

Input : data/drives/<run>/track.csv        written by pipeline/import_drive.py
        data/drives/<run>/raw/sensors.csv.gz   barometer at full rate, if the run has it
Output: data/road_profile.csv   station_m, height_m, n_passes, spread_m
        data/road_profile.png   every pass, the fused profile and the open-data profile

Runs may be partial, may overlap, and may be driven in either direction. Each run is cut into
"passes" (one continuous drive along the course). The method:

1. Match every sample to the course centreline: station (distance along) and sideways error.
2. Height per pass. Phone: barometer for the shape, GPS for the level. RTK: the fix height as is.
3. Each pass has its own slow error (GPS bias, weather, cabin pressure). Passes are pulled onto
   their shared average at long wavelengths only, so the short-wavelength shape (crests, dips)
   is kept and averaged across passes.
4. The result is shifted onto the open elevation model's datum, so it drops into the track build.

Driving a stretch in both directions matters: the barometer lags slightly, which shifts a crest
along the direction of travel. Opposite directions cancel that.
"""
from __future__ import annotations
import csv, sys, warnings
from pathlib import Path
import numpy as np
import pandas as pd
import yaml
from pyproj import Transformer
from scipy.ndimage import gaussian_filter1d
from scipy.spatial import cKDTree

ROOT = Path(__file__).resolve().parents[1]
TO_GRID = Transformer.from_crs("EPSG:4326", "EPSG:27700", always_xy=True)


# ----------------------------------------------------------------------------- helpers
def nan_lowpass(x: np.ndarray, sigma: float) -> np.ndarray:
    """Gaussian low-pass that ignores gaps. NaN where there is no data nearby."""
    ok = ~np.isnan(x)
    num = gaussian_filter1d(np.where(ok, x, 0.0), sigma, mode="constant")
    den = gaussian_filter1d(ok.astype(float), sigma, mode="constant")
    out = np.full_like(x, np.nan, dtype=float)
    good = den > 1e-3
    out[good] = num[good] / den[good]
    return out


class Centreline:
    def __init__(self, path: Path, spacing: float = 1.0):
        rows = list(csv.DictReader(path.open()))
        s = np.array([float(r["station_m"]) for r in rows])
        e = np.array([float(r["easting"]) for r in rows])
        n = np.array([float(r["northing"]) for r in rows])
        keep = np.r_[True, np.diff(s) > 0.01]
        s, e, n = s[keep], e[keep], n[keep]
        self.length = float(s[-1])
        self.s = np.arange(0.0, self.length, spacing)
        self.e, self.n = np.interp(self.s, s, e), np.interp(self.s, s, n)
        self.tree = cKDTree(np.c_[self.e, self.n])

    def match(self, lon, lat):
        e, n = TO_GRID.transform(np.asarray(lon), np.asarray(lat))
        d, i = self.tree.query(np.c_[e, n])
        return self.s[i], d


def baro_height(run_dir: Path, fixes: pd.DataFrame, cfg: dict):
    """Barometric height at about 10 Hz, levelled to GPS. Returns (t_ns, height) or None."""
    f = run_dir / "raw" / "sensors.csv.gz"
    if not f.exists():
        return None
    parts = []
    for chunk in pd.read_csv(f, usecols=["t_ns", "sensor", "x"], chunksize=2_000_000):
        parts.append(chunk.loc[chunk["sensor"] == "BARO", ["t_ns", "x"]])
    b = pd.concat(parts) if parts else pd.DataFrame()
    if len(b) < 50:
        return None
    step = int(1e9 / cfg["baro_rate_hz"])
    b = b.groupby(b["t_ns"] // step, sort=True).agg(t_ns=("t_ns", "mean"), x=("x", "median"))
    t = b["t_ns"].to_numpy(dtype=float)
    h = 44330.0 * (1.0 - (b["x"].to_numpy() / 1013.25) ** (1 / 5.255))
    g = fixes.dropna(subset=["h_ellipsoid_m"])
    if len(g) < 10:
        return None
    # Level: GPS minus barometer, low-passed over time. GPS gives the level, never the shape.
    tf = g["t_ns"].to_numpy(dtype=float)
    diff = g["h_ellipsoid_m"].to_numpy() - np.interp(tf, t, h)
    dt = max(float(np.median(np.diff(tf))) / 1e9, 0.05)
    level = gaussian_filter1d(diff, cfg["gps_level_window_s"] / dt, mode="nearest")
    return t, h + np.interp(t, tf, level)


def passes_from_run(run_dir: Path, cl: Centreline, grid: np.ndarray, cfg: dict):
    """Yield (label, weight, heights-on-grid) for each continuous pass in one run."""
    fx = pd.read_csv(run_dir / "track.csv").sort_values("t_ns").reset_index(drop=True)
    st, lateral = cl.match(fx["lon"], fx["lat"])
    fx["station"], fx["lateral"] = st, lateral
    ok = (fx["lateral"] <= cfg["max_lateral_m"]) & (fx["h_acc_m"].fillna(0) <= cfg["max_h_acc_m"])
    if "speed_mps" in fx:
        ok &= fx["speed_mps"].fillna(99) >= cfg["min_speed_mps"]
    fx = fx[ok].reset_index(drop=True)
    if len(fx) < 5:
        return
    rtk = (fx.get("source", pd.Series(["phone"] * len(fx))) == "rtk").mean() > 0.5
    t = fx["t_ns"].to_numpy(dtype=float)
    s = fx["station"].to_numpy()
    # A pass ends at a time gap, or where the station jumps (left the course, or crossed the start line).
    cut = (np.diff(t) > cfg["max_gap_s"] * 1e9) | (np.abs(np.diff(s)) > cfg["max_station_jump_m"])
    # ... or where the direction of travel reverses.
    trend = np.sign(gaussian_filter1d(np.gradient(s), 3, mode="nearest"))
    cut |= (trend[1:] * trend[:-1]) < 0
    bounds = np.r_[0, np.where(cut)[0] + 1, len(fx)]

    baro = None if rtk else baro_height(run_dir, fx, cfg)
    step = grid[1] - grid[0]
    for k, (a, b) in enumerate(zip(bounds[:-1], bounds[1:])):
        if b - a < 5 or abs(s[b - 1] - s[a]) < cfg["min_pass_m"]:
            continue
        ts, ss = t[a:b], s[a:b]
        if baro is not None:
            m = (baro[0] >= ts[0]) & (baro[0] <= ts[-1])
            samp_s, samp_h = np.interp(baro[0][m], ts, ss), baro[1][m]
        else:
            col = "h_ellipsoid_m" if rtk or "h_baro_fused_m" not in fx else "h_baro_fused_m"
            samp_s, samp_h = ss, fx[col].to_numpy()[a:b]
        good = ~np.isnan(samp_h)
        idx = np.round((samp_s[good] - grid[0]) / step).astype(int)
        inside = (idx >= 0) & (idx < len(grid))
        total = np.bincount(idx[inside], weights=samp_h[good][inside], minlength=len(grid))
        count = np.bincount(idx[inside], minlength=len(grid))
        h = np.where(count > 0, total / np.maximum(count, 1), np.nan)
        # Fill short gaps between samples (1 Hz fixes are about 20 m apart at 45 mph).
        have = np.where(count > 0)[0]
        if len(have) < 5:
            continue
        every = np.arange(len(grid))
        filled = np.interp(every, have, h[have])
        j = np.searchsorted(have, every).clip(1, len(have) - 1)
        gap = (have[j] - have[j - 1]) * step            # distance between the samples either side
        span = (every >= have[0]) & (every <= have[-1])
        h = np.where(span & ((count > 0) | (gap <= cfg["max_fill_m"])), filled, np.nan)
        direction = "fwd" if ss[-1] > ss[0] else "rev"
        kind = "rtk" if rtk else ("baro" if baro is not None else "gps")
        yield f"{run_dir.name}#{k + 1} {direction} {kind}", (cfg["rtk_weight"] if rtk else 1.0), h


def fuse(P: np.ndarray, w: np.ndarray, step: float, cfg: dict):
    """P: passes x stations, NaN where a pass has no data. Returns fused height, count, spread, aligned passes."""
    sigma = cfg["drift_wavelength_m"] / step
    W = np.where(np.isnan(P), 0.0, w[:, None])
    n = (~np.isnan(P)).sum(axis=0)

    def mean(A):
        tot = W.sum(axis=0)
        return np.where(tot > 0, np.nansum(np.where(np.isnan(A), 0, A) * W, axis=0) / np.maximum(tot, 1e-9), np.nan)

    A = P.copy()
    H = mean(A)
    for _ in range(cfg["iterations"]):
        for i in range(len(P)):
            drift = nan_lowpass(P[i] - H, sigma)
            A[i] = P[i] - np.where(np.isnan(drift), 0.0, drift)
        H = mean(A)
    # Robust final average: drop samples far from the consensus where there are enough passes.
    warnings.simplefilter("ignore", RuntimeWarning)  # all-gap columns are expected: most of the lap is undriven
    resid = A - H
    mad = np.nanmedian(np.abs(resid), axis=0) * 1.4826
    outlier = (np.abs(resid) > np.maximum(3 * mad, cfg["outlier_floor_m"])) & (n >= 4)
    A2 = np.where(outlier, np.nan, A)
    W[:] = np.where(np.isnan(A2), 0.0, w[:, None])
    H = mean(A2)
    spread = np.where(n >= 2, np.nanstd(A, axis=0), np.nan)
    return H, n, spread, A


def main():
    cfg_all = yaml.safe_load((ROOT / "config/course.yaml").read_text())
    cfg = cfg_all["drives"]
    cl = Centreline(ROOT / "data/course_centreline.csv")
    step = cfg["station_step_m"]
    grid = np.arange(0.0, cl.length, step)

    runs = sorted(p for p in (ROOT / "data/drives").glob("*") if (p / "track.csv").exists())
    if not runs:
        sys.exit("No imported runs in data/drives/. Run pipeline/import_drive.py on each run first.")
    labels, weights, rows = [], [], []
    for r in runs:
        for label, weight, h in passes_from_run(r, cl, grid, cfg):
            labels.append(label); weights.append(weight); rows.append(h)
    if not rows:
        sys.exit("No usable passes: nothing matched the course within the sideways limit.")
    P = np.vstack(rows)
    H, n, spread, A = fuse(P, np.array(weights), step, cfg)

    # Shift onto the open elevation model's datum (also removes the ellipsoid/sea-level difference).
    from build_track import Dem
    dem = Dem(ROOT / cfg_all["data"]["dem"], cfg_all["elevation"]["dem_smooth_px"])
    cl_at = np.searchsorted(cl.s, grid).clip(0, len(cl.s) - 1)
    dem_prof = gaussian_filter1d(dem.at(cl.e[cl_at], cl.n[cl_at]), cfg_all["elevation"]["profile_smooth_m"] / step, mode="nearest")
    cov = ~np.isnan(H)
    datum = float(np.nanmedian(H[cov] - dem_prof[cov]))
    H = H - datum
    A = A - datum
    Hs = np.where(cov, nan_lowpass(H, cfg["final_smooth_m"] / step), np.nan)

    out = ROOT / "data/road_profile.csv"
    with out.open("w", newline="") as f:
        wr = csv.writer(f)
        wr.writerow(["station_m", "height_m", "n_passes", "spread_m"])
        for i in np.where(cov)[0]:
            wr.writerow([f"{grid[i]:.1f}", f"{Hs[i]:.3f}", int(n[i]), "" if np.isnan(spread[i]) else f"{spread[i]:.3f}"])

    print(f"{len(runs)} runs, {len(labels)} passes")
    for label, h in zip(labels, P):
        have = np.where(~np.isnan(h))[0]
        print(f"  {label:<44} {grid[have[0]] / 1000:6.2f} to {grid[have[-1]] / 1000:6.2f} km")
    covered = cov.sum() * step / 1000
    print(f"covered {covered:.2f} km of {cl.length / 1000:.2f} km; "
          f"{(n >= 3).sum() * step / 1000:.2f} km has 3 or more passes")
    if (n >= 2).any():
        print(f"agreement between passes (after alignment): median {np.nanmedian(spread):.2f} m, "
              f"worst 5 % above {np.nanpercentile(spread, 95):.2f} m")
    print(f"datum shift applied: {datum:+.1f} m (about +55 m is expected from ellipsoid to sea level)")
    print(f"wrote {out.relative_to(ROOT)}")
    plot(ROOT / "data/road_profile.png", grid, A, Hs, dem_prof, n, labels)


def plot(path, grid, A, H, dem_prof, n, labels):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    cov = np.where(~np.isnan(H))[0]
    a, b = max(cov[0] - 100, 0), min(cov[-1] + 100, len(grid) - 1)
    km = grid[a:b] / 1000
    fig, (p1, p2) = plt.subplots(2, 1, figsize=(12, 7), height_ratios=[3, 1], sharex=True)
    for row in A:
        p1.plot(km, row[a:b], color="#999", lw=0.6, alpha=0.7)
    p1.plot(km, dem_prof[a:b], color="#d62728", lw=1.2, ls="--", label="open 30 m data (v1)")
    p1.plot(km, H[a:b], color="#1f77b4", lw=1.8, label="fused from drives")
    p1.plot([], [], color="#999", lw=0.6, label="individual passes, aligned")
    p1.set_ylabel("height (m)"); p1.legend(); p1.grid(alpha=.3); p1.set_title("Road height profile from logged drives")
    p2.fill_between(km, n[a:b], step="mid", color="#1f77b4", alpha=.5)
    p2.set_ylabel("passes"); p2.set_xlabel("distance from the start line (km)"); p2.grid(alpha=.3)
    fig.tight_layout(); fig.savefig(path, dpi=110); plt.close(fig)


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    main()
