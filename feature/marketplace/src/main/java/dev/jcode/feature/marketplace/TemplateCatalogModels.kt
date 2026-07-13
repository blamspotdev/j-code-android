package dev.jcode.feature.marketplace

/** One ordered step of a template's scaffold recipe, executed on-device. */
data class TemplateRecipeStep(
    val label: String,
    val run: String,
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
) {
    /** An empty template creates only the folder; there is nothing to run. */
    val isEmpty: Boolean get() = recipe.isEmpty()
}

/** The bundled first-party template extension (descriptor + its templates). */
data class TemplateExtension(
    val id: String,
    val name: String,
    val publisher: String?,
    val version: String?,
    val description: String,
    val templates: List<ProjectTemplate>,
)
