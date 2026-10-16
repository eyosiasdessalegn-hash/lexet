package app.lexet.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** One legal development, as synced from the server. */
@Entity(
    tableName = "items",
    indices = [Index("jurisdiction", "eventDate"), Index("statusDate"), Index(value = ["ftsDocId"], unique = true)],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val ftsDocId: Long,
    val version: Long,
    val jurisdiction: String,
    val docType: String,
    val lawId: String?,
    val lawNumber: Int?,
    val lawYearGc: Int?,
    val lawYearEc: Int?,
    val titleEn: String?,
    val titleAm: String?,
    val summaryEn: String?,
    val summaryAm: String?,
    val issuer: String?,
    val initiator: String?,
    val status: String?,
    val verifiedStatus: String?,
    val reportedStatus: String?,
    val verification: String,
    val sourceCount: Int,
    val hasOfficialSource: Boolean,
    /** Topic codes as ",a,b," so a topic filter is a simple LIKE. */
    val topics: String,
    val amends: String?,
    /** Epoch days (LocalDate.toEpochDay). */
    val eventDate: Long?,
    val statusDate: Long?,
    /** Epoch milliseconds. */
    val firstSeenAt: Long?,
    val updatedAt: Long?,
    val relevance: Double,
    val sourcesJson: String,
)

/** Lightweight projection for lists (no source excerpts), joined with the user's state. */
data class ItemRow(
    val id: String,
    val jurisdiction: String,
    val docType: String,
    val lawId: String?,
    val lawNumber: Int?,
    val lawYearEc: Int?,
    val titleEn: String?,
    val titleAm: String?,
    val issuer: String?,
    val status: String?,
    val verifiedStatus: String?,
    val verification: String,
    val sourceCount: Int,
    val hasOfficialSource: Boolean,
    val topics: String,
    val eventDate: Long?,
    val statusDate: Long?,
    val read: Boolean,
    val bookmarked: Boolean,
)

/**
 * Full-text index. Columns hold text already normalised by the domain module
 * (EthiopicText.indexText), so the "simple" tokenizer behaves the same on every
 * device and Amharic search does not depend on the phone's SQLite build.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_SIMPLE)
@Entity(tableName = "item_fts")
data class ItemFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val titles: String,
    val summary: String,
    val lawId: String,
    val meta: String,
    val body: String,
)

@Entity(tableName = "user_state")
data class UserStateEntity(
    @PrimaryKey val itemId: String,
    val read: Boolean = false,
    val bookmarked: Boolean = false,
    val bookmarkedAt: Long? = null,
    val note: String? = null,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(tableName = "item_tags", primaryKeys = ["itemId", "tagId"], indices = [Index("tagId")])
data class ItemTagEntity(val itemId: String, val tagId: Long)

data class TagCount(val id: Long, val name: String, val count: Int)

/** Verified developments waiting for the next hourly/daily digest. */
@Entity(tableName = "pending_notices")
data class PendingNoticeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val title: String,
    val lawId: String?,
    val kind: String,
    val statusPhrase: String,
    val createdAt: Long,
)

/** A generated PDF compilation. */
@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey val key: String,
    val kind: String,
    val calendar: String,
    val startDay: Long,
    val endDay: Long,
    val label: String,
    val filePath: String,
    val generatedAt: Long,
    val entryCount: Int,
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: String): ItemEntity?

    @Query("SELECT * FROM items WHERE id = :id")
    fun observe(id: String): Flow<ItemEntity?>

    @Upsert
    suspend fun upsert(item: ItemEntity)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COALESCE(MAX(ftsDocId), 0) + 1 FROM items")
    suspend fun nextDocId(): Long

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int

    /** Everything that came from the feed; the user's own saved posts (id "clip-…") stay. */
    @Query("DELETE FROM items WHERE id NOT LIKE 'clip-%'")
    suspend fun deleteAll()

    @Query("SELECT ftsDocId FROM items WHERE id NOT LIKE 'clip-%'")
    suspend fun feedDocIds(): List<Long>

    @Query(
        """SELECT i.id, i.jurisdiction, i.docType, i.lawId, i.lawNumber, i.lawYearEc, i.titleEn, i.titleAm,
                  i.issuer, i.status, i.verifiedStatus, i.verification, i.sourceCount, i.hasOfficialSource,
                  i.topics, i.eventDate, i.statusDate,
                  COALESCE(u.read, 0) AS read, COALESCE(u.bookmarked, 0) AS bookmarked
           FROM items i LEFT JOIN user_state u ON u.itemId = i.id
           WHERE i.jurisdiction = :jurisdiction
           ORDER BY MAX(COALESCE(i.statusDate, 0), COALESCE(i.eventDate, 0)) DESC, i.updatedAt DESC
           LIMIT :limit"""
    )
    fun observeFeed(jurisdiction: String, limit: Int): Flow<List<ItemRow>>

    @RawQuery(observedEntities = [ItemEntity::class, UserStateEntity::class, ItemTagEntity::class, ItemFtsEntity::class])
    fun observeRows(query: SupportSQLiteQuery): Flow<List<ItemRow>>

    @Query(
        """SELECT * FROM items WHERE jurisdiction = 'ET' AND id NOT LIKE 'clip-%'
           AND ((eventDate BETWEEN :startDay AND :endDay) OR (statusDate BETWEEN :startDay AND :endDay))"""
    )
    suspend fun ethiopianInRange(startDay: Long, endDay: Long): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items i LEFT JOIN user_state u ON u.itemId = i.id WHERE i.jurisdiction = 'ET' AND COALESCE(u.read, 0) = 0")
    fun observeUnreadCount(): Flow<Int>
}

@Dao
interface FtsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: ItemFtsEntity)

    @Query("DELETE FROM item_fts WHERE rowid = :rowId")
    suspend fun delete(rowId: Long)

    @Query("DELETE FROM item_fts")
    suspend fun deleteAll()
}

@Dao
interface UserDao {
    @Query("SELECT * FROM user_state WHERE itemId = :id")
    suspend fun get(id: String): UserStateEntity?

    @Query("SELECT * FROM user_state WHERE itemId = :id")
    fun observe(id: String): Flow<UserStateEntity?>

    @Upsert
    suspend fun upsert(state: UserStateEntity)

    @Query("DELETE FROM user_state WHERE itemId = :id")
    suspend fun delete(id: String)

    @Query("INSERT OR IGNORE INTO user_state (itemId, read, bookmarked) VALUES (:id, 1, 0)")
    suspend fun markReadIfAbsent(id: String)

    @Query("UPDATE user_state SET read = 1")
    suspend fun markAllRead()

    @Query("INSERT OR IGNORE INTO user_state (itemId, read, bookmarked) SELECT id, 1, 0 FROM items WHERE jurisdiction = 'ET'")
    suspend fun insertReadForAll()

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeTags(): Flow<List<TagEntity>>

    @Query(
        """SELECT t.id, t.name, COUNT(it.itemId) AS count FROM tags t
           LEFT JOIN item_tags it ON it.tagId = t.id GROUP BY t.id ORDER BY t.name COLLATE NOCASE"""
    )
    fun observeTagCounts(): Flow<List<TagCount>>

    @Query("SELECT t.* FROM tags t JOIN item_tags it ON it.tagId = t.id WHERE it.itemId = :itemId ORDER BY t.name")
    fun observeTagsFor(itemId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun tagByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTag(link: ItemTagEntity)

    @Query("DELETE FROM item_tags WHERE itemId = :itemId AND tagId = :tagId")
    suspend fun removeTag(itemId: String, tagId: Long)

    @Query("UPDATE OR IGNORE item_tags SET itemId = :to WHERE itemId = :from")
    suspend fun moveTags(from: String, to: String)

    @Query("DELETE FROM item_tags WHERE itemId = :itemId")
    suspend fun deleteTagsFor(itemId: String)

    @Query("DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tagId FROM item_tags)")
    suspend fun pruneTags()
}

@Dao
interface NoticeDao {
    @Insert
    suspend fun insert(n: PendingNoticeEntity)

    @Query("SELECT * FROM pending_notices ORDER BY createdAt")
    suspend fun all(): List<PendingNoticeEntity>

    @Query("DELETE FROM pending_notices WHERE id <= :maxId")
    suspend fun clearUpTo(maxId: Long)

    @Query("DELETE FROM pending_notices WHERE itemId = :itemId")
    suspend fun deleteFor(itemId: String)

    @Query("DELETE FROM pending_notices")
    suspend fun deleteAll()
}

@Dao
interface ReportDao {
    @Upsert
    suspend fun upsert(r: ReportEntity)

    @Query("SELECT * FROM reports WHERE `key` = :key")
    suspend fun get(key: String): ReportEntity?

    @Query("SELECT * FROM reports ORDER BY startDay DESC")
    fun observeAll(): Flow<List<ReportEntity>>

    @Query("DELETE FROM reports WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Database(
    entities = [ItemEntity::class, ItemFtsEntity::class, UserStateEntity::class, TagEntity::class,
        ItemTagEntity::class, PendingNoticeEntity::class, ReportEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LexetDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun fts(): FtsDao
    abstract fun user(): UserDao
    abstract fun notices(): NoticeDao
    abstract fun reports(): ReportDao
}
