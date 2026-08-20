package dev.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * How large an imported `.vsix` extension draws its own text, as a percentage.
 *
 * A percentage rather than a size in sp, which is what the editor and terminal use, because those
 * two render text JCode owns and this one does not. A `.vsix` is a web page the extension author
 * wrote and styled — headings, code blocks and captions all at sizes of their choosing — so there
 * is no single number to set. What there is, is a scale to apply to all of them at once, and saying
 * "125%" is honest about that where "16 sp" would not be.
 *
 * Applied through the WebView's own text zoom rather than by injecting CSS. It scales text and
 * leaves everything else alone, so a layout built on fixed-size boxes still holds together; and it
 * works on an extension whose stylesheet this app has never seen, which is every one of them.
 */
@Immutable
class ExtensionFontSizeSetting(
    val percent: Int = SettingsDefaults.EXTENSION_FONT_SCALE,
    val onChange: (Int) -> Unit = {},
)

val LocalExtensionFontSizeSetting = compositionLocalOf { ExtensionFontSizeSetting() }
