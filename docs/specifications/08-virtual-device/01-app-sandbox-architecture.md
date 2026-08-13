# App sandbox architecture

| | |
|---|---|
| **Status** | Implemented — device-verified on Android 13 |
| **Modules** | `:app` (`dev.jcode.vdevice`) |
| **Primary sources** | app/src/main/java/dev/jcode/vdevice/VirtualDevice.kt, VirtualDeviceApps.kt, VirtualDevicePolicy.kt, SimulatedHardware.kt, VirtualDeviceLog.kt, VirtualLauncher.kt, VirtualWallpaper.kt, VirtualInput.kt, GuestHierarchy.kt, UiXml.kt, AppSandbox.kt, AppSandboxPage.kt, AppPermissionsSheet.kt, VirtualHardwarePage.kt, AppSandboxSurfaceView.kt, EmbeddedGuest.kt, GuestSessionService.kt, GuestBootstrapActivity.kt, GuestActivity.kt, VirtualScreen.kt, app/src/main/aidl/dev/jcode/vdevice/IGuestSession.aidl, app/src/main/aidl/dev/jcode/vdevice/IGuestSessionCallback.aidl, app/src/main/AndroidManifest.xml |
| **Verified against** | device-verified on Android 13, 2026-08-13 |

---

## 1. Purpose and scope

Running a built APK **inside JCode** — no install, no ADB, no root — so a developer can build and
try an Android app on the same device, in an editor tab.

> **This is a sandboxed preview, not a security boundary.** The guest runs in a separate *process*
> of JCode, but shares JCode's uid, permissions and data directory. Never run untrusted APKs in it.

---

## 2. Two presentation modes

| Mode | Entry | Window | Used when |
|---|---|---|---|
| **Embedded** | `AppSandboxPage` → `GuestSessionService` → `EmbeddedGuest` | Window-less, composited into a `SurfaceView` in an editor tab | The window supports embedding |
| **Full-screen** | `VirtualDevice.launch` → `GuestBootstrapActivity` → `GuestActivity0..3` | A real activity in its own task | Otherwise, or by choice |

```kotlin
internal enum class AppSandboxTier { Embedded, FullScreen }
```

The tier is chosen by two things: embedding is impossible without hardware acceleration, and
**Settings → Virtual device → "Always run in full screen"** lets the user insist on a real window.

That switch exists because embedding is a trade, not a strict improvement. It buys the IDE around the
app and a screen an agent can read, and it costs the guest a real window: its activity token is one
no `ActivityRecord` answers to, so anything asking the activity manager about itself is answered by
the container rather than the system (see
[Guest runtime §4b](02-guest-runtime-and-hidden-api.md#4b-the-embedded-activitys-token--guestactivityclient)).
An app that wants a real task — its own recents entry, a `PendingIntent` the system will act on, an
SDK that interrogates its own activity — is better served by the full-screen path, and for those the
switch is the answer rather than a workaround for a bug.

`AppSandbox.alwaysFullScreen` holds it rather than the DataStore being read at each call site,
because the tab and the adb daemon's `am start` both have to give the same answer and the daemon is
not a composable. `am start --windowingMode 1` still asks for full screen explicitly.

### 2.1 Why not a virtual display

Putting a **system-launched** activity on a display the app owns is impossible for a normal app:
`ActivityOptions.setLaunchDisplayId` requires the `signature|privileged` `ACTIVITY_EMBEDDING`
permission.

`SurfaceControlViewHost` is what makes the embedded path permission-free — it exists precisely to
let one process's views be composited inside another's, the IDE and `:guest` share a uid, and unlike
a virtual display it asks nothing of the activity task manager.

---

## 3. Process topology

```mermaid
flowchart LR
    subgraph ide["dev.jcode (main)"]
        page["AppSandboxPage"]
        sv["AppSandboxSurfaceView"]
        sandbox["AppSandbox (bind/unbind)"]
    end
    subgraph guest["dev.jcode:guest"]
        svc["GuestSessionService (IGuestSession)"]
        eg["EmbeddedGuest"]
        gr["GuestRuntime + hooks"]
        apk["the guest APK's activity"]
    end
    sandbox -- "bindService" --> svc
    svc --> eg --> gr --> apk
    eg -- "SurfacePackage" --> sv
    sv -- "MotionEvent / KeyEvent / text" --> svc
```

Declared in `AndroidManifest.xml` with `android:process=":guest"`:
`GuestBootstrapActivity` (translucent, `excludeFromRecents`), `GuestActivity0`–`GuestActivity3`
(`launchMode="standard"`, `Theme.DeviceDefault.NoActionBar`, a deliberately broad `configChanges`
set), and `GuestSessionService`.

The manifest comment explains why the stubs exist at all:

> …the stubs the app-virtualization container launches in place of a guest APK's own activities,
> since the system cannot be asked to start an activity of a package it has never heard of.
> …they are never used as themselves — `GuestRuntime` swaps in the guest's class before the system
> instantiates them. `launchMode` is standard so one stub can back several guest instances, and
> `configChanges` is deliberately broad so the guest handles rotation itself instead of being
> relaunched through the stub.

Four stubs means up to four concurrent guest activities.

**Unbinding `GuestSessionService` is what tears a session down** — nothing else keeps the `:guest`
process alive.

---

## 4. The AIDL surface

```java
interface IGuestSession {
    Bundle start(String apkPath, String activityClass, int width, int height,
                 IBinder hostToken, IGuestSessionCallback callback);
    Bundle surface();
    Bundle capture(String pngPath);
    Bundle dump(String xmlPath);

    oneway void resize(int width, int height);
    oneway void touch(in MotionEvent event);
    oneway void key(in KeyEvent event);
    oneway void text(String text);
    oneway void back();
}

interface IGuestSessionCallback {
    oneway void onGuestFinished(String reason);
}
```

Design decisions recorded in the AIDL itself:

| Decision | Reason |
|---|---|
| `start` returns a `Bundle` | So a failure comes back as a **message** rather than an exception across the binder |
| `hostToken` is **required** | It is the `SurfaceView`'s input token. Without it the window manager refuses to grant the embedded hierarchy an input channel at all, so a null token is refused with a message rather than embedded |
| `surface()` exists separately | A `SurfaceView` releases its surface package when it detaches, so switching editor tabs and back needs a **new** package rather than a session restart |
| `capture(pngPath)` writes a **file** | The processes share a uid and a data directory, so a file beats a `Bundle` that a large screen would burst |
| `dump(xmlPath)` likewise | `GuestHierarchy` walks the guest's live view tree into uiautomator-shaped XML; a deep tree outgrows a `Bundle` for the same reason a screen does |
| Everything else is `oneway` | It is called from the IDE's UI thread and must never block on the guest's |

---

## 5. Input injection

Having an input channel is **not** the same as being fed by it. Measured on Android 13, touches over
the embedded hierarchy are not dispatched to it by the system, so `AppSandboxSurfaceView` forwards
raw `MotionEvent`s, `KeyEvent`s and typed text over AIDL, and `EmbeddedGuest` routes each to
whichever guest window is topmost.

`back()` pops the embedded back stack, or sends Back to the only activity.

Child windows (dialogs, popups, spinners) need extra work — see
[Guest runtime and hidden API](02-guest-runtime-and-hidden-api.md).

---

## 6. `VirtualDevice`

```kotlin
data class VirtualDeviceApp(
    val packageName: String, val label: String,
    val versionName: String?, val activities: List<String>,   // fully-qualified, manifest order
)

fun inspect(context: Context, apkPath: String): Result<VirtualDeviceApp>
fun launch(...)
```

`inspect` uses `PackageManager.getPackageArchiveInfo` with `GET_ACTIVITIES or GET_META_DATA` —
**public API only**, so it is safe to call from the IDE process. It fails with
`VirtualDeviceException` for an unreadable APK or one with no `<application>`.

`launch` is the full-screen path: a real activity, in its own task, with everything a real window
brings. `GuestOverlay.install()` adds a floating pill and a back/close bar, because JCode draws
nothing over a full-screen guest.

---

## 7. Screen capture

`VirtualScreen` renders the guest's current screen to a PNG.

> `screencap -d <displayId>` returns **0 bytes** for this kind of surface, and `PixelCopy` over the
> tab's `SurfaceView` answers `ERROR_SOURCE_NO_DATA` — the guest's pixels live in a `SurfaceControl`
> the view only *parents*. So the screen is asked of whoever is drawing it: the guest re-draws its
> own hierarchy (`EmbeddedGuest.capture`), and an idle device draws its home screen — see §7a.

This is what backs `adb shell screencap` against the virtual device — see
[ADB bridge §10](../03-runtime/05-adb-bridge.md#10-adbdaemon--serving-the-virtual-device).

---

## 7a. The device with nothing on it

The tab's resting state is a **device**, not an empty panel: `VirtualWallpaper` (dark grey, outlined
square/triangle/circle), the device's name, and either the installed apps as icons or the words
"No app installed".

**The launcher is device content, not IDE chrome, and that is a correctness property rather than a
style.** It is drawn onto the `SurfaceView`'s **own surface** by `VirtualLauncher` — not composed
over it, since a `SurfaceView` punches a hole in the window and nothing behind it is ever visible —
and `VirtualScreen.blank` draws it through the same code. So:

| | Answered by |
|---|---|
| What the tab shows | `VirtualLauncher.draw` onto the surface |
| What `screencap` returns | `VirtualLauncher.draw` into a bitmap |
| Where a finger lands | `VirtualLauncher.hit`, from `AppSandboxSurfaceView` |
| Where `input tap` lands | `VirtualLauncher.hit`, from `VirtualDeviceAdbService` |
| What `uiautomator dump` lists | `VirtualLauncher.dump`, off the same `tiles` |

One `tiles(width, height, density, apps)` behind all five, so **what an agent screenshots is where
its taps land**. Drawing the launcher a second time for the capture would have put the icons it sees
in one place and the taps it sends in another.

What is *not* on the device's screen is JCode's "Install an app" button: it is the IDE reaching onto
the device, so it is composed over the surface and is deliberately absent from a capture, exactly
like the control bar. A guest's surface package reparents above the surface, so starting an app needs
no matching erase and stopping one repaints.

### 7b. The device's status bar and shade

A slim bar across the top of the device's screen, plus a notification shade that pulls down from it.

**The bar is persistent — a property of the device, not of whatever app is on it.** It is drawn
twice, because the device's screen is drawn two ways, and both must produce the same strip:

| While | Drawn by | Shows |
|---|---|---|
| An app is running | `VirtualStatusBar`, real views in the guest's container | The app's label, and what it has posted |
| The home screen | `VirtualLauncher.drawStatusBar`, canvas | The device's name |

One height and one palette (`VirtualStatusBar`'s companion) behind both, so the strip does not change
shape the moment an app starts.

**The guest's window stops where the bar starts.** Its decor view is added with a top margin of the
bar's height, and `GuestWindow.applySize` is told that reduced height, so the app both lays out for
the space it has and cannot draw into the strip. Measured before this: NewPipe's toolbar came out
with "Trending" half-hidden behind the device's own name.

A margin rather than dispatched insets, deliberately: insets only help an app that reads them, and
one that does not would still draw underneath. A phone does not ask an app to avoid the status bar —
it gives the app a window that does not include it — and a window that stops at the bar is true for
every guest however it lays itself out. Touch, capture and `uiautomator dump` all go through the
container, so the offset applies to all three at once and a dumped node's bounds are still exactly
where `input tap` lands. On the home screen it carries the device's name and nothing else:
there is no app to report the state of and no notifications to count, because the guest process is
what holds them and there is no guest process.

**No clock and no battery, deliberately.** Those belong to the phone, and the phone's own status bar
is directly above this one — a second copy would be either a duplicate or a lie. What the device has
that the phone's bar cannot show is the state of the app *inside* it, so that is all it carries: the
running app's label on the left, and what it has posted on the right.

| Gesture | Effect |
|---|---|
| Drag down from the top strip | Opens the shade: one row per notification, title and text, with **Clear all** |
| Drag up, or tap below an open shade | Closes it |
| `back` | Closes the shade first, the way a phone answers, before the guest ever sees the key |
| Tap the pill in the middle | JCode's own control bar — back, keyboard, restart, full screen, stop. IDE chrome, not the device's |

It is a child of `EmbeddedGuest`'s container, added **last** so it is the topmost view, rather than
composed over the tab by the IDE. That one decision is what makes it part of the device:

| | Falls out of being a child of the container |
|---|---|
| `screencap` shows it | `EmbeddedGuest.capture` draws the container |
| `uiautomator dump` lists it | `EmbeddedGuest.dump` walks the container, and these are real views with real text |
| A finger and `input tap` reach it | Both arrive through `EmbeddedGuest.touch`, which dispatches into the container |

Only vertical movement in the top strip is claimed. A horizontal swipe starting at the top of the
screen belongs to the guest, because pagers live there.

> Verified on the Odin2: `uiautomator dump` against a guest with the shade open lists
> `Notify Fixture`, `2 notifications`, both notification rows with their text, and `Clear all` — so
> an agent can read the device's notifications and tap them, not just a person.

#### What a notification carries

| | |
|---|---|
| **Icon** | The notification's own `smallIcon`, loaded against the **guest's** context — its resource id is from the guest's table and a package the real `PackageManager` has never heard of, so JCode's context would resolve nothing, or worse, whatever JCode has at that id. Falls back to the app icon. One per app in the bar, deduped; one per row in the shade |
| **Actions** | `Notification.actions`, drawn as buttons that fire the `PendingIntent` |
| **Ongoing** | `FLAG_ONGOING_EVENT` or `FLAG_NO_CLEAR`, **and** anything handed to `startForeground` — the platform treats a foreground service's notification as ongoing whether or not the app also said so, and most do not because on a phone they never had to. Measured on NewPipe, whose player notification arrives with no flags at all |

**Clear all leaves the ongoing ones**, as a phone does. Not decoration: a media player's notification
*is* its transport controls and a download's is its progress, so sweeping them would take a running
app's only handle away while the app kept running. Only the app takes those down.

> Verified: three notes posted, one ongoing; Clear all leaves `Ongoing note 3` and the count goes
> from `3 notifications` to `1 notification`.

**A guest's buttons do not reach the guest's own components, and cannot from here.** The token is
real — minted under JCode's package by `GuestActivityManagerHook` — but the *component* inside it
names a package the real system has never heard of, so it resolves to nothing and reports no error.
Neither end can be caught: `PendingIntent.send` marshals the token to the real activity manager
rather than calling anything this process can stand in front of (traced across a tap, a wrapped
`IIntentSender` sees `asBinder` and never `send`), and the intent inside cannot be recovered either
because `PendingIntent.mTarget` is **blocked** at `targetSdk` 33 —
`NoSuchFieldException: No field mTarget`. Buttons aimed anywhere the real system can reach work
normally; what is lost is an app's buttons on its own screens, where its own UI still has them.

#### The bar follows the app under it

A bar that is the same colour and the same presence over every app is not a device's status bar, it
is a strip JCode drew. `GuestWindow.statusBarStyleOf` reads the foreground activity's window, the
same places the platform reads it, and `EmbeddedGuest.followForegroundApp` reshapes the bar around
the answer — on every layout pass, because an app changes its mind at runtime (full screen for a
video, back afterwards) and nothing else tells the container.

| The app sets | The bar |
|---|---|
| `FLAG_FULLSCREEN`, or `SYSTEM_UI_FLAG_FULLSCREEN` | is gone, and the guest's window grows into the space |
| `FLAG_TRANSLUCENT_STATUS`, `FLAG_LAYOUT_NO_LIMITS`, or a transparent `statusBarColor` | floats over the app rather than pushing it down |
| an opaque `statusBarColor` | is painted that colour, with the app below it |
| `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` / `APPEARANCE_LIGHT_STATUS_BARS` | switches to dark markings, so a pale bar stays readable |

The guest's `Configuration` is re-sized whenever that changes, not just its margin: a window that
gains or loses the bar's height has to measure again for it.

> Verified: NewPipe's bar is painted `0xFF992722`, its own dark red, continuous with its toolbar.

### 7c. The device's built-in apps

`filesDir/vdevice/` is emptied on every start, so anything that should always be on the device has to
be put back — a built-in is not exempt from the clean room, it is reinstalled into it.
`installBuiltIns` does that from `assets/vdevice/*.apk` immediately after the wipe, through the same
`install` path any other APK takes.

Today that is one app: **the browser** (`tools/vdevice-browser`). It exists so the device can open a
URL without reaching for the phone's browser, which would take the user out of JCode and load the
page under their own profile — their cookies, their signed-in accounts. Inside the device, what it
loads is wiped with the device, including the WebView profile (§7d).

It is an ordinary guest with no container privileges, which makes it a live test of the load, embed,
window and WebView paths as much as a feature.

### 7d. What a guest leaves behind

Nothing, and WebView was the exception that proved it needed saying. WebView keeps its profile beside
JCode's own — under the suffix `GuestRuntime` gives it, which is what stops the two colliding — and
that directory is outside `filesDir/vdevice/`, so nothing in the reset ever touched it. Cookies,
local storage and any session an app signed into survived a restart and would have been handed to
whatever app was installed next. `resetOnStart` now clears it too.

### `VirtualDeviceApps` — the package store

`filesDir/vdevice/apps/<package>.apk`, with each app's private storage beside it at
`filesDir/vdevice/<package>/`. One store behind all three readers: the launcher grid, the adb
daemon's `pm`/`am`, and the start-up reset.

An app bundle's config splits go in `filesDir/vdevice/apps/<package>.splits/`, a directory *beside*
the base rather than one replacing it — so every reader that already knew where a package's APK is
keeps working unchanged, and `GuestLoader` finds the rest by the same convention without anything
crossing the binder. `adb install-multiple` stages a session under `apps/session-<n>/` and commits it
whole; the base is whichever staged file parses as a package on its own, since a config split's
manifest has no `<application>` in it.

> **Nothing survives a restart.** `resetOnStart` empties `filesDir/vdevice/` once per process — no
> apps, no data, no preferences a previous run left. Both the workbench (`MainViewModel` init) and
> `VirtualDeviceAdbService.daemon` call it, because those two race and the loser must not wipe what
> the winner just installed; `@Synchronized` plus a one-shot flag makes the second call a no-op.

A `revision` snapshot-state counter is bumped on every install/uninstall, so an `adb install` shows
up on the home screen without the tab being touched.

The device is reachable on its own through the `tools.virtualDevice` palette command
("Open Virtual Device") — otherwise the tab only ever appears when a virtual-device build finishes,
which is no way to reach the apps already installed on it.

### 7e. Two settings, not one — `VirtualDevicePolicy`

What an app may reach is two questions with two answers, kept apart because they are about different
things: what the **device** has, and what the **app** may do with it.

**What the device has**, once for the whole device, on the hardware bench — a phone has one camera
however many apps read it:

| Hardware | Modes | Default | What each mode is |
|---|---|---|---|
| Camera | Off, Simulated | Off | Simulated gives the device a camera; **no frame ever arrives** — Camera2 is a native binder pipeline the container cannot stand in for, and the phone's is deliberately not on offer |
| Microphone | Off, Simulated, Real | Off | Real is the phone's, and is the one setting here that asks the user for something: `RECORD_AUDIO` is requested at the moment it is chosen |
| Location | Off, Simulated | Off | A fix the user sets, and a route it can walk. The phone's own location is never offered |
| Accelerometer | Off, Simulated, Real | **Simulated** | Simulated reports the attitude and motion set on the bench |
| Compass | Off, Simulated, Real | **Simulated** | Real is offered only if the phone has a magnetometer |
| Gyroscope | Off, Simulated, Real | **Simulated** | Real is offered only if the phone has one |

The three motion sensors default to Simulated rather than Off because Android has never gated them:
before this existed a guest was handed the host's own `SensorManager` and could feel the user's hand,
with nothing anywhere able to say no. Simulated is the setting that neither breaks an app that wants
a sensor nor leaks the phone's.

**What each app may do**, per app, long-press an icon → **Manage permissions**. The list is the
APK's own manifest — every `<uses-permission>` it declares and nothing else — each with the three
states a phone has:

| Rule | Means |
|---|---|
| **Allow** | Granted, provided the device has the hardware |
| **Deny** | Refused |
| **Ask** | Undecided. Reads as denied — Android has only two answers — until the app asks and the person answers the device's own prompt, which is then written down as Allow or Deny |

Defaults follow the platform's: a permission it asks about at runtime starts at Ask, everything else
at Allow, because "ask" for a permission nothing ever asks about is a state that could never be left.
A permission the app never declared is **denied**, which is what the platform itself answers and what
the container used to get wrong — it answered a blanket `PERMISSION_GRANTED` to everything.

**Both are required.** An app cannot be given a camera the device does not have, and a device with a
camera does not hand it to an app that was refused one. Device-verified: with the camera Simulated,
everything else Off, and the person tapping Allow to all three of a fixture's requests, the app is
answered `CAMERA=granted RECORD_AUDIO=denied ACCESS_FINE_LOCATION=denied`.

**Where it is enforced** — all in `:guest`, all resolving the *active* guest:

| Question | Answered by |
|---|---|
| `Context.checkSelfPermission` | **`GuestContext`**, which overrides the three public `check*Permission` members. It has to be in front rather than underneath: `PermissionManager` memoises the answer in a cache only the system can invalidate, and `disablePermissionCache` is blocked — measured, a camera granted while an app was running went on reading as denied through the binder hook |
| The same, from anywhere without a guest `Context` | `GuestActivityManagerHook`, on `IActivityManager.checkPermission` |
| `PackageManager.checkPermission`, `hasSystemFeature` | `GuestPackageHook` |
| `Activity.requestPermissions` | `GuestPermissions.consume`, off the start-activity hook. **This was broken outright before**: the intent went to the real permission controller, which was being asked to grant a permission to *JCode*, and the result came back addressed to an activity token no `ActivityRecord` answers to — so no dialog, no callback, and an app that waits for one stopped there. What is already decided is answered from the policy; what is still Ask goes to the person through the device's own dialog, over the session AIDL |
| `getSystemService(SENSOR_SERVICE)`, and every location call | `GuestSensorManager` and `GuestLocation` — see [Guest runtime §5c](02-guest-runtime-and-hidden-api.md#5c-the-devices-own-hardware) |

> **One runtime request per activity instance.** `Activity.requestPermissions` sets
> `mHasCurrentPermissionsRequest` and refuses a second request while it is set. Every route to
> clearing it is blocked at `targetSdk` 33 — the field, `dispatchRequestPermissionsResult` and
> `dispatchActivityResult` are all absent from `Activity`'s declared members, and the real path in
> goes through a record map an embedded activity was never in. The first request is answered
> properly; a second is cancelled by the platform with two empty arrays before the container sees it,
> which is a documented outcome apps handle, and reopening the app clears it.

The policy is a properties file inside `filesDir/vdevice/`, not `SharedPreferences`: the launcher
that writes it is in the IDE and the container that acts on it is in `:guest`, and a preferences file
is cached per process from first read — so a permission revoked while an app was on the screen would
have gone on being granted until the process died. It is written atomically and re-read whenever its
timestamp moves. Living inside `filesDir/vdevice/` also means `resetOnStart` takes it with everything
else, which is the honest behaviour: a grant that outlived the app it was granted to would be waiting
to apply itself to whatever was installed under that package name next.

`tools/hardware-fixture` is the regression test — one guest that prints what it can see of all six.

### 7f. The hardware bench — `VirtualHardwarePage`

The device's own settings screen: a tab opened from its control bar, and from the idle home screen
because a route is usually set up before the app meant to react to it is opened.

It opens on a **grid of what the device is made of** — one tile per piece of hardware, each showing
what it is wired to and what it is reporting — and each tile opens onto that piece's mode and its
tools. That shape is the point: the six Off/Simulated/Real choices are properties of the device, and
anywhere else they looked like properties of an app.

| Tool | What it does |
|---|---|
| Fixed location | Two coordinates the device is parked on |
| **Point to point** | Walks between two coordinates at a set speed, reporting the bearing and speed a real receiver would — `Location.speed` and `.bearing`, which is what a navigation app reads rather than differencing positions itself. Stops at the far end, starts over, or turns around |
| **Follow a trail** | Walks one of three built-in paths, drawn on a map with an arrow for the device that rotates to the heading it is reporting. Distance-based rather than fraction-based, so a steady speed means steady metres and not equal time on a 60 m corner and a 300 m straight |
| Attitude | Pitch, roll and heading, with five one-tap poses named after the accelerometer readings they produce |
| **Loops** | Shake and bounce (linear acceleration on one axis), tilt (the pitch rocking), spin (the heading turning, which is the one that accumulates) — each with an amplitude and a period |
| Shake once | One swing that dies away, so the device ends where it started |
| Reporting now | What the guest is being told, live |

Everything is a property of the **device**, not of one app, because a phone has one GPS and one set
of sensors however many apps read them.

**While the device is moving, the compass faces the way it is going.** The direction of travel is the
heading, which is what a phone on a dashboard reads, and it is what makes the simulated compass turn
through a corner without anybody touching the heading slider. The stored attitude is not changed —
it is what the device goes back to when it stops.

#### One output, one thing driving it

Several controls can reach the same reading, so each has a stated winner rather than a sum. A device
has one heading and one position; two settings quietly averaging into them is the failure mode this
table exists to prevent.

| When these overlap | What wins | Why |
|---|---|---|
| Travel heading vs the **heading slider** | Travel, while moving | The slider stays visible but inert, and says the trail is steering — it is still where the device points once it stops |
| Travel heading vs the **spin loop** | Travel; the spin waits | Otherwise the compass reports neither, but their sum — disagreeing with the bearing in the same reading and with the map arrow drawn from it |
| Travel heading vs the **tilt loop** | Both | Different axes: tilt rocks the pitch, travel turns the heading |
| A point-to-point route vs a **trail** | Whichever was started last | One `locationMode`, so starting either stops the other. The map and the readout only show the device when *its own* method is the one running |
| An app's rule vs the **device's mode** | Both must allow it | See §7e — an app cannot be given hardware the device does not have |

Two more overlaps are legal but worth saying out loud, so the bench says them rather than looking
broken: tools that run while their hardware is **Off** (the readouts stay honest, no app is being
told), and the three motion sensors wired **differently from each other** (an app deriving one
orientation from gravity and the magnetic field together is then being handed two devices).

#### The trails, and why they are wrong on purpose

Three, chosen for the three shapes of heading change a location app has to survive, and sitting in
three latitude bands and three time zones:

| Trail | Shape | What it is for |
|---|---|---|
| **Sunset Boulevard, Dipolog City** | 3.5 km of curving seafront | A heading that drifts a few degrees at a time and never settles — the hardest case for a compass that smooths |
| **Eixample, Barcelona** | Eight 230 m runs across a grid set 45° off north | A heading that *jumps* 90° and then holds still |
| **Trollstigen, Norway** | Eight switchbacks | ~180° reversals, at 62° north where a degree of longitude is half its equatorial width — which is where flat-earth distance maths shows itself |

Every one is **hand-drawn, simplified and displaced a few hundred metres**, and each carries a
`headingSkew` that puts the reported compass a few degrees off the true bearing of travel. That is
deliberate and it is the point of the design: a tool that replays a faithful trace of a real street,
at a realistic speed, with a matching compass, is a tool for making a fake journey look real. So the
repository contains no faithful trace to replay — the offsets are written down in `LocationTrails.kt`
rather than hidden, because the protection is that there is nothing accurate underneath them, not
that the numbers are secret.

The map is drawn from the trail's own points. No tiles, no network, nothing to attribute — which is
what makes it work offline, and which also keeps a real map from being laid under a deliberately
displaced path.

**Nothing is streamed.** The tab writes a *description* — "walk from here to there at 14 m/s,
starting at this clock reading" — and both the guest's `SensorManager` and the tab's own readout
evaluate it against `SystemClock.elapsedRealtime`, which counts from the same boot in every process.
So the two agree by construction rather than by synchronisation; there is no IPC per sample, no
policy file rewritten at 50 Hz, and a route that has been running for an hour costs what one that
just started costs. `SimulatedHardware.sample` is that function, and it is the only place the
accelerometer, the magnetometer and the rotation vector are derived — three views of one attitude,
so turning the heading turns all of them together.

Device-verified: with the spin loop at a 4 s period the guest reads a gyroscope of exactly
−1.57080 rad/s (−2π/4 s) and a rotating magnetic field, while gravity stays at (0, 0, 9.80665) —
a device turning about its vertical does not tip.

---

## 8. Session states

```kotlin
internal sealed interface SandboxStatus {
    data object Idle
    data object Starting
    …
}
```

`AppSandbox` holds the `ServiceConnection`, exposes status as a `StateFlow`, and provides
`requestOpen(...)` — which is also the target of `adb shell am start -n` against the virtual device.

---

## 9. Invariants and constraints

1. The container **only ever loads in `:guest`**; none of it runs in the IDE process.
2. `hostToken` must be the real `SurfaceView` input token.
3. Re-acquire the surface package after a detach; do not restart the session.
4. All `oneway` methods are called from the IDE UI thread and must not block.
5. Capture and hierarchy dumps go through a file path, not a `Bundle`.
6. Unbinding the service is the teardown; there is no separate stop call.
7. Treat every guest as trusted code — it shares JCode's uid.
8. The device is emptied on every JCode start. Nothing may assume an app installed in a previous
   session is still there.
9. Everything installed goes through `VirtualDeviceApps`; nothing else writes `filesDir/vdevice/`.

---

## 10. Failure modes

| Failure | Effect |
|---|---|
| `hostToken` null | `start` returns an error message rather than embedding |
| `input`/`uiautomator dump` with nothing running | Answered with one line naming `am start`, not a hang |
| Embedding unsupported on the window | Falls back to `AppSandboxTier.FullScreen` |
| Guest activity finishes | `onGuestFinished(reason)`; the tab reports the session ended |
| `:guest` process killed | Same callback path; the IDE process is unaffected |
| More than four concurrent guests | No stub available |
| Unreadable APK | `VirtualDeviceException` from `inspect` |

---

## 11. Known gaps

- Four concurrent guest activities maximum (`GuestActivity0`–`GuestActivity3`).
- The guest shares JCode's uid and permissions — no isolation, by design.
- Hidden-API coupling means this is validated against **Android 13 / targetSdk 33** specifically; see
  [Guest runtime and hidden API](02-guest-runtime-and-hidden-api.md).

---

## 12. References

- [Guest runtime and hidden API](02-guest-runtime-and-hidden-api.md)
- [Android app debugging](03-android-app-debugging.md)
- [ADB bridge](../03-runtime/05-adb-bridge.md)
- [System architecture](../01-architecture/01-system-architecture.md)
