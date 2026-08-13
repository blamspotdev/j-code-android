# appcompat-fixture

The guest that answers the question neither [`guest-fixture`](../guest-fixture) nor
[`compose-fixture`](../compose-fixture) can: **does the guest's own resource table actually reach the
libraries it ships?**

Both older fixtures theme themselves out of `android:Theme.*` and draw with code that never looks an
attribute up, so both pass even when a guest's libraries are being answered out of JCode's dex
instead of its own APK. `AppCompatDelegate` does not: before it inflates anything it reads
`windowActionBar` off the activity's theme, using an id out of the *guest's* generated
`androidx.appcompat.R`. If the container let the IDE's class loader answer for AppCompat, that id is
the IDE's, the guest's resource table has never heard of it, and the app dies on `setContentView`
with:

> You need to use a Theme.AppCompat theme (or descendant) with this activity.

which is a true statement about a theme that is, in fact, a Theme.AppCompat descendant. See
`GuestLoader`'s class-loader parent for the fix.

So the screen is a set of assertions:

- **`AppCompat is drawing`** — `createSubDecor` got past its gate at all.
- **`colorAccent = #FF7FD1AE`** — read back off the activity's theme through
  `androidx.appcompat.R.attr.colorAccent`. The value is written in `themes.xml`; anything else means
  the attribute resolved against the wrong resource table.
- **`Open dialog`** — an `AlertDialog`, so the child-window routing in `EmbeddedWindows` is exercised
  by a themed guest.
- **`Second screen`** — intra-guest navigation, themed the same way.

Every view carries an id, which also makes this the fixture to point `uiautomator dump` at.

## Build

It is a normal Gradle project, standalone so it can be built and broken without touching JCode's
own build. From the repo root, with the repo's wrapper:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat -p tools\appcompat-fixture assembleDebug
```

The APK lands at `tools/appcompat-fixture/build/outputs/apk/debug/appcompat-fixture-debug.apk`.

## Use

Keep it **uninstalled**: the point is that the container runs it anyway.

```powershell
adb -s <jcode-vdevice> install appcompat-fixture-debug.apk
adb -s <jcode-vdevice> shell am start -n com.example.appcompatguest/.MainActivity
adb -s <jcode-vdevice> shell uiautomator dump
adb -s <jcode-vdevice> shell input tap 200 320
```
