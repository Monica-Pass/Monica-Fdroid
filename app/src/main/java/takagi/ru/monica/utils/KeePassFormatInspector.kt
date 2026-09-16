package takagi.ru.monica.utils

import takagi.ru.monica.R

import java.util.Locale

enum class KeePassContainerFormat {
    KDBX,
    KDB_LEGACY,
    UNKNOWN
}

object KeePassFormatInspector {
    private val KDBX_SIGNATURE = byteArrayOf(
        0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
        0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte()
    )
    private val KDB_LEGACY_SIGNATURES = listOf(
        byteArrayOf(
            0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
            0x65, 0xFB.toByte(), 0x4B, 0xB5.toByte()
        ),
        byteArrayOf(
            0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
            0x66, 0xFB.toByte(), 0x4B, 0xB5.toByte()
        )
    )

    fun detect(bytes: ByteArray, sourceName: String? = null): KeePassContainerFormat {
        if (matchesSignature(bytes, KDBX_SIGNATURE)) {
            return KeePassContainerFormat.KDBX
        }
        if (KDB_LEGACY_SIGNATURES.any { matchesSignature(bytes, it) }) {
            return KeePassContainerFormat.KDB_LEGACY
        }
        if (sourceName.isLikelyLegacyKdbExtension()) {
            return KeePassContainerFormat.KDB_LEGACY
        }
        return KeePassContainerFormat.UNKNOWN
    }

    internal fun ensureKdbxSupported(bytes: ByteArray, sourceName: String? = null, strings: StringResolver) {
        val format = detect(bytes = bytes, sourceName = sourceName)
        if (format == KeePassContainerFormat.KDB_LEGACY) {
            throw KeePassOperationException(
                code = KeePassErrorCode.LEGACY_KDB_UNSUPPORTED,
                message = strings.get(R.string.keepass_error_legacy)
            )
        }
    }

    internal fun ensureKdbxSupportedHeader(header: ByteArray, sourceName: String? = null, strings: StringResolver) {
        ensureKdbxSupported(header, sourceName, strings = strings)
    }

    private fun matchesSignature(bytes: ByteArray, signature: ByteArray): Boolean {
        if (bytes.size < signature.size) return false
        for (index in signature.indices) {
            if (bytes[index] != signature[index]) {
                return false
            }
        }
        return true
    }

    private fun String?.isLikelyLegacyKdbExtension(): Boolean {
        val lower = this?.lowercase(Locale.ROOT) ?: return false
        return lower.endsWith(".kdb") && !lower.endsWith(".kdbx")
    }
}

