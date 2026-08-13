# hardware-fixture

The guest that says out loud what hardware the virtual device is giving it.

Every other fixture answers a question about whether an app can *run* inside the container. This one
answers a question about what the app can *reach* once it is running, which is the whole subject of
Manage permissions: the same app, unchanged and not reinstalled, should produce a visibly different
screen for each mode of each piece of hardware.

It declares camera, microphone and location in its manifest — an app that does not is refused by the
platform's own rules and would say nothing about the container — and requires none of them, because
the interesting case is running with the hardware switched off and reporting that it is off.

## What it shows

- **`checkSelfPermission`** for `CAMERA`, `RECORD_AUDIO` and `ACCESS_FINE_LOCATION`, which is where
  the device's policy has to surface for any app that asks before it reaches.
- **`hasSystemFeature`** for the six features in `VirtualHardware`, which is what the device
  *declares* it has.
- **`requestPermissions`**, behind a button. On a phone this raises a dialog; inside the device it is
  answered by the container from the policy, and this is the only way to see that the answer arrives
  at all — before it did, the callback never came and an app waiting for one simply stopped.
- **The three sensors**, by name and by value, live. The values are the tell:

  | Reading | Means |
  |---|---|
  | `ABSENT` | Off — the sensor is not in the list the guest is given |
  | `+0.00000, +0.00000, +9.80665` | Simulated — a device lying flat, face up, not moving |
  | anything that twitches | Real — the phone's own, and it is never exactly on those numbers |

- **Location**: the providers the device offers, whether GPS is enabled, the last known fix, and the
  live `requestLocationUpdates` stream. Simulated reports the fixed coordinates set in the sheet;
  Off reports no providers at all.

## Build

Plain `javac` + `d8` + `aapt2`, like the other small fixtures — no Gradle project, no resources.

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $jar = "$sdk\platforms\android-33\android.jar"; $bt = "$sdk\build-tools\37.0.0"
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $jar -d out (Get-ChildItem src -Recurse -Filter *.java | % FullName)
& "$bt\d8.bat" --min-api 24 --lib $jar --output out (Get-ChildItem out -Recurse -Filter *.class | % FullName)
& "$bt\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 33
Push-Location out; jar uf ..\base.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out hwfixture.apk aligned.apk
```

Then push it somewhere J Code can read and install it from the device's **Install an app** sheet:

```powershell
adb push hwfixture.apk /sdcard/JCode/hwfixture.apk
```

The device is wiped on every J Code start, so the install has to be repeated after each rebuild of
the IDE. Note also that opening an app from the launcher rewrites the sheet's APK path to the
*installed* copy — reinstalling without retyping the source path reinstalls the build already there.
