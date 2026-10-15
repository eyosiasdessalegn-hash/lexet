package app.lexet.ui.feed

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.lexet.AppContainer
import app.lexet.R
import app.lexet.data.AppSettings
import app.lexet.data.ItemCardModel
import app.lexet.data.SyncState
import app.lexet.domain.feed.FeedMixer
import app.lexet.domain.model.Urgency
import app.lexet.sync.SyncScheduler
import app.lexet.ui.EmptyState
import app.lexet.ui.ItemCard
import app.lexet.ui.appContainer
import app.lexet.ui.isAmharicUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FeedUiState(
    val entries: List<FeedMixer.Entry<ItemCardModel>> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val sync: SyncState = SyncState(),
    val loading: Boolean = true,
    val canLoadMore: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModel(private val c: AppContainer, private val app: Application) : ViewModel() {
    private val limit = MutableStateFlow(150)
    val urgency = MutableStateFlow<Urgency?>(null)

    val state: StateFlow<FeedUiState> = limit.flatMapLatest { lim ->
        combine(c.repository.feed(lim), c.repository.international(40), c.settings.settings, c.settings.syncState, urgency) { et, intl, s, sync, u ->
            val filtered = if (u == null) et else et.filter { (it.verifiedStatus ?: it.status)?.urgency == u }
            val exhausted = et.size < lim
            FeedUiState(
                entries = FeedMixer.interleave(filtered, if (u == null) intl else emptyList(), ratio = 10, primaryExhausted = exhausted),
                settings = s, sync = sync, loading = false, canLoadMore = !exhausted,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FeedUiState())

    val refreshing: StateFlow<Boolean> = WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("lexet.sync.now")
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun refresh() = SyncScheduler.syncNow(app, expedited = true)
    fun loadMore() { limit.value += 150 }
    fun setUrgency(u: Urgency?) { urgency.value = u }
    fun toggleBookmark(item: ItemCardModel) = viewModelScope.launch { c.repository.setBookmarked(item.id, !item.bookmarked) }
    fun markAllRead() = viewModelScope.launch { c.repository.markAllRead() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(onOpen: (String) -> Unit, onOpenSettings: () -> Unit) {
    val container = appContainer()
    val app = LocalContext.current.applicationContext as Application
    val vm: FeedViewModel = viewModel { FeedViewModel(container, app) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val urgency by vm.urgency.collectAsStateWithLifecycle()
    val labelsAm = isAmharicUi()

    LaunchedEffect(Unit) { vm.refresh() } // opening the app is a good moment to check

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.app_name))
                    Text(syncLine(state.sync), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                IconButton(onClick = { vm.markAllRead() }) {
                    Icon(Icons.Filled.Done, contentDescription = stringResource(R.string.action_mark_all_read))
                }
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
            },
        )
        NotificationPermissionBanner()
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(selected = urgency == null, onClick = { vm.setUrgency(null) },
                    label = { Text(stringResource(R.string.filter_all)) })
            }
            items(listOf(Urgency.UPCOMING, Urgency.IMMINENT, Urgency.IN_FORCE)) { u ->
                FilterChip(selected = urgency == u, onClick = { vm.setUrgency(if (urgency == u) null else u) },
                    label = { Text(container.taxonomy?.urgency(u.code)?.text(labelsAm) ?: u.code) })
            }
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
            if (!state.loading && state.entries.isEmpty() && state.settings.apiUrl.isBlank()) {
                // Fresh install: no feed chosen yet.
                EmptyState(stringResource(R.string.feed_setup_title), stringResource(R.string.feed_setup_body),
                    actionLabel = stringResource(R.string.set_server), onAction = onOpenSettings)
            } else if (!state.loading && state.entries.isEmpty() && state.sync.lastSuccessAt == 0L && state.sync.lastError != null) {
                // Never reached the server: most often the address (e.g. a fresh install on a phone).
                EmptyState(stringResource(R.string.feed_no_server_title),
                    stringResource(R.string.feed_no_server_body, state.settings.apiUrl),
                    actionLabel = stringResource(R.string.set_server), onAction = onOpenSettings)
            } else if (!state.loading && state.entries.isEmpty()) {
                EmptyState(stringResource(R.string.feed_empty_title), stringResource(R.string.feed_empty_body))
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()) {
                    items(state.entries, key = { e -> (if (e is FeedMixer.Entry.Secondary) "i-" else "e-") + e.value.id }) { e ->
                        ItemCard(e.value, state.settings.amharicFirst, labelsAm, state.settings.dateDisplay, container.taxonomy,
                            onClick = { onOpen(e.value.id) }, onToggleBookmark = { vm.toggleBookmark(e.value) })
                    }
                    if (state.canLoadMore) item {
                        TextButton(onClick = { vm.loadMore() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun syncLine(s: SyncState): String {
    if (s.lastError != null && s.lastAttemptAt > s.lastSuccessAt) return stringResource(R.string.sync_offline)
    if (s.lastSuccessAt == 0L) return stringResource(R.string.sync_never)
    val minutes = ((System.currentTimeMillis() - s.lastSuccessAt) / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.sync_just_now)
        minutes < 60 -> stringResource(R.string.sync_minutes_ago, minutes)
        else -> stringResource(R.string.sync_hours_ago, minutes / 60)
    }
}

/** Android 13+: explain, then ask for the notification permission — once, from a visible card. */
@Composable
private fun NotificationPermissionBanner() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val container = appContainer()
    var granted by remember { mutableStateOf(container.notifier.canPost()) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok }
    if (granted || dismissed) return
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.perm_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.action_not_now)) }
                TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text(stringResource(R.string.action_allow))
                }
            }
        }
    }
    LaunchedEffect(context) { granted = container.notifier.canPost() }
}
