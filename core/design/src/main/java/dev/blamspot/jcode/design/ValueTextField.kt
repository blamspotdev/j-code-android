package dev.blamspot.jcode.design

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle

/**
 * The app's bare text input, held as a plain `String` the caller owns.
 *
 * Compose 1.13 deprecates every value/onValueChange [BasicTextField] in favour of the
 * `TextFieldState` one. Moving there properly means each caller hoisting a `TextFieldState` instead
 * of a `String`; mirroring a `String` into one instead races the IME (an echo of an earlier
 * keystroke lands after a later one and resets the text and cursor). Until the callers are moved,
 * the legacy overload lives here and only here, behaving exactly as it always has.
 */
@Composable
fun ValueTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    cursorBrush: Brush = SolidColor(Color.Black),
) {
    @Suppress("DEPRECATION")
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle,
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = cursorBrush,
    )
}
