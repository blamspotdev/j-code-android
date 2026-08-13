# Guest runtime and hidden API

| | |
|---|---|
| **Status** | Implemented — verified on Android 13 with `targetSdk = 33` |
| **Modules** | `:app` (`dev.jcode.vdevice`) |
| **Primary sources** | app/src/main/java/dev/jcode/vdevice/HiddenApi.kt, GuestLoader.kt, GuestContext.kt, GuestRuntime.kt, GuestHooks.kt, GuestInstrumentation.kt, GuestOverlay.kt, VirtualIdentity.kt, EmbeddedWindows.kt |
| **Verified against** | device-verified on Android 13, 2026-08-11 (`tools/appcompat-fixture`) |

---

## 1. Purpose and scope

How a guest APK is actually loaded and made to run: class loading, resources, identity, the framework
hooks that persuade `ActivityThread` to instantiate the guest's activities, and the exact set of
non-SDK members this depends on.

> **This is coupled to `targetSdk`.** Every hidden member used here has been measured on Android 13
> with `targetSdk = 33`. A platform change can break it, and the fix is a different hook point — not
> a bypass.

---

## 2. Loading — `GuestLoader`

```kotlin
internal class LoadedGuest(
    val apkPath: String, val packageName: String,
    val applicationInfo: ApplicationInfo,
    val activities: Map<String, ActivityInfo>, val launchActivity: String,
    val classLoader: ClassLoader, val resources: Resources,
    val dataDir: File,
) {
    val filesDir, cacheDir, codeCacheDir, noBackupDir, databasesDir …
}
```

| Step | Mechanism |
|---|---|
| Code | `DexClassLoader` over the base APK **and its splits**, parented to the **boot** class loader |
| Resources | A hand-built `AssetManager` via the hidden `addAssetPath`, base first then each split |
| Manifest | Parsed with `XmlResourceParser` for the MAIN/LAUNCHER entry and for service/receiver actions |
| Native libraries | Extracted from `lib/<abi>/*.so` across **every** APK (regex `lib/([^/]+)/[^/]+\.so`) |
| Data directory | Redirected to `filesDir/vdevice/<packageName>/` |

### 2.0 Split APKs

An app bundle is not optional packaging — it is what `./gradlew bundle` and every Play install
produce, and its base APK contains **no native libraries at all**: they are the whole content of
`split_config.<abi>.apk`. Loading the base alone therefore yields an app whose `System.loadLibrary`
finds nothing, which is the quietest possible way to fail.

Splits are discovered from the store rather than passed in — `<package>.apk` beside
`<package>.splits/` — so `IGuestSession.start` still takes a single path and every caller that names
an APK is untouched. The cache fingerprint covers the splits too, so replacing one reloads the guest.

### 2.3 The default theme

An app that declares no `android:theme` is not asking for an empty theme; `ContextThemeWrapper` runs
the declared id through the hidden `Resources.selectSystemTheme` first, which picks a platform
default by `targetSdkVersion`. `selectDefaultTheme` reproduces that with the four **public**
`android.R.style` constants:

| `targetSdkVersion` | Theme |
|---|---|
| < 11 | `Theme` |
| < 14 | `Theme_Holo` |
| < 24 | `Theme_DeviceDefault` |
| ≥ 24 | `Theme_DeviceDefault_Light_DarkActionBar` |

> Skipping this leaves a guest with `theme={InheritanceMap=[], Themes=[]}` and the first framework
> layout that resolves a `?attr/…` against it dies. Measured: RetroArch (`targetSdk` 28, no
> `android:theme` anywhere in its manifest) failing to inflate `android:layout/screen_title`.

### 2.1 Why the class loader's parent is the boot loader

`DexClassLoader(apk, …, Context::class.java.classLoader)` — **not** JCode's own loader.

Parenting to JCode's would be parent-first, so every library the IDE also ships (AndroidX, Kotlin,
Compose) would be answered out of the IDE's dex rather than the guest's APK. The classes run, which
is what makes the mistake so quiet, but each library's generated `R` then carries **JCode's**
resource ids while the guest's resource table only knows the guest's.

> Measured: an `AppCompatActivity` whose theme *was* a `Theme.AppCompat` descendant, correctly
> applied and resolving `android:windowBackground` out of the guest's table, still died on
> `setContentView` with "You need to use a Theme.AppCompat theme (or descendant) with this
> activity." — `AppCompatDelegate` was reading JCode's `windowActionBar` attr id, which the guest's
> table has never heard of. `tools/appcompat-fixture` is the regression test.

Isolating the parent gives the guest its own copy of everything it ships, which is what a real app
process has. Nothing crosses between the two loaders but framework types.

### 2.2 Why the cache is keyed on the APK, not its path

`GuestLoader` keeps one `LoadedGuest` per APK **path plus its `lastModified` and length**, and
reloads when either moves.

Path alone is not enough, and the failure is the worst kind — silent and wrong. Unbinding
`GuestSessionService` is *supposed* to take `:guest` with it, but Android keeps the emptied process
around and rebinds into it, so a rebuilt APK written to the same path was answered out of this cache
and the device went on running the **previous build**. Measured: the `:guest` pid survived
`am force-stop` + `am start`, and the guest that came back had none of the newly added views.

That is the one thing a device you iterate against must never do, since build → install → run at one
path is the whole workflow. Native libraries are re-extracted on the same test (same size is not the
same file once an APK has been rebuilt).

---

## 3. Identity — `GuestContext`

```kotlin
internal class GuestContext(base: Context, private val guest: LoadedGuest) : ContextWrapper(base)
```

It wraps JCode's **real** `ContextImpl`, so every binder call still goes out under JCode's uid and
package — which is what makes those calls succeed — while reporting the *guest's* identity for
everything the guest can observe about itself: package name, `ApplicationInfo`, resources, class
loader, and a private storage tree under `filesDir/vdevice/<guest package>/`.

> The storage redirect is what keeps a guest from ever seeing or writing into JCode's own data
> directory.

A related constraint appears elsewhere in the system: some framework calls check that
`packageName` matches the calling uid (`packageName must match the calling uid`). Where that
applies, the wrapper must return the **host** package, not the guest's.

### 3.1 `VirtualIdentity`

Rewrites `Build`'s `static final` descriptive strings:

| Field | Value |
|---|---|
| `Build.MODEL` | `JCode vDevice` |
| `Build.DEVICE` | `jcode_vdevice` |
| `Build.PRODUCT` | `jcode_vdevice` |
| `Build.SERIAL` | `JCODEVD00000000` |

Design goal: **different identity, same hardware.** Only descriptive strings change; anything
hardware-derived — CPU count, memory, display metrics, ABI list — is left alone, so a guest still
measures the machine it is really running on.

`MODEL` uses a space rather than an underscore so `adb devices -l` renders it as `JCode_vDevice`
while `Build.MODEL` stays shaped like a real model name.

Because `Build`'s fields are `static final`, this is **process-wide and irreversible**, so `apply`
refuses to run anywhere but a process whose name ends in `:guest`.

---

## 4. Framework hooks — `GuestHooks`

```kotlin
internal sealed interface StartAction {
    data object Proceed                      // not the guest's; let it go out as-is
    data class Redirect(val intent: Intent)  // launch a stub carrying the guest activity's identity
    data object Consumed                     // already handled in-process; the binder call must not happen
}
```

`GuestHooks` installs the mechanism; the policy that decides what to do lives in `GuestRuntime`.

| Hook | What it does |
|---|---|
| `ActivityThread.mInstrumentation` | Swapped for `GuestInstrumentation`, which overrides `newActivity` and `callActivityOnCreate` — **both public SDK methods** |
| `ActivityThread.mH` `Handler.mCallback` | Intercepts `LAUNCH_ACTIVITY` `ClientTransaction`s and rewrites the intent and `ActivityInfo` (component, `theme = 0`, labels) *before* the system acts |
| `IActivityTaskManager` binder singleton | Replaced with a `Proxy` so outgoing `startActivity*` calls can be redirected or consumed |
| `Application.mActivityLifecycleCallbacks` | Dispatched manually (greylisted, so reachable) |

Reflected constants: `android.app.servertransaction.ClientTransaction`,
`android.app.servertransaction.LaunchActivityItem`, `mActivityLifecycleCallbacks`,
`onActivityPre…`, and `START_SUCCESS = 0` (`ActivityManager.START_SUCCESS`, absent from the SDK).

**Every step is guarded**, so a platform that withdraws a member degrades rather than crashes.

---

## 5. The hidden-API ledger

`HiddenApi.kt`'s class documentation is the authoritative record. Summarized:

### 5.1 Allowed (greylist, no `maxTargetSdk`)

Accessing these logs a warning and nothing more:

- `ActivityThread.mInstrumentation`
- `ActivityThread.mH`
- `AssetManager.addAssetPath` (building a bare `AssetManager`)
- The `IActivityTaskManager` binder proxy singleton
- `Activity.mMainThread`, `ContextWrapper.mBase`, `ContextThemeWrapper.mResources`,
  `ContextThemeWrapper.mInflater`
- `Application.mActivityLifecycleCallbacks`
- `ViewRootImpl.mAttachInfo` and the `IWindowSession` internals `EmbeddedWindows` wraps

### 5.2 Denied, and designed around

| Member | Status | Workaround |
|---|---|---|
| `ContextThemeWrapper.mTheme` | `max-target-p` — cannot be cleared | Never needed: **`ContextThemeWrapper.setTheme(Resources.Theme)` is public SDK from API 29** and replaces `mTheme` outright. `GuestRuntime.bind` builds the theme from the guest's own `Resources` and installs it, so which table the theme belongs to is no longer a matter of when `mTheme` happened to be created. `onLaunchActivity` still rewrites `ActivityInfo.theme` to `0` so nothing is built against JCode's resources in the first place |
| `ActivityThread.startActivityNow` | Absent from declared members — the signature of a **denied** member | The embedded path uses the public `Instrumentation.newActivity` |
| **Every** non-SDK member of `SurfaceControlViewHost`, including `SurfacePackage` | The class carries no `@UnsupportedAppUsage` at all | `EmbeddedWindows` reaches the host's view root through the container's public `getParent()`, and the root layer through `SurfacePackage`'s own public `Parcelable` contract |
| `Activity.performStart` / `performResume` | Denied | Driven through the public lifecycle path |
| `Activity.mActivityLifecycleCallbacks` | Denied — the list those two dispatch to, and the one AndroidX's `ReportFragment` registers on | `GuestRuntime.resumeEmbedded` dispatches `Application.mActivityLifecycleCallbacks` (greylisted) and drives the guest's own `LifecycleRegistry` reflectively for what the other would have reached |

### 5.3 No escape hatch

> `VMRuntime.setHiddenApiExemptions` — the usual escape hatch, reached by double reflection — is
> itself **blocked** from Android 10 on, confirmed denied on this device.

So if a future platform demotes any greylisted member above, the fix is a real one (a different hook
point), not a bypass.

---

## 4a. Two things `:guest` must claim before the guest runs

Both were found the same way — by hosting providers, which made guests run enough of themselves to
reach code the container had never exercised.

| Claim | Why |
|---|---|
| `WebView.setDataDirectorySuffix("jcode-guest")` | WebView takes an **exclusive lock** on its data directory and refuses to load in a second process of the same app without a suffix. J Code's own process always gets there first, so a guest that touched a WebView at all died with `Using WebView from more than one process at once with the same data directory is not supported`. Not a niche case: ad SDKs, sign-in flows, Cordova/Ionic apps and any in-app browser reach for one. Public API from API 28, and it must run before WebView is used — which is why it is the first thing `install` does |
| `GuestContext.getDatabasePath` accepting an **absolute** name | `ContextImpl` returns an absolute name as-is, and libraries rely on it: WorkManager hands Room a full path under `no_backup/`, and Room passes it straight back. Joining it onto `databases/` produced `…/databases/data/user/0/…/no_backup/androidx.work.workdb`, and the `SQLiteCantOpenDatabaseException` came back on a WorkManager thread where nothing catches it |

> Both crashes killed `:guest`, and where a **full-screen** guest was in J Code's task the activity
> manager's crash cleanup finished `MainActivity` along with it — so a guest's bug took the IDE off
> the screen. Embedded guests share no task and are unaffected.

## 4b. The embedded activity's token — `GuestActivityClient`

An embedded activity is built by hand, so its token is a bare `Binder` rather than something the
window manager minted, and the server rejects it before doing anything:

```
Bad activity token: android.os.BinderProxy@5fb1255
java.lang.ClassCastException: android.os.BinderProxy cannot be cast to
    com.android.server.wm.ActivityRecord$Token
    at com.android.server.wm.ActivityRecord.getTaskForActivityLocked
```

Everything the framework routes through `ActivityClient` carries that token, so the whole surface —
`getTaskForActivity`, `setTaskDescription`, `finishActivity`, `getDisplayId` — failed. Measured on
CPU-Z, whose Mobile Ads SDK asks for its task from inside a WebView and took the guest down with a
native `SIGTRAP` when no answer came.

`IActivityClientController` is an interface, so one `Proxy` covers every entry point. Calls carrying
a token this container minted are answered locally; everything else — including a full-screen
guest's, whose token *is* real — passes through.

**Where the proxy gets in matters.** Not through `ActivityClient.INTERFACE_SINGLETON`, which is
**blocked** at `targetSdk` 33 (measured: `blocked, reflection, denied`). One level up instead: that
singleton builds its controller by calling `IActivityTaskManager.getActivityClientController()`, and
the `IActivityTaskManager` binder is already proxied through a greylisted member. The container
answers that call with a wrapper and the singleton caches it as though the server had handed it over
— no new hidden member, and the one it leans on was already load-bearing. It follows that the hook
must be installed before anything in `:guest` touches an activity: the singleton asks once.

A method the proxy does not model returns a type-appropriate nothing rather than being forwarded,
because the alternative is not a correct answer but the exception above. Separately, an embedded
token is blanked out of any outgoing `startActivity` — `resultTo` naming an activity the server has
never heard of is rejected outright, and null is both accepted and accurate.

## 4c. The notification service — `GuestNotificationHook`

Every binder call a guest makes goes out under **J Code's** uid and package, so a guest that posts a
notification puts it in the *phone's* real shade, attributed to J Code, where it outlives the device
being emptied. The virtual device exists so an app can be tried without leaving anything on the
phone, and the notification shade is part of the phone.

`INotificationManager` is an interface, so the same `Proxy` shape as the other hooks works, taken
from `NotificationManager.sService`. Posting and cancelling are answered into `VirtualNotifications`
and never reach the system; what a guest merely *asks* — whether notifications are enabled, what
importance it has — is answered as a permissive yes, because a "no" is a guest that never posts at
all and so never proves anything. Channel bookkeeping is accepted and dropped: the device keeps no
channels, and accepting them silently is what lets an O+ guest reach the `notify()` that matters.

Anything unmodelled still goes through to the real service — most of it is harmless reads, and a
notification manager that throws is worse than one that over-answers.

The store lives in `:guest` and dies with it, which is the same lifetime the device's screen has:
stopping an app takes its process, and a stopped app's notifications with it.

### Full screen — `HostNotificationMirror`

The device's status bar is a view inside the embedded guest's container, so it exists only while the
guest is in the tab. A **full-screen** guest has taken the whole screen and left the tab behind, and
with it the only surface the device had to show a notification on. Posting nowhere would mean an app
that behaves correctly appears not to, which is the failure this whole section exists to remove.

So while a guest is full screen its notifications are mirrored onto the phone's own shade, and
**taken back down when it exits**. That bound is what keeps the mirror from being the thing the hook
above prevents: notifications are never *left* on the user's phone, only borrowed while there is
nowhere else to put them. Each carries the guest's label as its sub-text, so it is clear which app
inside the device is talking.

| | |
|---|---|
| On | `GuestRuntime.created` for a full-screen guest activity |
| Off | `callActivityOnDestroy` of the last one — public SDK, and the counterpart to the create hook |
| Rebuilt, not forwarded | The guest's notification names a package the phone has never heard of, and its small icon is a resource id in the guest's table. Only the text is the guest's |
| Tapping one dismisses it | Its content intent belongs to a package the system cannot start, so there is nowhere honest to send anyone |

A thread-local guard makes the mirror's own calls pass through the hook; without it they would be
caught and fed straight back into the device they came from.

> Verified on the Odin2: host shade 0 before launch, 2 while full screen, 4 after posting two more,
> and **0 again** the moment the guest exits.

> Verified on `tools/notification-fixture`: two notifications posted from `onCreate` appear in the
> device's own bar and shade, and `dumpsys notification` on the host counts **zero** of them.

## 4c-bis. The Application comes first

`ActivityThread` builds an app's `Application` in `handleBindApplication`, long before it instantiates
any activity, and apps depend on that ordering far more than they ever say so. A field initialiser or
a static `<clinit>` reached from an activity's **constructor** routinely reads a context some holder
captured in `Application.onCreate`.

The container used to create the guest's `Application` inside `bind()`, which the framework only
reaches on the way into `onCreate` — one step too late. Measured on MiXplorer:

```
ExceptionInInitializerError at libs.v04.<clinit>
Caused by: NullPointerException: Context.getResources() on a null object reference
```

Its static holder was still null because the constructor had beaten the `Application` to it, and the
activity could not even be built. `ensureApplication` now runs before `Instrumentation.newActivity`.

## 4d. Two reasons a guest drew nothing

Both looked identical from outside — an app that loaded, started, stayed alive and showed an empty
screen — and neither was what it appeared to be. Measured on AI Edge Gallery, which now renders in
full.

**The lifecycle never advanced.** `ReportFragment` registers on the *activity's* callback list, which
is blocked here, so `GuestRuntime.resumeEmbedded` drives the guest's `LifecycleRegistry` by hand
instead. That fallback resolved its event constants with `Enum.valueOf` — and **R8 removes `valueOf`
from an enum nothing looks up by name**, so against any release build it threw
`NoSuchMethodException: androidx.lifecycle.Lifecycle$Event.valueOf`. The registry stayed at
INITIALIZED, Compose never started a composition, and the app drew nothing. The static field survives
where the method does not, because the enum's own code reads it. The sequence also has to begin at
`ON_CREATE`: sending `ON_START` to a registry that has never been created is an illegal transition
and `LifecycleRegistry` refuses it.

**A dialog was placed off the screen.** `EmbeddedWindows.place` sized a child window from the child
*view's* own measured width, and a view that measured itself before there was a real frame keeps that
size — Gallery's Compose `Dialog` came back **8190px wide on a 1080px device**, so gravity centred it
at `left = -3555`. The app had opened its welcome dialog correctly and it was simply nowhere anyone
could see it. Oversized children are now re-measured `AT_MOST` the tab, which is what a window
manager would have asked for; a window cannot be wider than the screen it is on, so a number saying
otherwise is wrong wherever it came from.

## 4e. What a game engine waits for

Three things an embedded guest is never given, each of which a rendering framework treats as a
reason not to start.

**Window focus.** `onWindowFocusChanged` is delivered by the window manager to a *real* window, and
an embedded guest's token is one no `ActivityRecord` answers to — so the system has no window here to
give focus to. `GuestRuntime.focus` says it anyway, on both routes a framework might listen on (the
activity's callback and the view tree's), because in the tab the guest genuinely is the only thing on
the screen. SDL will not start its render thread without focus *and* a surface; most game engines
pause on the same signal.

**A believable orientation.** `getRequestedOrientation` goes through `ActivityClient`, and the
container's generic integer default was `0` — which is `SCREEN_ORIENTATION_LANDSCAPE`, not
`UNSPECIFIED`. Every embedded guest was claiming it had asked for landscape while sitting in a
portrait tab, and SDL refuses on exactly that mismatch:

```
V SDL: Window size: 1080x1510
V SDL: Skip .. Surface is not ready.
```

Answering `SCREEN_ORIENTATION_UNSPECIFIED` is both the fix and the honest answer — the tab has one
shape and the guest does not choose it, which is what `GuestWindow.makeResizable` already says about
the manifest.

**A visible surface.** A `SurfaceView` does not paint; its pixels are a separate `SurfaceControl`
below the window, shown through a transparent hole the window punches. A windowless host does not
honour that hole, so the guest's own opaque background covers it. `GuestSurfaces` raises a
**full-bleed** surface above the window instead — only full-bleed, because a video player putting one
behind its controls means it, and raising that would trade a black screen for an unusable one.

## 5a. Non-activity components — `GuestComponents`

Providers, services and receivers cannot be registered with the system: they belong to a package the
real `PackageManager` has never heard of, and every registration path ends at a binder call that
checks exactly that. What the container does instead is what `ActivityThread` does inside an app
process — build the objects, attach them to a `GuestContext`, drive their lifecycle by hand.

| Component | Hosting |
|---|---|
| `<provider>` | Instantiated and `attachInfo(context, info)` — public API, and it calls `onCreate` itself. Ordered by descending `initOrder`, **between** `Application.attachBaseContext` and `Application.onCreate`, exactly where `ActivityThread.handleBindApplication` runs `installContentProviders` |
| `<service>` | `Service.attach` (greylisted) when available, else a `ContextWrapper.mBase` swap; then `onCreate`, and `onStartCommand`/`onBind` per call. `bindService` calls back on the main thread, never inline |
| `<receiver>` | Instantiated per broadcast and given `onReceive`. Resolved by explicit component, or by action from the manifest scan |

`Context.startService`/`bindService`/`sendBroadcast` on a `GuestContext` offer the intent to the
guest first and fall through to the host when the target is not the guest's — so a guest can still
fire an intent at the phone while talking to itself in-process.

> **In-process only, and that is the boundary.** Another app cannot query a hosted provider, no
> system broadcast arrives on its own, and a service gets no process to be restarted in. What it buys
> is an app talking to itself, which is where the frameworks live: `androidx.startup` — and so
> WorkManager, Firebase, `emoji2`, ProfileInstaller and Coil — boots from a `<provider>`. Measured
> before this existed: NewPipe dying on "WorkManager is not initialized properly" before its first
> frame.

## 5b. The guest's own package — `GuestPackageHook`

Hosting a provider is not enough on its own. `androidx.startup` reads its `InitializationProvider`'s
`<meta-data>` back through `getProviderInfo`; AppCompat looks its own activity up through
`getActivityInfo`; analytics libraries read `getPackageInfo(…).versionName`. All of those go out to a
package manager that has never heard of the guest:

```
androidx.startup.StartupException: PackageManager$NameNotFoundException:
    ComponentInfo{org.newpipex/androidx.startup.InitializationProvider}
    at androidx.startup.AppInitializer.discoverAndInitialize(AppInitializer.java:208)
```

`PackageManager` is an abstract class with a couple of hundred abstract members, so a delegating
wrapper is not writable by hand. `IPackageManager` is an *interface*, which is what `Proxy` needs,
and it sits underneath every `ApplicationPackageManager` method — so one proxy on
`ActivityThread.sPackageManager` (plus the `mPM` the existing instance already cached) covers every
entry point. Same shape as the `IActivityTaskManager` hook, for the same reason.

Answered for a loaded guest: `getActivityInfo`, `getServiceInfo`, `getReceiverInfo`,
`getProviderInfo`, `getPackageInfo`, `getApplicationInfo`, `resolveContentProvider`, and the two
enabled-setting queries. Everything else passes straight through. Arguments are matched **by type**,
never by position, because these signatures gained a `userId` and widened `flags` to `long` across
releases.

## 6. Child windows — `EmbeddedWindows`

Dialogs, popup menus and drop-downs inside an embedded guest are the subtlest part of the system.

**Why they route at all.** A `SurfaceControlViewHost` already owns a `WindowlessWindowManager`, and
`WindowManagerGlobal` will route a window into it — no permission, no real activity token — as long
as the window's `LayoutParams.token` is the host's own window token. `Dialog` takes that token from
the activity window's app token (which `GuestHooks.hostWindowIn` sets), while `PopupWindow`,
`Spinner` drop-downs and option menus already read it off their anchor view.

**Why they need help.** The windowless session does not *lay a window out*. It answers every relayout
with `frame = (0, 0, attrs.width, attrs.height)` and never moves the surface. So a `WRAP_CONTENT`
window — which every dialog and every popup is — comes back with a **−2 × −2** frame, is given a 1×1
surface, draws nothing, and would sit in the tab's top-left corner if it did.

**What `install()` does.** It wraps the host's `IWindowSession` in a `Proxy` over `addToDisplay`,
`relayout` and `remove`, and performs the two jobs a real window manager would:

1. Resolve `WRAP_CONTENT` / `MATCH_PARENT` against the tab's bounds.
2. Place the window's surface by its `Gravity`, reparenting each child's `SurfaceControl` under its
   own layer.

```kotlin
internal class EmbeddedWindow(val view: View, val frame: Rect)
```

---

## 7. Full-screen extras

`GuestOverlay.install()` adds a floating pill and a back/close bar to a full-screen guest, since
JCode draws nothing over it.

---

## 8. Invariants and constraints

1. None of `dev.jcode.vdevice`'s container code may run in the IDE process.
2. `VirtualIdentity.apply` refuses any process not ending in `:guest`.
3. Only descriptive `Build` strings are rewritten; hardware-derived values are left alone.
4. Every reflective access is guarded and degrades rather than crashing.
5. Prefer a public API wherever one exists (`Instrumentation.newActivity`, `Parcelable`,
   `getParent()`, `ContextThemeWrapper.setTheme(Resources.Theme)`).
6. The guest's `dataDir` is always redirected under `filesDir/vdevice/<package>/`.
7. `ActivityInfo.theme` is rewritten to `0`, and the activity's theme is then installed as an
   *object* built from the guest's `Resources` — a style id alone never says which table it means.
8. The guest's class loader never delegates to JCode's. A library the IDE also ships must still come
   out of the guest's APK, or its `R` ids belong to the wrong resource table.

---

## 9. Failure modes

| Failure | Effect |
|---|---|
| A greylisted member is demoted by a platform update | The guarded step degrades; the affected capability stops working |
| A guest library resolved from JCode's dex | Its `R` ids miss the guest's resource table — see §2.1 |
| A child window without the host token | Not routed into the windowless session |
| `EmbeddedWindows` not installed | Dialogs and popups draw at 1×1 in the top-left corner |
| Guest APK missing a native library for this ABI | `UnsatisfiedLinkError` inside the guest |
| Two guests of the same package | They share the redirected `dataDir` |

---

## 10. Known gaps

- Validated on **Android 13 / targetSdk 33** only. Other platform versions are untested.
- Hosted components are reachable only from inside `:guest` — see §5a. A `ContentResolver` query
  from another process, a system broadcast, and a service restarted after death all remain out of
  scope.
- Only **config** splits are merged. A *feature* split that declares components of its own has its
  code and resources loaded, but its manifest is not scanned.
- The guest's own permissions are not honoured; it inherits JCode's.
- Compose guests can start with an empty view tree where the app gates its first composition on
  something the container does not provide — measured on AI Edge Gallery, which loads and starts
  clean but dumps a bare `FrameLayout`.
- The token answers of §4b are *plausible*, not real. `getTaskForActivity` hands back a synthetic id
  and unmodelled calls become no-ops, so a guest leaning hard on its own task — recents entries,
  picture-in-picture, task descriptions — sees those features inert rather than working. CPU-Z runs
  and stays up under this, but its tab content never populates.
- A Compose guest's content is in its composition, not its view tree, so `uiautomator dump` shows
  the `AndroidComposeView` and nothing inside it. Semantics are not walked. A driver can screenshot
  a Compose guest and tap by coordinate, but cannot find a node by text the way it can in a View app.
- **`screencap` cannot see a `SurfaceView`.** `EmbeddedGuest.capture` re-draws the container's view
  hierarchy into a bitmap, and a `SurfaceView`'s pixels are not in that hierarchy — they are in its
  own `SurfaceControl`. So an SDL, Unity or video guest captures as black whether or not it is
  rendering, and the tab on the phone is the only place to tell. Worth knowing before concluding a
  GL app is broken from a capture.
- A guest's `startActivity` can still escape to the phone's own copy of the same package where one
  is installed: measured on ES-DE, whose `ConfiguratorActivity` opened the *installed* app rather
  than the guest's. Same family as the `PendingIntent` escape.

---

## 11. References

- [App sandbox architecture](01-app-sandbox-architecture.md)
- [Android app debugging](03-android-app-debugging.md)
- [Security and privacy](../09-platform/04-security-and-privacy.md)
