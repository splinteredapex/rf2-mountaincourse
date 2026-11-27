"""Download the open source data into data/raw/ (not kept in git).

    python pipeline/fetch_data.py

Roads    : Overture Maps transportation segments for the Isle of Man. Derived from
           OpenStreetMap, licence ODbL 1.0. (c) OpenStreetMap contributors, Overture Maps Foundation.
Elevation: Copernicus DEM GLO-30 tile N54 W005, 30 m. (c) DLR e.V. 2010-2014 and (c) Airbus Defence
           and Space GmbH 2014-2018, provided under COPERNICUS by the European Union and ESA.
"""
from pathlib import Path
import urllib.request
import pyarrow.compute as pc
import pyarrow.dataset as ds
import pyarrow.fs as pafs
import pyarrow.parquet as pq

RAW = Path(__file__).resolve().parents[1] / "data/raw"
OVERTURE_RELEASE = "2026-11-18.0"
BBOX = (-4.85, 54.04, -4.30, 54.42)  # lon min, lat min, lon max, lat max: the whole island
DEM = "Copernicus_DSM_COG_10_N54_00_W005_00_DEM"


def roads():
    fs = pafs.S3FileSystem(anonymous=True, region="us-west-2")
    d = ds.dataset(f"overturemaps-us-west-2/release/{OVERTURE_RELEASE}/theme=transportation/type=segment/",
                   filesystem=fs, format="parquet")
    x0, y0, x1, y1 = BBOX
    f = ((pc.field("bbox", "xmin") < x1) & (pc.field("bbox", "xmax") > x0)
         & (pc.field("bbox", "ymin") < y1) & (pc.field("bbox", "ymax") > y0))
    t = d.to_table(filter=f)
    pq.write_table(t, RAW / "iom_segments.parquet")
    print(f"roads: {t.num_rows} segments")


def dem():
    out = RAW / f"{DEM}.tif"
    urllib.request.urlretrieve(f"https://copernicus-dem-30m.s3.amazonaws.com/{DEM}/{DEM}.tif", out)
    print(f"elevation: {out.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    RAW.mkdir(parents=True, exist_ok=True)
    roads()
    dem()
