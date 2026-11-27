"""
Import a run recorded with the SplinteredApex Course Logger app.

    python pipeline/import_drive.py path/to/run_YYYYMMDD_HHMMSS.zip   (or the unzipped folder)

Writes data/drives/<run>/:
  track.csv     best position per epoch, used by the track build
                (RTK fixed from the external receiver where available, else phone GNSS)
  track.gpx     same, for viewing in Google Earth / GPX tools
  marks.csv     points marked with the "Mark point" button, with position
  summary.txt   what the run contains and how good it is

Heights are WGS84 ellipsoidal. For the phone-only case a barometer-smoothed height
(h_baro_fused_m) is added: barometric shape over short distances, GNSS level over minutes.
"""
from __future__ import annotations

import argparse
import json
import math
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]


def open_run(path: Path) -> tuple[Path, Path | None]:
    if path.is_dir():
        return path, None
    tmp = Path(tempfile.mkdtemp(prefix="apexrun_"))
    with zipfile.ZipFile(path) as z:
        z.extractall(tmp)
    dirs = [p for p in tmp.iterdir() if p.is_dir()]
    return (dirs[0] if len(dirs) == 1 else tmp), tmp


def read_csv(p: Path) -> pd.DataFrame | None:
    if not p.exists() or p.stat().st_size == 0:
        return None
    df = pd.read_csv(p)
    return df if len(df) else None


def utc_offset_ms(loc: pd.DataFrame | None) -> float | None:
    """UTC minus phone elapsed clock, from phone fixes."""
    if loc is None:
        return None
    return float(np.median(loc["utc_ms"] - loc["t_ns"] / 1e6))


def baro_fused(loc: pd.DataFrame, sensors_path: Path, window_s: float = 60.0) -> pd.Series | None:
    if not sensors_path.exists():
        return None
    baro = []
    for chunk in pd.read_csv(sensors_path, usecols=["t_ns", "sensor", "x"], chunksize=2_000_000):
        baro.append(chunk[chunk["sensor"] == "BARO"][["t_ns", "x"]])
    b = pd.concat(baro) if baro else None
    if b is None or len(b) < 10:
        return None
    # Pressure altitude (relative shape only), interpolated to fix times
    h_p = 44330.0 * (1.0 - (b["x"].to_numpy() / 1013.25) ** (1 / 5.255))
    h_at_fix = np.interp(loc["t_ns"], b["t_ns"], h_p)
    gnss = loc["h_ellipsoid_m"].to_numpy(dtype=float)
    ok = ~np.isnan(gnss)
    if ok.sum() < 10:
        return None
    diff = pd.Series(np.where(ok, gnss - h_at_fix, np.nan))
    t_s = (loc["t_ns"] - loc["t_ns"].iloc[0]) / 1e9
    dt = float(np.median(np.diff(t_s))) or 1.0
    win = max(3, int(window_s / dt))
    level = diff.interpolate(limit_direction="both").rolling(win, center=True, min_periods=1).mean()
    return pd.Series(h_at_fix + level.to_numpy(), index=loc.index)


def write_gpx(df: pd.DataFrame, path: Path, name: str):
    with path.open("w") as fh:
        fh.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        fh.write('<gpx version="1.1" creator="SplinteredApex import_drive" xmlns="http://www.topografix.com/GPX/1/1">\n')
        fh.write(f"<trk><name>{name}</name><trkseg>\n")
        for r in df.itertuples():
            ele = "" if pd.isna(r.h_ellipsoid_m) else f"<ele>{r.h_ellipsoid_m:.3f}</ele>"
            t = pd.to_datetime(r.utc_ms, unit="ms", utc=True).strftime("%Y-%m-%dT%H:%M:%S.%fZ") if not pd.isna(r.utc_ms) else ""
            tt = f"<time>{t[:-4]}Z</time>" if t else ""
            fh.write(f'<trkpt lat="{r.lat:.9f}" lon="{r.lon:.9f}">{ele}{tt}</trkpt>\n')
        fh.write("</trkseg></trk></gpx>\n")


def path_length_m(lat: np.ndarray, lon: np.ndarray) -> float:
    if len(lat) < 2:
        return 0.0
    la, lo = np.radians(lat), np.radians(lon)
    dla, dlo = np.diff(la), np.diff(lo)
    a = np.sin(dla / 2) ** 2 + np.cos(la[:-1]) * np.cos(la[1:]) * np.sin(dlo / 2) ** 2
    return float(np.sum(2 * 6371008.8 * np.arcsin(np.sqrt(a))))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run", type=Path, help="run .zip shared from the app, or its folder")
    ap.add_argument("--out", type=Path, default=ROOT / "data" / "drives")
    ap.add_argument("--min-quality", type=int, default=4,
                    help="external receiver quality to use: 4 = RTK fixed only (default), 5 = also float")
    a = ap.parse_args(argv)

    run_dir, tmp = open_run(a.run)
    try:
        meta = json.loads((run_dir / "run.json").read_text()) if (run_dir / "run.json").exists() else {}
        name = meta.get("run", run_dir.name)
        out = a.out / name
        out.mkdir(parents=True, exist_ok=True)

        loc = read_csv(run_dir / "location.csv")
        ext = read_csv(run_dir / "external_fix.csv")
        events = read_csv(run_dir / "events.csv")
        off = utc_offset_ms(loc)

        lines = [f"Run {name}", f"Device {meta.get('device', '?')}, app {meta.get('app_version', '?')}",
                 f"Notes: {meta.get('notes', '')}"]

        parts = []
        if loc is not None:
            phone = pd.DataFrame({
                "t_ns": loc["t_ns"], "utc_ms": loc["utc_ms"], "lat": loc["lat"], "lon": loc["lon"],
                "h_ellipsoid_m": loc["h_ellipsoid_m"], "h_acc_m": loc["h_acc_m"], "v_acc_m": loc["v_acc_m"],
                "speed_mps": loc["speed_mps"], "quality": 1, "source": "phone",
            })
            fused = baro_fused(loc, run_dir / "sensors.csv.gz")
            if fused is not None:
                phone["h_baro_fused_m"] = fused.round(3)
            lines.append(f"Phone fixes: {len(loc)}, median horizontal accuracy {loc['h_acc_m'].median():.1f} m, "
                         f"vertical {loc['v_acc_m'].median():.1f} m")
            parts.append(phone)

        if ext is not None:
            q = ext["quality"]
            counts = q.value_counts()
            n = len(ext)
            lines.append(
                f"External fixes: {n} ({ext['msg'].iloc[0]}), RTK fixed {100 * counts.get(4, 0) / n:.1f} %, "
                f"float {100 * counts.get(5, 0) / n:.1f} %")
            good = ext[(q == 4) | ((q == 5) & (a.min_quality == 5))].copy()
            if len(good):
                if good["utc_ms"].isna().any() and off is not None:
                    good["utc_ms"] = good["utc_ms"].fillna(good["t_ns"] / 1e6 + off)
                rtk = pd.DataFrame({
                    "t_ns": good["t_ns"], "utc_ms": good["utc_ms"], "lat": good["lat"], "lon": good["lon"],
                    "h_ellipsoid_m": good["h_ellipsoid_m"], "h_acc_m": good["h_acc_m"], "v_acc_m": good["v_acc_m"],
                    "speed_mps": good["speed_mps"], "quality": good["quality"], "source": "rtk",
                })
                lines.append(f"RTK fixes used: {len(rtk)}, median vertical accuracy {rtk['v_acc_m'].median() * 100:.1f} cm")
                parts.insert(0, rtk)

        if not parts:
            sys.exit("This run has no position data.")

        best = parts[0]
        if best["source"].iloc[0] == "rtk" and len(parts) > 1:
            # Fill RTK gaps longer than 1 s with phone fixes, flagged by source column
            phone = parts[1]
            rtk_t = best["t_ns"].to_numpy()
            idx = np.searchsorted(rtk_t, phone["t_ns"].to_numpy())
            prev = np.abs(phone["t_ns"].to_numpy() - rtk_t[np.clip(idx - 1, 0, len(rtk_t) - 1)])
            nxt = np.abs(rtk_t[np.clip(idx, 0, len(rtk_t) - 1)] - phone["t_ns"].to_numpy())
            gap = np.minimum(prev, nxt) > 1e9
            best = pd.concat([best, phone[gap]]).sort_values("t_ns")
        best = best.reset_index(drop=True)
        best.to_csv(out / "track.csv", index=False, float_format="%.9g")
        write_gpx(best, out / "track.gpx", name)
        dist = path_length_m(best["lat"].to_numpy(), best["lon"].to_numpy())
        dur = (best["t_ns"].iloc[-1] - best["t_ns"].iloc[0]) / 1e9
        lines.append(f"Track: {len(best)} points, {dist / 1000:.2f} km, {dur / 60:.1f} min "
                     f"({(best['source'] == 'rtk').mean() * 100:.0f} % RTK)")

        if events is not None:
            marks = events[events["kind"] == "MARK"].copy()
            if len(marks):
                for col in ("lat", "lon", "h_ellipsoid_m"):
                    marks[col] = np.interp(marks["t_ns"], best["t_ns"], best[col])
                marks[["number", "t_ns", "lat", "lon", "h_ellipsoid_m", "label"]].to_csv(out / "marks.csv", index=False)
                lines.append(f"Marked points: {len(marks)}")

        for f in ("gnss_raw.txt", "external_raw.ubx", "ntrip_rtcm.bin"):
            p = run_dir / f
            if p.exists() and p.stat().st_size:
                lines.append(f"Raw file kept for post-processing: {f} ({p.stat().st_size / 1e6:.1f} MB)")
        raw_keep = out / "raw"
        raw_keep.mkdir(exist_ok=True)
        for f in run_dir.iterdir():
            if f.is_file():
                shutil.copy2(f, raw_keep / f.name)

        (out / "summary.txt").write_text("\n".join(lines) + "\n")
        print("\n".join(lines))
        print(f"\nWritten to {out}")
    finally:
        if tmp is not None:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
