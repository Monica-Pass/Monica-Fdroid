package takagi.ru.monica.keepass

import java.util.Date

/** Source metadata wins; missing source dates never become the time of a refresh. */
internal fun resolveKeePassEntryDates(created: Long?, updated: Long?, oldCreated: Date? = null, oldUpdated: Date? = null): Pair<Date, Date> {
    val creation = created?.takeIf { it > 0 } ?: oldCreated?.time?.takeIf { it > 0 } ?: 0L
    val modification = updated?.takeIf { it > 0 } ?: oldUpdated?.time?.takeIf { it > 0 } ?: creation
    return Date(creation) to Date(modification)
}
