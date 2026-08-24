# midi-fixture

The guest that answers the question a would-be MIDI app author asks about JCode's **virtual device**:
*can I build and test an app that makes sound and speaks MIDI without leaving the IDE?*

It probes the three things such an app needs and reports every result twice — on screen and to
logcat under the tag `MIDIFIX` — so one screenshot plus one `adb logcat -s MIDIFIX` describes a whole
run:

| Section | What it measures |
|---|---|
| `WHERE` | package / pid / uid / process, `Build.MODEL`, and whether `PackageManager` knows the package at all |
| `AUDIO PROPERTIES` | `PROPERTY_OUTPUT_SAMPLE_RATE`, `PROPERTY_OUTPUT_FRAMES_PER_BUFFER`, min buffer sizes, `STREAM_MUSIC` volume, low-latency/pro feature flags, output device list |
| `AUDIOTRACK` | a 440 Hz sine through `AudioTrack`: `getState()`, play state, frames written, underruns, routed device, and `AudioManager.isMusicActive()` sampled mid-tone |
| `TONEGENERATOR` | the second audio path — native `ToneGenerator` on `STREAM_MUSIC` rather than an app-owned track |
| `MIDI` | `FEATURE_MIDI`, `getSystemService(MidiManager)`, and `getDevices()` / `getDevicesForTransport` enumerated with names, types and port counts |
| `VIRTUAL MIDI DEVICE` | whether this app's own `MidiDeviceService` was registered, asked two ways: `queryIntentServices` and whether `MidiManager` lists the device |

`STREAM_MUSIC volume` is printed because a silent phone and a broken container look identical
otherwise. **Turn the volume up before trusting a run.**

## Measured on an AYN Odin2 (Android 13, API 33)

Same APK both ways. Installed normally, versus started in the device-sandbox tab through the
virtual device's own adb daemon.

| | installed | in the container |
|---|---|---|
| identity | uid 10155, own process | uid 10166, `dev.blamspot.jcode.debug:guest`, `installed = NO`, filesDir redirected under `vdevice/` |
| `AudioTrack` | `STATE_INITIALIZED`, `PLAYSTATE_PLAYING`, 132300/132300 frames, 0 underruns, `performanceMode = 2` | **identical** |
| | `performanceMode 2` is `PERFORMANCE_MODE_POWER_SAVING` — the fixture asks for a 200 ms buffer, so neither column is a low-latency track. It is not a container limit. | |
| routed device | `BUILTIN_SPEAKER "Odin2"` | `BUILTIN_SPEAKER "JCode vDevice"` |
| `ToneGenerator` | starts, music active | **identical** |
| `PROPERTY_OUTPUT_SAMPLE_RATE` | 48000 | 48000 |
| `PROPERTY_OUTPUT_FRAMES_PER_BUFFER` | 144 | 144 |
| min buffer 44100 mono16 | 7072 | 7072 |
| `FEATURE_AUDIO_LOW_LATENCY` / `_PRO` | true / true | true / true |
| `FEATURE_MIDI` | true | true |
| `MidiManager` | non-null | non-null |
| device enumeration | works | works |
| **own `MidiDeviceService`** | **registered and listed** | **never registered, never listed** |

The one failure is the expected one, and it is structural rather than a gap that could be patched:
the platform's MIDI service finds virtual devices by scanning *installed packages*, and a guest is
by definition not one.

Enumeration and registration were separated deliberately, because a container that showed zero
devices could be failing at either. With the fixture *also* installed normally, a guest run listed
the installed copy's device (`getDevices() = 1`, `type=VIRTUAL`, `product=MIDI Fixture`). So the
container reads the MIDI device list correctly; it just cannot contribute to it.

One caveat that run also exposed: with the package separately installed, the guest's
`queryIntentServices` *still* reported `NO`. That is Android 11+ package-visibility filtering
answering JCode's uid, not the container. Only the clean run — package not installed at all — makes
that line mean what it says.

No USB or Bluetooth MIDI hardware was attached, so enumeration of *external* devices is untested in
both columns.

## It runs itself

Every probe fires on a timer from `onCreate`; no tap is needed. Relaying a tap into an embedded guest
is a *different* subsystem from the ones under test, and a fixture that needs one cannot tell "audio
is broken" from "the tap never arrived".

```
t+0.0s  WHERE, AUDIO PROPERTIES, MIDI, VIRTUAL MIDI DEVICE
t+1.2s  AudioTrack tone, 3 s        <- sample dumpsys inside this window
t+5.6s  ToneGenerator beep, 1.2 s
t+8.1s  MIDI + VIRTUAL re-scan, then "===== auto sweep done ====="
```

The buttons re-run any of it, plus a 10 s tone for a leisurely `dumpsys`.

## Proving the tone reached the mixer

No screenshot can show sound. Three pieces of evidence, in ascending order of strength:

1. `AudioTrack.getPlayState()` is `PLAYSTATE_PLAYING` and the writes return — proves only that the
   app-side object is happy.
2. `AudioManager.isMusicActive()` is true mid-tone — this crosses into AudioFlinger, so it is the
   fixture's own answer and the line labelled `AUDIBLE TONE`.
3. An active output track for the guest's pid, from outside:

```powershell
# while the 3 s tone is playing
adb shell dumpsys media.audio_flinger | Select-String -Context 2,12 'Output thread'
```

Match the `Session` column against the `sessionId` the fixture prints, and the pid against its
`pid/uid` line. Inside the container the pid is JCode's `:guest` process, not the fixture's own —
there isn't one.

## Build

No Gradle. One resource (`res/xml/midi_device_info.xml`, which the `MidiDeviceService` meta-data has
to point at), so `aapt2 compile` joins the `guest-fixture` recipe; everything else is the same.
From this directory, with a JDK 17+ on PATH:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $jar = "$sdk\platforms\android-33\android.jar"; $bt = "$sdk\build-tools\36.1.0"
New-Item -ItemType Directory -Force out, gen | Out-Null
& "$bt\aapt2.exe" compile --dir res -o res.zip
& "$bt\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 33 --java gen res.zip
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $jar -d out ((Get-ChildItem src, gen -Recurse -Filter *.java) | % FullName)
& "$bt\d8.bat" --min-api 24 --lib $jar --output out (Get-ChildItem out -Recurse -Filter *.class | % FullName)
Push-Location out; jar uf ..\base.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out midi-fixture.apk aligned.apk
```

## Use

The same APK goes both ways; the comparison is worthless otherwise.

```powershell
$s = ((adb devices) | Select-String "\sdevice$")[0] -split "`t" | Select-Object -First 1

# baseline: normally installed
& adb -s $s install -r midi-fixture.apk
& adb -s $s logcat -c
& adb -s $s shell am start -n com.example.midiguest/.MidiMain

# container: through the sandbox's own adb daemon (adb forward tcp:15620 tcp:5620; adb connect 127.0.0.1:15620)
& adb -s 127.0.0.1:15620 install midi-fixture.apk
& adb -s 127.0.0.1:15620 shell am start -n com.example.midiguest/.MidiMain
```

Take the screenshots from **bash, not PowerShell** — `>` in PowerShell re-encodes the stream and
leaves a PNG that no reader will open:

```bash
adb -s 127.0.0.1:15620 exec-out screencap -p > container.png
```

Uninstall between the two — a container run with the package still installed measures the installed
app's registrations, not the container's, and the `VIRTUAL MIDI DEVICE` section would read as a pass
it did not earn.

```powershell
& adb -s $s uninstall com.example.midiguest
```
