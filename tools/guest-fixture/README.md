# guest-fixture

A deliberately tiny Android app used to test JCode's **virtual device** container — the path that
runs a user's APK without installing it and without ADB.

It is not a demo. It exists to make one question answerable: *is the app really containerised, or
did it just get installed and launched normally?* So it prints, both to logcat (tag `GUESTAPP`) and
on screen, the identity it observes:

- `getPackageName()` and the process uid
- `Build.MODEL` / `Build.DEVICE` / `Build.FINGERPRINT`
- `Settings.Secure.ANDROID_ID`
- `getFilesDir()`
- `Runtime.availableProcessors()`

`SecondActivity` exists so intra-app `startActivity` can be exercised, which the container has to
intercept and route onto a stub.

The dialog, popup-menu and spinner controls exist for the app-sandbox **tab**: each of those is a
separate window rather than a view, so the embedded container has to host it in the
`SurfaceControlViewHost`'s own windowless session, size it, place it and relay input into it. The
"last: …" line reports what was picked, so one screenshot shows the window was interactive.

## Baseline — normally installed on an AYN Odin2 (Android 13)

```
package     = com.example.guestapp
uid         = 10169            (its own)
Build.MODEL = Odin2
Build.DEVICE= kalama
ANDROID_ID  = 7d9324d0c48813a8
filesDir    = /data/user/0/com.example.guestapp/files
cpus        = 8
```

## What the container should change

Running the same APK **uninstalled**, inside the container, the design goal is *different identity,
same hardware*:

| Field | Expected inside the container |
|---|---|
| uid | JCode's uid, not 10169 — this is the proof it is contained |
| `filesDir` | redirected under JCode's own storage |
| `Build.MODEL` | a virtual value, not `Odin2` |
| `ANDROID_ID` | a virtual value (needs a provider hook; may still be the host's) |
| cpus | still **8** — hardware is shared, not emulated |

## Build

No Gradle: it is a single-dex, resource-free APK, so plain `javac` + `d8` + `aapt2` + `apksigner`
is enough. From this directory, with a JDK 17+ on PATH:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $jar = "$sdk\platforms\android-33\android.jar"; $bt = "$sdk\build-tools\36.1.0"
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $jar -d out (Get-ChildItem src -Recurse -Filter *.java | % FullName)
& "$bt\d8.bat" --min-api 24 --lib $jar --output out (Get-ChildItem out -Recurse -Filter *.class | % FullName)
& "$bt\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 33
Push-Location out; jar uf ..\base.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out guest.apk aligned.apk
```

Keep it **uninstalled** on the test device — the whole point is that the container runs it anyway.
`adb shell pm list packages | grep guestapp` should come back empty before a container test.
