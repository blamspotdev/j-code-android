# gl-fixture

Clears the screen to magenta, and nothing else.

It exists because every real GL app has something in the way — a setup wizard, a licence check, a
first-run flow — so a black screen could always mean either *"the container cannot composite a
`SurfaceView`"* or *"this app has not drawn anything yet"*. Chasing that ambiguity through ES-DE and
PPSSPP cost far more than writing this did.

| What the tab shows | What it means |
|---|---|
| Magenta | GL compositing works in the embedded tab |
| Black, with the label | The activity rendered and only the surface is missing |
| Black, no label | The guest did not render at all |

The label is a plain `View` sibling *over* the GL surface on purpose, to separate the last two. Note
that when `GuestSurfaces` raises a full-bleed surface above the window, the label is covered — that
is the documented cost of the raise, and seeing magenta *without* the label is the expected pass.

`adb exec-out screencap` against the virtual device will **not** show this fixture's colour:
`EmbeddedGuest.capture` re-draws the view hierarchy, and a `SurfaceView`'s pixels are not in it. Take
the screenshot of the phone instead, where the tab composites the real surface.

## Verified

Magenta in the tab on an Odin2 (Android 13), with:

```
I GLFIXTURE: onSurfaceCreated: GL is up, renderer=Adreno (TM) 740
I GLFIXTURE: onSurfaceChanged: 1080x1510
I VDEVICE  : raised android.opengl.GLSurfaceView above the window so it can be seen
```

## Build

From this directory, with a JDK 17+ on PATH:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $jar = "$sdk\platforms\android-33\android.jar"; $bt = "$sdk\build-tools\37.0.0"
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $jar -d out (Get-ChildItem src -Recurse -Filter *.java | % FullName)
& "$bt\d8.bat" --min-api 24 --lib $jar --output out (Get-ChildItem out -Recurse -Filter *.class | % FullName)
& "$bt\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 33
Push-Location out; jar uf ..\base.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out glfixture.apk aligned.apk
```
