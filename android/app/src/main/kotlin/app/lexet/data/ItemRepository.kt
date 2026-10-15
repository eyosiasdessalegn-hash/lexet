package app.lexet.data

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.lexet.data.db.ItemFtsEntity
import app.lexet.data.db.ItemTagEntity
import app.lexet.data.db.LexetDatabase
import app.lexet.data.db.TagEntity
import app.lexet.data.db.UserStateEntity
import app.lexet.data.net.ItemDto
import app.lexet.data.net.LexetApi
import app.lexet.domain.model.LegalItem
import app.lexet.domain.model.Taxonomy
import app.lexet.domain.notify.ChangeKind
import app.lexet.domain.notify.NotificationRules
import app.lexet.domain.search.SearchIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

data class SyncOutcome(
    val pages: Int,
    val items: Int,
    val events: List<Pair<LegalItem, ChangeKind>>,
    val notModified: Boolean,
)

class ItemRepository(
    private val db: LexetDatabase,
    private val api: LexetApi,
    private val settings: SettingsStore,
    private val taxonomy: () -> Taxonomy?,
) {
    // ---------------------------------------------------------------- sync

    /**
     * Downloads what changed since the stored version. A new install (or a dataset
     * the feed has rebuilt) takes the snapshot in one request; after that only the
     * segments ending after our version are fetched — usually one small file, and
     * nothing at all when the index shows no change. Each file is applied in one
     * transaction and the cursor saved after it, so an interrupted sync resumes.
     * Returns the changes that may deserve a notification (filters are applied by the caller).
     */
    private val syncMutex = Mutex()

    suspend fun sync(): SyncOutcome = syncMutex.withLock { syncLocked() }

    private suspend fun syncLocked(): SyncOutcome {
        val state = settings.currentSync()
        val index = api.feedIndex()
        var since = state.since
        var bootstrap = !state.bootstrapped || db.items().count() == 0
        if (state.epoch != 0L && state.epoch != index.epoch) {
            // The feed was rebuilt from scratch: its version numbers no longer match ours.
            db.withTransaction {
                db.fts().deleteAll()
                db.items().deleteAll()
                db.notices().deleteAll()
            }
            since = 0
            bootstrap = true
        }
        if (state.epoch != index.epoch) settings.saveFeedEpoch(index.epoch, since)
        if (index.version <= since) {
            settings.recordSyncSuccess(since, System.currentTimeMillis())
            return SyncOutcome(0, 0, emptyList(), notModified = true)
        }
        val files = if (since == 0L) listOf(index.snapshot.file)
            else index.segments.filter { it.to > since }.map { it.file }
        var count = 0
        val events = ArrayList<Pair<LegalItem, ChangeKind>>()
        for (file in files) {
            val page = api.feedPage(file)
            val after = since
            db.withTransaction {
                for (dto in page.items) {
                    if (dto.version <= after) continue
                    applyItem(dto, bootstrap)?.let { events.add(it) }
                    count++
                }
            }
            since = maxOf(since, page.nextSince)
            settings.saveCursor(since)
        }
        if (bootstrap) db.user().insertReadForAll() // the first archive download is not "unread news"
        settings.recordSyncSuccess(since, System.currentTimeMillis())
        return SyncOutcome(files.size, count, events, notModified = false)
    }

    private suspend fun applyItem(dto: ItemDto, bootstrap: Boolean): Pair<LegalItem, ChangeKind>? {
        val existing = db.items().get(dto.id)
        if (dto.deleted) {
            existing?.let { db.fts().delete(it.ftsDocId) }
            db.items().delete(dto.id)
            db.notices().deleteFor(dto.id)
            dto.mergedInto?.let { moveUserState(dto.id, it) } ?: run {
                db.user().delete(dto.id)
                db.user().deleteTagsFor(dto.id)
            }
            return null
        }
        if (existing != null && existing.version >= dto.version) return null
        val entity = dto.toEntity(existing?.ftsDocId ?: db.items().nextDocId())
        db.items().upsert(entity)
        val current = entity.toDomain()
        val doc = SearchIndex.document(current, taxonomy())
        db.fts().put(ItemFtsEntity(entity.ftsDocId, doc.titles, doc.summary, doc.lawId, doc.meta, doc.body))
        val kind = NotificationRules.detectChange(existing?.toDomain(withSources = false), current, bootstrap)
        if (kind != null) {
            // A newly verified or advanced item becomes unread again.
            val s = db.user().get(dto.id)
            db.user().upsert((s ?: UserStateEntity(dto.id)).copy(read = false))
        }
        return kind?.let { current to it }
    }

    /** Server merged two items: bookmarks, tags and read state follow the survivor. */
    private suspend fun moveUserState(from: String, to: String) {
        val old = db.user().get(from)
        if (old != null) {
            val target = db.user().get(to) ?: UserStateEntity(to)
            db.user().upsert(target.copy(
                bookmarked = target.bookmarked || old.bookmarked,
                bookmarkedAt = target.bookmarkedAt ?: old.bookmarkedAt,
                read = target.read && old.read,
                note = listOfNotNull(target.note, old.note).joinToString("\n").ifBlank { null },
            ))
            db.user().delete(from)
        }
        db.user().moveTags(from, to)
        db.user().deleteTagsFor(from)
    }

    // ---------------------------------------------------------------- queries

    fun feed(limit: Int) = db.items().observeFeed("ET", limit).map { rows -> rows.map { it.toCard() } }
    fun international(limit: Int) = db.items().observeFeed("INTL", limit).map { rows -> rows.map { it.toCard() } }
    fun unreadCount(): Flow<Int> = db.items().observeUnreadCount()

    fun observeItem(id: String): Flow<LegalItem?> = db.items().observe(id).map { it?.toDomain() }
    fun observeUserState(id: String) = db.user().observe(id)
    fun observeTagsFor(id: String) = db.user().observeTagsFor(id)
    fun observeTags() = db.user().observeTagCounts()

    fun archive(filter: ArchiveFilter, limit: Int = 500): Flow<List<ItemCardModel>> {
        val (sql, args) = buildArchiveQuery(filter, limit, LocalDate.now())
        return db.items().observeRows(SimpleSQLiteQuery(sql, args.toTypedArray())).map { rows -> rows.map { it.toCard() } }
    }

    suspend fun itemsInRange(start: LocalDate, end: LocalDate): List<LegalItem> =
        db.items().ethiopianInRange(start.toEpochDay(), end.toEpochDay()).map { it.toDomain() }

    // ---------------------------------------------------------------- user actions

    suspend fun setRead(id: String, read: Boolean) {
        val s = db.user().get(id) ?: UserStateEntity(id)
        db.user().upsert(s.copy(read = read))
    }

    suspend fun setBookmarked(id: String, bookmarked: Boolean) {
        val s = db.user().get(id) ?: UserStateEntity(id)
        db.user().upsert(s.copy(bookmarked = bookmarked, bookmarkedAt = if (bookmarked) System.currentTimeMillis() else null))
    }

    suspend fun setNote(id: String, note: String?) {
        val s = db.user().get(id) ?: UserStateEntity(id)
        db.user().upsert(s.copy(note = note?.takeIf { it.isNotBlank() }))
    }

    suspend fun markAllRead() {
        db.user().insertReadForAll()
        db.user().markAllRead()
    }

    suspend fun addTag(itemId: String, rawName: String) {
        val name = rawName.trim().take(40)
        if (name.isEmpty()) return
        db.withTransaction {
            val id = db.user().tagByName(name)?.id ?: db.user().insertTag(TagEntity(name = name))
            db.user().addTag(ItemTagEntity(itemId, id))
        }
    }

    suspend fun removeTag(itemId: String, tagId: Long) {
        db.user().removeTag(itemId, tagId)
        db.user().pruneTags()
    }

    /** Wipes the local archive (e.g. after switching servers). Must run off the main thread. */
    suspend fun resetArchive() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        db.clearAllTables()
    }
}
