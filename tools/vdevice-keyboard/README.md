# vdevice-keyboard

The virtual device's keyboard. Built into every device, like the browser, the camera and Files.

## What it replaces

The device had no keyboard of its own. It borrowed the phone's: `AppSandboxSurfaceView` declared
itself a text editor, held an `InputConnection`, and replayed whatever was typed into the guest as
`KeyCharacterMap` key events.

That worked, and it was the wrong shape. The keyboard was **JCode's chrome**, drawn in JCode's
window, which is outside everything that makes this device inspectable:

| | With the phone's IME | With this |
|---|---|---|
| `screencap` on the device | The keyboard is not in the picture | It is |
| `uiautomator dump` | No keys listed | Every key, with `text`, `content-desc` and bounds |
| `input tap` on a key | Nothing there to hit | Presses it |
| Typing `café` | `caf` — `KeyCharacterMap` has no key for `é` and drops it silently | `café` |
| `IME_ACTION_SEARCH` | No way to fire it | The action key says **Search** and fires it |

An agent driving the device over adb could see a text field and had no way to answer it. That is the
failure this app exists to end.

## What it is

An **ordinary guest**, with no container privileges — the same claim the Camera app makes and for
the same reason: the app that proves the device can type should not be the one app that needs special
help to run.

It is deliberately **not an activity** on the device's back stack. A keyboard has to appear over the
app being typed into without pausing it, and pushing an activity would do exactly the opposite. The
container loads this app, asks it for a `View`, and hosts that over whatever is running — the way a
phone hosts an IME's window over the app it is serving. Its own activity is its **settings**, which
is what an IME's activity is on a phone too.

## The contract

`KeyboardHost` is the only class the container talks to, reflectively, because a guest's class
loader is parented to the boot loader rather than to JCode's — so a type declared on either side is a
different class on the other. Every parameter is therefore a framework type:

```java
public KeyboardHost(Context context, Handler host);
public View view();
public void startInput(InputConnection connection, EditorInfo info);
public void finishInput();
```

That constraint is the design rather than a tax on it. `InputConnection` is the platform's own
editing contract — the object a `TextView` hands a real IME — so nothing here re-implements editing:
the container passes the focused field's connection straight through and this app commits text,
deletes it and fires the editor's action with the framework's own code.

Two things travel back, over the `Handler`, because `Message` is a framework type and an interface
would not be: `MSG_HIDE` for the hide key, and `MSG_KEY` for the one case a connection cannot express
— Enter on a single-line field that asked for no editor action, which on a phone arrives as a key.

## What it reads off the field

`EditorInfo` is what the app filled in when the container called `onCreateInputConnection`, and this
app acts on all of it:

- **`inputType` class** — a keypad for `NUMBER` and `DATETIME`, a phone pad for `PHONE`.
- **`inputType` variation** — `@` and `.` on the bottom row of an email field, `/` and `.com` on a
  URI field, and the key preview **suppressed entirely** on a password field.
- **`TYPE_TEXT_FLAG_CAP_*`** — shift comes back on where the field says a capital belongs, asked of
  `getCursorCapsMode` rather than tracked here, so it stays right when the app rewrites the text
  itself.
- **`imeOptions`** — the action key reads Go, Search, Send, Next, Done or Back, and fires that
  action. `IME_FLAG_NO_ENTER_ACTION` and multi-line fields get a newline instead.
- **`hintText`** — shown on the strip above the keys when the field has nothing more specific to say.

## Driving it over adb

Keys are real views, so they are addressable:

```bash
adb -H 127.0.0.1 -P 5038 shell uiautomator dump /sdcard/ui.xml
```

Character keys carry their own letter as both `text` and `content-desc`; the rest carry a name and a
declared id — `dev.blamspot.jcode.vdevice.keyboard:id/key_shift`, `key_backspace`, `key_action`, `key_space`,
`key_page`, `key_hide`.

The container also answers `ime`:

```bash
adb -H 127.0.0.1 -P 5038 shell ime status
```

and `input text` goes down the focused field's `InputConnection` rather than through
`KeyCharacterMap`, so accents and emoji survive.

## Settings

Arrangement (QWERTY / QWERTZ / AZERTY), key size, key preview and haptics, kept in the device's own
policy through the container's settings provider — the same route the Camera app reads its scene by.
The device's Settings app links here rather than reproducing the rows, because how it feels to type
is a thing to try while you change it, and this screen has a field to try it on.

## Build

Plain `javac` + `d8` + `aapt2`, like the other fixtures — no Gradle project.

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $jar = "$sdk\platforms\android-33\android.jar"; $bt = "$sdk\build-tools\37.0.0"
& "$bt\aapt2.exe" compile --dir res -o res.zip
& "$bt\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 33 res.zip --java gen
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $jar -d out (Get-ChildItem src,gen -Recurse -Filter *.java | % FullName)
& "$bt\d8.bat" --min-api 24 --lib $jar --output out (Get-ChildItem out -Recurse -Filter *.class | % FullName)
Push-Location out; jar uf ..\base.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out keyboard.apk aligned.apk
```

Then copy it over the bundled copy, which is what every device is built from:

```powershell
Copy-Item keyboard.apk X:\jcode-extensions\android\native\vdevice\assets\vdevice\keyboard.apk
```
