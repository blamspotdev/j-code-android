# vdevice-browser

The virtual device's **built-in browser** — an address bar, a page, and Back.

It exists so the device has a way to open a URL without reaching for the phone's browser, which
would take the user out of JCode and load the page under their own profile: their cookies, their
signed-in accounts. Inside the device, everything it loads is wiped with the device.

It is an **ordinary guest**. No container privileges, no special casing — JCode installs it onto the
device on every start like any other APK, and it goes through the same load, embed, window and
WebView paths every other app takes. That makes it a live test of those paths as much as a feature.

## Where it ends up

The signed APK is copied to `app/src/main/assets/vdevice/browser.apk`, which the root `.gitignore`
un-ignores on purpose: it is an input to the app's build.
`VirtualDeviceApps.installBuiltIns` reinstalls it after every `resetOnStart`, because a built-in is
not exempt from the clean room — it is put back into it.

## Notes on the manifest

- **`Theme.DeviceDefault.NoActionBar`** — the address bar is this app's chrome. The platform default
  for `targetSdk` 33 would add an empty dark action bar above it, a second useless header between
  the device's status bar and the only control the browser has.
- **A framework icon** (`@android:drawable/ic_menu_search`) rather than one of its own, so this stays
  a single-dex, resource-free APK that plain `javac` + `d8` + `aapt2` can build.
- `INTERNET` and `ACCESS_NETWORK_STATE` are declared, but a guest inherits JCode's permissions
  regardless — they are there so the manifest is honest about what it needs.

## Build

From this directory, with a JDK 17+ on PATH:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $jar = "$sdk\platforms\android-33\android.jar"; $bt = "$sdk\build-tools\37.0.0"
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $jar -d out (Get-ChildItem src -Recurse -Filter *.java | % FullName)
& "$bt\d8.bat" --min-api 24 --lib $jar --output out (Get-ChildItem out -Recurse -Filter *.class | % FullName)
& "$bt\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 33
Push-Location out; jar uf ..\base.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out browser.apk aligned.apk
Copy-Item browser.apk ..\..\app\src\main\assets\vdevice\browser.apk -Force
```

The copy is the step that matters — the asset is what ships.
