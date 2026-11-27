"""Trace the Mountain Course through the open road network.

Input : data/raw/iom_segments.parquet (Overture Maps transportation, OSM-derived, ODbL)
Output: data/course_centreline.csv  (station_m, lon, lat, easting, northing, name, ref)

The course is described in config/course.yaml as an ordered list of waypoints.
Between waypoints we take the shortest path, heavily preferring the A-roads the
course is known to use, so a rough waypoint coordinate is enough.
"""
from __future__ import annotations
import csv, heapq, math, sys
from pathlib import Path
import pyarrow.parquet as pq
import yaml
from pyproj import Transformer
from shapely import wkb

ROOT = Path(__file__).resolve().parents[1]
TO_GRID = Transformer.from_crs("EPSG:4326", "EPSG:27700", always_xy=True)  # British National Grid, metres
DRIVABLE = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential"}


def load_graph(parquet: Path, preferred_refs: set[str], preferred_names: set[str], penalty: float):
    nodes: dict[tuple[int, int], int] = {}
    xy: list[tuple[float, float]] = []
    lonlat: list[tuple[float, float]] = []
    adj: list[list[tuple[int, float, float, str, str, bool]]] = []

    def node(lon, lat):
        key = (round(lon * 1e7), round(lat * 1e7))
        i = nodes.get(key)
        if i is None:
            i = len(xy)
            nodes[key] = i
            xy.append(TO_GRID.transform(lon, lat))
            lonlat.append((lon, lat))
            adj.append([])
        return i

    for r in pq.read_table(parquet).to_pylist():
        if r["subtype"] != "road" or r["class"] not in DRIVABLE:
            continue
        geom = wkb.loads(r["geometry"])
        if geom.geom_type != "LineString":
            continue
        name = (r["names"] or {}).get("primary") or ""
        refs = {x.get("ref") for x in (r["routes"] or []) if x.get("ref")}
        ref = ",".join(sorted(refs))
        pref = bool(refs & preferred_refs) or name in preferred_names
        ids = [node(lon, lat) for lon, lat in geom.coords]
        for a, b in zip(ids, ids[1:]):
            if a == b:
                continue
            d = math.dist(xy[a], xy[b])
            cost = d if pref else d * penalty
            adj[a].append((b, cost, d, name, ref, pref))
            adj[b].append((a, cost, d, name, ref, pref))
    return xy, lonlat, adj


def snap(xy, adj, lon, lat, need_pref=True):
    x, y = TO_GRID.transform(lon, lat)
    best, bd = None, 1e18
    for i, (px, py) in enumerate(xy):
        if need_pref and not any(e[5] for e in adj[i]):
            continue
        d = (px - x) ** 2 + (py - y) ** 2
        if d < bd:
            best, bd = i, d
    return best, math.sqrt(bd)


def shortest(adj, src, dst):
    dist = {src: 0.0}
    prev: dict[int, tuple[int, tuple]] = {}
    pq_ = [(0.0, src)]
    while pq_:
        d, u = heapq.heappop(pq_)
        if u == dst:
            break
        if d > dist.get(u, 1e18):
            continue
        for e in adj[u]:
            nd = d + e[1]
            if nd < dist.get(e[0], 1e18):
                dist[e[0]] = nd
                prev[e[0]] = (u, e)
                heapq.heappush(pq_, (nd, e[0]))
    if dst not in prev and dst != src:
        raise RuntimeError("no route between waypoints")
    path = []
    u = dst
    while u != src:
        p, e = prev[u]
        path.append((u, e))
        u = p
    path.reverse()
    return path


def main():
    cfg = yaml.safe_load((ROOT / "config/course.yaml").read_text())
    rt = cfg["route"]
    xy, lonlat, adj = load_graph(ROOT / cfg["data"]["roads"], set(rt["preferred_refs"]),
                                 set(rt.get("preferred_names", [])), rt["off_route_penalty"])
    wps = rt["waypoints"]
    snapped = []
    for w in wps:
        i, d = snap(xy, adj, w["lon"], w["lat"])
        snapped.append(i)
        print(f"  waypoint {w['name']:<22} snapped {d:6.0f} m")
    rows = [(0.0, snapped[0], "", "")]
    s = 0.0
    legs = list(zip(snapped, snapped[1:]))
    if rt.get("closed", True):
        legs.append((snapped[-1], snapped[0]))
    for (a, b), w in zip(legs, wps):
        leg0 = s
        for u, e in shortest(adj, a, b):
            s += e[2]
            rows.append((s, u, e[3], e[4]))
        print(f"  leg from {w['name']:<22} {(s - leg0) / 1000:6.2f} km")
    out = ROOT / "data/course_centreline.csv"
    with out.open("w", newline="") as f:
        wr = csv.writer(f)
        wr.writerow(["station_m", "lon", "lat", "easting", "northing", "name", "ref"])
        for st, i, name, ref in rows:
            wr.writerow([f"{st:.2f}", f"{lonlat[i][0]:.7f}", f"{lonlat[i][1]:.7f}",
                         f"{xy[i][0]:.2f}", f"{xy[i][1]:.2f}", name, ref])
    official = cfg["course"]["official_length_km"]
    print(f"traced length {s / 1000:.2f} km, official {official} km, difference {100 * (s / 1000 - official) / official:+.2f}%")
    print(f"wrote {out.relative_to(ROOT)} ({len(rows)} points)")


if __name__ == "__main__":
    sys.exit(main())
