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

/** Enable the accessibility service / IME that let a computer control the phone. */
@Composable
internal fun PhoneControlDetail() {
    val context = LocalContext.current
    val prefs = remember { SetupPreferences(context) }
    var refresh by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    refresh // re-read service state after returning from system settings
    val accessibilityOn = GlideAccessibilityService.instance != null
    val imeEnabled = remember(refresh) {
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
    }
    var editingOnly by remember { mutableStateOf(prefs.remoteKeyboardEditingOnly) }

    Body("Дозвольте підключеному комп’ютеру рухати вказівник, натискати й друкувати на цьому телефоні.")
    Spacer(Modifier.height(16.dp))
    ServiceRow("Миша й клавіатура (спеціальні можливості)", accessibilityOn) {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Spacer(Modifier.height(8.dp))
    ServiceRow("Клавіатура Glide Phone (метод введення)", imeEnabled) {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Spacer(Modifier.height(8.dp))
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        ServiceRow("Файли телефону для комп’ютера (SFTP)", remember(refresh) { SftpSupport.hasStorageAccess() }) {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Лише під час редагування", style = MaterialTheme.typography.titleMedium)
            Text("Клавіатура Glide Phone друкує, тільки коли вона відкрита", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = editingOnly,
            onCheckedChange = {
                prefs.remoteKeyboardEditingOnly = it
                editingOnly = it
                RemoteInput.keyboardAvailabilityChanged(context)
            },
            modifier = Modifier.semantics { contentDescription = "Лише під час редагування" }
        )
    }
}

@Composable
private fun ServiceRow(title: String, enabled: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, LocalGlideColors.current.cardBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (enabled) "Увімкнено" else "Вимкнено", style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) LocalGlideColors.current.connectedDot else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onOpen) { Text("Налаштувати") }
    }
}

/** Per-app notification forwarding and privacy (KDE Connect NotificationFilterActivity). */
@Composable
internal fun NotificationFilterSection() {
    val context = LocalContext.current
    val filter = remember { NotificationFilter(context) }
    var version by remember { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val apps by produceState(initialValue = emptyList<Pair<String, String>>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filter { it.first != context.packageName }
                .sortedBy { it.second.lowercase() }
        }
    }
    version // recompose after changes
    var locked by remember { mutableStateOf(filter.alertOnlyWhenLocked) }
    SwitchRow("Сповіщати лише коли телефон заблоковано", "Інакше сповіщення надходять без звуку", locked) {
        filter.alertOnlyWhenLocked = it; locked = it
    }
    Spacer(Modifier.height(8.dp))
    SwitchRow("Усі програми", "Надсилати сповіщення з усіх програм", filter.allEnabled) {
        filter.allEnabled = it; version++
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Пошук програми") })
    Spacer(Modifier.height(8.dp))
    val shown = apps.filter { query.isBlank() || it.second.contains(query, ignoreCase = true) || it.first.contains(query, ignoreCase = true) }
    shown.take(200).forEach { (pkg, label) ->
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = if (expanded == pkg) null else pkg }, modifier = Modifier.weight(1f)) {
                    Text(label, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(
                    checked = filter.isEnabled(pkg),
                    onCheckedChange = { filter.setEnabled(pkg, it); version++ },
                    modifier = Modifier.semantics { contentDescription = label }
                )
            }
            if (expanded == pkg) {
                SwitchRow("Приховати вміст", "Надсилати лише назву програми", filter.blocksContents(pkg)) {
                    filter.setBlocksContents(pkg, it); version++
                }
                SwitchRow("Не надсилати зображення", "Без значків і картинок", filter.blocksImages(pkg)) {
                    filter.setBlocksImages(pkg, it); version++
                }
            }
        }
    }
}

/** Contacts consent and numbers never announced on the computer. */
@Composable
internal fun SyncPrivacySection() {
    val context = LocalContext.current
    val prefs = remember { SetupPreferences(context) }
    var contacts by remember { mutableStateOf(prefs.contactsAccepted) }
    var asking by remember { mutableStateOf(false) }
    SwitchRow("Передавати контакти", "Комп’ютер зможе бачити вашу адресну книгу", contacts) {
        if (it) asking = true else { prefs.contactsAccepted = false; contacts = false }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Поділитися контактами?") },
            text = { Text("Сполучені комп’ютери отримають копію всіх контактів цього телефону.") },
            confirmButton = { TextButton(onClick = { prefs.contactsAccepted = true; contacts = true; asking = false }) { Text("Дозволити") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Скасувати") } }
        )
    }
    Spacer(Modifier.height(16.dp))
    Text("Заблоковані номери", style = MaterialTheme.typography.titleMedium)
    Text("Дзвінки з цих номерів не показуються на комп’ютері, по одному в рядку",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    var blocked by remember { mutableStateOf(TelephonyReporter.blockedNumbers(context).joinToString("\n")) }
    OutlinedTextField(
        blocked,
        { blocked = it; TelephonyReporter.setBlockedNumbers(context, it.lines()) },
        Modifier.fillMaxWidth(),
        minLines = 2,
        label = { Text("Номери") }
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

/** MprisPlugin preferences: media notification and volume keys. */
@Composable
internal fun MediaNotificationSettings() {
    val context = LocalContext.current
    val prefs = remember { SetupPreferences(context) }
    var shown by remember { mutableStateOf(prefs.mprisNotification) }
    var volumeKeys by remember { mutableStateOf(prefs.mprisVolumeKeys) }
    SwitchRow("Сповіщення з керуванням", "Кнопки плеєра в шторці й на екрані блокування", shown) {
        prefs.mprisNotification = it; shown = it
        RemoteMediaNotification.update(context)
    }
    SwitchRow("Кнопки гучності телефона", "Змінюють гучність комп’ютера, поки показано сповіщення", volumeKeys) {
        prefs.mprisVolumeKeys = it; volumeKeys = it
        RemoteMediaNotification.update(context)
    }
}

/**
 * PresenterActivity volume keys: up = next slide, down = previous. Where the platform lets
 * a remote-volume MediaSession receive the keys with the screen off it is used, otherwise
 * the keys are taken while the screen is in front.
 */
@Composable
internal fun PresenterVolumeKeys(connector: PairingConnector?) {
    val context = LocalContext.current
    val prefs = remember { SetupPreferences(context) }
    var volumeKeys by remember { mutableStateOf(prefs.presenterVolumeKeys) }
    var sensitivity by remember { mutableStateOf(prefs.presenterSensitivity.toFloat()) }
    val offScreen = android.os.Build.VERSION.SDK_INT < 33 || android.os.Build.VERSION.SDK_INT >= 36
    DisposableEffect(volumeKeys, connector) {
        if (!volumeKeys || connector == null) return@DisposableEffect onDispose { }
        val session = if (offScreen) android.media.session.MediaSession(context, "GlidePresenter").apply {
            setPlaybackState(android.media.session.PlaybackState.Builder()
                .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 0f).build())
            setPlaybackToRemote(object : android.media.VolumeProvider(VOLUME_CONTROL_RELATIVE, 0, 0) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction > 0) connector.sendPresenterSpecialKey(ProtocolPackets.KEY_NEXT_SLIDE)
                    else if (direction < 0) connector.sendPresenterSpecialKey(ProtocolPackets.KEY_PREV_SLIDE)
                }
            })
            isActive = true
        } else null
        if (session == null) {
            MainActivity.keyInterceptor = { event ->
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                        if (event.action == android.view.KeyEvent.ACTION_UP) connector.sendPresenterSpecialKey(ProtocolPackets.KEY_NEXT_SLIDE)
                        true
                    }
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        if (event.action == android.view.KeyEvent.ACTION_UP) connector.sendPresenterSpecialKey(ProtocolPackets.KEY_PREV_SLIDE)
                        true
                    }
                    else -> false
                }
            }
        }
        onDispose {
            session?.let { it.isActive = false; it.release() }
            MainActivity.keyInterceptor = null
        }
    }
    Spacer(Modifier.height(12.dp))
    SwitchRow("Кнопки гучності гортають слайди", if (offScreen) "Працює й з вимкненим екраном" else "Поки цей екран відкрито", volumeKeys) {
        prefs.presenterVolumeKeys = it; volumeKeys = it
    }
    Text("Чутливість указки: ${sensitivity.toInt()}", style = MaterialTheme.typography.bodyMedium)
    Slider(sensitivity, { sensitivity = it }, onValueChangeFinished = { prefs.presenterSensitivity = sensitivity.toInt() }, valueRange = 0f..100f)
}
