package app.lexet.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.lexet.LexetApp
import app.lexet.data.AppSettings
import app.lexet.data.db.LexetDatabase
import app.lexet.data.db.PendingNoticeEntity
import app.lexet.domain.model.LegalItem
import app.lexet.domain.notify.ChangeKind
import app.lexet.domain.notify.DigestMode
import app.lexet.domain.notify.DigestPlanner
import app.lexet.domain.notify.NotificationRules
import app.lexet.domain.notify.PendingNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Applies the user's filters and digest mode to the changes a sync produced. */
class NotificationDispatcher(
    private val context: Context,
    private val db: LexetDatabase,
    private val notifier: Notifier,
) {
    suspend fun dispatch(events: List<Pair<LegalItem, ChangeKind>>, settings: AppSettings) {
        val filters = settings.notifications
        val matched = events.filter { (item, _) -> NotificationRules.matches(filters, item) }
        if (matched.isEmpty()) return
        if (filters.digest == DigestMode.INSTANT) {
            matched.forEach { (item, kind) -> notifier.showItem(item, kind, settings) }
            return
        }
        val now = System.currentTimeMillis()
        for ((item, kind) in matched) {
            val am = settings.amharicFirst
            db.notices().insert(PendingNoticeEntity(
                itemId = item.id,
                title = item.title(am),
                lawId = if (am) item.lawIdAm ?: item.lawId else item.lawId,
                kind = kind.name,
                statusPhrase = NotificationRules.statusPhrase(kind, item.verifiedStatus ?: item.status, am),
                createdAt = now,
            ))
        }
        DigestScheduler.ensure(context, filters.digest)
    }
}

object DigestScheduler {
    private const val NAME = "lexet.digest"

    /** Schedules the next digest at the top of the hour / 08:00 local, unless one is already queued. */
    fun ensure(context: Context, mode: DigestMode, replace: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (mode == DigestMode.INSTANT) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val delay = DigestPlanner.delayUntilNext(mode, Instant.now(), ZoneId.systemDefault())
        val req = OneTimeWorkRequestBuilder<DigestWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }
}

class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as LexetApp).container
        val settings = c.settings.current()
        val pending = c.db.notices().all()
        if (pending.isNotEmpty()) {
            val notices = pending.map {
                PendingNotice(it.itemId, it.title, it.lawId, ChangeKind.valueOf(it.kind), it.statusPhrase,
                    Instant.ofEpochMilli(it.createdAt))
            }
            DigestPlanner.build(notices, settings.amharicFirst)?.let { c.notifier.showDigest(it, settings) }
            c.db.notices().clearUpTo(pending.maxOf { it.id })
        }
        // Chain the next one only while there is something to say; dispatch() re-arms it.
        return Result.success()
    }
}

/** "Mark read" / "Bookmark" buttons on a notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_READ = "app.lexet.action.MARK_READ"
        const val ACTION_BOOKMARK = "app.lexet.action.BOOKMARK"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Notifier.EXTRA_ITEM_ID) ?: return
        val c = (context.applicationContext as LexetApp).container
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_READ -> c.repository.setRead(id, true)
                    ACTION_BOOKMARK -> {
                        c.repository.setBookmarked(id, true)
                        c.repository.setRead(id, true)
                    }
                }
                c.notifier.cancel(id)
            } finally {
                pending.finish()
            }
        }
    }
}
