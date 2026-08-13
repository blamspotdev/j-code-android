# compose-fixture

The guest that answers one question the plain-view [`guest-fixture`](../guest-fixture) cannot:
**does an AndroidX activity's lifecycle actually advance inside the container?**

Compose's frame clock starts *paused* and is resumed only on `Lifecycle.Event.ON_START`. A
`ComponentActivity` gets that event from `ReportFragment`, which on API 29+ listens to
`onActivityPostStarted` — one of the `Application.ActivityLifecycleCallbacks` that
`Activity.performStart` dispatches, and `performStart` is denied to JCode at `targetSdk` 33. If the
container does not re-create that dispatch itself, this app composes and then draws **nothing at
all**: a black screen, not a broken one.

So the screen is deliberately unsubtle:

- **`frames:`** climbs once per frame, driven by `withFrameNanos`. A composition can be produced and
  never reach the display; a counter that moves proves the clock is running.
- **`lifecycle:`** is the activity's own `Lifecycle.State`. `RESUMED` is the pass mark; `CREATED`
  means the events never landed.
- **`Tapped n`** proves relayed input reaches a Compose hit-test tree.
- package / uid / `Build.MODEL` / `Build.DEVICE`, the same identity the other fixture prints.

## Build

It is a normal Gradle project, standalone so it can be built and broken without touching JCode's
own build. From the repo root, with the repo's wrapper:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat -p tools\compose-fixture assembleDebug
```

The APK lands at `tools/compose-fixture/build/outputs/apk/debug/compose-fixture-debug.apk`, signed
with the standard debug keystore.

## Use

Keep it **uninstalled**: the point is that the container runs it anyway.

```powershell
# on the device sandbox's own adb daemon
adb -s <jcode-vdevice> install compose-fixture-debug.apk
adb -s <jcode-vdevice> shell am start -n com.example.composeguest/.MainActivity
adb -s <jcode-vdevice> exec-out screencap -p > shot.png
```
