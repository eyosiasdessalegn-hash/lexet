package app.lexet.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class SourceDto(
    val publisher: String,
    val name: String,
    val tier: Int,
    val url: String,
    val title: String = "",
    val lang: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val counted: Boolean = false,
    val reason: String = "",
    @SerialName("primary_document") val primaryDocument: Boolean = false,
    val gazette: Boolean = false,
    val excerpt: String? = null,
)

@Serializable
data class ItemDto(
    val id: String,
    val version: Long,
    val deleted: Boolean = false,
    @SerialName("merged_into") val mergedInto: String? = null,
    val jurisdiction: String = "ET",
    @SerialName("doc_type") val docType: String = "other",
    @SerialName("law_id") val lawId: String? = null,
    @SerialName("law_number") val lawNumber: Int? = null,
    @SerialName("law_year_gc") val lawYearGc: Int? = null,
    @SerialName("law_year_ec") val lawYearEc: Int? = null,
    @SerialName("title_en") val titleEn: String? = null,
    @SerialName("title_am") val titleAm: String? = null,
    @SerialName("summary_en") val summaryEn: String? = null,
    @SerialName("summary_am") val summaryAm: String? = null,
    val issuer: String? = null,
    val initiator: String? = null,
    val status: String? = null,
    @SerialName("verified_status") val verifiedStatus: String? = null,
    @SerialName("reported_status") val reportedStatus: String? = null,
    val urgency: String? = null,
    val verification: String = "unconfirmed",
    @SerialName("source_count") val sourceCount: Int = 0,
    @SerialName("has_official_source") val hasOfficialSource: Boolean = false,
    val topics: List<String> = emptyList(),
    val amends: String? = null,
    val references: List<String> = emptyList(),
    @SerialName("event_date") val eventDate: String? = null,
    @SerialName("status_date") val statusDate: String? = null,
    @SerialName("first_seen_at") val firstSeenAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("verified_at") val verifiedAt: String? = null,
    val relevance: Double = 0.0,
    val sources: List<SourceDto> = emptyList(),
)

@Serializable
data class SyncPage(
    @SerialName("server_time") val serverTime: String? = null,
    @SerialName("next_since") val nextSince: Long,
    @SerialName("has_more") val hasMore: Boolean = false,
    val items: List<ItemDto> = emptyList(),
)

@Serializable
data class SourceInfoDto(
    val id: String,
    val name: String,
    val publisher: String,
    val tier: Int,
    val jurisdiction: String,
    val lang: String = "",
    val kind: String = "",
    val enabled: Boolean = true,
    @SerialName("robots_state") val robotsState: String = "unknown",
    @SerialName("last_success_at") val lastSuccessAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
)

/** feed/index.json — see backend/lexet/static_feed.py for the layout. */
@Serializable
data class FeedFile(val file: String, val count: Int = 0)

@Serializable
data class FeedSegment(val k: Int, val from: Long, val to: Long, val file: String, val count: Int = 0)

@Serializable
data class FeedIndex(
    val format: String,
    val epoch: Long,
    val version: Long,
    @SerialName("generated_at") val generatedAt: String? = null,
    val snapshot: FeedFile,
    val segments: List<FeedSegment> = emptyList(),
    val sources: String = "sources.json",
)

/** The address answers, but not with a Aristophanes feed this app can read. */
class FeedUnavailableException(message: String) : IOException(message)

val LexetJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * Thin client for the Aristophanes feed. The feed is a handful of JSON files — published
 * by a scheduled job to any static host (e.g. GitHub Pages) or served by the Aristophanes
 * server — so the app needs no account, device ID or cookies: it only downloads files.
 */
class LexetApi(
    private val client: OkHttpClient,
    private val baseUrl: suspend () -> String,
    private val json: Json = LexetJson,
) {
    private suspend fun url(path: String): String = baseUrl().trim().trimEnd('/') + path

    private suspend fun get(path: String, revalidate: Boolean = false, isIndex: Boolean = false): String {
        val target = url(path)
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(target).header("Accept", "application/json")
                .apply { if (revalidate) cacheControl(CacheControl.FORCE_NETWORK) }
                .build()
            client.newCall(request).execute().use { r ->
                when {
                    r.code == 404 && isIndex ->
                        throw FeedUnavailableException("No Aristophanes feed at ${target.substringBeforeLast("/feed/")}/ (HTTP 404)")
                    r.code == 404 -> throw IOException("The feed is being updated; trying again shortly")
                    !r.isSuccessful -> throw IOException("HTTP ${r.code} from $target")
                    else -> r.body?.string() ?: throw IOException("Empty response from $target")
                }
            }
        }
    }

    /** Small and always revalidated, so a new publication is seen at once. */
    suspend fun feedIndex(): FeedIndex {
        val index = json.decodeFromString(FeedIndex.serializer(), get("/feed/index.json", revalidate = true, isIndex = true))
        if (!index.format.startsWith("lexet-feed-1")) throw FeedUnavailableException("Feed format ${index.format} needs a newer app")
        return index
    }

    /** A snapshot or segment. Names carry a content hash, so cached copies are always right. */
    suspend fun feedPage(file: String): SyncPage =
        json.decodeFromString(SyncPage.serializer(), get("/feed/$file"))

    suspend fun sources(): List<SourceInfoDto> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SourceInfoDto.serializer()),
            get("/feed/sources.json", revalidate = true))
}
