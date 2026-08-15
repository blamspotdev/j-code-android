# vdevice-camera

The virtual device's camera app. Built into every device, like the browser and Files.

An app that wants a photo starts `ACTION_IMAGE_CAPTURE` and waits, and the careful ones call
`resolveActivity` first and hide their camera button when nothing answers. The container used to draw
a viewfinder itself, which worked and was still the wrong shape: a drawn screen is not something
`PackageManager` can find, so an app that asks before it reaches never got as far as reaching.

This is an **ordinary guest**. No container privileges and no Camera2 — the picture is drawn from the
device's own motion sensors, which is something any app may read, and saved with ordinary file IO.
That is deliberate: the app that proves the device has a camera should not be the one app that needs
special help to run. It is also a live check on the simulated sensors, and it earned its keep on the
first run — see below.

## What it does

| Started by | What happens |
|---|---|
| The launcher | Viewfinder and a shutter; photos go to the device's `DCIM/Camera` |
| `ACTION_IMAGE_CAPTURE` | The same, and answers the caller |
| `ACTION_VIDEO_CAPTURE` | Records three seconds to an MP4 and answers with its URI |

The capture contract is honoured as written: with `EXTRA_OUTPUT` the full-size JPEG is written to
that URI and the result carries no data; without it the result carries a thumbnail under the `"data"`
extra. Either way the full-size file is kept in `DCIM/Camera`, because the picture somebody just took
should be somewhere they can find it — and here that is a path `adb pull` takes.

The scene is colour bars, a horizon that rolls and pitches with the device's attitude, a compass rose
on its heading, and a frame counter. Drawn, and drawn to look drawn: nothing here could be mistaken
for a photograph of a room, which is what a camera quietly handing over *something* would invite.

## Three things it found

- **The simulated compass was mirrored.** With the bench at 45° the viewfinder read 315°.
  `SimulatedHardware.rotation` built its heading matrix with the sign that makes
  `getRotationMatrix` + `getOrientation` — the way every app reads a heading — return −a. Nothing had
  caught it: gravity is unaffected by that sign, so the accelerometer values that were checked
  exactly stayed correct, and the bench's own readout reports the azimuth it was given rather than
  deriving it. The two had quietly disagreed since the bench was written.
- **A permission request from `onCreate` goes nowhere.** The device's dialog is raised on behalf of
  whichever activity is in front, and an embedded activity is not in front until it has been resumed
  — so the request could not be addressed to anybody and vanished, leaving this app on "Waiting for
  permission" with nothing in the device's log. It asks from `onResume` instead.
- **`active` was never put back.** The container attributes permission checks to the guest in front,
  and that was set when an activity *started* and never restored when it finished. Harmless while
  the only cross-app launch was fire-and-forget; once an app could start this one and be returned to,
  the caller's own checks were answered from **this app's** grants — measured: the hardware fixture
  read `CAMERA = GRANTED` right after the Camera was allowed it.

## Build

Plain `javac` + `d8` + `aapt2`, like the other fixtures — no Gradle project, no resources.

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $jar = "$sdk\platforms\android-33\android.jar"; $bt = "$sdk\build-tools\37.0.0"
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $jar -d out (Get-ChildItem src -Recurse -Filter *.java | % FullName)
& "$bt\d8.bat" --min-api 24 --lib $jar --output out (Get-ChildItem out -Recurse -Filter *.class | % FullName)
& "$bt\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 33
Push-Location out; jar uf ..\base.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out camera.apk aligned.apk
```

Then copy it over the bundled copy, which is what every device is built from:

```powershell
Copy-Item camera.apk ..\..\app\src\main\assets\vdevice\camera.apk
```
