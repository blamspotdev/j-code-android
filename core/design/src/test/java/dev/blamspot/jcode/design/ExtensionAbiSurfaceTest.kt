package dev.blamspot.jcode.design

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The part of `:core:design` that is extension ABI, pinned.
 *
 * A native extension is loaded into JCode's own process against JCode's own classes, so anything it
 * links to is a binary contract even though nothing in this repo says so. A Kotlin top-level
 * declaration compiles into a class named after its **file**, which makes renaming a file a silent
 * ABI break: `IconBundle.kt` became `UiIconSet.kt` and four published extensions — Source Control,
 * the SQL and Postgres clients, the VM manager — died on their first frame with
 * `NoClassDefFoundError: dev.blamspot.jcode.design.IconBundleKt`, having compiled and installed
 * perfectly well.
 *
 * The names below are not a wish list: they were read out of the dex of every published native
 * extension. Breaking one of them means either restoring it (see IconBundle.kt for how) or bumping
 * `JCODE_EXT_ABI` so the loader refuses the old package with a message instead of crashing in its
 * first composition.
 */
class ExtensionAbiSurfaceTest {

    /**
     * Every `dev.blamspot.jcode.design` class the published extensions reference.
     *
     * Loaded without initialisation: this asserts the *name* still resolves, which is the thing a
     * file rename takes away. Running the static initialisers would need a live Android runtime and
     * would test something else entirely.
     */
    private val referencedByExtensions = listOf(
        // Top-level declarations — the fragile ones, since each is named after its file.
        "CompactButtonsKt",
        "ContextMenuKt",
        "IconBundleKt",
        "JCodeDialogKt",
        "ManagerFilterChipKt",
        "ManagerNoticeCardKt",
        "ManagerSectionCardKt",
        "ManagerSummaryRowKt",
        "PointerCursorsKt",
        "SettingsDropdownRowKt",
        "SettingsTextFieldRowKt",
        // Declared types, which move only if someone renames the declaration itself.
        "ContextAction",
        "ControlSize",
        "IconSize",
        "JCodeIcon",
        "JCodeSemanticColors",
        "JCodeTheme",
        "Radius",
        "Space",
        "StrokeWidth",
    )

    @Test
    fun `every class published extensions link against still resolves`() {
        val missing = referencedByExtensions.filter { simpleName ->
            runCatching {
                Class.forName("dev.blamspot.jcode.design.$simpleName", false, javaClass.classLoader)
            }.isFailure
        }
        assertEquals(
            "Renaming a file moves its top-level declarations to a new JVM class and breaks every " +
                "installed native extension that calls them. Restore the name (see IconBundle.kt) " +
                "or bump JCODE_EXT_ABI so the loader refuses old packages cleanly.",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `the legacy jcIcon still returns an ImageVector`() {
        // What extensions built against JCode <= 1.7.1 call. The parameter list is (slot, Composer,
        // changed) because it is @Composable; only the return type distinguishes it from the new one.
        val method = Class.forName("dev.blamspot.jcode.design.IconBundleKt", false, javaClass.classLoader)
            .declaredMethods
            .singleOrNull { it.name == "jcIcon" }
        assertNotNull("IconBundleKt.jcIcon is gone — installed extensions will not resolve it", method)
        assertEquals(ImageVector::class.java, method!!.returnType)
        assertEquals(JCodeIcon::class.java, method.parameterTypes.first())
    }

    @Test
    fun `the current jcIcon returns a Painter`() {
        // The one the app itself uses, and what an extension gets when it is next rebuilt. Kept
        // distinct from the legacy overload by living in a different file, hence a different class.
        val method = Class.forName("dev.blamspot.jcode.design.UiIconSetKt", false, javaClass.classLoader)
            .declaredMethods
            .singleOrNull { it.name == "jcIcon" }
        assertNotNull("UiIconSetKt.jcIcon is missing", method)
        assertEquals(Painter::class.java, method!!.returnType)
        assertEquals(JCodeIcon::class.java, method.parameterTypes.first())
    }

    @Test
    fun `both jcIcon overloads coexist under the same JVM name`() {
        // The whole trick: same JVM method name, same parameters, different return type and different
        // owning class. If these ever collapse into one class the JVM would reject the duplicate.
        val legacy = Class.forName("dev.blamspot.jcode.design.IconBundleKt", false, javaClass.classLoader)
        val current = Class.forName("dev.blamspot.jcode.design.UiIconSetKt", false, javaClass.classLoader)
        assertEquals(1, legacy.declaredMethods.count { it.name == "jcIcon" })
        assertEquals(1, current.declaredMethods.count { it.name == "jcIcon" })
    }
}
