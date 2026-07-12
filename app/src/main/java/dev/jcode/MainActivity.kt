package dev.jcode

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.adaptive.rememberJCodeWindowInfo
import dev.jcode.design.DensityMode
import dev.jcode.design.IconBundleRegistry
import dev.jcode.design.M3Theme
import dev.jcode.design.ThemeBundleRegistry

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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

    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironment()
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
        JCodeApp(viewModel = viewModel, modifier = Modifier)
    }
}
