package app.lexet.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.lexet.BuildConfig
import app.lexet.domain.model.Urgency
import app.lexet.domain.notify.DigestMode
import app.lexet.domain.notify.NotificationFilters
import app.lexet.domain.notify.TypeFilter
import app.lexet.domain.report.CalendarSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class TitleLanguage { AMHARIC_FIRST, ENGLISH_FIRST }
enum class DateDisplay { GREGORIAN, ETHIOPIAN, BOTH }

/** Everything the user can configure. Stored on the device only. */
data class AppSettings(
    val apiUrl: String = BuildConfig.API_URL,
    val notifications: NotificationFilters = NotificationFilters(),
    val titleLanguage: TitleLanguage = TitleLanguage.ENGLISH_FIRST,
    val dateDisplay: DateDisplay = DateDisplay.BOTH,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val reportCalendar: CalendarSystem = CalendarSystem.GREGORIAN,
    val autoReports: Boolean = true,
    val notifyReports: Boolean = true,
    val pushEnabled: Boolean = true,
    val syncIntervalMinutes: Int = 60,
    val unmeteredOnly: Boolean = false,
) {
    val amharicFirst get() = titleLanguage == TitleLanguage.AMHARIC_FIRST
}

data class SyncState(
    val since: Long = 0,
    /** Identifies the feed's dataset; a different value means "start again from the snapshot". */
    val epoch: Long = 0,
    val bootstrapped: Boolean = false,
    val lastSuccessAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val lastError: String? = null,
)

class SettingsStore(private val context: Context) {
    private object K {
        val apiUrl = stringPreferencesKey("api_url")
        val notifyEnabled = booleanPreferencesKey("notify_enabled")
        val types = stringSetPreferencesKey("filter_types")
        val issuers = stringSetPreferencesKey("filter_issuers")
        val topics = stringSetPreferencesKey("filter_topics")
        val urgencies = stringSetPreferencesKey("filter_urgencies")
        val keywords = stringPreferencesKey("filter_keywords")
        val digest = stringPreferencesKey("digest_mode")
        val lockScreen = booleanPreferencesKey("lock_screen_content")
        val includeUnconfirmed = booleanPreferencesKey("notify_unconfirmed")
        val titleLang = stringPreferencesKey("title_language")
        val dateDisplay = stringPreferencesKey("date_display")
        val theme = stringPreferencesKey("theme")
        val reportCalendar = stringPreferencesKey("report_calendar")
        val autoReports = booleanPreferencesKey("auto_reports")
        val notifyReports = booleanPreferencesKey("notify_reports")
        val push = booleanPreferencesKey("push_enabled")
        val interval = intPreferencesKey("sync_interval_min")
        val unmetered = booleanPreferencesKey("unmetered_only")

        val since = longPreferencesKey("sync_since")
        val epoch = longPreferencesKey("feed_epoch")
        val bootstrapped = booleanPreferencesKey("bootstrapped")
        val lastSuccess = longPreferencesKey("last_success_at")
        val lastAttempt = longPreferencesKey("last_attempt_at")
        val lastError = stringPreferencesKey("last_error")
    }

    private inline fun <reified E : Enum<E>> enumOr(v: String?, default: E): E =
        v?.let { s -> enumValues<E>().firstOrNull { it.name == s } } ?: default

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            apiUrl = p[K.apiUrl]?.takeIf { it.isNotBlank() } ?: d.apiUrl,
            notifications = NotificationFilters(
                enabled = p[K.notifyEnabled] ?: true,
                types = p[K.types].orEmpty().mapNotNull { s -> TypeFilter.entries.firstOrNull { it.name == s } }.toSet(),
                issuers = p[K.issuers].orEmpty(),
                topics = p[K.topics].orEmpty(),
                keywords = p[K.keywords].orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() },
                urgencies = p[K.urgencies].orEmpty().mapNotNull { s -> Urgency.entries.firstOrNull { it.name == s } }.toSet(),
                digest = enumOr(p[K.digest], DigestMode.INSTANT),
                showOnLockScreen = p[K.lockScreen] ?: true,
                includeUnconfirmed = p[K.includeUnconfirmed] ?: true,
            ),
            titleLanguage = enumOr(p[K.titleLang], d.titleLanguage),
            dateDisplay = enumOr(p[K.dateDisplay], d.dateDisplay),
            theme = enumOr(p[K.theme], d.theme),
            reportCalendar = enumOr(p[K.reportCalendar], d.reportCalendar),
            autoReports = p[K.autoReports] ?: d.autoReports,
            notifyReports = p[K.notifyReports] ?: d.notifyReports,
            pushEnabled = p[K.push] ?: d.pushEnabled,
            syncIntervalMinutes = p[K.interval] ?: d.syncIntervalMinutes,
            unmeteredOnly = p[K.unmetered] ?: d.unmeteredOnly,
        )
    }

    val syncState: Flow<SyncState> = context.dataStore.data.map { p ->
        SyncState(p[K.since] ?: 0, p[K.epoch] ?: 0, p[K.bootstrapped] ?: false, p[K.lastSuccess] ?: 0,
            p[K.lastAttempt] ?: 0, p[K.lastError])
    }

    suspend fun current(): AppSettings = settings.first()
    suspend fun currentSync(): SyncState = syncState.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current())
        context.dataStore.edit { p ->
            p[K.apiUrl] = next.apiUrl
            val n = next.notifications
            p[K.notifyEnabled] = n.enabled
            p[K.types] = n.types.map { it.name }.toSet()
            p[K.issuers] = n.issuers
            p[K.topics] = n.topics
            p[K.urgencies] = n.urgencies.map { it.name }.toSet()
            p[K.keywords] = n.keywords.joinToString("\n")
            p[K.digest] = n.digest.name
            p[K.lockScreen] = n.showOnLockScreen
            p[K.includeUnconfirmed] = n.includeUnconfirmed
            p[K.titleLang] = next.titleLanguage.name
            p[K.dateDisplay] = next.dateDisplay.name
            p[K.theme] = next.theme.name
            p[K.reportCalendar] = next.reportCalendar.name
            p[K.autoReports] = next.autoReports
            p[K.notifyReports] = next.notifyReports
            p[K.push] = next.pushEnabled
            p[K.interval] = next.syncIntervalMinutes
            p[K.unmetered] = next.unmeteredOnly
        }
    }

    suspend fun recordSyncAttempt(now: Long) = context.dataStore.edit { it[K.lastAttempt] = now }

    suspend fun recordSyncSuccess(since: Long, now: Long) = context.dataStore.edit {
        it[K.since] = since
        it[K.bootstrapped] = true
        it[K.lastSuccess] = now
        it.remove(K.lastError)
    }

    suspend fun saveFeedEpoch(epoch: Long, since: Long) = context.dataStore.edit {
        it[K.epoch] = epoch
        it[K.since] = since
    }

    /** Saves progress after each page so an interrupted sync resumes where it stopped. */
    suspend fun saveCursor(since: Long) = context.dataStore.edit { it[K.since] = since }

    suspend fun recordSyncError(message: String) = context.dataStore.edit { it[K.lastError] = message.take(200) }

    /** Changing server means a fresh archive. */
    suspend fun resetSync() = context.dataStore.edit {
        it[K.since] = 0
        it[K.epoch] = 0
        it[K.bootstrapped] = false
        it.remove(K.lastError)
        it[K.lastSuccess] = 0
    }
}
