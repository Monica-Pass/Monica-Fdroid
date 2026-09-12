package takagi.ru.monica.data

import kotlinx.serialization.Serializable

/** Local wallet presentation metadata; members keep their own storage and lifecycle. */
@Serializable
data class WalletStack(
    val id: String,
    val memberIds: List<Long>,
    val coverId: Long = memberIds.firstOrNull() ?: 0L
) {
    internal fun withMembers(ids: List<Long>): WalletStack? {
        val members = ids.filter { it > 0 }.distinct()
        if (members.size < 2) return null
        return copy(
            memberIds = members,
            coverId = coverId.takeIf { it in members } ?: members.first()
        )
    }
}
