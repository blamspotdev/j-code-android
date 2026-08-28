# vdevice-launcher

The virtual device's home screen. Built into every device, like the browser and the camera — and,
unlike them, the app the device rests on: it is what runs when nothing else does.

## Why it is an app at all

It used to be drawn by the container. First as Compose laid over the device's `SurfaceView`, which
meant `adb shell screencap` answered a bare wallpaper — an agent could not see what was installed.
Then as a `Canvas` painted straight onto the surface, with taps resolved against the very rectangles
that had been drawn, which fixed the capture and left one thing wrong: the home screen was the only
part of the device that was not an app.

That cost more than tidiness. It had no activity, so `uiautomator dump` reported a bare SurfaceView
where a phone reports a view tree. It could not be started, stopped, paused or switched away from, so
"nothing is running" had to be special-cased everywhere instead of being answered by asking the app
that was. And the device had no activity stack: an app was hosted, and Back from its last screen left
a blank device rather than the screen you came from.

As an app, all of that is the platform's problem again. The launcher is the stack's root; starting an
app pushes onto it and pauses the launcher; Back pops and resumes it.

## How it sees the device

`queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)` — the call a real launcher makes. On a phone
it answers with the phone's apps; here `GuestPackageHook` answers it with the device's, reading each
archive's manifest without loading the app. Nothing in this source knows it is running anywhere
unusual, which is the point: if this app needs a special API, the device is not a device.

It declares `HOME` and `DEFAULT` but **not** `LAUNCHER`, which is what keeps it off its own home
screen and out of that query's answer.

## Build

Plain `javac` + `d8` + `aapt2`, like the other device apps — no Gradle project. There is a `res/`
directory for the launcher icon, so compile it first and pass `res.zip --java gen` to `link`, then
compile `gen\**\R.java` alongside `src`.

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $jar = "$sdk\platforms\android-33\android.jar"; $bt = "$sdk\build-tools\36.0.0"
& "$bt\aapt2.exe" compile --dir res -o res.zip
New-Item -ItemType Directory gen -Force | Out-Null
& "$bt\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 33 res.zip --java gen
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $jar -d out (Get-ChildItem src, gen -Recurse -Filter *.java | % FullName)
& "$bt\d8.bat" --min-api 24 --lib $jar --output out (Get-ChildItem out -Recurse -Filter *.class | % FullName)
Push-Location out; jar uf ..\base.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out launcher.apk aligned.apk
```

Then copy it over the bundled copy, which is what every device is built from. **That copy lives in
the Android Dev Pack, not in this repo** — the virtual device moved there at 1.7.0, so the sources
are here and the artifact is there:

```powershell
Copy-Item launcher.apk X:\jcode-extensions\android\native\vdevice\assets\vdevice\launcher.apk
```

Gradle has been seen to miss that asset when the new APK is byte-for-byte the same size as the old
one; touch it if a rebuilt launcher does not reach the device.

## The one thing to know before editing it

Do not give it a `LAUNCHER` intent filter. It would then appear on its own home screen, and — because
`GuestLoader.launchActivityOf` looks for MAIN/LAUNCHER to decide what an app's entry point is — the
container would start listing it among the apps it can launch.
