package takagi.ru.monica.localization

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.keepass.KeePassSourceChangedException
import takagi.ru.monica.keepass.KeePassSourceSafety
import takagi.ru.monica.sync.SyncErrorKind
import takagi.ru.monica.sync.classifySyncFailure
import takagi.ru.monica.utils.GoogleDriveKeePassFileSource
import takagi.ru.monica.utils.KeePassCredentialSupport
import takagi.ru.monica.utils.OneDriveKeePassFileSource
import takagi.ru.monica.utils.WebDavKeePassFileSource

class LocalizedCloudFileMessagesTest {
    private val locales = listOf("en", "zh", "lzh", "vi", "ja", "ru", "ko", "de", "es", "fr", "pl")

    @Test fun modifiedSourceRemainsAConflictInEveryLanguageEvenWhenWrapped() {
        locales.forEach { locale ->
            val strings = xmlTestStrings(locale)
            val error = runCatching {
                KeePassSourceSafety.requireUnchanged(
                    KeePassSourceSafety.revisionOf("original".toByteArray()),
                    "changed".toByteArray(), "sample.kdbx", strings
                )
            }.exceptionOrNull()!!
            assertTrue(error is KeePassSourceChangedException)
            assertEquals(strings.get(R.string.cloud_message_local_source_changed, "sample.kdbx"), error.message)
            assertEquals(SyncErrorKind.CONFLICT, classifySyncFailure(IOException("Wrapped", error)).kind)
            assertFalse(classifySyncFailure(error).retryable)
        }
    }

    @Test fun remotePathValidationUsesTheChosenLanguageAndPreservesNames() {
        locales.forEach { locale ->
            val strings = xmlTestStrings(locale)
            val failures = listOf(
                runCatching { WebDavKeePassFileSource.buildChildPath("root", "a/b", strings) },
                runCatching { OneDriveKeePassFileSource.buildChildPath("root", "a/b", strings) },
                runCatching { GoogleDriveKeePassFileSource.buildChildPath("root", "a/b", strings) }
            )
            failures.forEach {
                assertEquals(strings.get(R.string.cloud_message_filename_separator), it.exceptionOrNull()?.message)
            }
            assertEquals("root/私人 vault.kdbx", WebDavKeePassFileSource.buildChildPath("root", "私人 vault.kdbx", strings))
        }
    }

    @Test fun credentialDiagnosticsPreserveTechnicalLabelsAndAttemptCounts() {
        val labels = listOf("raw/password+key", "xml-data/password+key", "hex-text/password+key", "sha256(raw)/key-only", "raw/key-only")
        locales.forEach { locale ->
            val strings = xmlTestStrings(locale)
            val message = KeePassCredentialSupport.buildInvalidCredentialMessage(labels + labels, strings)
            assertEquals(strings.get(R.string.cloud_message_credentials_tried_more, labels.take(4).joinToString(", "), 5), message)
            assertEquals(strings.get(R.string.keepass_error_credentials), KeePassCredentialSupport.buildInvalidCredentialMessage(emptyList(), strings))
        }
    }
}
