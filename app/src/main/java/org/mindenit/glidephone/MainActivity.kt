package org.mindenit.glidephone

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhonelinkRing
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.mindenit.glidephone.ui.theme.GlidePhoneTheme
import org.mindenit.glidephone.ui.theme.LocalGlideColors

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    companion object {
        /** Open a detail screen directly, e.g. "COMMANDS" from the persistent notification. */
        const val EXTRA_DETAIL = "detail"
        internal var pendingDetail by androidx.compose.runtime.mutableStateOf<String?>(null)
        /** Lets a screen take hardware keys (presenter volume keys). */
        internal var keyInterceptor: ((android.view.KeyEvent) -> Boolean)? = null
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        keyInterceptor?.invoke(event) == true || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        keyInterceptor?.invoke(event) == true || super.onKeyUp(keyCode, event)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_DETAIL)?.let { pendingDetail = it }
    }

    override fun onStart() {
        super.onStart()
        GlideHub.inForeground = true
    }

    override fun onStop() {
        GlideHub.inForeground = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        intent?.getStringExtra(EXTRA_DETAIL)?.let { pendingDetail = it }
        setContent {
            GlidePhoneTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    GlideApp()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Domain
// ---------------------------------------------------------------------------

internal enum class Detail(val title: String, val icon: ImageVector) {
    FILES("Файли", Icons.Outlined.Folder),
    NOTIFICATIONS("Сповіщення", Icons.Outlined.Notifications),
    PRESENTER("Віддалене керування", Icons.Outlined.NearMe),
    RING("Знайти телефон", Icons.Outlined.LocationOn),
    CLIPBOARD("Буфер обміну", Icons.Outlined.ContentCopy),
    BATTERY("Заряд батареї", Icons.Outlined.BatteryChargingFull),
    REMOTE("Миша та клавіатура", Icons.Outlined.Mouse),
    COMMANDS("Команди", Icons.Outlined.Terminal),
    MEDIA("Керування медіа", Icons.Outlined.PlayCircle),
    VOLUME("Гучність комп’ютера", Icons.Outlined.Speaker),
    DIGITIZER("Графічний планшет", Icons.Outlined.Draw),
    NETWORK("Мережа", Icons.Outlined.Lan),
    DEVICE_PLUGINS("Плагіни пристрою", Icons.Outlined.Extension),
    PHONE_CONTROL("Керування телефоном", Icons.Outlined.Mouse),
    SETTINGS("Можливості", Icons.Outlined.Tune),
    PRIVACY("Приватність", Icons.Outlined.Shield),
    ABOUT("Про застосунок", Icons.Outlined.Info)
}

private data class Feature(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val id: String,
    val detail: Detail
)

private val features = listOf(
    Feature(Icons.Outlined.Folder, "Файли", "Надсилайте посилання й нотатки на комп’ютер", FeatureId.FILES.key, Detail.FILES),
    Feature(Icons.Outlined.Notifications, "Сповіщення", "Переглядайте сповіщення телефону на ПК", FeatureId.NOTIFICATIONS.key, Detail.NOTIFICATIONS),
    Feature(Icons.Outlined.NearMe, "Віддалене керування", "Телефон як пульт і указка для презентацій", FeatureId.PRESENTER.key, Detail.PRESENTER),
    Feature(Icons.Outlined.LocationOn, "Знайти телефон", "Телефон задзвонить за командою з комп’ютера", FeatureId.RING.key, Detail.RING),
    Feature(Icons.Outlined.ContentCopy, "Буфер обміну", "Копіюйте на одному — вставляйте на іншому", FeatureId.CLIPBOARD.key, Detail.CLIPBOARD),
    Feature(Icons.Outlined.BatteryChargingFull, "Заряд батареї", "Стежте за зарядом телефону з комп’ютера", FeatureId.BATTERY.key, Detail.BATTERY)
)

internal data class RemoteDevice(val id: String, val name: String)

// ---------------------------------------------------------------------------
// App root
// ---------------------------------------------------------------------------

@Composable
private fun GlideApp() {
    val context = LocalContext.current
    val preferences = remember { SetupPreferences(context) }
    var completed by remember { mutableStateOf(preferences.isSetupComplete) }
    var selectedFeatures by remember { mutableStateOf(preferences.selectedFeatures()) }
    var deviceName by remember { mutableStateOf(preferences.deviceName) }
    val setFeature: (String, Boolean) -> Unit = { id, selected ->
        selectedFeatures = preferences.setFeatureSelected(id, selected)
        if (id == FeatureId.NOTIFICATIONS.key && selected) NotificationBridge.refresh()
    }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by rememberSaveable { mutableStateOf<Detail?>(null) }
    val connector = remember { GlideHub.connector(context) }
    val pairingEvent = GlideHub.pairingEvent
    val connectedDevices = GlideHub.connectedDevices
    val isPhoneRinging = GlideHub.isPhoneRinging

    var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedDevice = connectedDevices.firstOrNull { it.id == selectedDeviceId }
        ?: connectedDevices.firstOrNull()
    LaunchedEffect(selectedDevice?.id) { connector.setTarget(selectedDevice?.id) }
    val peerBattery = selectedDevice?.let { GlideHub.peerBatteries[it.id] }
    val remoteCommands = selectedDevice?.let { GlideHub.remoteCommands[it.id] } ?: emptyList()

    // Go online (advertise + listen + background service) once setup is complete, asking
    // for the permissions that discovery and notifications depend on.
    val startPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_LOCAL_NETWORK] == true) connector.restartServer()
    }
    LaunchedEffect(completed) {
        if (!completed) return@LaunchedEffect
        GlideHub.goOnline(context)
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
            if (Build.VERSION.SDK_INT >= 33 && !RemoteNotifications.hasPermission(context)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (wanted.isNotEmpty()) startPermissions.launch(wanted.toTypedArray())
    }
    val advancedPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        connector.refreshTelephony()
    }
    val requestAdvanced: () -> Unit = {
        advancedPermissions.launch(arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        ))
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            val started = connector.sendFiles(uris)
            android.widget.Toast.makeText(
                context,
                if (started) "Надсилання файлу розпочато" else "Спочатку підключіть пристрій",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(MainActivity.pendingDetail, completed) {
        val requested = MainActivity.pendingDetail ?: return@LaunchedEffect
        if (completed) {
            runCatching { Detail.valueOf(requested) }.getOrNull()?.let { detail = it }
            MainActivity.pendingDetail = null
        }
    }

    BackHandler(enabled = detail != null || (!completed && step > 0) || (completed && tab != 0)) {
        when {
            detail != null -> detail = null
            !completed -> step--
            else -> tab = 0
        }
    }

    // Pairing prompts must be reachable from every screen, not only the device list.
    PairingDialogs(connector, pairingEvent) { GlideHub.pairingEvent = it }

    if (!completed) {
        key(step) {
            Onboarding(
                step = step,
                deviceName = deviceName,
                connector = connector,
                pairingEvent = pairingEvent,
                onPairingEvent = { GlideHub.pairingEvent = it },
                selectedFeatures = selectedFeatures,
                onFeatureChange = setFeature,
                onBack = { step-- },
                onNext = {
                    if (step < 2) {
                        step++
                    } else {
                        preferences.completeSetup()
                        completed = true
                    }
                }
            )
        }
    } else if (detail != null) {
        DetailScreen(
            detail = detail!!,
            connector = connector,
            isRinging = isPhoneRinging || connector.isRinging,
            onStopAlarm = {
                connector.stopAlarm()
                GlideHub.isPhoneRinging = false
            },
            deviceId = selectedDevice?.id,
            lastClipboard = GlideHub.lastClipboard,
            peerBattery = peerBattery,
            remoteCommands = remoteCommands,
            lastShare = GlideHub.lastShare,
            onBack = { detail = null },
            selectedFeatures = selectedFeatures,
            onFeatureChange = setFeature
        )
    } else {
        AppNavigation(tab, onTabChange = { tab = it }) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                when (tab) {
                    0 -> HomeScreen(
                        devices = connectedDevices,
                        selectedDevice = selectedDevice,
                        onSelectDevice = { selectedDeviceId = it },
                        onDevices = { tab = 1 },
                        onDetail = { detail = it },
                        onSettings = { detail = Detail.SETTINGS },
                        selectedFeatures = selectedFeatures,
                        onFeatureChange = setFeature,
                        isRinging = isPhoneRinging || connector.isRinging,
                        onStopAlarm = {
                            connector.stopAlarm()
                            GlideHub.isPhoneRinging = false
                        },
                        onSendFile = { fileLauncher.launch("*/*") },
                        onPing = {
                            val sent = connector.sendPing()
                            android.widget.Toast.makeText(context, if (sent) "Пінг надіслано" else "Пристрій не підтримує пінг", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                    1 -> DevicesScreen(deviceName, connector, pairingEvent) { GlideHub.pairingEvent = it }
                    else -> MeScreen(deviceName, onRename = { name ->
                        preferences.setDeviceName(name)
                        deviceName = name
                        connector.setDeviceName(name)
                    }, onDetail = { detail = it }, onAdvanced = requestAdvanced)
                }
            }
        }
    }
}

@Composable
private fun AppNavigation(tab: Int, onTabChange: (Int) -> Unit, content: @Composable () -> Unit) {
    val destinations = listOf(
        Icons.Outlined.Home to "Головна",
        Icons.Outlined.Devices to "Пристрої",
        Icons.Outlined.Person to "Я"
    )
    NavigationSuiteScaffold(
        containerColor = MaterialTheme.colorScheme.background,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            navigationRailContainerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        navigationSuiteItems = {
            destinations.forEachIndexed { index, (icon, label) ->
                item(
                    selected = tab == index,
                    onClick = { onTabChange(index) },
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(label) }
                )
            }
        },
        content = content
    )
}

// ---------------------------------------------------------------------------
// Onboarding
// ---------------------------------------------------------------------------

@Composable
private fun Onboarding(
    step: Int,
    deviceName: String,
    connector: PairingConnector,
    pairingEvent: PairingConnector.Event?,
    onPairingEvent: (PairingConnector.Event?) -> Unit,
    selectedFeatures: Set<String>,
    onFeatureChange: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .widthIn(max = 560.dp)
            .padding(horizontal = 24.dp)
    ) {
        if (step == 0) {
            WelcomeStep(onNext)
        } else {
            StepHeader(current = step + 1, total = 4, onBack = onBack)
            Spacer(Modifier.height(20.dp))
            when (step) {
                1 -> FindComputerStep(deviceName, connector, pairingEvent, onPairingEvent, onNext)
                else -> ChooseFeaturesStep(selectedFeatures, onFeatureChange, onNext)
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep(onNext: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    DeviceIllustration(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .heightIn(min = 180.dp, max = 300.dp)
            .padding(vertical = 12.dp)
    )
    Spacer(Modifier.weight(1f))
    Text(
        "З’єднайте свої\nпристрої",
        style = MaterialTheme.typography.headlineLarge,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(14.dp))
    Text(
        "Нехай телефон і комп’ютер працюють разом: діліться файлами, отримуйте сповіщення, керуйте комп’ютером та багато іншого.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.weight(2f))
    PrimaryButton("Почати", onNext, trailingIcon = Icons.AutoMirrored.Outlined.ArrowForward)
    Spacer(Modifier.height(6.dp))
    TextButton(onClick = onNext, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text("Як це працює", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(8.dp))
    PageDots(count = 4, index = 0, Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ColumnScope.FindComputerStep(
    deviceName: String,
    connector: PairingConnector,
    pairingEvent: PairingConnector.Event?,
    onPairingEvent: (PairingConnector.Event?) -> Unit,
    onNext: () -> Unit
) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text("Знайдіть свій комп’ютер", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "Знайдемо комп’ютер поблизу, щоб підключитися до нього.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        DeviceDiscoverySection(
            deviceName = deviceName,
            connector = connector,
            pairingEvent = pairingEvent,
            onPairingEvent = onPairingEvent,
            autoStart = true
        )
        Spacer(Modifier.height(16.dp))
        TipCard(
            "Не бачите комп’ютер?",
            "Переконайтеся, що на комп’ютері відкрито Glide Phone або KDE Connect і обидва пристрої в одній мережі Wi-Fi."
        )
        Spacer(Modifier.height(16.dp))
    }
    PrimaryButton(
        if (connector.online) "Далі" else "Пропустити",
        onNext,
        trailingIcon = Icons.AutoMirrored.Outlined.ArrowForward
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ColumnScope.ChooseFeaturesStep(
    selectedFeatures: Set<String>,
    onFeatureChange: (String, Boolean) -> Unit,
    onNext: () -> Unit
) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text("Оберіть можливості", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "Виберіть функції між телефоном і комп’ютером. Їх можна будь-коли змінити в налаштуваннях.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        FeatureToggleList(selectedFeatures, onFeatureChange)
        Spacer(Modifier.height(16.dp))
    }
    PrimaryButton("Завершити", onNext, trailingIcon = Icons.AutoMirrored.Outlined.ArrowForward)
    Spacer(Modifier.height(16.dp))
}

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

@Composable
internal fun HomeScreen(
    devices: List<RemoteDevice>,
    selectedDevice: RemoteDevice?,
    onSelectDevice: (String) -> Unit,
    onDevices: () -> Unit,
    onDetail: (Detail) -> Unit,
    onSettings: () -> Unit = {},
    selectedFeatures: Set<String> = emptySet(),
    onFeatureChange: (String, Boolean) -> Unit = { _, _ -> },
    isRinging: Boolean = false,
    onStopAlarm: () -> Unit = {},
    onSendFile: () -> Unit = {},
    onPing: () -> Unit = {}
) {
    var showDevices by rememberSaveable { mutableStateOf(false) }
    ScreenColumn {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            FilledIconButton(
                onClick = onSettings,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = "Налаштування")
            }
        }
        if (isRinging) {
            RingingAlertCard(onStopAlarm)
            Spacer(Modifier.height(16.dp))
        }
        if (selectedDevice == null) {
            Text("Підключіть пристрій", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Body("Підключіть комп’ютер, щоб ділитися файлами, отримувати сповіщення та керувати ним із телефону.")
            Spacer(Modifier.height(20.dp))
            InfoCard(Icons.Outlined.Devices, "Немає підключених пристроїв", "Відкрийте вкладку «Пристрої», щоб знайти комп’ютер у спільній мережі Wi-Fi.")
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Мої пристрої", onDevices, trailingIcon = Icons.AutoMirrored.Outlined.ArrowForward)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Outlined.Laptop, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ваш підключений пристрій", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selectedDevice.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                }
                if (devices.size > 1) {
                    Box {
                        TextButton(onClick = { showDevices = true }) { Text("Змінити ▾") }
                        DropdownMenu(expanded = showDevices, onDismissRequest = { showDevices = false }) {
                            devices.forEach { device ->
                                DropdownMenuItem(
                                    text = { Text(device.name) },
                                    onClick = {
                                        onSelectDevice(device.id)
                                        showDevices = false
                                    },
                                    trailingIcon = if (device.id == selectedDevice.id) {
                                        { Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            ConnectedBadge()
            Spacer(Modifier.height(20.dp))
            TonalButton("Надіслати файл", onSendFile, leadingIcon = Icons.AutoMirrored.Outlined.Send)
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                features.forEach { feature ->
                    if (feature.id == FeatureId.NOTIFICATIONS.key) {
                        FeatureToggleRow(
                            icon = feature.icon,
                            title = feature.title,
                            description = feature.description,
                            checked = feature.id in selectedFeatures,
                            onCheckedChange = { onFeatureChange(feature.id, it) }
                        )
                    } else {
                        FeatureNavRow(feature.icon, feature.title, feature.description) { onDetail(feature.detail) }
                    }
                }
                FeatureNavRow(Icons.Outlined.Mouse, "Миша та клавіатура", "Керуйте вказівником і друкуйте на комп’ютері") { onDetail(Detail.REMOTE) }
                FeatureNavRow(Icons.Outlined.PlayCircle, "Керування медіа", "Пауза, треки й гучність плеєрів комп’ютера") { onDetail(Detail.MEDIA) }
                FeatureNavRow(Icons.Outlined.Speaker, "Гучність комп’ютера", "Регулюйте аудіовиходи комп’ютера") { onDetail(Detail.VOLUME) }
                FeatureNavRow(Icons.Outlined.Terminal, "Команди", "Запускайте команди на комп’ютері") { onDetail(Detail.COMMANDS) }
                FeatureNavRow(Icons.Outlined.Draw, "Графічний планшет", "Малюйте на комп’ютері пальцем або стилусом") { onDetail(Detail.DIGITIZER) }
                FeatureNavRow(Icons.Outlined.WifiTethering, "Пінг", "Перевірте зв’язок із комп’ютером", trailing = Icons.AutoMirrored.Outlined.Send) { onPing() }
                FeatureNavRow(Icons.Outlined.Extension, "Плагіни пристрою", "Які функції доступні «${selectedDevice.name}»") { onDetail(Detail.DEVICE_PLUGINS) }
                FeatureNavRow(Icons.Outlined.Shield, "Приватність", "Керуйте доступом і тим, хто може підключатися") { onDetail(Detail.PRIVACY) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Devices tab
// ---------------------------------------------------------------------------

@Composable
private fun DevicesScreen(
    deviceName: String,
    connector: PairingConnector,
    pairingEvent: PairingConnector.Event?,
    onPairingEvent: (PairingConnector.Event?) -> Unit
) {
    ScreenColumn {
        Spacer(Modifier.height(20.dp))
        Text("Пристрої", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Body("Відкрийте Glide Phone на комп’ютері та під’єднайте обидва пристрої до однієї мережі Wi-Fi.")
        Spacer(Modifier.height(20.dp))
        DeviceDiscoverySection(
            deviceName = deviceName,
            connector = connector,
            pairingEvent = pairingEvent,
            onPairingEvent = onPairingEvent,
            autoStart = false
        )
    }
}

// ---------------------------------------------------------------------------
// Shared discovery + pairing
// ---------------------------------------------------------------------------

@Composable
private fun DeviceDiscoverySection(
    deviceName: String,
    connector: PairingConnector,
    pairingEvent: PairingConnector.Event?,
    onPairingEvent: (PairingConnector.Event?) -> Unit,
    autoStart: Boolean
) {
    val context = LocalContext.current
    var services by remember { mutableStateOf(emptySet<String>()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var resolveFailed by remember { mutableStateOf(false) }
    var namesVersion by remember { mutableIntStateOf(0) }

    val discovery = remember {
        NearbyDiscovery(context) { found, active, error ->
            services = found
            searching = active
            failed = error
            namesVersion++
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) {
            connector.restartServer()
            discovery.start()
        }
    }
    DisposableEffect(discovery) { onDispose { discovery.stop() } }

    fun needsPermission() = Build.VERSION.SDK_INT >= 37 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

    fun startSearch() {
        if (needsPermission()) permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        else {
            permissionDenied = false
            discovery.start()
        }
    }

    fun stopSearch() {
        discovery.stop()
        searching = false
        services = emptySet()
        resolving = false
        resolveFailed = false
    }

    fun connectTo(id: String) {
        // A device whose link is already open can be asked to pair right away.
        if (connector.requestPairing(id)) return
        if (resolving) return
        resolving = true
        resolveFailed = false
        discovery.resolve(id) { candidate ->
            resolving = false
            if (candidate != null) connector.start(candidate, deviceName) else resolveFailed = true
        }
    }

    LaunchedEffect(autoStart) {
        connector.startServer()
        connector.refreshAdvertise()
        if (autoStart && !needsPermission()) discovery.start()
    }

    // Search control
    if (autoStart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Пристрої поблизу", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            if (searching) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { startSearch() }) { Text("Оновити") }
            }
        }
        Spacer(Modifier.height(12.dp))
    } else {
        PrimaryButton(
            if (searching) "Зупинити пошук" else "Шукати пристрої",
            onClick = { if (searching) stopSearch() else startSearch() },
            leadingIcon = if (searching) null else Icons.Outlined.Search
        )
        Spacer(Modifier.height(16.dp))
    }

    // Pairing status
    when (pairingEvent) {
        PairingConnector.Event.Connecting -> {
            InfoCard(Icons.Outlined.Search, "Підключення", "Очікуємо на відповідь комп’ютера.")
            TextButton(onClick = { connector.cancel(); onPairingEvent(null) }) { Text("Скасувати") }
            Spacer(Modifier.height(8.dp))
        }
        is PairingConnector.Event.Disconnected -> {
            InfoCard(Icons.Outlined.Info, "Зв’язок втрачено", "Пристрій підключиться знову, щойно з’явиться в мережі.")
            Spacer(Modifier.height(8.dp))
        }
        PairingConnector.Event.Failed -> {
            InfoCard(Icons.Outlined.Info, "Не вдалося сполучити", "Запит відхилено або минув час очікування.")
            Spacer(Modifier.height(8.dp))
        }
        else -> Unit
    }

    // Paired and connected devices
    val connected = GlideHub.connectedDevices
    if (connected.isNotEmpty()) {
        SectionLabel("Підключені")
        Spacer(Modifier.height(8.dp))
        connected.forEach { device ->
            ConnectedDeviceRow(device.name) { connector.unpair(device.id); onPairingEvent(null) }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(6.dp))
    }

    // Plain KDE Connect computers are seen but not offered: say so instead of staying silent.
    val refused = remember(pairingEvent, namesVersion) { connector.refusedPeerNames.toList() }
    if (refused.isNotEmpty()) {
        InfoCard(Icons.Outlined.Info, "Знайдено KDE Connect: ${refused.joinToString()}",
            "Glide Phone з’єднується лише з glide-phone на комп’ютері. Для KDE Connect скористайтеся застосунком KDE Connect.")
        Spacer(Modifier.height(10.dp))
    }

    // Paired but not reachable now (KDE Connect lists these too, and can forget them).
    var forgotten by remember { mutableIntStateOf(0) }
    val offlinePaired = remember(connected, pairingEvent, forgotten) {
        connector.pairedDevices().filter { paired -> connected.none { it.id == paired.id } }
    }
    if (offlinePaired.isNotEmpty()) {
        SectionLabel("Сполучені, недоступні")
        Spacer(Modifier.height(8.dp))
        offlinePaired.forEach { device ->
            OfflinePairedRow(
                device.name,
                onWake = if (device.macs.isNotEmpty()) ({
                    val context2 = context
                    Thread {
                        val ok = connector.wake(device.id)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(context2,
                                if (ok) "Сигнал увімкнення надіслано" else "Не вдалося надіслати сигнал",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                }) else null
            ) { connector.forget(device.id); forgotten++ }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(6.dp))
    }

    // Devices that can be paired: open links first, then anything discovery has seen.
    namesVersion // re-read names when discovery resolves them
    val connectedIds = connected.map { it.id }.toSet() + offlinePaired.map { it.id }
    val linked = GlideHub.availableDevices.filterNot { it.id in connectedIds }
    val linkedIds = linked.map { it.id }.toSet()
    val discovered = services.filterNot { it in connectedIds || it in linkedIds }.sorted()
        .map { id -> RemoteDevice(id, discovery.deviceName(id) ?: if (resolving) "Отримання відомостей…" else "Пристрій поблизу") }
    val candidates = linked + discovered
    when {
        permissionDenied -> InfoCard(Icons.Outlined.Info, "Немає доступу до локальної мережі", "Дозвольте доступ у налаштуваннях застосунку, щоб знайти пристрої.")
        failed -> InfoCard(Icons.Outlined.Info, "Не вдалося розпочати пошук", "Перевірте підключення до мережі та спробуйте ще раз.")
        candidates.isEmpty() && connected.isEmpty() -> InfoCard(
            Icons.Outlined.Devices,
            "Поки що немає пристроїв",
            if (searching) "Триває пошук у локальній мережі…" else "Почніть пошук пристроїв поблизу."
        )
        else -> {
            if (resolveFailed) {
                InfoCard(Icons.Outlined.Info, "Не вдалося отримати дані пристрою", "Спробуйте підключитися ще раз.")
                Spacer(Modifier.height(10.dp))
            }
            if (candidates.isNotEmpty() && connected.isNotEmpty()) {
                SectionLabel("Доступні")
                Spacer(Modifier.height(8.dp))
            }
            candidates.forEachIndexed { index, device ->
                if (index == 0) {
                    NearbyDeviceCard(
                        name = device.name,
                        connecting = resolving || pairingEvent == PairingConnector.Event.Connecting,
                        onConnect = { connectTo(device.id) }
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                    FeatureNavRow(Icons.Outlined.Laptop, device.name, "Поблизу") { connectTo(device.id) }
                }
            }
        }
    }
}

@Composable
private fun OfflinePairedRow(name: String, onWake: (() -> Unit)? = null, onForget: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, LocalGlideColors.current.cardBorder, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(Icons.Outlined.Laptop)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text("Не в мережі", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onWake != null) TextButton(onClick = onWake) { Text("Увімкнути") }
        TextButton(onClick = onForget) { Text("Забути") }
    }
}

@Composable
private fun ConnectedDeviceRow(name: String, onUnpair: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, LocalGlideColors.current.cardBorder, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(Icons.Outlined.Laptop)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            ConnectedBadge()
        }
        TextButton(onClick = onUnpair) { Text("Роз’єднати") }
    }
}

@Composable
private fun PairingDialogs(
    connector: PairingConnector,
    pairingEvent: PairingConnector.Event?,
    onPairingEvent: (PairingConnector.Event?) -> Unit
) {
    (pairingEvent as? PairingConnector.Event.Verify)?.let { verification ->
        AlertDialog(
            onDismissRequest = { connector.cancel(); onPairingEvent(null) },
            icon = { Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Підтвердьте підключення") },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Код сполучення", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        verification.code,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Порівняйте код із пристроєм «${verification.name}» і підтвердьте сполучення на обох пристроях.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    connector.decide(verification.id, true)
                    onPairingEvent(PairingConnector.Event.Connecting)
                }) { Text("Код збігається") }
            },
            dismissButton = { TextButton(onClick = { connector.decide(verification.id, false); connector.cancel(); onPairingEvent(null) }) { Text("Скасувати") } }
        )
    }
    (pairingEvent as? PairingConnector.Event.Connected)?.let { connected ->
        var show by rememberSaveable(connected.id) { mutableStateOf(true) }
        if (show) {
            AlertDialog(
                onDismissRequest = { show = false },
                icon = { Icon(Icons.Outlined.CheckCircle, null, tint = LocalGlideColors.current.connectedDot) },
                title = { Text("Успішно підключено") },
                text = { Text("Пристрій «${connected.name}» підключено до Glide Phone.") },
                confirmButton = {
                    Button(onClick = { show = false }) { Text("Готово") }
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Me tab
// ---------------------------------------------------------------------------

@Composable
private fun MeScreen(deviceName: String, onRename: (String) -> Unit, onDetail: (Detail) -> Unit, onAdvanced: () -> Unit = {}) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Назва пристрою") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.length <= 50) draft = it },
                        label = { Text("Назва") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Назва зберігається в Glide Phone. Показ на інших пристроях з’явиться після налаштування підключення.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onRename(draft.trim()); editing = false }, enabled = draft.isNotBlank()) { Text("Зберегти") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Скасувати") } }
        )
    }

    ScreenColumn {
        Spacer(Modifier.height(20.dp))
        Text("Я", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Body("Інформація та налаштування цього телефону.")
        Spacer(Modifier.height(20.dp))
        FeatureNavRow(Icons.Outlined.Smartphone, "Назва пристрою", deviceName, trailing = Icons.Outlined.Edit) {
            draft = deviceName
            editing = true
        }
        Spacer(Modifier.height(10.dp))
        InfoCard(Icons.Outlined.Info, "Інформація про пристрій", "Модель: ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE}")
        Spacer(Modifier.height(24.dp))
        SectionLabel("Керування")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureNavRow(Icons.Outlined.Tune, "Можливості", "Вибір функцій для підключення") { onDetail(Detail.SETTINGS) }
            FeatureNavRow(Icons.Outlined.PhonelinkRing, "Телефонія, контакти та SMS", "Надати доступ для синхронізації з комп’ютером", trailing = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { onAdvanced() }
            FeatureNavRow(Icons.Outlined.Lan, "Мережа", "Пристрої за адресою та довірені мережі") { onDetail(Detail.NETWORK) }
            FeatureNavRow(Icons.Outlined.Mouse, "Керування телефоном", "Комп’ютер керує телефоном мишею й клавіатурою") { onDetail(Detail.PHONE_CONTROL) }
            FeatureNavRow(Icons.Outlined.Shield, "Приватність", "Дозволи та доступ до даних") { onDetail(Detail.PRIVACY) }
            FeatureNavRow(Icons.Outlined.Info, "Про застосунок", "Інформація про Glide Phone") { onDetail(Detail.ABOUT) }
        }
    }
}

// ---------------------------------------------------------------------------
// Detail
// ---------------------------------------------------------------------------

@Composable
private fun DetailScreen(
    detail: Detail,
    connector: PairingConnector? = null,
    isRinging: Boolean = false,
    onStopAlarm: () -> Unit = {},
    lastClipboard: String? = null,
    peerBattery: Pair<Int, Boolean>? = null,
    remoteCommands: List<PairingConnector.RemoteCommand> = emptyList(),
    lastShare: PairingConnector.Event.ShareReceived? = null,
    deviceId: String? = null,
    onBack: () -> Unit,
    selectedFeatures: Set<String>,
    onFeatureChange: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().widthIn(max = 560.dp)) {
        DetailTopBar(detail.title, onBack)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
        ) {
            when (detail) {
                Detail.SETTINGS -> {
                    AppSettingsSection()
                    Spacer(Modifier.height(16.dp))
                    Body("Оберіть можливості, якими хочете користуватися при підключенні.")
                    Spacer(Modifier.height(20.dp))
                    FeatureToggleList(selectedFeatures, onFeatureChange)
                    Spacer(Modifier.height(20.dp))
                    val prefs = remember { SetupPreferences(context) }
                    var skipSensitive by remember { mutableStateOf(prefs.skipSensitiveClipboard) }
                    FeatureToggleRow(
                        Icons.Outlined.Shield, "Не надсилати паролі",
                        "Пропускати вміст буфера, позначений як конфіденційний",
                        skipSensitive
                    ) { prefs.skipSensitiveClipboard = it; skipSensitive = it }
                }
                Detail.ABOUT -> {
                    Body("Glide Phone з’єднує телефон із застосунком Phone Link / KDE Connect на комп’ютері.")
                    Spacer(Modifier.height(16.dp))
                    InfoCard(Icons.Outlined.Info, "Сумісність", "Підтримуються презентації, пошук пристроїв, обмін буфером, посиланнями, сповіщеннями та станом батареї.")
                }
                Detail.PRIVACY -> {
                    Body("Усі з’єднання шифруються через TLS із взаємною автентифікацією за локальними сертифікатами. Дані не надсилаються на сторонні сервери.")
                    Spacer(Modifier.height(16.dp))
                    InfoCard(Icons.Outlined.Shield, "Наскрізне шифрування", "Сполучення підтверджується кодом на обох пристроях перед обміном даними.")
                    Spacer(Modifier.height(20.dp))
                    SyncPrivacySection()
                }
                Detail.PRESENTER -> PresenterDetail(connector, selectedFeatures, onFeatureChange)
                Detail.REMOTE -> RemoteControlDetail(connector, deviceId)
                Detail.COMMANDS -> RunCommandDetail(connector, remoteCommands, deviceId)
                Detail.MEDIA -> MediaDetail(connector, deviceId)
                Detail.VOLUME -> VolumeDetail(connector, deviceId)
                Detail.DIGITIZER -> DigitizerDetail(connector)
                Detail.NETWORK -> NetworkDetail(connector)
                Detail.DEVICE_PLUGINS -> DevicePluginsDetail(connector, deviceId)
                Detail.PHONE_CONTROL -> PhoneControlDetail(connector, deviceId)
                Detail.RING -> RingDetail(context, connector, isRinging, onStopAlarm, selectedFeatures, onFeatureChange)
                Detail.CLIPBOARD -> ClipboardDetail(context, connector, lastClipboard, selectedFeatures, onFeatureChange)
                Detail.FILES -> FilesDetail(connector, lastShare, selectedFeatures, onFeatureChange)
                Detail.NOTIFICATIONS -> {
                    val feature = features.first { it.detail == detail }
                    NotificationAccessControl(
                        enabled = feature.id in selectedFeatures,
                        connected = connector?.online == true,
                        supported = connector?.supportsNotifications == true
                    )
                    Spacer(Modifier.height(20.dp))
                    FeatureToggleRow(feature.icon, feature.title, feature.description, feature.id in selectedFeatures) { onFeatureChange(feature.id, it) }
                    Spacer(Modifier.height(20.dp))
                    SectionLabel("Програми")
                    Spacer(Modifier.height(8.dp))
                    NotificationFilterSection()
                }
                Detail.BATTERY -> BatteryDetail(context, connector, peerBattery, selectedFeatures, onFeatureChange)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun NotConnectedNotice(connector: PairingConnector?) {
    if (connector?.online != true) {
        InfoCard(Icons.Outlined.Info, "Комп’ютер не підключено", "Підключіть комп’ютер у вкладці «Пристрої», щоб надсилати команди.")
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ColumnScope.PresenterDetail(
    connector: PairingConnector?,
    selectedFeatures: Set<String>,
    onFeatureChange: (String, Boolean) -> Unit
) {
    val feature = features.first { it.detail == Detail.PRESENTER }
    Body("Перемикайте слайди та керуйте указкою рухами телефону або пальцем.")
    Spacer(Modifier.height(16.dp))
    NotConnectedNotice(connector)
    SectionLabel("Керування слайдами")
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { connector?.sendPresenterSpecialKey(ProtocolPackets.KEY_PREV_SLIDE) },
            modifier = Modifier.weight(1f).height(60.dp),
            shape = RoundedCornerShape(18.dp)
        ) { Text("◀  Попередній", fontWeight = FontWeight.SemiBold) }
        Button(
            onClick = { connector?.sendPresenterSpecialKey(ProtocolPackets.KEY_NEXT_SLIDE) },
            modifier = Modifier.weight(1f).height(60.dp),
            shape = RoundedCornerShape(18.dp)
        ) { Text("Наступний  ▶", fontWeight = FontWeight.SemiBold) }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { connector?.sendPresenterSpecialKey(ProtocolPackets.KEY_START) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("F5") }
        OutlinedButton(onClick = { connector?.sendPresenterSpecialKey(ProtocolPackets.KEY_EXIT) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Esc") }
        OutlinedButton(onClick = { connector?.sendPresenterKey("+", ctrl = true) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Зум +") }
        OutlinedButton(onClick = { connector?.sendPresenterKey("-", ctrl = true) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Зум −") }
    }
    PresenterVolumeKeys(connector)
    Spacer(Modifier.height(20.dp))
    GyroscopePointerControl(connector, feature.id in selectedFeatures)
    Spacer(Modifier.height(20.dp))
    SectionLabel("Сенсорна панель")
    Spacer(Modifier.height(8.dp))
    Body("Водіть пальцем у полі нижче, щоб рухати указку на комп’ютері.")
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .background(LocalGlideColors.current.tonal, RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { connector?.sendPresenterStop() },
                    onDragCancel = { connector?.sendPresenterStop() }
                ) { change, drag ->
                    change.consume()
                    connector?.sendPresenterMove(drag.x, drag.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.NearMe, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Сенсорна зона указки", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text("Торкніться та рухайте пальцем", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { connector?.sendPresenterStop() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Скинути указку") }
    Spacer(Modifier.height(20.dp))
    FeatureToggleRow(feature.icon, feature.title, feature.description, feature.id in selectedFeatures) { onFeatureChange(feature.id, it) }
}

@Composable
private fun ColumnScope.RunCommandDetail(connector: PairingConnector?, commands: List<PairingConnector.RemoteCommand>, deviceId: String?) {
    LaunchedEffect(connector?.online) { if (connector?.online == true) connector.requestCommands() }
    val output = deviceId?.let { GlideHub.commandOutput[it] }.orEmpty()
    val running = output.any { it.status == PairingConnector.CommandStatus.RUNNING }
    val canAdd = deviceId != null && deviceId in GlideHub.canAddCommand
    Body("Запускайте команди, налаштовані на комп’ютері.")
    Spacer(Modifier.height(16.dp))
    NotConnectedNotice(connector)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Доступні команди")
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { connector?.requestCommands() }, enabled = connector?.online == true) { Text("Оновити") }
    }
    Spacer(Modifier.height(8.dp))
    if (commands.isEmpty()) {
        InfoCard(Icons.Outlined.Terminal, "Немає команд", "Додайте команди в налаштуваннях KDE Connect або Glide Phone на комп’ютері.")
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            commands.forEach { cmd ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        FeatureNavRow(
                            Icons.AutoMirrored.Outlined.PlaylistPlay,
                            cmd.name.ifBlank { "Команда" },
                            cmd.command.ifBlank { "Виконати на комп’ютері" },
                            trailing = Icons.Outlined.Terminal
                        ) { connector?.sendRunCommandRequest(cmd.key) }
                    }
                    // RunCommandActivity "copy URL": for NFC tags, automation apps, etc.
                    if (deviceId != null) {
                        val context = LocalContext.current
                        IconButton(onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                                android.content.ClipData.newPlainText("Command", RunCommandUrlActivity.url(deviceId, cmd.key)))
                            android.widget.Toast.makeText(context, "Посилання на команду скопійовано", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Outlined.ContentCopy, "Копіювати посилання на ${cmd.name}") }
                    }
                }
            }
        }
    }
    if (canAdd) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { connector?.sendRunCommandSetup() }) { Text("Додати команду на комп’ютері") }
    }
    if (output.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Вивід")
            Spacer(Modifier.weight(1f))
            if (running) TextButton(onClick = { connector?.sendRunCommandStop() }) { Text("Зупинити") }
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            output.takeLast(200).forEach { line ->
                val color = when (line.status) {
                    PairingConnector.CommandStatus.STDERR, PairingConnector.CommandStatus.FAILED -> MaterialTheme.colorScheme.error
                    PairingConnector.CommandStatus.SUCCESSFUL -> LocalGlideColors.current.connectedDot
                    PairingConnector.CommandStatus.RUNNING -> MaterialTheme.colorScheme.primary
                    PairingConnector.CommandStatus.STDOUT -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val suffix = when (line.status) {
                    PairingConnector.CommandStatus.RUNNING -> "  …"
                    PairingConnector.CommandStatus.SUCCESSFUL -> "  ✓"
                    PairingConnector.CommandStatus.FAILED -> "  ✗"
                    else -> ""
                }
                Text(
                    line.text + suffix, color = color,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.RemoteControlDetail(connector: PairingConnector?, deviceId: String?) {
    val context = LocalContext.current
    var typed by rememberSaveable { mutableStateOf("") }
    Body("Керуйте вказівником і клавіатурою комп’ютера з телефону.")
    Spacer(Modifier.height(16.dp))
    NotConnectedNotice(connector)
    SectionLabel("Сенсорна панель")
    Spacer(Modifier.height(8.dp))
    val mousepad = remember { MousepadSettings(context) }
    // MousePadPlugin.getUiButtons: a TV peer also gets the Bigscreen remote.
    val isTv = remember(deviceId) { connector?.pairedDevices()?.firstOrNull { it.id == deviceId }?.type == "tv" }
    if (isTv) {
        SectionLabel("Пульт для ТБ")
        Spacer(Modifier.height(8.dp))
        TvRemote(connector, deviceId)
        Spacer(Modifier.height(8.dp))
        BigscreenSettingsSection()
        Spacer(Modifier.height(16.dp))
        if (BigscreenSettings(context).hideMouseInput) return
    }
    Body(if (mousepad.doubleTapDrag)
        "Ведіть пальцем, щоб рухати вказівник. Торкання — клік, двома пальцями — правий клік, тремя — середній, прокрутка — двома пальцями. Подвійне торкання й ведення — перетягування."
    else
        "Ведіть пальцем, щоб рухати вказівник. Торкання — клік, двома пальцями — правий клік, тремя — середній, прокрутка — двома пальцями. Утримання — перетягування.")
    Spacer(Modifier.height(12.dp))
    TouchpadSurface(
        connector,
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(LocalGlideColors.current.tonal, RoundedCornerShape(24.dp))
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Mouse, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Зона тачпада", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    if (mousepad.mouseButtons) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { connector?.sendMouseClick("left") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Лівий") }
            OutlinedButton(onClick = { connector?.sendMouseClick("middle") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Середній") }
            OutlinedButton(onClick = { connector?.sendMouseClick("right") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Правий") }
        }
    }
    Spacer(Modifier.height(16.dp))
    SectionLabel("Прокрутка")
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(LocalGlideColors.current.tonal, RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    // Same sign as KDE Connect's two-finger scroll: finger up scrolls down.
                    val direction = if (mousepad.invertScroll) -1 else 1
                    connector?.sendMouseScroll(0f, -drag.y * direction)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text("Ведіть вгору/вниз для прокрутки", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(16.dp))
    SectionLabel("Клавіші")
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "←" to ProtocolPackets.KEY_LEFT, "↑" to ProtocolPackets.KEY_UP,
            "↓" to ProtocolPackets.KEY_DOWN, "→" to ProtocolPackets.KEY_RIGHT
        ).forEach { (label, key) ->
            OutlinedButton(onClick = { connector?.sendSpecialKey(key) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(label) }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { connector?.sendSpecialKey(ProtocolPackets.KEY_ESCAPE) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Esc") }
        OutlinedButton(onClick = { connector?.sendSpecialKey(ProtocolPackets.KEY_TAB) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Tab") }
        OutlinedButton(onClick = { connector?.sendSuperKey() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Super") }
    }
    if (deviceId != null && deviceId in GlideHub.keyboardDisabled) {
        Spacer(Modifier.height(16.dp))
        InfoCard(Icons.Outlined.Keyboard, "Клавіатура недоступна", "Комп’ютер повідомив, що введення з клавіатури зараз вимкнене.")
        return
    }
    Spacer(Modifier.height(16.dp))
    SectionLabel("Клавіатура")
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Текст для введення") },
        leadingIcon = { Icon(Icons.Outlined.Keyboard, null) },
        shape = RoundedCornerShape(16.dp)
    )
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { if (connector?.sendType(typed) == true) typed = "" },
            enabled = typed.isNotEmpty() && connector?.online == true,
            modifier = Modifier.weight(1f),
            shape = CircleShape
        ) { Text("Надрукувати") }
        OutlinedButton(onClick = { connector?.sendSpecialKey(ProtocolPackets.KEY_ENTER) }, modifier = Modifier.weight(1f), shape = CircleShape) { Text("Enter") }
        OutlinedButton(onClick = { connector?.sendSpecialKey(ProtocolPackets.KEY_BACKSPACE) }, modifier = Modifier.weight(1f), shape = CircleShape) { Text("⌫") }
    }
    Spacer(Modifier.height(20.dp))
    var showSettings by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showSettings = !showSettings }) {
        Text(if (showSettings) "Сховати налаштування тачпада" else "Налаштування тачпада")
    }
    if (showSettings) MousepadSettingsSection()
}

@Composable
private fun ColumnScope.RingDetail(
    context: Context,
    connector: PairingConnector?,
    isRinging: Boolean,
    onStopAlarm: () -> Unit,
    selectedFeatures: Set<String>,
    onFeatureChange: (String, Boolean) -> Unit
) {
    val feature = features.first { it.detail == Detail.RING }
    var testingAlarm by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (testingAlarm) AlarmPlayer.stop() } }
    Body("Знайдіть телефон або комп’ютер за допомогою гучного звукового сигналу.")
    Spacer(Modifier.height(16.dp))
    if (isRinging) {
        RingingAlertCard(onStopAlarm)
        Spacer(Modifier.height(16.dp))
    }
    NotConnectedNotice(connector)
    PrimaryButton("Увімкнути дзвінок на комп’ютері", { connector?.sendRing() }, leadingIcon = Icons.Outlined.PhonelinkRing)
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            if (isRinging || testingAlarm) {
                onStopAlarm(); testingAlarm = false
            } else {
                AlarmPlayer.start(context); testingAlarm = AlarmPlayer.isPlaying
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = CircleShape
    ) {
        Text(if (isRinging || testingAlarm) "Зупинити сигнал на телефоні" else "Тестовий дзвінок на цьому телефоні")
    }
    Spacer(Modifier.height(20.dp))
    val prefs = remember { SetupPreferences(context) }
    var ringtone by remember { mutableStateOf(prefs.findMyPhoneRingtone) }
    var flashlight by remember { mutableStateOf(prefs.findMyPhoneFlashlight) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        @Suppress("DEPRECATION")
        val uri = result.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            prefs.findMyPhoneRingtone = uri?.toString()
            ringtone = prefs.findMyPhoneRingtone
        }
    }
    val ringtoneName = remember(ringtone) {
        runCatching {
            android.media.RingtoneManager.getRingtone(context, ringtone?.let(android.net.Uri::parse) ?: android.provider.Settings.System.DEFAULT_RINGTONE_URI)
                ?.getTitle(context)
        }.getOrNull() ?: "Типова мелодія"
    }
    FeatureNavRow(Icons.Outlined.NotificationsActive, "Мелодія", ringtoneName) {
        picker.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALL)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, ringtone?.let(android.net.Uri::parse)))
    }
    Spacer(Modifier.height(10.dp))
    FeatureToggleRow(Icons.Outlined.Lightbulb, "Блимати ліхтариком", "Під час дзвінка вмикати й вимикати спалах", flashlight) {
        prefs.findMyPhoneFlashlight = it; flashlight = it
    }
    Spacer(Modifier.height(10.dp))
    FeatureToggleRow(feature.icon, feature.title, feature.description, feature.id in selectedFeatures) { onFeatureChange(feature.id, it) }
}

@Composable
private fun ColumnScope.ClipboardDetail(
    context: Context,
    connector: PairingConnector?,
    lastClipboard: String?,
    selectedFeatures: Set<String>,
    onFeatureChange: (String, Boolean) -> Unit
) {
    val feature = features.first { it.detail == Detail.CLIPBOARD }
    var textToSend by rememberSaveable { mutableStateOf("") }
    Body("Обмінюйтеся скопійованим текстом між телефоном і комп’ютером.")
    Spacer(Modifier.height(16.dp))
    NotConnectedNotice(connector)
    SectionLabel("Надіслати в буфер комп’ютера")
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = textToSend,
        onValueChange = { textToSend = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Введіть текст") },
        minLines = 3,
        shape = RoundedCornerShape(16.dp)
    )
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { if (connector?.sendClipboard(textToSend) == true) textToSend = "" },
            enabled = textToSend.isNotBlank() && connector?.online == true && FeatureId.CLIPBOARD.key in selectedFeatures,
            modifier = Modifier.weight(1f),
            shape = CircleShape
        ) { Text("Надіслати") }
        OutlinedButton(
            onClick = {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.primaryClip?.getItemAt(0)?.text?.toString()
                if (!clip.isNullOrEmpty()) connector?.sendClipboard(clip)
            },
            modifier = Modifier.weight(1f),
            shape = CircleShape
        ) { Text("Скопійоване зараз") }
    }
    Spacer(Modifier.height(20.dp))
    SectionLabel("Останнє з комп’ютера")
    Spacer(Modifier.height(8.dp))
    if (lastClipboard.isNullOrBlank()) {
        InfoCard(Icons.Outlined.ContentCopy, "Буфер обміну порожній", "Скопіюйте текст на комп’ютері — він з’явиться тут автоматично.")
    } else {
        Column(Modifier.fillMaxWidth().background(LocalGlideColors.current.tonal, RoundedCornerShape(18.dp)).padding(18.dp)) {
            Text(lastClipboard, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText("Glide Phone", lastClipboard))
                },
                shape = CircleShape
            ) { Text("Скопіювати на телефон") }
        }
    }
    Spacer(Modifier.height(20.dp))
    FeatureToggleRow(feature.icon, feature.title, feature.description, feature.id in selectedFeatures) { onFeatureChange(feature.id, it) }
}

/** Glide only: browse the computer's home folder and download files from it. */
@Composable
private fun ComputerFilesSection(connector: PairingConnector) {
    val listing = GlideHub.computerFiles
    var fetching by remember { mutableStateOf<String?>(null) }
    SectionLabel("Файли комп’ютера")
    Spacer(Modifier.height(8.dp))
    if (listing == null) {
        TonalButton("Переглянути файли комп’ютера", { connector.requestComputerFiles("") }, leadingIcon = Icons.Outlined.Folder)
        return
    }
    when (listing.error) {
        "off" -> {
            InfoCard(Icons.Outlined.Info, "Вимкнено на комп’ютері",
                "Увімкніть у Glide Phone на комп’ютері: «Керувати телефоном» → «Файли комп’ютера на телефоні».")
            Spacer(Modifier.height(8.dp))
            TonalButton("Спробувати ще", { connector.requestComputerFiles(listing.path) })
            return
        }
        null -> Unit
        else -> {
            InfoCard(Icons.Outlined.Info, "Не вдалося відкрити", "Цієї теки немає або її не можна прочитати.")
            Spacer(Modifier.height(8.dp))
        }
    }
    val path = listing.path
    Text(if (path.isEmpty()) "~" else "~/$path", style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().background(LocalGlideColors.current.tonal, RoundedCornerShape(18.dp)).padding(vertical = 6.dp)) {
        if (path.isNotEmpty()) {
            ComputerFileRow("..", "Вгору", Icons.Outlined.Folder) {
                connector.requestComputerFiles(path.substringBeforeLast('/', ""))
            }
        }
        listing.entries.forEach { entry ->
            val child = if (path.isEmpty()) entry.name else "$path/${entry.name}"
            if (entry.dir) {
                ComputerFileRow(entry.name, "Тека", Icons.Outlined.Folder) { connector.requestComputerFiles(child) }
            } else {
                val subtitle = if (fetching == child) "Завантажується в «Завантаження»…"
                    else android.text.format.Formatter.formatShortFileSize(LocalContext.current, entry.size)
                ComputerFileRow(entry.name, subtitle, Icons.Outlined.Description) {
                    if (connector.requestComputerFiles(child, fetch = true)) fetching = child
                }
            }
        }
        if (listing.entries.isEmpty()) {
            Text("Порожня тека", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComputerFileRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColumnScope.FilesDetail(
    connector: PairingConnector?,
    lastShare: PairingConnector.Event.ShareReceived?,
    selectedFeatures: Set<String>,
    onFeatureChange: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val feature = features.first { it.detail == Detail.FILES }
    var shareContent by rememberSaveable { mutableStateOf("") }
    if (connector?.supportsComputerFiles == true) {
        ComputerFilesSection(connector)
        Spacer(Modifier.height(20.dp))
    }
    Body("Відкривайте веб-посилання у браузері комп’ютера або надсилайте текстові нотатки.")
    Spacer(Modifier.height(16.dp))
    NotConnectedNotice(connector)
    SectionLabel("Надіслати на комп’ютер")
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = shareContent,
        onValueChange = { shareContent = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("URL або текст") },
        placeholder = { Text("https://example.com або нотатка") },
        minLines = 3,
        shape = RoundedCornerShape(16.dp)
    )
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { if (connector?.sendShareUrl(shareContent) == true) shareContent = "" },
            enabled = shareContent.isNotBlank() && connector?.online == true && FeatureId.FILES.key in selectedFeatures,
            modifier = Modifier.weight(1f),
            shape = CircleShape
        ) { Text("Відкрити URL") }
        OutlinedButton(
            onClick = { if (connector?.sendShareText(shareContent) == true) shareContent = "" },
            enabled = shareContent.isNotBlank() && connector?.online == true && FeatureId.FILES.key in selectedFeatures,
            modifier = Modifier.weight(1f),
            shape = CircleShape
        ) { Text("Як текст") }
    }
    val shared = lastShare?.url ?: lastShare?.text
    if (!shared.isNullOrBlank()) {
        Spacer(Modifier.height(20.dp))
        SectionLabel("Отримано з комп’ютера")
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().background(LocalGlideColors.current.tonal, RoundedCornerShape(18.dp)).padding(18.dp)) {
            Text(shared, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Only web links open from here (no intent:, file:, content: from the computer).
                val scheme = lastShare?.url?.let { runCatching { android.net.Uri.parse(it).scheme?.lowercase() }.getOrNull() }
                if (lastShare?.url != null && (scheme == "http" || scheme == "https")) {
                    OutlinedButton(
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(lastShare.url))) } },
                        shape = CircleShape
                    ) { Text("Відкрити") }
                }
                OutlinedButton(
                    onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.setPrimaryClip(ClipData.newPlainText("Glide Phone", shared))
                    },
                    shape = CircleShape
                ) { Text("Копіювати") }
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    FeatureToggleRow(feature.icon, feature.title, feature.description, feature.id in selectedFeatures) { onFeatureChange(feature.id, it) }
    Spacer(Modifier.height(20.dp))
    SectionLabel("Отримані файли")
    Spacer(Modifier.height(8.dp))
    ShareSettingsSection()
}

@Composable
private fun ColumnScope.BatteryDetail(
    context: Context,
    connector: PairingConnector?,
    peerBattery: Pair<Int, Boolean>?,
    selectedFeatures: Set<String>,
    onFeatureChange: (String, Boolean) -> Unit
) {
    val feature = features.first { it.detail == Detail.BATTERY }
    val localBattery = remember {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(BatterySnapshot::fromIntent)
    }
    Body("Відстежуйте стан заряду телефону та комп’ютера.")
    Spacer(Modifier.height(16.dp))
    InfoCard(
        Icons.Outlined.Smartphone,
        "Цей телефон",
        "Заряд: ${localBattery?.charge ?: "—"}%\nСтан: ${if (localBattery?.charging == true) "Заряджається ⚡" else "Працює від батареї"}"
    )
    Spacer(Modifier.height(12.dp))
    if (peerBattery != null) {
        InfoCard(
            Icons.Outlined.Laptop,
            "Підключений комп’ютер",
            "Заряд: ${peerBattery.first}%\nСтан: ${if (peerBattery.second) "Підключено до мережі ⚡" else "Працює від батареї"}"
        )
    } else {
        InfoCard(
            Icons.Outlined.Laptop,
            "Підключений комп’ютер",
            if (connector?.online == true) "Дані очікуються від комп’ютера." else "Комп’ютер не підключено."
        )
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Оновити заряд телефону на комп’ютері", { connector?.sendBattery() }, leadingIcon = Icons.Outlined.Bolt)
    Spacer(Modifier.height(20.dp))
    FeatureToggleRow(feature.icon, feature.title, feature.description, feature.id in selectedFeatures) { onFeatureChange(feature.id, it) }
}

// ---------------------------------------------------------------------------
// Reusable building blocks
// ---------------------------------------------------------------------------

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        content = content
    )
}

@Composable
private fun StepHeader(current: Int, total: Int, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text("Крок $current з $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(total) { index ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .background(
                                if (index < current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape
                            )
                    )
                }
            }
        }
        Spacer(Modifier.width(44.dp))
    }
}

@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().safeDrawingPadding().height(56.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun PageDots(count: Int, index: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val active = i == index
            Box(
                Modifier
                    .height(8.dp)
                    .width(if (active) 24.dp else 8.dp)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        }
    }
}

@Composable
internal fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = CircleShape
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontSize = 16.sp)
        if (trailingIcon != null) {
            Spacer(Modifier.width(10.dp))
            Icon(trailingIcon, null, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TonalButton(text: String, onClick: () -> Unit, leadingIcon: ImageVector? = null) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontSize = 16.sp)
    }
}

@Composable
private fun IconTile(
    icon: ImageVector,
    size: Dp = 48.dp,
    container: Color = LocalGlideColors.current.tonal,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        Modifier.size(size).background(container, RoundedCornerShape(size / 3.4f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = tint)
    }
}

@Composable
private fun ConnectedBadge() {
    val colors = LocalGlideColors.current
    Row(
        Modifier
            .background(colors.connectedContainer, CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Circle, null, Modifier.size(9.dp), tint = colors.connectedDot)
        Spacer(Modifier.width(8.dp))
        Text("Підключено", style = MaterialTheme.typography.labelLarge, color = colors.onConnected)
    }
}

@Composable
internal fun InfoCard(icon: ImageVector, title: String, description: String) {
    Row(
        Modifier.fillMaxWidth().background(LocalGlideColors.current.tonal, RoundedCornerShape(22.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon, size = 50.dp, container = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TipCard(title: String, description: String) {
    Row(
        Modifier.fillMaxWidth().background(LocalGlideColors.current.tonal, RoundedCornerShape(22.dp)).padding(18.dp)
    ) {
        Icon(Icons.Outlined.Lightbulb, null, Modifier.size(26.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RingingAlertCard(onStopAlarm: () -> Unit) {
    val colors = LocalGlideColors.current
    Column(
        Modifier.fillMaxWidth().background(colors.alertContainer, RoundedCornerShape(22.dp)).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(32.dp), tint = colors.onAlert)
        Spacer(Modifier.height(8.dp))
        Text("Телефон дзвенить!", style = MaterialTheme.typography.titleMedium, color = colors.onAlert)
        Spacer(Modifier.height(4.dp))
        Text("Комп’ютер надіслав запит на пошук телефону.", style = MaterialTheme.typography.bodyMedium, color = colors.onAlert, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStopAlarm, colors = ButtonDefaults.buttonColors(containerColor = colors.onAlert)) {
            Text("Вимкнути звук")
        }
    }
}

@Composable
private fun NearbyDeviceCard(name: String, connecting: Boolean, onConnect: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.Laptop, size = 52.dp, container = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Circle, null, Modifier.size(8.dp), tint = LocalGlideColors.current.connectedDot)
                    Spacer(Modifier.width(6.dp))
                    Text("Поблизу · Готовий до підключення", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onConnect,
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            shape = CircleShape
        ) {
            if (connecting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text("Підключення…", fontWeight = FontWeight.SemiBold)
            } else {
                Text("Підключити", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FeatureNavRow(
    icon: ImageVector,
    title: String,
    description: String,
    trailing: ImageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, LocalGlideColors.current.cardBorder, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(trailing, null, Modifier.padding(start = 8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeatureToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, LocalGlideColors.current.cardBorder, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title }
        )
    }
}

@Composable
private fun FeatureToggleList(selectedFeatures: Set<String>, onFeatureChange: (String, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        features.forEach { feature ->
            FeatureToggleRow(
                icon = feature.icon,
                title = feature.title,
                description = feature.description,
                checked = feature.id in selectedFeatures,
                onCheckedChange = { onFeatureChange(feature.id, it) }
            )
        }
    }
}

@Composable
private fun DeviceIllustration(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val deep = LocalGlideColors.current.deepAccent
    val tonal = LocalGlideColors.current.tonal
    val ink = MaterialTheme.colorScheme.onBackground
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val screenFill = MaterialTheme.colorScheme.primaryContainer
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawOval(tonal, topLeft = Offset(w * .05f, h * .08f), size = Size(w * .9f, h * .84f))
        // laptop
        drawRoundRect(deep, Offset(w * .39f, h * .22f), Size(w * .48f, h * .56f), cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(5.dp.toPx()))
        drawRoundRect(screenFill, Offset(w * .41f, h * .25f), Size(w * .44f, h * .49f), cornerRadius = CornerRadius(6.dp.toPx()))
        drawLine(deep, Offset(w * .34f, h * .81f), Offset(w * .91f, h * .81f), 7.dp.toPx(), StrokeCap.Round)
        // phone
        drawRoundRect(ink, Offset(w * .14f, h * .38f), Size(w * .20f, h * .49f), cornerRadius = CornerRadius(12.dp.toPx()))
        drawRoundRect(screenFill, Offset(w * .155f, h * .40f), Size(w * .17f, h * .44f), cornerRadius = CornerRadius(8.dp.toPx()))
        // check mark badge on laptop
        drawCircle(primary, 21.dp.toPx(), Offset(w * .64f, h * .49f))
        drawLine(onPrimary, Offset(w * .61f, h * .49f), Offset(w * .635f, h * .53f), 3.dp.toPx(), StrokeCap.Round)
        drawLine(onPrimary, Offset(w * .635f, h * .53f), Offset(w * .68f, h * .45f), 3.dp.toPx(), StrokeCap.Round)
        // small check on phone
        drawCircle(primary, 11.dp.toPx(), Offset(w * .235f, h * .52f))
        drawLine(onPrimary, Offset(w * .215f, h * .52f), Offset(w * .23f, h * .545f), 2.5.dp.toPx(), StrokeCap.Round)
        drawLine(onPrimary, Offset(w * .23f, h * .545f), Offset(w * .26f, h * .50f), 2.5.dp.toPx(), StrokeCap.Round)
    }
}

/** Observable (Compose-state backed) view of "is any device connected". */
private val PairingConnector.online: Boolean
    get() = GlideHub.connectedDevices.isNotEmpty()
