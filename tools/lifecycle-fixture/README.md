# lifecycle-fixture

A guest that says out loud what the virtual device is doing to it.

Every other fixture here tests something the container draws — a GL surface, a sensor reading, a
camera frame. This one tests the lifecycle behind all of them, which has no picture at all. Whether
an app covered by another one is *stopped* as well as paused, whether it is *restarted* rather than
merely resumed on the way back, whether it is asked to save anything, and whether a screen it never
agreed to handle rebuilds it — none of that can be seen, and all of it can be read.

Two screens. `LifecycleActivity` reports every callback to logcat under `VDEVICE-LIFECYCLE` **and**
onto its own screen, so a log can be diffed and a person can look. `SecondActivity` exists only to
cover the first one, because the interesting question is what happens *underneath*.

## What it is for that nothing else covers

**It declares no `android:configChanges`.** Every other app on the device declares the full set, so
every one of them is resized in place — which left the relaunch path with nothing on the device that
could exercise it. This is the app a phone would rebuild on rotation, so it is the app that shows
whether the container does too.

The screen also answers a question the log cannot: **whether the instance is the same one.** A
rebuilt activity starts its list again from `onCreate`; a resumed one continues the list it had. And
because the list itself travels in the saved state, a rebuild that restored properly shows the whole
history with `Instance 2 … restored build-1` above it.

## Reading it

```powershell
adb logcat -c; adb logcat -s VDEVICE-LIFECYCLE:*
```

What the container should produce, and what was measured on an Odin2 (Android 13):

| Do this | Expect |
|---|---|
| Open it from the home screen | `onCreate(null)` → `onStart` → `onResume`, at the **device's** screen size, not the phone's |
| **Cover me** | `main: onPause` → `second: onStart`/`onResume` → `main: onStop` → `onSaveInstanceState` |
| Back | `second:` pause/stop/destroy → `main: onRestart` → `onStart` → `onResume` |
| Home | `onPause` → `onStop` → `onSaveInstanceState`, and **no** `onDestroy` |
| Recents → its card | `onRestart` → `onStart` → `onResume`, same instance, no `onRestoreInstanceState` |
| A screen-options change | pause → stop → save → destroy → `onCreate(restored build-N)` → start → **`onRestoreInstanceState`** → resume |

## The `own:` lines

Anything logged with an `own:` prefix came back through
`Activity.registerActivityLifecycleCallbacks` — a callback this app registered **on the activity**,
not on the Application. That distinction is the whole point: the list behind that public method is a
non-SDK field, so a container that cannot read it has no way to dispatch to whatever an app put
there, and AndroidX's own `ReportFragment` is one of the apps that puts something there.

Measured on Android 13 at `targetSdk` 33: every callback arrives **except**
`onActivityPostStarted`, `onActivityPostResumed` and `onActivityPreStopped`, which only
`Activity.performStart`/`performResume` send and which the container cannot call. The device says so
itself, behind the warning triangle on its control bar.

Lift it with the platform's own developer setting and all three arrive:

```bash
adb shell settings put global hidden_api_policy 1
```

Restart JCode afterwards. `settings delete global hidden_api_policy` puts it back. This is the
device's to give, not the app's to take — `VMRuntime.setHiddenApiExemptions` by double reflection was
tried and is blocklisted, which `HiddenApi`'s notes record.

`onConfigurationChanged` appearing in this app's log is a bug: it declares nothing, so it is the one
app on the device that should never be told about a change instead of rebuilt for it. The container
says which it chose, under the `VDEVICE` tag:

```
BrowserActivity told for config 0x1d00 (declares 0x1da3)
LifecycleActivity relaunched for config 0x1d00 (declares 0x0)
```

## Build

Plain `aapt2` + `javac` + `d8`, like the other device apps — no Gradle project, no resources of its
own (it uses a framework icon, and builds its views in code).

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $jar = "$sdk\platforms\android-33\android.jar"; $bt = "$sdk\build-tools\36.0.0"
& "$bt\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 33
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $jar -d out (Get-ChildItem src -Recurse -Filter *.java | % FullName)
& "$bt\d8.bat" --min-api 24 --lib $jar --output out (Get-ChildItem out -Recurse -Filter *.class | % FullName)
Push-Location out; jar uf ..\base.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out lifecycle.apk aligned.apk
```

## Installing it

**Not bundled**, unlike the hardware fixture. This is a fixture for the container's maintainers
rather than a tool for the device's users, and every bundled app is an icon on every device's home
screen. Push it in for a test run instead:

```powershell
adb push lifecycle.apk /sdcard/JCode/lifecycle.apk
```

then the device's **Install an app** sheet, which reads the phone's storage. Not `adb install` over
the device's own adb: that runs in JCode's terminal, which is inside the distro, and the distro has
no view of `/sdcard` at all — `adb: failed to stat /sdcard/JCode/lifecycle.apk`. Either way it is
gone at the next JCode start, which is what a device rebuilt from its assets on every start means.

For a longer session, dropping it beside the built-ins in the Android Dev Pack
(`X:\jcode-extensions\android\native\vdevice\assets\vdevice\`) and rebuilding the pack makes it
install itself on every device — just remember to take it out again before committing.
