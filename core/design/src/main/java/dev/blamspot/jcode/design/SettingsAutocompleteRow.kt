package dev.blamspot.jcode.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A text setting that offers what it knows, without insisting on it.
 *
 * The values here are not a closed set — a model id the extension has never heard of is still a
 * valid model id — so this is a text field first and a list second. What you type is what is saved,
 * and the suggestions only save you the typing.
 *
 * [loadSuggestions] runs when the field appears rather than when the list is opened, because it goes
 * out to the Linux runtime to ask a tool what it supports: doing that on the tap would mean opening
 * an empty list and watching it fill. It is allowed to come back with nothing — the tool may not be
 * installed — and a field with no suggestions is simply a text field.
 */
@Composable
fun SettingsAutocompleteRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    loadSuggestions: suspend () -> List<String>,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    placeholder: String = "",
    onCommit: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var open by remember { mutableStateOf(false) }
    var wasFocused by remember { mutableStateOf(false) }

    // Keyed on the loader's identity so a change of tool re-asks; a stable loader fetches once.
    LaunchedEffect(loadSuggestions) {
        loading = true
        suggestions = runCatching { loadSuggestions() }.getOrDefault(emptyList())
        loading = false
    }

    // Typing filters; an exact match stops filtering, so picking a suggestion does not leave the
    // list showing that one entry as though it were still narrowing something down.
    val matches = remember(suggestions, value) {
        when {
            value.isBlank() || suggestions.any { it.equals(value, ignoreCase = true) } -> suggestions
            else -> suggestions.filter { it.contains(value, ignoreCase = true) }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        supporting?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            shape = RoundedCornerShape(Radius.xl),
            border = BorderStroke(StrokeWidth.thin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = Space.ms),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f).textCursor()) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { onValueChange(it); open = true },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        // Done saves and lets go. Commit-on-blur alone loses a typed value on a
                        // touch screen, where a field can hold focus until something else takes it —
                        // and on a settings page there is often nothing else that will.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { onCommit?.invoke(); open = false; focus.clearFocus() },
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { st ->
                            if (st.isFocused) open = true
                            if (wasFocused && !st.isFocused) onCommit?.invoke()
                            wasFocused = st.isFocused
                        },
                    )
                }
                when {
                    loading -> CircularProgressIndicator(
                        modifier = Modifier.size(IconSize.xs),
                        strokeWidth = StrokeWidth.thick,
                    )
                    suggestions.isNotEmpty() -> Icon(
                        imageVector = jcIcon(if (open) JCodeIcon.ChevronUp else JCodeIcon.ChevronDown),
                        contentDescription = if (open) "Hide suggestions" else "Show suggestions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(IconSize.sm)
                            .clickable { open = !open }
                            .handCursor(),
                    )
                }
            }
        }
        if (open && matches.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(Radius.lg),
                border = BorderStroke(StrokeWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = SuggestionsMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    matches.forEachIndexed { index, suggestion ->
                        if (index > 0) {
                            HorizontalDivider(
                                thickness = StrokeWidth.hairline,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                        val selected = suggestion.equals(value, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                )
                                .clickable {
                                    onValueChange(suggestion)
                                    onCommit?.invoke()
                                    open = false
                                }
                                .handCursor()
                                .padding(horizontal = Space.ms, vertical = Space.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Roughly six rows: enough to browse, short enough that the settings page still scrolls as a page. */
private val SuggestionsMaxHeight = 240.dp
