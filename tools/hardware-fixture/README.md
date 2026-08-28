# hardware-fixture

The guest that says out loud what hardware the virtual device is giving it.

Every other fixture answers a question about whether an app can *run* inside the container. This one
answers a question about what the app can *reach* once it is running, which is the whole subject of
Manage permissions: the same app, unchanged and not reinstalled, should produce a visibly different
screen for each mode of each piece of hardware.

It declares camera, microphone and location in its manifest — an app that does not is refused by the
platform's own rules and would say nothing about the container — and requires none of them, because
the interesting case is running with the hardware switched off and reporting that it is off.

It is also the only guest here with an **action bar**, which makes it the fixture for the other half
of hosting an app properly: not what the app can reach, but whether the device gives it a window it
can lay itself out in. Every other fixture declares `NoActionBar`, so nothing was checking that the
container can host the screen furniture the *platform* builds — the decor layout with a bar in it,
the title the framework puts there, the options menu, and the overflow popup that opens in a window
of its own.

## What it shows

- **The window it was given**, at the top of the report and worth reading first:

  | Line | What a wrong answer means |
  |---|---|
  | `action bar = showing` | `none` — the theme asked for one and the container did not produce it |
  | `its title = "Hardware Fixture"` | `EMPTY` — the bar was built but `onPostCreate` never ran, so `mTitleReady` stayed false and the title never reached the window |
  | `insets = status N, nav 0, ime 0` | the phone's numbers, or all zeros in "draw behind it" mode — the app is being told about the wrong device |
  | `content = WxH px` | a height taller than the window, which is an app laid out for a screen it does not have |

  The **Status bar** items in the overflow move the window between the three shapes the container
  reads, and each should produce a visibly different screen for the same app:

  | Asked for | The device should | The insets should say |
  |---|---|---|
  | *sit below it* | paint its bar the app's own colour, app starts underneath | `status 0` — the window already excludes the bar |
  | *draw behind it* | float a transparent bar over the app, **app bar moves down by itself** | `status N` — the app owes its own padding, and the framework pays it |
  | *take the screen* | remove the bar entirely | `status 0`, reported `hidden` |

  Nothing in this fixture pads itself. The action bar sliding below the device's clock in "draw
  behind it" mode is the framework doing that with the insets the container substituted, which is
  the thing being tested.

- **`checkSelfPermission`** for `CAMERA`, `RECORD_AUDIO` and `ACCESS_FINE_LOCATION`, which is where
  the device's policy has to surface for any app that asks before it reaches.
- **`hasSystemFeature`** for the six features in `VirtualHardware`, which is what the device
  *declares* it has.
- **`requestPermissions`**, behind a button. On a phone this raises a dialog; inside the device it
  raises the device's own, and this is the only way to see that the answer arrives at all — before it
  did, the callback never came and an app waiting for one simply stopped. Note that an activity gets
  **one** request: the platform refuses a second, and the button then reports "answered with
  nothing", which is the platform's cancellation rather than a fault here. Reopening the app clears
  it.
- **The three sensors**, by name and by value, live. The values are the tell:

  | Reading | Means |
  |---|---|
  | `ABSENT` | Off — the sensor is not in the list the guest is given |
  | `+0.00000, +0.00000, +9.80665` | Simulated — a device lying flat, face up, not moving |
  | anything that twitches | Real — the phone's own, and it is never exactly on those numbers |

- **`ACTION_IMAGE_CAPTURE`**, behind a button, reporting the size of the thumbnail that comes back.
  Deliberately sent with no `EXTRA_OUTPUT`, so the answer is the contract's thumbnail — which
  exercises the whole round trip: the intent resolving to the device's **Camera app**, that app
  running as an ordinary guest, and its result reaching an embedded activity at all. With the camera
  Simulated this reads `got a 512x384 thumbnail` and the full-size JPEG is in the device's
  `DCIM/Camera`; with it Off, the Camera app says the device has none.
- **`ACTION_OPEN_DOCUMENT`**, behind a button, answered by the device's **Files app**. It reports the
  first bytes it could read back through the returned `content://` URI — `read 30 bytes` — because a
  URI that resolves to nothing looks identical to one that works until something tries it, and "the
  picker returned a URI" was never the interesting claim.
- **Location**: the providers the device offers, whether GPS is enabled, the last known fix, and the
  live `requestLocationUpdates` stream. Simulated reports whatever the hardware bench says — a fixed
  point, or a position walked along a route; Off reports no providers at all.

The bench is the other half of this fixture: open **Device hardware** beside the device, set an
attitude or start a loop, and the numbers here should move with it. Two readings worth knowing,
because they are exact rather than approximate and so make a wrong sign obvious:

| Bench setting | What this should read |
|---|---|
| Spin, 4 s period | gyroscope `+0.00000, +0.00000, -1.57080` — that is −2π/4 s — with gravity unmoved at `+0.00000, +0.00000, +9.80665` |
| Landscape ◀ | accelerometer `+9.80665, +0.00000, +0.00000` |

The other half is that both settings are required. With the camera Simulated on the bench, everything
else Off, and Allow tapped for all three on the device's prompt, this app is answered
`CAMERA=granted RECORD_AUDIO=denied ACCESS_FINE_LOCATION=denied` — allowed by the app's rule, refused
because the device has no microphone and no GPS.

## Build

Plain `javac` + `d8` + `aapt2`, like the other small fixtures — no Gradle project. It does have
resources (a launcher icon), so `aapt2 compile` comes first and the link is given the result; a link
without it fails on the manifest's `@drawable/ic_launcher`.

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $jar = "$sdk\platforms\android-33\android.jar"; $bt = "$sdk\build-tools\37.0.0"
& "$bt\aapt2.exe" compile --dir res -o res.zip
& "$bt\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 33 res.zip --java gen
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $jar -d out (Get-ChildItem src,gen -Recurse -Filter *.java | % FullName)
& "$bt\d8.bat" --min-api 24 --lib $jar --output out (Get-ChildItem out -Recurse -Filter *.class | % FullName)
Push-Location out; jar uf ..\base.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out hwfixture.apk aligned.apk
```

Then copy it over the bundled copy, which is what every device is built from:

```powershell
Copy-Item hwfixture.apk X:\jcode-extensions\android\native\vdevice\assets\vdevice\hardware.apk
```

Being bundled is what makes it survive a wipe: the device is rebuilt on every JCode start and
installs whatever is in `assets/vdevice`, so there is nothing to push and nothing to reinstall. A
one-off build can still go in through the device's **Install an app** sheet from
`adb push hwfixture.apk /sdcard/JCode/hwfixture.apk`, but it is gone on the next start — and note
that opening an app from the launcher rewrites the sheet's APK path to the *installed* copy, so
reinstalling without retyping the source path reinstalls the build already there.
