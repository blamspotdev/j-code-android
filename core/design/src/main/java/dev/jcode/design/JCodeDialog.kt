package dev.jcode.design

import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog as Material3AlertDialog

/**
 * The app's alert dialog — a thin wrapper over the Material3 one that exists to set dialog width in
 * a single place.
 *
 * Android sizes a dialog window to the platform default, which on JCode's wide landscape screens
 * leaves a narrow column: prompts wrap their button row onto two lines and long file paths get
 * squeezed. Turning `usePlatformDefaultWidth` off hands the width back to the content, so this sets
 * it explicitly to [JCodeDialogDefaults.width].
 *
 * Call sites import this instead of `androidx.compose.material3.AlertDialog` and are otherwise
 * unchanged; every dialog in the app moves together when the constant changes.
 */
object JCodeDialogDefaults {
    /** Widest a dialog may get, regardless of screen size. */
    val MaxWidth: Dp = 640.dp

    /** Share of a narrow screen a dialog may use, so it never runs edge to edge on a phone. */
    const val ScreenFraction: Float = 0.92f

    /** The dialog width for the current screen: [MaxWidth], or [ScreenFraction] when that is smaller. */
    @Composable
    fun width(): Dp {
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val fraction = screenWidth * ScreenFraction
        return if (fraction < MaxWidth) fraction else MaxWidth
    }
}

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
    width: Dp = JCodeDialogDefaults.width(),
) {
    Material3AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        // The width goes on first so a caller-supplied modifier can still layer over it.
        modifier = Modifier.width(width).then(modifier),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        // Rebuilt rather than copied so the caller keeps its dismiss behaviour while the width
        // decision stays here; a platform-default width would override everything above.
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
        ),
    )
}
