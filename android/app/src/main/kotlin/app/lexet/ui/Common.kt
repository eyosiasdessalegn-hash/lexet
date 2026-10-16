package app.lexet.ui

import app.lexet.domain.clip.ClipParser
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lexet.AppContainer
import app.lexet.LexetApp
import app.lexet.R
import app.lexet.data.DateDisplay
import app.lexet.data.ItemCardModel
import app.lexet.domain.model.Jurisdiction
import app.lexet.domain.model.LegalStatus
import app.lexet.domain.model.Taxonomy
import app.lexet.domain.model.Urgency
import app.lexet.domain.model.Verification
import app.lexet.domain.text.EthiopianCalendar
import app.lexet.ui.theme.LocalLexColors
import java.time.LocalDate

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as LexetApp).container

/** True when the app UI is shown in Amharic (system or per-app language). */
@Composable
fun isAmharicUi(): Boolean = LocalConfiguration.current.locales[0].language == "am"

fun formatDate(date: LocalDate?, display: DateDisplay, amharic: Boolean): String {
    if (date == null) return ""
    val gc = EthiopianCalendar.formatGregorian(date, amharic)
    val ec = EthiopianCalendar.format(date, amharic)
    return when (display) {
        DateDisplay.GREGORIAN -> gc
        DateDisplay.ETHIOPIAN -> ec
        DateDisplay.BOTH -> "$gc · $ec"
    }
}

fun Taxonomy?.statusLabel(status: LegalStatus?, amharic: Boolean): String =
    status?.let { this?.status(it.code)?.text(amharic) ?: it.code } ?: ""

fun Taxonomy?.issuerLabel(code: String?, amharic: Boolean): String? =
    code?.let { this?.issuer(it)?.text(amharic) ?: it }

fun Taxonomy?.topicLabel(code: String, amharic: Boolean): String = this?.topic(code)?.text(amharic) ?: code

fun Taxonomy?.docLabel(code: String, amharic: Boolean): String = this?.docType(code)?.text(amharic) ?: code

@Composable
fun urgencyColor(u: Urgency?): Color {
    val c = LocalLexColors.current
    return when (u) {
        Urgency.IMMINENT -> c.imminent
        Urgency.IN_FORCE -> c.inForce
        Urgency.ENDED -> c.ended
        else -> c.upcoming
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun StatusPill(status: LegalStatus?, taxonomy: Taxonomy?, amharic: Boolean) {
    if (status == null) return
    Pill(taxonomy.statusLabel(status, amharic), urgencyColor(status.urgency))
}

@Composable
fun VerificationBadge(verification: Verification, sources: Int, official: Boolean, compact: Boolean = false) {
    val c = LocalLexColors.current
    val (color, icon, label) = when (verification) {
        Verification.VERIFIED -> Triple(c.verified, Icons.Filled.CheckCircle, stringResource(R.string.badge_verified))
        Verification.UNCONFIRMED -> Triple(c.unconfirmed, Icons.Filled.Info, stringResource(R.string.badge_unconfirmed))
        Verification.REPORTED -> Triple(c.upcoming, Icons.Filled.Info, stringResource(R.string.badge_international))
    }
    val count = pluralStringResource(R.plurals.source_count, sources, sources)
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label, $count" }) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (compact) label else "$label · $count", style = MaterialTheme.typography.labelSmall, color = color)
        if (official && !compact) {
            Spacer(Modifier.width(6.dp))
            Pill(stringResource(R.string.badge_official), c.official)
        }
    }
}

@Composable
fun ItemCard(
    item: ItemCardModel,
    amharicFirst: Boolean,
    labelsAmharic: Boolean,
    dateDisplay: DateDisplay,
    taxonomy: Taxonomy?,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.jurisdiction == Jurisdiction.INTL)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!item.read) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                        .semantics { contentDescription = "unread" })
                    Spacer(Modifier.width(6.dp))
                }
                if (ClipParser.isClip(item.id)) {
                    Pill(clipPlatform(item.id), LocalLexColors.current.official)
                    Spacer(Modifier.width(6.dp))
                } else if (item.jurisdiction == Jurisdiction.INTL) {
                    Pill(stringResource(R.string.label_international), LocalLexColors.current.upcoming)
                    Spacer(Modifier.width(6.dp))
                } else {
                    StatusPill(item.verifiedStatus ?: item.status, taxonomy, labelsAmharic)
                    Spacer(Modifier.width(6.dp))
                }
                Text(formatDate(item.date, dateDisplay, labelsAmharic), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleBookmark) {
                    Icon(if (item.bookmarked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(if (item.bookmarked) R.string.action_remove_bookmark else R.string.action_bookmark),
                        tint = if (item.bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val lawId = if (amharicFirst) item.lawIdAm ?: item.lawId else item.lawId ?: item.lawIdAm
            Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                lawId?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(item.title(amharicFirst), style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (item.read) FontWeight.Medium else FontWeight.SemiBold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                item.secondaryTitle(amharicFirst)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    if (ClipParser.isClip(item.id)) {
                        Text(stringResource(R.string.clip_saved_badge, clipPlatform(item.id)), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (item.jurisdiction == Jurisdiction.ET) {
                        VerificationBadge(item.verification, item.sourceCount, item.hasOfficialSource, compact = true)
                        Spacer(Modifier.width(8.dp))
                    }
                    taxonomy.issuerLabel(item.issuer, labelsAmharic)?.takeIf { item.jurisdiction == Jurisdiction.ET }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Platform a saved post came from, read from its id ("clip-linkedin-…"). */
fun clipPlatform(id: String): String = when (val slug = id.removePrefix(ClipParser.ID_PREFIX).substringBefore('-')) {
    "linkedin" -> "LinkedIn"
    "telegram" -> "Telegram"
    "facebook" -> "Facebook"
    "x" -> "X"
    "sharedtext", "" -> "Shared"
    else -> slug
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null) {
            Spacer(Modifier.size(16.dp))
            androidx.compose.material3.OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 18.dp, bottom = 6.dp))
}
