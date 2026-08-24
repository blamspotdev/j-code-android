package dev.blamspot.jcode

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blamspot.jcode.adaptive.rememberJCodeWindowInfo
import dev.blamspot.jcode.design.DensityMode
import dev.blamspot.jcode.design.IconBundleRegistry
import dev.blamspot.jcode.design.M3Theme
import dev.blamspot.jcode.design.ThemeBundleRegistry
import dev.blamspot.jcode.design.VolumeKeyAction

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val mouseContextClick = MouseContextClick(::dispatchReplayedTouch)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_JCode)
        super.onCreate(savedInstanceState)
        // Hardware acceleration is opt-out via Settings → Performance. The manifest disables it for
        // this activity (window level can only ENABLE), so apply the flag here — before setContent,
        // after which it is immutable for this window. Synchronous SharedPreferences (mirrored from
        // the DataStore pref by MainViewModel) because DataStore can't be read before the UI exists.
        val hwAccel = getSharedPreferences(UI_STARTUP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HW_ACCELERATION, true)
        if (hwAccel) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            )
        }
        enableEdgeToEdge()
        // Draw into the display cutout unless the user opted to respect it (Settings). Mutable, so
        // JCodeShell also updates it live; set here for the first frame. Mirrored from DataStore.
        val respectCutout = getSharedPreferences(UI_STARTUP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESPECT_CUTOUT, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (respectCutout) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        // POST_NOTIFICATIONS is a runtime permission at targetSdk 33 and the backend FGS
        // notification ("Stop & close", session status) starts with the first terminal/run
        // session — so ask right away rather than dropping notifications silently.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            JCodeRoot(viewModel = viewModel)
        }
    }

    companion object {
        const val UI_STARTUP_PREFS = "jcode-ui-startup"
        const val KEY_HW_ACCELERATION = "hw_acceleration"
        const val KEY_RESPECT_CUTOUT = "respect_device_cutout"
    }

    // A mouse right-click opens the context menu instead of going Back — see MouseContextClick.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (mouseContextClick.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (mouseContextClick.onGenericMotionEvent(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    /** Entry point for the replayed long-press; goes to the window, bypassing our own filter. */
    private fun dispatchReplayedTouch(ev: MotionEvent) {
        super.dispatchTouchEvent(ev)
    }

    // Volume buttons can be remapped (Settings → Input → Volume keys). Activity.dispatchKeyEvent is
    // the only hook that reliably sees volume keys regardless of which pane holds focus, and can
    // suppress the OS volume change by consuming the event before the window handles it.
    //
    // RestrictedApi is suppressed rather than worked around: the method is public framework API on
    // Activity, and overriding it is the documented way to intercept dispatch. androidx.core's own
    // ComponentActivity override carries @RestrictTo(LIBRARY_GROUP_PREFIX), which is what `super`
    // resolves to here — so lint reads "an app is calling androidx-internal code" where what is
    // actually happening is an override delegating to its supertype. Not delegating is not an
    // option; dropping the super call would swallow every key the app does not handle itself.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (mouseContextClick.shouldSwallowBack(event)) return true
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val action = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                viewModel.volumeUpAction.value
            } else {
                viewModel.volumeDownAction.value
            }
            if (action == VolumeKeyAction.SystemDefault) return super.dispatchKeyEvent(event)
            // Fire on key-down (repeating only for repeatable actions); consume both down and up so no
            // volume panel or adjustment leaks for a mapped key.
            if (event.action == KeyEvent.ACTION_DOWN && (action.repeatable || event.repeatCount == 0)) {
                when (action) {
                    VolumeKeyAction.Undo -> viewModel.undoActiveTab()
                    VolumeKeyAction.Redo -> viewModel.redoActiveTab()
                    else -> viewModel.emitVolumeKeyAction(action)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironment()
        // ComponentCallbacks2 announces trouble and then goes quiet — there is no "recovered"
        // callback — so being interactive again is the only honest signal that the squeeze is over.
        viewModel.resourceManager.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        // Capture the latest workbench state when backgrounded, in case the process is killed next.
        viewModel.flushSessionNow()
    }
}

@Composable
private fun JCodeRoot(viewModel: MainViewModel) {
    val windowInfo by rememberJCodeWindowInfo()
    val densityMode = if (windowInfo.hasPhysicalKeyboard) DensityMode.Compact else DensityMode.Comfortable
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeBundleId by viewModel.themeBundleId.collectAsStateWithLifecycle()
    val themeBundle = ThemeBundleRegistry.byId(themeBundleId)
    val iconBundleId by viewModel.iconBundleId.collectAsStateWithLifecycle()
    val iconBundle = IconBundleRegistry.byId(iconBundleId)

    M3Theme(
        themeMode = themeMode,
        densityMode = densityMode,
        themeBundle = themeBundle,
        iconBundle = iconBundle,
    ) {
        WithoutExtractedIme {
            JCodeApp(viewModel = viewModel, modifier = Modifier)
        }
    }
}

/**
 * Keep the keyboard out of extract mode, everywhere in the app.
 *
 * In landscape an IME covers the screen and edits a copy of the text in a window of its own unless
 * the editor asks it not to. That is wrong for every field JCode has — a commit message wants the
 * diff still visible behind it, a find field wants its results, a merge wants both versions — and it
 * is a property of the app, not of any one screen, so it is set once here rather than remembered at
 * each field.
 *
 * The flag lives on the `EditorInfo` that `onCreateInputConnection` fills in. JCode's own editor,
 * terminal and browser are Views and override that method directly; Compose builds the object
 * itself, and this interceptor is the supported way to reach it. `createInputConnection` has to run
 * first — it is what populates the object being amended.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WithoutExtractedIme(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val patched = PlatformTextInputMethodRequest { outAttrs ->
                val connection = request.createInputConnection(outAttrs)
                outAttrs.imeOptions = outAttrs.imeOptions or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN
                connection
            }
            nextHandler.startInputMethod(patched)
        },
        content = content,
    )
}
