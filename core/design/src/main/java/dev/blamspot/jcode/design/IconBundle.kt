package dev.blamspot.jcode.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Binary compatibility for native extensions built before the icon bundle split in two.
 *
 * **This file's name is load-bearing.** A Kotlin top-level function compiles into a class named
 * after its file, so `jcIcon` used to live in `dev.blamspot.jcode.design.IconBundleKt`. Renaming
 * `IconBundle.kt` to `UiIconSet.kt` moved it to `UiIconSetKt` and every already-published native
 * extension — Source Control, the SQL and Postgres clients, the VM manager — died on its first
 * frame with `NoClassDefFoundError: IconBundleKt`. A plugin is loaded into JCode's own process
 * against JCode's own classes, so the design system's JVM surface is part of the extension ABI
 * whether or not anyone meant it to be.
 *
 * So `IconBundleKt` still exists, and still has exactly the one method those extensions call. The
 * Kotlin name differs (a package cannot hold two `jcIcon`s that differ only in return type) but
 * `@JvmName` restores the descriptor they were compiled against.
 *
 * Nothing in JCode calls this. It is here for packages built against JCode ≤ 1.7.1, and can be
 * deleted once every published native extension has been rebuilt — [JCODE_EXT_ABI] is the gate that
 * makes that safe to check.
 */
@Deprecated(
    message = "ABI shim for extensions built before the UI/file icon-set split. Use jcIcon(), which " +
        "returns a Painter and can therefore draw a raster icon pack.",
    replaceWith = ReplaceWith("jcIcon(icon)"),
)
@JvmName("jcIcon")
@Composable
fun jcIconVector(icon: JCodeIcon): ImageVector = when (val art = LocalUiIconSet.current.art(icon)) {
    is IconArt.Vector -> art.image
    // An ImageVector cannot carry a bitmap. A PNG-based icon pack therefore does not reach an
    // extension still compiled against this signature; it gets the built-in glyph instead, which is
    // the one outcome here that is wrong-looking rather than broken. Rebuilding the extension against
    // `jcIcon(): Painter` is what fixes it.
    is IconArt.Raster -> when (val builtIn = defaultUiIconSet.art(icon)) {
        is IconArt.Vector -> builtIn.image
        // Not reachable today — every built-in slot is a vector — but expressed rather than cast, so
        // adding a raster to a built-in set cannot turn this into a ClassCastException at draw time.
        is IconArt.Raster -> Icons.Rounded.Circle
    }
}
