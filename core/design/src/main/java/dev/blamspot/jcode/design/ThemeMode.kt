package dev.blamspot.jcode.design

/** User-selectable theme preference. [System] follows the OS dark-mode setting. */
enum class ThemeMode(val configId: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromConfigId(id: String?): ThemeMode = when (id?.lowercase()) {
            "light" -> Light
            "system", "auto" -> System
            else -> Dark
        }
    }
}
