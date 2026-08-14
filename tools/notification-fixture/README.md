# notification-fixture

A tiny guest that posts notifications, so the virtual device's **notification service** and its
**status bar and shade** have something real to catch and draw.

Two notifications go out from `onCreate`, which makes a plain launch enough to prove the path end to
end. The buttons post more and cancel, which is what exercises the shade *updating* rather than
merely being populated once — and because they sit below the status bar, tapping them also proves
the bar did not steal the guest's touches.

It deliberately declares **no `android:theme`**, so it doubles as a regression test for the platform
default-theme fallback the container has to reproduce (`selectDefaultTheme`).

## What should happen

| | Expected |
|---|---|
| The device's status bar | `Notify Fixture` on the left, `2 notifications` on the right — no clock, no battery |
| Drag down from the top strip | The shade opens with both notifications, title and text, and `Clear all` |
| `dumpsys notification` on the **host** | Zero `Fixture note` entries — they never reached the phone |
| `uiautomator dump` on the device | Lists the bar and every shade row, so an agent can read and tap them |

## Build

No Gradle: single-dex and resource-free, so `javac` + `d8` + `aapt2` + `apksigner` is enough. From
this directory, with a JDK 17+ on PATH:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $jar = "$sdk\platforms\android-33\android.jar"; $bt = "$sdk\build-tools\37.0.0"
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $jar -d out (Get-ChildItem src -Recurse -Filter *.java | % FullName)
& "$bt\d8.bat" --min-api 24 --lib $jar --output out (Get-ChildItem out -Recurse -Filter *.class | % FullName)
& "$bt\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 33
Push-Location out; jar uf ..\base.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out notify.apk aligned.apk
```

Then, against a running virtual device:

```bash
adb -s 127.0.0.1:5620 install notify.apk
adb -s 127.0.0.1:5620 shell am start -n com.example.notifyguest/com.example.notifyguest.NotifyMain
```

Keep it **uninstalled** on the host — the point is that the container runs it anyway.
