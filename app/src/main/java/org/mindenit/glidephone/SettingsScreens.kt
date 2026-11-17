package org.mindenit.glidephone

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mindenit.glidephone.ui.theme.LocalGlideColors

/** Custom device addresses and trusted networks (KDE Connect CustomDevices / TrustedNetworks). */
@Composable
internal fun NetworkDetail(connector: PairingConnector?) {
    val context = LocalContext.current
    val settings = remember { NetworkSettings(context) }
    var custom by remember { mutableStateOf(settings.customDevices) }
    var trusted by remember { mutableStateOf(settings.trustedNetworks) }
    var allAllowed by remember { mutableStateOf(settings.allNetworksAllowed) }
    var hasPermission by remember { mutableStateOf(settings.hasSsidPermission) }
    var draft by rememberSaveable { mutableStateOf("") }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = settings.hasSsidPermission
        allAllowed = settings.allNetworksAllowed
    }
    fun changed() = connector?.networkSettingsChanged()

    Body("Додайте комп’ютери за адресою, якщо їх не видно автоматично (наприклад, через VPN чи іншу підмережу).")
    Spacer(Modifier.height(16.dp))
    SectionLabel("Пристрої за адресою")
    Spacer(Modifier.height(8.dp))
    custom.forEach { host ->
        AddressRow(Icons.Outlined.Lan, host) {
            custom = custom - host
            settings.customDevices = custom
            changed()
        }
        Spacer(Modifier.height(8.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.trim() },
            label = { Text("IP-адреса або ім’я хоста") },
            singleLine = true,
            isError = draft.isNotEmpty() && !NetworkSettings.isValidHost(draft),
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                custom = (custom + draft).distinct()
                settings.customDevices = custom
                draft = ""
                changed()
            },
            enabled = NetworkSettings.isValidHost(draft)
        ) { Text("Додати") }
    }

    Spacer(Modifier.height(24.dp))
    SectionLabel("Довірені мережі")
    Spacer(Modifier.height(4.dp))
    Body("Поза довіреними мережами телефон не оголошує себе й приймає лише вже сполучені пристрої.")
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Дозволити всі мережі", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(
            checked = allAllowed,
            onCheckedChange = { value ->
                if (!value && !hasPermission) {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    settings.allNetworksAllowed = value
                    allAllowed = settings.allNetworksAllowed
                    changed()
                }
            },
            modifier = Modifier.semantics { contentDescription = "Дозволити всі мережі" }
        )
    }
    if (!allAllowed) {
        Spacer(Modifier.height(8.dp))
        trusted.forEach { ssid ->
            AddressRow(Icons.Outlined.Wifi, ssid.trim('"')) {
                trusted = trusted - ssid
                settings.trustedNetworks = trusted
                changed()
            }
            Spacer(Modifier.height(8.dp))
        }
        val current = settings.currentSsid()
        if (current != null && current !in trusted) {
            OutlinedButton(onClick = {
                trusted = trusted + current
                settings.trustedNetworks = trusted
                changed()
            }) { Text("Додати поточну мережу ${current.trim('"')}") }
        } else if (current == null) {
            Text("Поточну мережу Wi-Fi не визначено.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddressRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, LocalGlideColors.current.cardBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "Видалити $label") }
    }
}

/** Per-device plugin switches (KDE Connect's device plugin settings). */
@Composable
internal fun DevicePluginsDetail(connector: PairingConnector?, deviceId: String?) {
    if (connector == null || deviceId == null) {
        NotConnectedNotice(connector)
        return
    }
    Body("Оберіть, які функції доступні цьому пристрою. Налаштування зберігаються окремо для кожного пристрою.")
    Spacer(Modifier.height(16.dp))
    DevicePlugin.entries.forEach { plugin ->
        var enabled by remember(deviceId, plugin) { mutableStateOf(connector.isPluginEnabled(deviceId, plugin)) }
        Row(
            Modifier.fillMaxWidth()
                .border(1.dp, LocalGlideColors.current.cardBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(plugin.title, style = MaterialTheme.typography.titleMedium)
                Text(plugin.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = enabled,
                onCheckedChange = { connector.setPluginEnabled(deviceId, plugin, it); enabled = it },
                modifier = Modifier.semantics { contentDescription = plugin.title }
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
