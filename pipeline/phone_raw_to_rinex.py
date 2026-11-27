"""
Convert the logger's phone raw GNSS file (gnss_raw.txt, Google GnssLogger "Raw" format)
to a RINEX 3.03 observation file for post-processing (RTKLIB rnx2rtkp / RTKPOST, CSRS-PPP, etc.).

    python pipeline/phone_raw_to_rinex.py path/to/run_folder_or_gnss_raw.txt [-o out.obs]

Writes pseudorange (C), carrier phase (L), Doppler (D) and signal strength (S) for
GPS, GLONASS, Galileo, BeiDou and QZSS on L1/E1/B1 and L5/E5a/B2a.
"""
from __future__ import annotations

import argparse
import csv
import math
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

C = 299_792_458.0
WEEK_NS = 604_800 * 10**9
DAY_NS = 86_400 * 10**9
GPS_EPOCH = datetime(1980, 1, 6)

# Android constants
SYS = {1: "G", 3: "R", 4: "J", 5: "C", 6: "E"}
STATE_CODE_LOCK = 1
STATE_TOW_DECODED = 8
STATE_GLO_TOD_DECODED = 128
STATE_TOW_KNOWN = 16384
STATE_GLO_TOD_KNOWN = 32768
ADR_VALID, ADR_RESET, ADR_SLIP, ADR_HALF_RESOLVED, ADR_HALF_REPORTED = 1, 2, 4, 8, 16

DEFAULT_ATTR = {("G", "1"): "C", ("G", "5"): "Q", ("E", "1"): "C", ("E", "5"): "Q",
                ("C", "2"): "I", ("C", "1"): "P", ("C", "5"): "P", ("R", "1"): "C",
                ("J", "1"): "C", ("J", "5"): "Q"}


def band(sys: str, f_hz: float | None) -> str | None:
    if f_hz is None:
        return "2" if sys == "C" else "1"
    mhz = f_hz / 1e6
    if sys == "R":
        return "1" if 1595 < mhz < 1610 else None
    if abs(mhz - 1575.42) < 1:
        return "1"
    if abs(mhz - 1176.45) < 1:
        return "5"
    if sys == "C" and abs(mhz - 1561.098) < 1:
        return "2"
    return None


def fnum(row: dict, key: str) -> float | None:
    v = row.get(key, "")
    if v in ("", None):
        return None
    try:
        x = float(v)
    except ValueError:
        return None
    return None if math.isnan(x) else x


def read_rows(path: Path):
    header = None
    with path.open(encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if line.startswith("# Raw,"):
                header = line[2:].split(",")
                continue
            if not line.startswith("Raw,") or header is None:
                continue
            vals = line.split(",")
            yield dict(zip(header, vals))


def observation(row: dict):
    """Return (epoch_gps_ns, sat_id, band, attr, C, L, D, S, lli, glo_k) or None."""
    try:
        sys = SYS.get(int(row["ConstellationType"]))
        svid = int(row["Svid"])
        state = int(row["State"])
        time_nanos = int(row["TimeNanos"])
        full_bias = int(row["FullBiasNanos"])
    except (KeyError, ValueError):
        return None
    if sys is None:
        return None
    bias = fnum(row, "BiasNanos") or 0.0
    offset = fnum(row, "TimeOffsetNanos") or 0.0
    rx_sv = int(row["ReceivedSvTimeNanos"])
    unc = fnum(row, "ReceivedSvTimeUncertaintyNanos") or 0.0
    f_hz = fnum(row, "CarrierFrequencyHz")
    b = band(sys, f_hz)
    if b is None:
        return None
    if f_hz is None:
        f_hz = {"1": 1575.42e6, "2": 1561.098e6, "5": 1176.45e6}[b]
    attr = (row.get("CodeType") or "").strip() or DEFAULT_ATTR.get((sys, b), "C")

    gps_int = time_nanos - full_bias          # exact integer ns since GPS epoch
    frac = offset - bias                       # sub-ns corrections
    leap = int(fnum(row, "LeapSecond") or 18)

    pr = None
    if state & STATE_CODE_LOCK and unc < 500:
        if sys == "R":
            if state & (STATE_GLO_TOD_DECODED | STATE_GLO_TOD_KNOWN):
                t_rx = ((gps_int + (3 * 3600 - leap) * 10**9) % DAY_NS) + frac
                d = t_rx - rx_sv
                if d > DAY_NS / 2:
                    d -= DAY_NS
                if d < -DAY_NS / 2:
                    d += DAY_NS
                pr = d * 1e-9 * C
        elif state & (STATE_TOW_DECODED | STATE_TOW_KNOWN):
            t_rx = (gps_int % WEEK_NS) + frac
            if sys == "C":
                t_rx -= 14 * 10**9
            d = t_rx - rx_sv
            if d > WEEK_NS / 2:
                d -= WEEK_NS
            if d < -WEEK_NS / 2:
                d += WEEK_NS
            pr = d * 1e-9 * C
        if pr is not None and not (1.0e7 < pr < 5.0e7):
            pr = None

    lam = C / f_hz
    adr_state = int(row.get("AccumulatedDeltaRangeState") or 0)
    adr = fnum(row, "AccumulatedDeltaRangeMeters")
    L, lli = None, 0
    if adr_state & ADR_VALID and adr is not None and adr != 0.0:
        L = adr / lam
        if adr_state & (ADR_RESET | ADR_SLIP):
            lli |= 1
        if adr_state & ADR_HALF_REPORTED and not adr_state & ADR_HALF_RESOLVED:
            lli |= 2
    prr = fnum(row, "PseudorangeRateMetersPerSecond")
    D = -prr / lam if prr is not None else None
    S = fnum(row, "Cn0DbHz")
    if pr is None and L is None:
        return None
    glo_k = round((f_hz - 1602e6) / 0.5625e6) if sys == "R" else None
    return time_nanos, f"{sys}{svid:02d}", gps_int, frac, b, attr, pr, L, D, S, lli, glo_k


def hline(text: str, label: str) -> str:
    return f"{text:<60.60}{label:<20}\n"


def gps_calendar(gps_ns_int: int, frac: float) -> tuple[datetime, float]:
    secs, ns = divmod(gps_ns_int, 10**9)
    t = GPS_EPOCH + timedelta(seconds=secs)
    return t, t.second + (ns + frac) * 1e-9


def approx_xyz(run_dir: Path) -> tuple[float, float, float]:
    loc = run_dir / "location.csv"
    if loc.exists():
        with loc.open() as fh:
            for r in csv.DictReader(fh):
                try:
                    lat, lon = math.radians(float(r["lat"])), math.radians(float(r["lon"]))
                    h = float(r["h_ellipsoid_m"] or 0)
                except (ValueError, KeyError):
                    continue
                a, e2 = 6378137.0, 6.69437999014e-3
                n = a / math.sqrt(1 - e2 * math.sin(lat) ** 2)
                return ((n + h) * math.cos(lat) * math.cos(lon),
                        (n + h) * math.cos(lat) * math.sin(lon),
                        (n * (1 - e2) + h) * math.sin(lat))
    return 0.0, 0.0, 0.0


def convert(raw_path: Path, out_path: Path, device: str = "Android phone") -> dict:
    epochs: dict[int, dict] = {}
    types: dict[str, set] = {}
    glo_slots: dict[str, int] = {}
    for row in read_rows(raw_path):
        ob = observation(row)
        if ob is None:
            continue
        tn, sat, gps_int, frac, b, attr, pr, L, D, S, lli, k = ob
        ep = epochs.setdefault(tn, {"gps_int": gps_int, "frac": frac, "sats": {}})
        obs = ep["sats"].setdefault(sat, {})
        code = b + attr
        obs["C" + code] = (pr, 0)
        obs["L" + code] = (L, lli)
        obs["D" + code] = (D, 0)
        obs["S" + code] = (S, 0)
        types.setdefault(sat[0], set()).add(code)
        if k is not None:
            glo_slots[sat] = k

    if not epochs:
        raise SystemExit("No usable raw measurements found (is this a gnss_raw.txt from the logger?)")

    order = sorted(epochs)
    obs_types = {s: [t + c for c in sorted(codes) for t in "CLDS"] for s, codes in sorted(types.items())}
    first = epochs[order[0]]
    t0, s0 = gps_calendar(first["gps_int"], first["frac"])
    x, y, z = approx_xyz(raw_path.parent)

    with out_path.open("w", newline="\n") as fh:
        fh.write(hline("     3.03           OBSERVATION DATA    M", "RINEX VERSION / TYPE"))
        fh.write(hline(f"{'phone_raw_to_rinex':<20}{'SplinteredApex':<20}{datetime.now(timezone.utc):%Y%m%d %H%M%S} UTC", "PGM / RUN BY / DATE"))
        fh.write(hline("PHONE", "MARKER NAME"))
        fh.write(hline("GEODETIC", "MARKER TYPE"))
        fh.write(hline(f"{'SplinteredApex':<20}{'':<40}", "OBSERVER / AGENCY"))
        fh.write(hline(f"{'':<20}{device[:20]:<20}{'':<20}", "REC # / TYPE / VERS"))
        fh.write(hline(f"{'':<20}{'PHONE INTERNAL':<20}", "ANT # / TYPE"))
        fh.write(hline(f"{x:14.4f}{y:14.4f}{z:14.4f}", "APPROX POSITION XYZ"))
        fh.write(hline(f"{0:14.4f}{0:14.4f}{0:14.4f}", "ANTENNA: DELTA H/E/N"))
        for s, lst in obs_types.items():
            for i in range(0, len(lst), 13):
                chunk = " ".join(lst[i:i + 13])
                head = f"{s}  {len(lst):3d}" if i == 0 else " " * 6
                fh.write(hline(f"{head} {chunk}", "SYS / # / OBS TYPES"))
        fh.write(hline("DBHZ", "SIGNAL STRENGTH UNIT"))
        fh.write(hline(f"{t0.year:6d}{t0.month:6d}{t0.day:6d}{t0.hour:6d}{t0.minute:6d}{s0:13.7f}     GPS", "TIME OF FIRST OBS"))
        if "R" in obs_types:
            slots = sorted(glo_slots.items())
            for i in range(0, max(len(slots), 1), 8):
                part = "".join(f" {s} {k:2d}" for s, k in slots[i:i + 8])
                head = f"{len(slots):3d}" if i == 0 else "   "
                fh.write(hline(f"{head}{part}", "GLONASS SLOT / FRQ #"))
            fh.write(hline(" C1C    0.000 C1P    0.000 C2C    0.000 C2P    0.000", "GLONASS COD/PHS/BIS"))
        fh.write(hline("", "END OF HEADER"))

        for tn in order:
            ep = epochs[tn]
            t, sec = gps_calendar(ep["gps_int"], ep["frac"])
            sats = sorted(ep["sats"])
            fh.write(f"> {t.year:4d} {t.month:02d} {t.day:02d} {t.hour:02d} {t.minute:02d}{sec:11.7f}  0{len(sats):3d}\n")
            for sat in sats:
                obs = ep["sats"][sat]
                parts = []
                for ot in obs_types[sat[0]]:
                    v, lli = obs.get(ot, (None, 0))
                    if v is None:
                        parts.append(" " * 16)
                    else:
                        parts.append(f"{v:14.3f}{lli if lli else ' '} ")
                fh.write(sat + "".join(parts).rstrip() + "\n")

    return {"epochs": len(order), "systems": "".join(obs_types), "first": f"{t0:%Y-%m-%d %H:%M}:{s0:06.3f} GPST"}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("path", type=Path, help="run folder or gnss_raw.txt")
    ap.add_argument("-o", "--out", type=Path, help="output .obs file (default: next to the input)")
    a = ap.parse_args(argv)
    raw = a.path / "gnss_raw.txt" if a.path.is_dir() else a.path
    if not raw.exists():
        sys.exit(f"Not found: {raw}")
    out = a.out or raw.with_name("phone.obs")
    info = convert(raw, out)
    print(f"Wrote {out}: {info['epochs']} epochs, systems {info['systems']}, first epoch {info['first']}")


if __name__ == "__main__":
    main()
