"""End-to-end check: partial, overlapping, two-way phone runs recover a known road profile."""
import shutil, subprocess, sys
from pathlib import Path
import numpy as np
import pandas as pd
import pytest
import yaml
from scipy.ndimage import gaussian_filter1d

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "pipeline"))
sys.path.insert(0, str(ROOT / "tests"))

pytestmark = pytest.mark.skipif(not (ROOT / "data/raw").exists(), reason="run pipeline/fetch_data.py first")


def test_partial_runs_recover_profile(tmp_path, monkeypatch):
    import fuse_drives, simulate_drives
    from build_track import Dem
    cfg = yaml.safe_load((ROOT / "config/course.yaml").read_text())
    cl = fuse_drives.Centreline(ROOT / "data/course_centreline.csv")
    dem = Dem(ROOT / cfg["data"]["dem"], cfg["elevation"]["dem_smooth_px"])
    base = gaussian_filter1d(dem.at(cl.e, cl.n), cfg["elevation"]["profile_smooth_m"], mode="nearest")
    truth = simulate_drives.true_profile(cl.s, base)

    # Work in a copy of the project so real data/drives is never touched.
    proj = tmp_path / "proj"
    for d in ("config", "pipeline"):
        shutil.copytree(ROOT / d, proj / d)
    (proj / "data").mkdir()
    shutil.copy(ROOT / "data/course_centreline.csv", proj / "data")
    (proj / "data/raw").symlink_to(ROOT / "data/raw")
    drives = proj / "data/drives"
    plan = [  # name, from, to (m), speed (m/s): partial, overlapping, both directions
        ("run_a", 2800, 5600, 18), ("run_b", 5200, 7800, 20), ("run_c", 7800, 4900, 17),
        ("run_d", 5300, 2800, 19), ("run_e", 3000, 7600, 22), ("run_f", 7700, 2900, 16),
    ]
    for k, (name, a, b, v) in enumerate(plan):
        simulate_drives.make_run(drives, name, cl, cl.s, truth, a, b, v, seed=k)
    r = subprocess.run([sys.executable, str(proj / "pipeline/fuse_drives.py")], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    print(r.stdout)

    prof = pd.read_csv(proj / "data/road_profile.csv")
    s = prof["station_m"].to_numpy()
    assert s.min() < 2900 and s.max() > 7700, "runs should stitch into one continuous stretch"
    assert np.diff(s).max() <= 2.0 + 1e-6, "no holes between partial runs"
    m = (s > 3000) & (s < 7600)
    t = np.interp(s, cl.s, truth)
    err = prof["height_m"].to_numpy() - t
    err_m = err[m] - np.median(err[m])          # the absolute level is the open data's, by design
    open_err = (np.interp(s, cl.s, base) - t)[m]
    open_err -= np.median(open_err)
    rms, open_rms = float(np.sqrt(np.mean(err_m**2))), float(np.sqrt(np.mean(open_err**2)))
    print(f"fused rms {rms:.2f} m, open data rms {open_rms:.2f} m")
    assert rms < 0.8
    assert rms < open_rms / 2.5

    # Short features the open data cannot see must come through at most of their height.
    def feature(centre, half):
        w = (s > centre - 4 * half) & (s < centre + 4 * half)
        f = prof["height_m"].to_numpy()[w]
        trend = np.polyval(np.polyfit(s[w][[0, -1]], f[[0, -1]], 1), s[w])
        tr = t[w] - np.polyval(np.polyfit(s[w][[0, -1]], t[w][[0, -1]], 1), s[w])
        i = np.argmax(np.abs(tr))
        return (f - trend)[i], tr[i]
    for centre, half in [(3600, 35), (4400, 60), (7000, 45)]:
        got, want = feature(centre, half)
        print(f"feature at {centre} m: recovered {got:+.2f} m of {want:+.2f} m")
        assert got / want > 0.6
