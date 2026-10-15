package app.lexet

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.lexet.data.ItemRepository
import app.lexet.data.SettingsStore
import app.lexet.data.db.LexetDatabase
import app.lexet.data.net.LexetApi
import app.lexet.domain.model.Taxonomy
import app.lexet.notify.DigestScheduler
import app.lexet.notify.NotificationDispatcher
import app.lexet.notify.Notifier
import app.lexet.report.ReportRepository
import app.lexet.report.ReportScheduler
import app.lexet.sync.ConnectivityObserver
import app.lexet.sync.Push
import app.lexet.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Manual dependency wiring: small app, no DI framework needed. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(context)

    val db: LexetDatabase = Room.databaseBuilder(context, LexetDatabase::class.java, "lexet.db")
        .fallbackToDestructiveMigration(dropAllTables = true) // archive is re-syncable; bump with real migrations once user data matters
        .build()

    val taxonomy: Taxonomy? by lazy {
        runCatching { context.assets.open("taxonomy.json").bufferedReader().use { Taxonomy.parse(it.readText()) } }.getOrNull()
    }

    private val http = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "http"), 10L * 1024 * 1024))   // feed files are content-addressed
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val api = LexetApi(http, baseUrl = { settings.current().apiUrl })
    val notifier = Notifier(context)
    val repository = ItemRepository(db, api, settings) { taxonomy }
    val dispatcher = NotificationDispatcher(context, db, notifier)
    val reports = ReportRepository(context, db, repository) { taxonomy }
}

open class LexetApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        ConnectivityObserver(this).start()

        // Re-apply background work whenever the relevant settings change.
        container.appScope.launch {
            container.settings.settings
                .distinctUntilChangedBy { listOf(it.pushEnabled, it.syncIntervalMinutes, it.unmeteredOnly, it.autoReports, it.notifications.digest) }
                .collect { s ->
                    val pushActive = Push.apply(this@LexetApp, s.pushEnabled)
                    SyncScheduler.schedulePeriodic(this@LexetApp, s.syncIntervalMinutes, s.unmeteredOnly, pushActive)
                    ReportScheduler.apply(this@LexetApp, s.autoReports)
                    DigestScheduler.ensure(this@LexetApp, s.notifications.digest)
                }
        }
    }
}
