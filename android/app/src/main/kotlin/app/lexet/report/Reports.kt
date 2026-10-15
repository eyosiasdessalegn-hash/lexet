package app.lexet.report

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.lexet.LexetApp
import app.lexet.data.ItemRepository
import app.lexet.data.db.LexetDatabase
import app.lexet.data.db.ReportEntity
import app.lexet.domain.model.Taxonomy
import app.lexet.domain.report.CalendarSystem
import app.lexet.domain.report.PeriodKind
import app.lexet.domain.report.Report
import app.lexet.domain.report.ReportBuilder
import app.lexet.domain.report.ReportPeriod
import app.lexet.domain.report.ReportPeriods
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class ReportRepository(
    private val context: Context,
    private val db: LexetDatabase,
    private val items: ItemRepository,
    private val taxonomy: () -> Taxonomy?,
) {
    private val dir get() = File(context.filesDir, "reports")

    fun observe(): Flow<List<ReportEntity>> = db.reports().observeAll()

    suspend fun build(period: ReportPeriod): Report =
        ReportBuilder.build(items.itemsInRange(period.start, period.endInclusive), period, Instant.now(), taxonomy())

    /** Builds the compilation for [period] and writes its PDF. */
    suspend fun generate(period: ReportPeriod, amharicFirst: Boolean): ReportEntity = withContext(Dispatchers.Default) {
        val report = build(period)
        val file = File(dir, "${period.key}.pdf")
        ReportPdfWriter(context, taxonomy()).write(report, file, amharicFirst)
        val entity = ReportEntity(
            key = period.key, kind = period.kind.name, calendar = period.calendar.name,
            startDay = period.start.toEpochDay(), endDay = period.endInclusive.toEpochDay(),
            label = period.label(amharicFirst), filePath = file.absolutePath,
            generatedAt = System.currentTimeMillis(), entryCount = report.entryCount,
        )
        db.reports().upsert(entity)
        entity
    }

    /**
     * Generates the last completed week, month and year if they are missing.
     * Periods with nothing in them are skipped (no empty PDFs).
     */
    suspend fun generateDue(today: LocalDate, calendar: CalendarSystem, amharicFirst: Boolean): List<ReportEntity> {
        val made = ArrayList<ReportEntity>()
        for (kind in PeriodKind.entries) {
            val period = ReportPeriods.lastCompleted(today, kind, calendar)
            if (db.reports().get(period.key) != null) continue
            if (build(period).isEmpty) continue
            made += generate(period, amharicFirst)
        }
        return made
    }

    suspend fun delete(key: String) {
        db.reports().get(key)?.let { File(it.filePath).delete() }
        db.reports().delete(key)
    }

    fun file(entity: ReportEntity) = File(entity.filePath)

    companion object {
        /** Inverse of ReportPeriod.key ("week-gr-2026-10-12"). */
        fun periodFromKey(key: String): ReportPeriod? = runCatching {
            val parts = key.split('-', limit = 3)
            val kind = PeriodKind.valueOf(parts[0].uppercase())
            val calendar = if (parts[1] == "et") CalendarSystem.ETHIOPIAN else CalendarSystem.GREGORIAN
            ReportPeriods.containing(LocalDate.parse(parts[2]), kind, calendar)
        }.getOrNull()
    }
}

object ReportScheduler {
    private const val NAME = "lexet.reports.daily"

    fun apply(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val req = PeriodicWorkRequestBuilder<ReportWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** Daily: produce any newly completed weekly / monthly / yearly compilation. */
class ReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as LexetApp).container
        val s = c.settings.current()
        val made = c.reports.generateDue(LocalDate.now(), s.reportCalendar, s.amharicFirst)
        if (s.notifyReports) made.forEach { c.notifier.showReportReady(it.label, it.key) }
        return Result.success()
    }
}
