package dev.jcode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import dev.jcode.adaptive.rememberJCodeWindowInfo
import dev.jcode.design.DensityMode
import dev.jcode.design.M3Theme

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
}

@Composable
private fun JCodeRoot(viewModel: MainViewModel) {
    val windowInfo by rememberJCodeWindowInfo()
    val densityMode = if (windowInfo.hasPhysicalKeyboard) DensityMode.Compact else DensityMode.Comfortable

    M3Theme(densityMode = densityMode) {
        JCodeApp(viewModel = viewModel, modifier = Modifier)
    }
}
