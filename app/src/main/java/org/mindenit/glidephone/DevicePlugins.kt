package org.mindenit.glidephone

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import org.mindenit.glidephone.ProtocolPackets as P

/**
 * KDE Connect's plugin list (PluginFactory) with the packet types each plugin receives and
 * sends, and per-device enable flags (Device.isPluginEnabled). A packet is handled, or may
 * be sent, only if at least one enabled plugin of that device covers its type.
 */
internal enum class DevicePlugin(
    val title: String,
    val description: String,
    val incoming: Set<String>,
    val outgoing: Set<String>,
    private val enabledByDefault: (Context) -> Boolean = { true }
) {
    BATTERY("Батарея", "Обмін станом заряду", setOf(P.TYPE_BATTERY), setOf(P.TYPE_BATTERY)),
    CLIPBOARD("Буфер обміну", "Спільний буфер обміну",
        setOf(P.TYPE_CLIPBOARD, P.TYPE_CLIPBOARD_CONNECT), setOf(P.TYPE_CLIPBOARD, P.TYPE_CLIPBOARD_CONNECT)),
    CONNECTIVITY("Звіт про зв’язок", "Сигнал мобільної мережі на комп’ютері", emptySet(), setOf(P.TYPE_CONNECTIVITY_REPORT)),
    CONTACTS("Контакти", "Синхронізація адресної книги",
        setOf(P.TYPE_CONTACTS_REQUEST_UIDS, P.TYPE_CONTACTS_REQUEST_VCARDS),
        setOf(P.TYPE_CONTACTS_RESPONSE_UIDS, P.TYPE_CONTACTS_RESPONSE_VCARDS)),
    DIGITIZER("Графічний планшет", "Малювання на комп’ютері", emptySet(),
        setOf(P.TYPE_DIGITIZER, P.TYPE_DIGITIZER_SESSION), ::isTablet),
    FIND_MY_PHONE("Знайти телефон", "Комп’ютер може змусити телефон дзвонити", setOf(P.TYPE_FINDMYPHONE_REQUEST), emptySet()),
    FIND_REMOTE("Знайти комп’ютер", "Телефон може змусити комп’ютер дзвонити", emptySet(), setOf(P.TYPE_FINDMYPHONE_REQUEST)),
    MOUSEPAD("Миша та клавіатура", "Керування вказівником і введенням",
        setOf(P.TYPE_MOUSEPAD_KEYBOARDSTATE), setOf(P.TYPE_MOUSEPAD_REQUEST)),
    MOUSE_RECEIVER("Керування телефоном мишею", "Комп’ютер рухає вказівник і натискає на телефоні",
        setOf(P.TYPE_MOUSEPAD_REQUEST), emptySet()),
    REMOTE_KEYBOARD("Клавіатура комп’ютера", "Введення тексту на телефоні з клавіатури комп’ютера",
        setOf(P.TYPE_MOUSEPAD_REQUEST), setOf(P.TYPE_MOUSEPAD_ECHO, P.TYPE_MOUSEPAD_KEYBOARDSTATE)),
    INPUT_DEVICES("Спільні пристрої введення", "Курсор комп’ютера переходить на екран телефону",
        setOf(P.TYPE_MOUSEPAD_REQUEST, P.TYPE_SHAREINPUTDEVICES_REQUEST), setOf(P.TYPE_SHAREINPUTDEVICES)),
    MPRIS("Керування медіа", "Пульт для плеєрів комп’ютера", setOf(P.TYPE_MPRIS), setOf(P.TYPE_MPRIS_REQUEST)),
    MPRIS_RECEIVER("Медіа телефону", "Комп’ютер керує плеєрами телефону", setOf(P.TYPE_MPRIS_REQUEST), setOf(P.TYPE_MPRIS)),
    NOTIFICATIONS("Сповіщення телефону", "Показ сповіщень телефону на комп’ютері",
        setOf(P.TYPE_NOTIFICATION_REQUEST, P.TYPE_NOTIFICATION_REPLY, P.TYPE_NOTIFICATION_ACTION), setOf(P.TYPE_NOTIFICATION)),
    PING("Пінг", "Перевірка зв’язку", setOf(P.TYPE_PING), setOf(P.TYPE_PING)),
    PRESENTER("Презентації", "Пульт для слайдів", emptySet(), setOf(P.TYPE_PRESENTER, P.TYPE_MOUSEPAD_REQUEST)),
    RECEIVE_NOTIFICATIONS("Сповіщення комп’ютера", "Показ сповіщень комп’ютера на телефоні",
        setOf(P.TYPE_NOTIFICATION), setOf(P.TYPE_NOTIFICATION_REQUEST), { false }),
    RUN_COMMAND("Команди", "Запуск команд на комп’ютері",
        setOf(P.TYPE_RUNCOMMAND, P.TYPE_RUNCOMMAND_OUTPUT), setOf(P.TYPE_RUNCOMMAND_REQUEST)),
    SFTP("Файли телефону", "Перегляд файлів телефону з комп’ютера (SFTP)",
        setOf(P.TYPE_SFTP_REQUEST), setOf(P.TYPE_SFTP)),
    SHARE("Обмін файлами", "Надсилання й отримання файлів і посилань",
        setOf(P.TYPE_SHARE_REQUEST, P.TYPE_SHARE_REQUEST_UPDATE), setOf(P.TYPE_SHARE_REQUEST)),
    SMS("SMS", "Читання й надсилання SMS з комп’ютера",
        setOf(P.TYPE_SMS_REQUEST, P.TYPE_SMS_REQUEST_CONVERSATIONS, P.TYPE_SMS_REQUEST_CONVERSATION), setOf(P.TYPE_SMS_MESSAGES)),
    SYSTEM_VOLUME("Гучність комп’ютера", "Регулювання аудіовиходів комп’ютера",
        setOf(P.TYPE_SYSTEMVOLUME), setOf(P.TYPE_SYSTEMVOLUME_REQUEST)),
    VOLUME_RECEIVER("Гучність телефону", "Комп’ютер регулює гучність телефону (лише Glide)",
        setOf(P.TYPE_SYSTEMVOLUME_REQUEST), setOf(P.TYPE_SYSTEMVOLUME)),
    TELEPHONY("Телефонія", "Сповіщення комп’ютера про дзвінки",
        setOf(P.TYPE_TELEPHONY_REQUEST_MUTE), setOf(P.TYPE_TELEPHONY)),
    WAKE_ON_LAN("Увімкнення комп’ютера", "Будити сплячий комп’ютер з телефона (лише Glide)",
        setOf(P.TYPE_GLIDE_WOL), emptySet()),
    COMPUTER_FILES("Файли комп’ютера", "Перегляд і завантаження файлів комп’ютера (лише Glide)",
        setOf(P.TYPE_GLIDE_FILES), setOf(P.TYPE_GLIDE_FILES_REQUEST));

    fun isEnabledByDefault(context: Context) = enabledByDefault(context)
}

/**
 * Top level on purpose: an enum constant's `::isTablet` in a companion object is bound to the
 * companion while the enum is still being built, when the companion is null, and the first
 * call (on every trusted link) threw a NullPointerException.
 */
private fun isTablet(context: Context): Boolean =
    context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK >= Configuration.SCREENLAYOUT_SIZE_LARGE

internal class DevicePluginSettings(context: Context) {
    private val app = context.applicationContext

    private fun prefs(deviceId: String) = app.getSharedPreferences("device_plugins_$deviceId", Context.MODE_PRIVATE)

    fun isEnabled(deviceId: String, plugin: DevicePlugin): Boolean =
        prefs(deviceId).getBoolean(plugin.name, plugin.isEnabledByDefault(app))

    fun setEnabled(deviceId: String, plugin: DevicePlugin, enabled: Boolean) =
        prefs(deviceId).edit { putBoolean(plugin.name, enabled) }

    /** May a packet of [type] from [deviceId] be handled? */
    fun accepts(deviceId: String, type: String): Boolean =
        DevicePlugin.entries.filter { type in it.incoming }.let { owners -> owners.isEmpty() || owners.any { isEnabled(deviceId, it) } }

    /** May a packet of [type] be sent to [deviceId]? */
    fun allowsSending(deviceId: String, type: String): Boolean =
        DevicePlugin.entries.filter { type in it.outgoing }.let { owners -> owners.isEmpty() || owners.any { isEnabled(deviceId, it) } }

    /** Forget the settings of a device that was unpaired. */
    fun clear(deviceId: String) {
        prefs(deviceId).edit { clear() }
    }
}
