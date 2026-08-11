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
| Code | `DexClassLoader` over the raw APK, parented to the **boot** class loader |
| Resources | A hand-built `AssetManager` via the hidden `addAssetPath` |
| Manifest | Parsed with `XmlResourceParser` to find activities and the MAIN/LAUNCHER entry |
| Native libraries | Extracted from `lib/<abi>/*.so` (regex `lib/([^/]+)/[^/]+\.so`) |
| Data directory | Redirected to `filesDir/vdevice/<packageName>/` |

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
- Content providers, services and broadcast receivers declared by the guest are not hosted — only
  activities.
- The guest's own permissions are not honoured; it inherits JCode's.

---

## 11. References

- [App sandbox architecture](01-app-sandbox-architecture.md)
- [Android app debugging](03-android-app-debugging.md)
- [Security and privacy](../09-platform/04-security-and-privacy.md)
