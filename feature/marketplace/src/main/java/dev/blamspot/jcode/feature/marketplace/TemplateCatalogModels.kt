package dev.blamspot.jcode.feature.marketplace

/**
 * One ordered step of a template's scaffold recipe, executed on-device.
 *
 * Either [run] (the shell inline in `template.yaml`) or [script] (a `.sh` beside it). A step that
 * writes a build file is mostly a heredoc, and a heredoc reads as shell rather than as YAML — so a
 * template of any size keeps its scaffolding in files a shell linter can see and an editor can
 * colour, and `template.yaml` stays a list of what happens in what order.
 */
data class TemplateRecipeStep(
    val label: String,
    val run: String = "",
    /** Path to a `.sh`, relative to the template's own directory. Takes precedence over [run]. */
    val script: String? = null,
    /** Optional working directory for this step (placeholders resolved before exec). */
    val workdir: String? = null,
)

/**
 * A value the user picks before scaffolding (e.g. Android min/target SDK, a .NET version). The
 * chosen value is substituted into the recipe as `{{id}}`. All specifics — labels, choices,
 * defaults — live in the extension's template.yaml so the app stays generic and small.
 */
data class TemplateInput(
    /** Placeholder token: an input with id "minSdk" fills `{{minSdk}}` in the recipe. */
    val id: String,
    val label: String,
    /** "select" (a fixed choice list) or "text" (free entry). Unknown types fall back to text. */
    val type: String = "select",
    val options: List<String> = emptyList(),
    /** Optional guest command whose stdout lines become the live select options at New-Project time
     *  (e.g. installed .NET SDKs → net8.0/net10.0). Falls back to [options] when empty, the runtime
     *  isn't ready, or the command yields nothing — so a new .NET release needs no app change. */
    val optionsCommand: String = "",
    val default: String? = null,
) {
    /** The value to pre-fill / fall back to when the user leaves it untouched. */
    val defaultValue: String get() = default ?: options.firstOrNull() ?: ""
}

/** A project template that can be scaffolded on-device by the embedded runtime. */
data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    /** Toolchain ids (from the SDK catalog) this template needs at scaffold time. */
    val requires: List<String> = emptyList(),
    /** User-configurable inputs collected before scaffolding; empty for fixed templates. */
    val inputs: List<TemplateInput> = emptyList(),
    val recipe: List<TemplateRecipeStep> = emptyList(),
    /**
     * The template's own directory (`<extension>/templates/<id>`), against which a step's
     * [TemplateRecipeStep.script] resolves. Null for a template that declares no scripts.
     *
     * A path rather than the file's text: the extension directory is bound into the runtime at the
     * same absolute path, so a script step runs the real file and a failure names it.
     */
    val dir: java.io.File? = null,
) {
    /** An empty template creates only the folder; there is nothing to run. */
    val isEmpty: Boolean get() = recipe.isEmpty()
}
