package dev.blamspot.jcode.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One installable version in a detail-page picker: the clean [value] used for install plus an optional
 *  presentational [tag] (e.g. "LTS Jod") shown as a badge. */
data class VersionOption(val value: String, val tag: String? = null)

/** Newest-first version chosen from a picker, plus the installed-versions list for [multiVersion] tools. */
@Composable
internal fun VersionSection(
    multiVersion: Boolean,
    versions: List<VersionOption>,
    installedVersions: List<String>,
    activeVersion: String?,
    selectedVersion: String,
    loading: Boolean,
    enabled: Boolean,
    onSelectVersion: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (multiVersion) "Versions" else "Version",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        // One picker rather than a row per installed version: a tool with a dozen of them turned the
        // top of the page into a list to scroll past. What each version *is* — installed, in use — is
        // marked in the picker, and the actions below apply to whichever is selected.
        VersionDropdown(
            versions = versions,
            selected = selectedVersion,
            installedVersions = installedVersions,
            activeVersion = activeVersion,
            loading = loading,
            enabled = enabled,
            onSelect = onSelectVersion,
        )
    }
}

@Composable
private fun VersionDropdown(
    versions: List<VersionOption>,
    selected: String,
    installedVersions: List<String>,
    activeVersion: String?,
    loading: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val latest = versions.firstOrNull()?.value
    val selectedTag = versions.firstOrNull { it.value == selected }?.tag
    Box {
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(StrokeWidth.thin, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .clickable(enabled = enabled && !loading && versions.isNotEmpty()) { expanded = true }
                    .handCursor()
                    .padding(horizontal = Space.md, vertical = Space.ms),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                if (loading) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text(
                        text = "Loading versions…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        text = versionLabel(selected, latest),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (selectedTag != null) VersionBadge(selectedTag)
                    VersionStateLabel(selected, installedVersions, activeVersion)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(text = "▾", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            versions.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Text(versionLabel(option.value, latest))
                            if (option.tag != null) VersionBadge(option.tag)
                            VersionStateLabel(option.value, installedVersions, activeVersion)
                        }
                    },
                    onClick = {
                        onSelect(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Small accent pill for a version tag such as "LTS Jod". */
@Composable
private fun VersionBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Space.s, vertical = Space.xxs),
        )
    }
}

/** "in use" / "installed" beside a version — what the per-version list used to say. */
@Composable
private fun VersionStateLabel(version: String, installedVersions: List<String>, activeVersion: String?) {
    val state = when {
        version == activeVersion -> "in use"
        version in installedVersions -> "installed"
        else -> return
    }
    Text(
        text = state,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun versionLabel(version: String, latest: String?): String =
    if (latest != null && version == latest) "$version · latest" else version
