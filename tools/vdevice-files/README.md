# vdevice-files

The virtual device's file explorer, which is also its file and folder picker. Built into every
device, like the browser and the camera.

Two jobs on purpose. A device wants somewhere to look at what is on it — until now the only way to
see the device's storage was `adb ls` from outside — and an app that opens a document wants a picker.
On a phone those are the same app, and making them the same app here means the screen that answers
`ACTION_OPEN_DOCUMENT` is one somebody has actually used, rather than a dialog that only ever appears
on somebody else's behalf.

The SAF filters are what make it resolvable: an app that calls `resolveActivity` before offering an
"attach a file" button needs something installed to find. The container used to draw a picker itself,
which no `PackageManager` query could ever have found.

## What it is asked to do

| Started by | Mode |
|---|---|
| The launcher | Browse. Tapping a file says what it is; nothing is returned |
| `ACTION_OPEN_DOCUMENT`, `ACTION_GET_CONTENT` | Pick one file |
| `ACTION_CREATE_DOCUMENT` | Choose a folder and type a name; the file is created empty |
| `ACTION_OPEN_DOCUMENT_TREE` | Pick the folder you are looking at |

Back walks up the tree before it leaves, which is what a file explorer's Back does.

## What it can do to what it finds

An explorer that can only look is a listing. There is a **New folder** action and a **Sort** chip in
the bar above the list — name, newest, largest — and a long press on any row opens **Rename**,
**Delete** and **Details**. Deleting a folder takes what is in it, and says how much that is before
it does.

The list itself carries what somebody scans for rather than only names: a tinted icon by kind, item
counts and dates on folders, sizes on files, and the two volumes on the first screen with what each
one means underneath — `/sdcard` *emptied when JCode starts*, `/storage/external` *kept in your
workspace*. That is the same design the device's Settings app is built from; both are rounded
surfaces, tinted icon chips and section labels, made from `GradientDrawable` in code.

## How an answer gets back

The device path goes back under `dev.jcode.vdevice.DEVICE_PATH`, and the **container** turns it into
the `content://` URI the requesting app receives — a tree URI for a folder request, a document URI
otherwise. That split is deliberate: the URI belongs to JCode's own documents provider, whose
authority and document-id encoding are the container's business, and an app that guessed at them
would be coupled to a format it cannot see change. What this app knows is which file the person
chose, which is the part it is qualified to answer.

## The one thing to know before editing it

**`/sdcard` is a presentation path.** The bytes live in JCode's app-private tree and the container
redirects the `Context` storage APIs onto it; `Environment.getExternalStorageDirectory()` is *not*
among them and still answers the phone's path. So `new File("/sdcard/…")` in an app here reads the
**user's real storage** — and a file explorer doing that would show somebody their own photos and
call them the device's. `DeviceStorage` derives the root from `getExternalFilesDir(null)`, which is
redirected, by walking up the four names of the documented `Android/data/<pkg>/files` layout.

## Build

Plain `javac` + `d8` + `aapt2`, like the other fixtures — no Gradle project. There **is** a `res/`
directory, for the launcher icon and the glyphs in the list; `aapt2` compiles one on its own, so
compile it first and pass `res.zip --java gen` to `link`, then compile `gen\**\R.java` alongside
`src`.

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $jar = "$sdk\platforms\android-33\android.jar"; $bt = "$sdk\build-tools\37.0.0"
& "$bt\aapt2.exe" compile --dir res -o res.zip
New-Item -ItemType Directory gen -Force | Out-Null
& "$bt\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 33 res.zip --java gen
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $jar -d out (Get-ChildItem src, gen -Recurse -Filter *.java | % FullName)
& "$bt\d8.bat" --min-api 24 --lib $jar --output out (Get-ChildItem out -Recurse -Filter *.class | % FullName)
Push-Location out; jar uf ..\base.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out files.apk aligned.apk
```

Then copy it over the bundled copy, which is what every device is built from:

```powershell
Copy-Item files.apk ..\..\app\src\main\assets\vdevice\files.apk
```
