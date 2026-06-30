package dev.jcode

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JCodeRoot(viewModel = viewModel)
        }
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

    fun openPermissionSettings() {
        Log.d(TAG, "Opening permission settings")
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    fun runEnvironmentStep(stepId: dev.jcode.core.distro.WizardStepId) {
        Log.d(TAG, "Running environment step: ${stepId.key}")
        viewModel.runEnvironmentStep(stepId)
    }

    fun runAutoSetup() {
        Log.d(TAG, "Running environment auto setup")
        viewModel.runAutoSetup()
    }

    companion object {
        private const val TAG = "MainActivity"
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
