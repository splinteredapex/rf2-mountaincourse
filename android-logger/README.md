# Course Logger (Android)

Records a drive of the course from your car, for the v2 "GPS-logged laps" build.

## Install on your Pixel (once, about 2 minutes)

1. On the phone, open **https://github.com/splinteredapex/rf2-mountaincourse/releases/tag/logger-latest**
   (sign in to GitHub if asked).
2. Tap **SplinteredApex-Logger.apk**. When Chrome asks, allow it to install unknown apps.
3. Open **Apex Logger**. Allow **Precise** location, notifications and nearby devices.
4. Optional but recommended: Settings > System > Developer options > **Force full GNSS measurements** on.
   This keeps carrier phase running for post-processing. (Developer options: tap Build number 7 times.)

New versions install over the old one from the same link. Runs already recorded are kept.

## What it records

| File | Contents | Rate |
| --- | --- | --- |
| `location.csv`, `track.gpx` | Phone GNSS fix: lat, lon, ellipsoid and sea-level height, accuracy, speed | 1 Hz |
| `gnss_raw.txt` | Raw satellite measurements (GnssLogger format) for post-processing to RINEX | 1 Hz |
| `sensors.csv.gz` | Accelerometer, gyro, magnetometer, barometer, orientation | about 200 to 400 Hz |
| `external_raw.ubx`, `external_fix.csv` | RTK receiver stream and parsed fixes, if one is connected | up to 10 Hz |
| `ntrip_rtcm.bin` | RTK corrections received, kept for reprocessing | |
| `events.csv` | Camera sync flashes and marked points | |
| `run.json` | Device, settings, notes | |

Every row has `t_ns`, one shared phone clock, so all files line up exactly.

## Accuracy, honestly

- **Phone only**: layout ±3 to 5 m, height ±5 m. Good for the speed trace, braking points and broad hills.
  The barometer gives a smoother relative height profile (`h_baro_fused_m` after import). Not good enough for crests or camber.
- **Phone raw data, post-processed**: possibly sub-metre. Experimental.
- **u-blox ZED-F9P + roof antenna + NTRIP corrections**: ±1 to 2 cm. This is what the road surface needs.

## With an RTK receiver

- **USB**: plug the F9P into the phone with a USB-C OTG cable. Choose *USB* in Setup. Leave
  "Set u-blox to 10 Hz with raw data" ticked: it sets 10 Hz plus raw output in RAM only, so nothing on the receiver is changed permanently.
- **Bluetooth**: pair the receiver in Android settings, choose *Bluetooth serial*, then pick the device.
  Configure its rate and messages in u-center first (Bluetooth links are slow, 5 Hz NAV-PVT + GGA is safe).
- **Corrections**: tick NTRIP and enter the caster host, port, mountpoint, username and password from your correction provider.
  The card turns green at **RTK FIXED**. Don't trust a run that's mostly "RTK float".

## Driving checklist

1. Mount the phone (and antenna) rigidly. Note where in **Run notes** (e.g. "antenna roof centre, phone windscreen").
2. Start recording, wait until the GNSS card shows a fix (and **RTK FIXED** if using a receiver).
3. Tap **Camera sync** and hold the phone so the dashcam sees the screen for 3 seconds. Do the same at the end.
4. Drive at road-legal speeds. Tap **Mark point** at landmarks (start line, junctions) if useful.
5. Stop recording. **Recorded runs > Share** sends the .zip to Google Drive or email.
6. On the PC: `python pipeline/import_drive.py run_YYYYMMDD_HHMMSS.zip`

The Mountain Course is a public road: drive both directions and both lanes where safe, then the
pipeline averages the runs and offsets them to the centreline.

## Post-processing phone raw data

```
python pipeline/phone_raw_to_rinex.py data/drives/<run>/raw     # writes phone.obs (RINEX 3.03)
```

Process `phone.obs` in RTKLIB against a base station's RINEX for the same time.
For the F9P, convert `external_raw.ubx` with RTKLIB `convbin`.

## Building it yourself

GitHub Actions builds the APK on every push that touches `android-logger/`
(workflow `.github/workflows/android-logger.yml`). No Android tools are needed on your PC.
The signing key in `keystore/` is a public debug key, not a secret. It is fixed so updates install over each other.
