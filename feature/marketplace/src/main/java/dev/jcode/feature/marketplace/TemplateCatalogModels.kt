package dev.jcode.feature.marketplace

/** One ordered step of a template's scaffold recipe, executed on-device. */
data class TemplateRecipeStep(
    val label: String,
    val run: String,
    /** Optional working directory for this step (placeholders resolved before exec). */
    val workdir: String? = null,
)

/** A project template that can be scaffolded on-device by the embedded runtime. */
data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    /** Toolchain ids (from the SDK catalog) this template needs at scaffold time. */
    val requires: List<String> = emptyList(),
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
