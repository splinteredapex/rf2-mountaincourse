# Getting a build into rFactor 2 (Windows)

These steps run on your PC. They have **not been tested end to end yet**: the first run of the
pilot is the test. Tell me where it breaks and I will fix the pipeline or these notes.

## One-off setup

1. Install Blender 4.5 (free).
2. Install 3DSimEd (paid). It converts FBX to rFactor 2's GMT mesh format.
3. In Steam, right-click rFactor 2, Properties, Launch options, and start it in Dev Mode (ModDev).
4. In `rFactor 2\ModDev\Locations\`, copy the example track folder that ships with ModDev and
   rename the copy `SnaefellPilot`. This gives known-good GDB, SCN and TDF files to edit.

## Each build

1. **Blender:** File, Import, Wavefront (.obj), choose `build/pilot/track.obj`. Leave the default
   axes (Forward -Z, Up Y). You should see a 5 km road running north-west with green terrain.
2. Check object names in the outliner: `RaceSurface_001` and so on, `xfinish`, `xsector1`,
   `xsector2`, `xpitin`, `xpitout`. These names matter to the game.
3. **Blender:** File, Export, FBX. Tick Apply Transform and set Scale to 0.01.
4. **3DSimEd:** import the FBX, check each object kept its name (FBX can lose mesh names),
   then export every object as GMT into `SnaefellPilot\Assets\GMTs\`.
5. **SCN file:** list the new GMT files as instances, replacing the example track's objects.
   Copy an existing instance block from the example file for each kind of object.
6. **GDB file:** change the first line to `SnaefellPilot` and set the name, length (5.0 km),
   and the latitude and longitude from `build/pilot/build_info.yaml`.
7. **ModDev:** load the track. Use the built-in AIW editor to place a garage spot, pit spot and
   grid spot on the pit strip, then drive.

## What to report back

- Does it load? If not, the last lines of `UserData\Log\trace.txt` (start with `trace=3`).
- Does the car sit on the road, and does lap timing trigger at the gates?
- Frame rate, and any shaking of the car at standstill.
- How the hills feel against your memory of the road.

## Known limits of the pilot

- It is a point-to-point section, not a loop, so there is no AI and no complete lap.
- Materials are plain colours. Textures and rFactor 2 shaders come next.
