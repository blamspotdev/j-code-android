package com.example.composeguest

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * A Compose guest, for the one thing the plain-view fixture cannot answer.
 *
 * Compose's frame clock starts paused and is only resumed on `Lifecycle.Event.ON_START`, which a
 * `ComponentActivity` only ever gets from `ReportFragment`'s activity lifecycle callbacks — the ones
 * `Activity.performStart` dispatches and the container has to re-create by hand. So this screen is
 * not merely *a* Compose screen: if the container gets that wrong, nothing here draws at all.
 *
 * The frame counter is the sharp end of it. A composition can be produced and still never reach the
 * screen; a counter that climbs proves the clock is actually running.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { GuestScreen() }
            }
        }
    }
}

@Composable
private fun GuestScreen() {
    var frames by remember { mutableIntStateOf(0) }
    var taps by remember { mutableIntStateOf(0) }
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val packageName = LocalContext.current.packageName

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            frames++
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Compose is drawing", style = MaterialTheme.typography.titleLarge)
        Text("frames: $frames", style = MaterialTheme.typography.headlineSmall)
        Text("lifecycle: $state", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { taps++ }) { Text("Tapped $taps") }
        Text(
            text = """
                package  = $packageName
                uid      = ${Process.myUid()}
                MODEL    = ${Build.MODEL}
                DEVICE   = ${Build.DEVICE}
            """.trimIndent(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
