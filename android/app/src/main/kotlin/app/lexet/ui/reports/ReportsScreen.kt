package app.lexet.ui.reports

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lexet.AppContainer
import app.lexet.R
import app.lexet.data.AppSettings
import app.lexet.data.db.ReportEntity
import app.lexet.domain.report.PeriodKind
import app.lexet.domain.report.Report
import app.lexet.domain.report.ReportPeriod
import app.lexet.domain.report.ReportPeriods
import app.lexet.report.ReportRepository
import app.lexet.ui.SectionTitle
import app.lexet.ui.StatusPill
import app.lexet.ui.VerificationBadge
import app.lexet.ui.appContainer
import app.lexet.ui.formatDate
import app.lexet.ui.isAmharicUi
import app.lexet.ui.issuerLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

class ReportsViewModel(private val c: AppContainer) : ViewModel() {
    val reports: StateFlow<List<ReportEntity>> = c.reports.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val busy = MutableStateFlow(false)

    fun period(kind: PeriodKind, previous: Boolean): ReportPeriod {
        val cal = settings.value.reportCalendar
        val today = LocalDate.now()
        return if (previous) ReportPeriods.lastCompleted(today, kind, cal) else ReportPeriods.containing(today, kind, cal)
    }

    fun generate(period: ReportPeriod, then: (ReportEntity) -> Unit = {}) = viewModelScope.launch {
        busy.value = true
        try {
            then(c.reports.generate(period, settings.value.amharicFirst))
        } finally {
            busy.value = false
        }
    }

    fun delete(key: String) = viewModelScope.launch { c.reports.delete(key) }
}

fun pdfUri(context: Context, file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

fun openPdf(context: Context, file: File) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(pdfUri(context, file), "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_pdf_viewer, Toast.LENGTH_LONG).show()
    }
}

fun sharePdf(context: Context, file: File, subject: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, pdfUri(context, file))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(onView: (String) -> Unit) {
    val container = appContainer()
    val vm: ReportsViewModel = viewModel { ReportsViewModel(container) }
    val reports by vm.reports.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf<ReportEntity?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val r = saving ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> File(r.filePath).inputStream().use { it.copyTo(out) } }
            }.onSuccess { Toast.makeText(context, R.string.saved_to_device, Toast.LENGTH_SHORT).show() }
        }
        saving = null
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.nav_reports)) })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.reports_intro), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { SectionTitle(stringResource(R.string.reports_compile)) }
            items(PeriodKind.entries) { kind ->
                val label = stringResource(when (kind) {
                    PeriodKind.WEEK -> R.string.kind_week; PeriodKind.MONTH -> R.string.kind_month; PeriodKind.YEAR -> R.string.kind_year
                })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = { onView(vm.period(kind, previous = false).key) }) {
                        Text(stringResource(R.string.reports_current))
                    }
                    TextButton(enabled = !busy, onClick = { onView(vm.period(kind, previous = true).key) }) {
                        Text(stringResource(R.string.reports_previous))
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.reports_saved)) }
            if (reports.isEmpty()) item {
                Text(stringResource(R.string.reports_none), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(reports, key = { it.key }) { r ->
                Card(onClick = { onView(r.key) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
                        Text(ReportRepository.periodFromKey(r.key)?.label(settings.amharicFirst) ?: r.label,
                            style = MaterialTheme.typography.titleSmall)
                        Text(pluralStringResource(R.plurals.report_entries, r.entryCount, r.entryCount),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { openPdf(context, File(r.filePath)) }) { Text(stringResource(R.string.action_open_pdf)) }
                            TextButton(onClick = { saving = r; saver.launch("Aristophanes-${r.key}.pdf") }) { Text(stringResource(R.string.action_save_pdf)) }
                            IconButton(onClick = { sharePdf(context, File(r.filePath), r.label) }) {
                                Icon(Icons.Filled.Share, stringResource(R.string.action_share))
                            }
                            IconButton(onClick = { vm.delete(r.key) }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- in-app view

class ReportViewModel(private val c: AppContainer, key: String) : ViewModel() {
    val period: ReportPeriod? = ReportRepository.periodFromKey(key)
    val report = MutableStateFlow<Report?>(null)
    val busy = MutableStateFlow(false)
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    init {
        period?.let { p -> viewModelScope.launch { report.value = c.reports.build(p) } }
    }

    fun exportPdf(onReady: (ReportEntity) -> Unit) {
        val p = period ?: return
        viewModelScope.launch {
            busy.value = true
            try { onReady(c.reports.generate(p, c.settings.current().amharicFirst)) } finally { busy.value = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportViewScreen(key: String, onBack: () -> Unit, onOpenItem: (String) -> Unit) {
    val container = appContainer()
    val vm: ReportViewModel = viewModel(key = "report-$key") { ReportViewModel(container, key) }
    val report by vm.report.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val am = isAmharicUi()
    val tax = container.taxonomy
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(vm.period?.label(settings.amharicFirst) ?: "") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            actions = {
                TextButton(enabled = !busy && report?.isEmpty == false, onClick = { vm.exportPdf { openPdf(context, File(it.filePath)) } }) {
                    Text(stringResource(R.string.action_export_pdf))
                }
            },
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val r = report ?: return@Column
        // Index of each entry's row in the list, for the table of contents.
        val headerRows = 3
        val tocRows = r.sections.sumOf { it.entries.size + 1 }
        LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(stringResource(R.string.pdf_title), style = MaterialTheme.typography.titleLarge)
                Text("${formatDate(r.period.start, settings.dateDisplay, am)} – ${formatDate(r.period.endInclusive, settings.dateDisplay, am)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text(stringResource(R.string.pdf_totals, r.totals["new"] ?: 0, r.totals["status_changes"] ?: 0,
                    r.totals["verified"] ?: 0, r.totals["unconfirmed"] ?: 0), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                if (r.isEmpty) Text(stringResource(R.string.pdf_empty), style = MaterialTheme.typography.titleSmall)
                else SectionTitle(stringResource(R.string.pdf_contents))
            }
            // Table of contents
            var bodyIndex = headerRows + tocRows + 1
            for (sec in r.sections) {
                item { Text(if (am) sec.titleAm else sec.titleEn, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
                bodyIndex++ // section heading row in the body
                for (e in sec.entries) {
                    val target = bodyIndex
                    item(key = "toc-${e.number}") {
                        Text("${e.number}. ${e.item.lawId?.let { "$it — " } ?: ""}${e.item.title(settings.amharicFirst)}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().clickable { scope.launch { listState.animateScrollToItem(target) } }.padding(vertical = 4.dp))
                    }
                    bodyIndex++
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            // Body
            for (sec in r.sections) {
                item { SectionTitle(if (am) sec.titleAm else sec.titleEn) }
                items(sec.entries, key = { "e-${it.number}" }) { e ->
                    val item = e.item
                    Card(onClick = { onOpenItem(item.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(listOfNotNull("${e.number}.", item.lawId, item.lawIdAm).joinToString("  "),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            val first = item.title(settings.amharicFirst)
                            Text(first, style = MaterialTheme.typography.titleMedium)
                            val second = if (settings.amharicFirst) item.titleEn else item.titleAm
                            if (second != null && second != first) Text(second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(item.verifiedStatus ?: item.status, tax, am)
                                Text("  " + (tax.issuerLabel(item.issuer, am) ?: ""), style = MaterialTheme.typography.labelSmall)
                            }
                            VerificationBadge(item.verification, item.sourceCount, item.hasOfficialSource)
                            item.summary(settings.amharicFirst)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (e.citations.isNotEmpty()) Text(stringResource(R.string.pdf_sources, e.citations.joinToString(" ") { "[$it]" }),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (r.citations.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.pdf_references)) }
                items(r.citations, key = { "c-${it.number}" }) { c ->
                    Column(Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(c.url) } }.padding(vertical = 4.dp)) {
                        Text("[${c.number}] ${c.publisher} — ${c.title}", style = MaterialTheme.typography.bodySmall)
                        Text(c.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
