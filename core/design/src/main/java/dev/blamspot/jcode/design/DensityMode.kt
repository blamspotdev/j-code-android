package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

enum class DensityMode {
    Compact,
    Comfortable,
}

val LocalDensityMode = compositionLocalOf { DensityMode.Comfortable }
