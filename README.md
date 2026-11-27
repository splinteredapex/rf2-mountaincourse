# rf2-mountaincourse

A data-driven build of the Snaefell Mountain Course (Isle of Man) for rFactor 2.

The track is **generated from data by script**, not modelled by hand. When better data
arrives, the build is re-run and everything follows the road.

| Version | Height data | Status |
| --- | --- | --- |
| v1 | Open 30 m elevation (Copernicus) | Pilot section built |
| v2 | GPS-logged laps | Logger app ready (`android-logger/`) |
| v3 | 0.5 m LiDAR, if licensed | Planned |

## Run it

```
pip install -r requirements.txt
python pipeline/fetch_data.py        # downloads open road and elevation data (about 16 MB)
python pipeline/route.py             # traces the course, writes data/course_centreline.csv
python pipeline/build_track.py pilot # writes build/pilot/track.obj
```

The traced lap is 60.75 km against the official 60.72 km (+0.06 %).

All settings (road width, mesh density, section limits) are in `config/course.yaml`.
Getting the result into the game is described in `docs/windows-steps.md`.

## Recording the course from a car

`android-logger/` is an Android app that logs GNSS (phone, or an RTK receiver), motion sensors and
marked points while you drive. Install and driving steps: [android-logger/README.md](android-logger/README.md).
Import a run with `python pipeline/import_drive.py <run>.zip`.

## What v1 is and is not

- Layout, lap length and corner radii come from map data and are accurate to a few metres.
- Heights come from 30 m data that includes treetops and roofs, so the road profile is heavily
  smoothed. Hills are right; crests, dips, bumps and camber are **not real yet**.
- Road width is a constant 7 m placeholder. Crossfall is a constant 2.5 % crown.
- The route uses the modern junction at Governor's Bridge, not the old-road dip the races use.
- No scenery yet: road, pit strip, verges, terrain and timing gates only.

## Rules of the build

1. Every object is positioned by station (distance along the road) and offset (sideways).
   Nothing uses fixed world coordinates.
2. Hand-made assets live in their own folder and are never overwritten by a rebuild.
3. Generated files (`build/`) and downloaded data (`data/raw/`) are not committed.

## Naming and licences

The mod is named "Snaefell Mountain Course". It does not use the TT name, logos or event branding.

- Roads: OpenStreetMap contributors via Overture Maps, ODbL 1.0.
- Elevation: Copernicus DEM GLO-30. (c) DLR e.V. 2010-2014 and (c) Airbus Defence and Space GmbH
  2014-2018, provided under COPERNICUS by the European Union and ESA; all rights reserved.
