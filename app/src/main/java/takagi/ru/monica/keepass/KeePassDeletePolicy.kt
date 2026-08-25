package takagi.ru.monica.keepass

internal object KeePassDeletePolicy {
    fun allowPermanentFallback(useRecycleBin: Boolean): Boolean = !useRecycleBin
}
