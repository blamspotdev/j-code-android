package dev.jcode.vdevice

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jcode.design.CompactOutlinedButton
import dev.jcode.design.ManagerNoticeCard
import dev.jcode.design.ManagerSectionCard
import dev.jcode.design.SettingsDropdownRow

/** One permission an app declares, as the sheet needs to show it. */
private class Declared(
    val name: String,
    val label: String,
    /** True for the ones the platform asks about at runtime; the rest are granted at install. */
    val runtime: Boolean,
)

/**
 * What one app installed on the virtual device may do.
 *
 * The list is the app's **own manifest** — every `<uses-permission>` it declares, and nothing else,
 * because a permission an app never asked for is one the platform would refuse it whatever anybody
 * here decided. Each gets the three states a phone has, and they mean the same things.
 *
 * This is only half of the answer. It says what the *app* may do; the hardware bench says what the
 * *device* has, and both are required — an app cannot be given a camera the device does not have.
 * The two are deliberately separate: one is about trust, the other about equipment.
 *
 * Over the device's screen rather than on it, like [InstallSheet] and for the same reason: it is
 * J Code talking about the app, so it must not appear in what `screencap` answers with, where it
 * would read as something the guest drew.
 */
@Composable
internal fun AppPermissionsSheet(
    app: VirtualDeviceApp,
    onSnackbar: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Read so that every write below redraws the sheet: the policy lives in a file, not in state.
    val revision = VirtualDevicePolicy.revision.intValue
    val declared = remember(app.apkPath) { declaredBy(context, app) }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "What ${app.label} declared in its manifest, and what this device " +
                            "answers when it asks. Whether the device has the hardware at all is " +
                            "the other half, on the hardware tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            val runtime = declared.filter { it.runtime }
            val install = declared.filterNot { it.runtime }

            if (declared.isEmpty()) {
                ManagerNoticeCard(
                    title = "It asked for nothing",
                    message = "${app.label} declares no permissions at all, so there is nothing here " +
                        "to decide. Anything it reaches for is refused, which is what the platform " +
                        "would do too.",
                )
            }

            if (runtime.isNotEmpty()) {
                ManagerSectionCard(
                    title = "Asked for at runtime",
                    description = "The dangerous ones. Ask means undecided — it reads as denied " +
                        "until the app asks and you answer the device's prompt, exactly as a " +
                        "phone behaves.",
                ) {
                    runtime.forEach { Rule(app = app, permission = it, revision = revision) }
                }
            }

            if (install.isNotEmpty()) {
                ManagerSectionCard(
                    title = "Granted at install",
                    description = "The ordinary ones, which a phone grants without asking and an " +
                        "app never prompts for. Deny still works, and is the only way to take one " +
                        "away.",
                ) {
                    install.forEach { Rule(app = app, permission = it, revision = revision) }
                }
            }

            ManagerSectionCard(
                title = "Running",
                description = "The device shows one app at a time, so leaving an app is the closest " +
                    "thing it has to closing one.",
            ) {
                var background by remember(revision) {
                    mutableStateOf(VirtualDevicePolicy.backgroundAllowed(context, app.packageName))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Runs in background",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Keep its services and notifications alive after you leave it — " +
                                "what a music player or a download needs, and what nothing else does.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = background,
                        onCheckedChange = {
                            background = it
                            VirtualDevicePolicy.setBackgroundAllowed(context, app.packageName, it)
                        },
                    )
                }
            }

            CompactOutlinedButton(
                text = "Open hardware",
                onClick = { SimulatedHardware.requestOpen() },
                modifier = Modifier.fillMaxWidth(),
            )

            ManagerNoticeCard(
                title = "Cleared when J Code restarts",
                message = "The device is wiped on every start — apps, their data, and these answers " +
                    "with them. A grant that outlived the app it was given to would be waiting for " +
                    "whatever was installed under that name next.",
            )
        }
    }
}

/** One permission's three-way control, plus what the device says about it right now. */
@Composable
private fun Rule(app: VirtualDeviceApp, permission: Declared, revision: Int) {
    val context = LocalContext.current
    val rule = VirtualDevicePolicy.rule(context, app.packageName, permission.name)
    // The hardware this permission is about, if any: a camera that is off is worth saying out loud,
    // because otherwise "Allow" and "denied" appear to disagree.
    val hardware = remember(permission.name) { VirtualHardware.byPermission(permission.name) }
    val missing = hardware != null &&
        remember(revision, hardware) { VirtualDevicePolicy.mode(context, hardware) } == HardwareMode.Off

    SettingsDropdownRow(
        label = permission.label,
        supporting = when {
            missing && rule == PermissionRule.Allow ->
                "Allowed, but the device's ${hardware!!.label.lowercase()} is off — so it is refused."
            else -> permission.name
        },
        options = PermissionRule.entries.map { it.name },
        selected = rule.name,
        optionLabel = { PermissionRule.valueOf(it).label },
        onSelect = {
            VirtualDevicePolicy.setRule(
                context,
                app.packageName,
                permission.name,
                PermissionRule.valueOf(it),
            )
        },
    )
}

/**
 * Everything the APK's manifest asks for, named the way the platform names it.
 *
 * Read straight out of the archive rather than from a running guest, so the sheet answers the same
 * whether the app has ever been opened.
 */
private fun declaredBy(context: Context, app: VirtualDeviceApp): List<Declared> {
    val info = runCatching {
        context.packageManager.getPackageArchiveInfo(app.apkPath, PackageManager.GET_PERMISSIONS)
    }.getOrNull()
    return info?.requestedPermissions.orEmpty().map { name ->
        Declared(
            name = name,
            label = label(context, name),
            runtime = VirtualDevicePolicy.dangerous(context, name),
        )
    }.sortedWith(compareByDescending<Declared> { it.runtime }.thenBy { it.label.lowercase() })
}

/**
 * A permission as a person would name it: the platform's own label where there is one, since the
 * phone's package manager has already translated its own permissions, and the tail of the name for
 * one a guest declares itself.
 */
private fun label(context: Context, permission: String): String = runCatching {
    val info = context.packageManager.getPermissionInfo(permission, 0)
    info.loadLabel(context.packageManager).toString().replaceFirstChar { it.uppercase() }
}.getOrDefault(permission.substringAfterLast('.').replace('_', ' '))
