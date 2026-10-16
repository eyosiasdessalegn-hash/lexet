package app.lexet.sync

import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.lexet.BuildConfig
import app.lexet.LexetApp
import app.lexet.notify.Notifier
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One sync run: delta download, local apply, notifications.
 * Retries with exponential backoff on network errors and leaves a "catch-up"
 * job that runs the moment connectivity returns.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as LexetApp).container
        if (c.settings.current().apiUrl.isBlank()) return Result.success()   // no feed chosen yet
        c.settings.recordSyncAttempt(System.currentTimeMillis())
        return try {
            val outcome = c.repository.sync()
            c.dispatcher.dispatch(outcome.events, c.settings.current())
            Result.success()
        } catch (e: IOException) {
            c.settings.recordSyncError(e.message ?: "network error")
            SyncScheduler.scheduleCatchUp(applicationContext)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (e: SerializationException) {
            Log.w("Aristophanes", "unexpected server response", e)
            c.settings.recordSyncError("unexpected server response")
            Result.failure()
        }
    }

    /** Used only when the system runs an expedited sync as a foreground service (Android 11 and older). */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = (applicationContext as LexetApp).container.notifier.syncNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.SYNC_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.SYNC_NOTIFICATION_ID, notification)
        }
    }
}

object SyncScheduler {
    private const val PERIODIC = "lexet.sync.periodic"
    private const val NOW = "lexet.sync.now"
    private const val CATCH_UP = "lexet.sync.catchup"

    private fun constraints(unmeteredOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /**
     * Background polling. With push active it is only a safety net (every 6 h);
     * without push it is the main mechanism, at the user's chosen interval.
     */
    fun schedulePeriodic(context: Context, intervalMinutes: Int, unmeteredOnly: Boolean, pushActive: Boolean) {
        val minutes = (if (pushActive) maxOf(intervalMinutes, 360) else intervalMinutes).coerceAtLeast(15)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints(unmeteredOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Sync as soon as there is a network. Requests queue behind a running sync. */
    fun syncNow(context: Context, expedited: Boolean = false) {
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(false))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        if (expedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
    }

    /**
     * A job gated on connectivity. The system (JobScheduler) starts it as soon as
     * the device is back online, even if the app process is not running.
     */
    fun scheduleCatchUp(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.KEEP, request)
    }
}

/**
 * While the app process is alive, react to the network coming back immediately
 * (the catch-up job covers the case where the process is gone).
 */
class ConnectivityObserver(private val context: Context) {
    @Volatile private var lostSinceLastSync = false

    fun start() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                lostSinceLastSync = true
                SyncScheduler.scheduleCatchUp(context)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (lostSinceLastSync && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    lostSinceLastSync = false
                    SyncScheduler.syncNow(context, expedited = true)
                }
            }
        })
    }
}

/** Optional Firebase Cloud Messaging. Never initialised unless keys are configured and the user keeps push on. */
object Push {
    val configured: Boolean
        get() = BuildConfig.FCM_APP_ID.isNotBlank() && BuildConfig.FCM_API_KEY.isNotBlank() &&
            BuildConfig.FCM_PROJECT_ID.isNotBlank() && BuildConfig.FCM_SENDER_ID.isNotBlank()

    private fun ensureApp(app: Application): Boolean {
        if (!configured) return false
        if (FirebaseApp.getApps(app).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FCM_APP_ID)
                .setApiKey(BuildConfig.FCM_API_KEY)
                .setProjectId(BuildConfig.FCM_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(app, options)
        }
        return true
    }

    /** Returns true if push is active after the call. */
    fun apply(app: Application, enabled: Boolean): Boolean {
        if (!configured) return false
        if (!enabled) {
            if (FirebaseApp.getApps(app).isNotEmpty()) {
                val fm = FirebaseMessaging.getInstance()
                fm.unsubscribeFromTopic(BuildConfig.FCM_TOPIC)
                fm.isAutoInitEnabled = false
                fm.deleteToken()
            }
            return false
        }
        if (!ensureApp(app)) return false
        val fm = FirebaseMessaging.getInstance()
        fm.isAutoInitEnabled = true
        fm.subscribeToTopic(BuildConfig.FCM_TOPIC)
        return true
    }
}

/** Receives the server's data-only tickle and syncs. The message carries no content. */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["kind"] == "sync") {
            SyncScheduler.syncNow(applicationContext, expedited = message.priority == RemoteMessage.PRIORITY_HIGH)
        }
    }

    override fun onNewToken(token: String) {
        // Topic subscriptions survive token refresh, but re-subscribing is cheap and safe.
        if (Push.configured) FirebaseMessaging.getInstance().subscribeToTopic(BuildConfig.FCM_TOPIC)
    }
}
