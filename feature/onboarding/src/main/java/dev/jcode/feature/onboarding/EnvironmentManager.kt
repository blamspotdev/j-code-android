package dev.jcode.feature.onboarding

import androidx.compose.runtime.compositionLocalOf
import dev.jcode.core.distro.EnvironmentInfo

/**
 * Bridges the installed-environments list + switch/delete actions into [OnboardingFeature.EnvironmentSetupPage]
 * without threading them through the (register-pressured) workbench shell composable. The app provides this at
 * the same level as the other workbench CompositionLocals; the setup page consumes it to render the
 * "Installed environments" switcher.
 */
data class EnvironmentManagerActions(
    val environments: List<EnvironmentInfo> = emptyList(),
    val onSwitch: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
)

val LocalEnvironmentManager = compositionLocalOf { EnvironmentManagerActions() }
