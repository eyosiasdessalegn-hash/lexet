package app.lexet.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.lexet.R
import app.lexet.data.AppSettings
import app.lexet.domain.model.LegalItem
import app.lexet.domain.notify.ChangeKind
import app.lexet.domain.notify.DigestContent
import app.lexet.domain.notify.NotificationRules
import app.lexet.ui.MainActivity

/**
 * Posts notifications. Three channels by urgency ("now in force", "about to be
 * released", "drafts and bills") so people can tune sound/importance per stage
 * in system settings, plus digest, report and background-sync channels.
 */
class Notifier(private val context: Context) {
    companion object {
        const val CH_IN_FORCE = "in_force"
        const val CH_IMMINENT = "imminent"
        const val CH_UPCOMING = "upcoming"
        const val CH_DIGEST = "digest"
        const val CH_REPORTS = "reports"
        const val CH_SYNC = "sync"
        const val GROUP = "app.lexet.verified"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_DESTINATION = "destination"
        const val SYNC_NOTIFICATION_ID = 7001
        private const val DIGEST_ID = 7002
    }

    private val nm = NotificationManagerCompat.from(context)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        fun ch(id: String, nameRes: Int, descRes: Int, importance: Int) =
            NotificationChannel(id, context.getString(nameRes), importance).apply {
                description = context.getString(descRes)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        mgr.createNotificationChannels(listOf(
            ch(CH_IN_FORCE, R.string.channel_in_force, R.string.channel_in_force_desc, NotificationManager.IMPORTANCE_HIGH),
            ch(CH_IMMINENT, R.string.channel_imminent, R.string.channel_imminent_desc, NotificationManager.IMPORTANCE_HIGH),
            ch(CH_UPCOMING, R.string.channel_upcoming, R.string.channel_upcoming_desc, NotificationManager.IMPORTANCE_DEFAULT),
            ch(CH_DIGEST, R.string.channel_digest, R.string.channel_digest_desc, NotificationManager.IMPORTANCE_DEFAULT),
            ch(CH_REPORTS, R.string.channel_reports, R.string.channel_reports_desc, NotificationManager.IMPORTANCE_LOW),
            ch(CH_SYNC, R.string.channel_sync, R.string.channel_sync_desc, NotificationManager.IMPORTANCE_MIN),
        ))
    }

    fun canPost(): Boolean {
        if (!nm.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun openIntent(itemId: String?, destination: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            itemId?.let { putExtra(EXTRA_ITEM_ID, it) }
            destination?.let { putExtra(EXTRA_DESTINATION, it) }
        }
        return PendingIntent.getActivity(context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(action: String, itemId: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        return PendingIntent.getBroadcast(context, (action + itemId).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun notificationId(itemId: String) = itemId.hashCode()

    fun showItem(item: LegalItem, kind: ChangeKind, settings: AppSettings) {
        if (!canPost()) return
        val am = settings.amharicFirst
        val phrase = NotificationRules.statusPhrase(kind, item.verifiedStatus ?: item.status, am)
        val title = item.title(am)
        val heading = listOfNotNull(if (am) item.lawIdAm ?: item.lawId else item.lawId, phrase).joinToString(" · ")
        val sources = context.resources.getQuantityString(R.plurals.verified_by_sources, item.sourceCount, item.sourceCount)
        val body = listOfNotNull(title, item.summary(am)?.substringBefore(". ")?.plus("."), sources).joinToString("\n")

        val publicVersion = NotificationCompat.Builder(context, NotificationRules.channelFor(item))
            .setSmallIcon(R.drawable.ic_stat_lexet)
            .setContentTitle(context.getString(R.string.notif_public_title))
            .setContentText(phrase)
            .build()

        val n = NotificationCompat.Builder(context, NotificationRules.channelFor(item))
            .setSmallIcon(R.drawable.ic_stat_lexet)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(heading)
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (kind == ChangeKind.STATUS_ADVANCED) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(if (settings.notifications.showOnLockScreen) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setContentIntent(openIntent(item.id, null, notificationId(item.id)))
            .addAction(0, context.getString(R.string.action_mark_read), actionIntent(NotificationActionReceiver.ACTION_READ, item.id))
            .addAction(0, context.getString(R.string.action_bookmark), actionIntent(NotificationActionReceiver.ACTION_BOOKMARK, item.id))
            .build()
        post(notificationId(item.id), n)
    }

    fun showDigest(content: DigestContent, settings: AppSettings) {
        if (!canPost()) return
        val style = NotificationCompat.InboxStyle().setBigContentTitle(content.title)
        content.lines.forEach { style.addLine(it) }
        if (content.more > 0) style.setSummaryText(context.getString(R.string.digest_more, content.more))
        val n = NotificationCompat.Builder(context, CH_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_lexet)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(content.title)
            .setContentText(content.lines.firstOrNull())
            .setStyle(style)
            .setNumber(content.itemIds.size)
            .setVisibility(if (settings.notifications.showOnLockScreen) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openIntent(null, "feed", DIGEST_ID))
            .build()
        post(DIGEST_ID, n)
    }

    fun showReportReady(label: String, key: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CH_REPORTS)
            .setSmallIcon(R.drawable.ic_stat_lexet)
            .setContentTitle(context.getString(R.string.report_ready_title))
            .setContentText(label)
            .setAutoCancel(true)
            .setContentIntent(openIntent(null, "report:$key", key.hashCode()))
            .build()
        post(key.hashCode(), n)
    }

    fun syncNotification(): Notification =
        NotificationCompat.Builder(context, CH_SYNC)
            .setSmallIcon(R.drawable.ic_stat_lexet)
            .setContentTitle(context.getString(R.string.syncing))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    fun cancel(itemId: String) = nm.cancel(notificationId(itemId))

    @Suppress("MissingPermission") // checked in canPost()
    private fun post(id: Int, n: Notification) {
        if (canPost()) nm.notify(id, n)
    }
}
