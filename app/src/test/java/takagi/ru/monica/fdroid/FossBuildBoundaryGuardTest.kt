package takagi.ru.monica.fdroid

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FossBuildBoundaryGuardTest {
    private val root: File by lazy {
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle").isFile }
    }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `proprietary service bridges stay outside the fdroid build`() {
        val gradle = source("app/build.gradle")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val manifest = source("app/src/main/AndroidManifest.xml")
        val forbidden = listOf(
            "com.google.android.gms:play-services-auth",
            "com.google.mlkit:barcode-scanning",
            "credentials-play-services-auth",
            "androidx.credentials.providerevents",
            "com.microsoft.identity.client:msal",
            "com.google.firebase",
            "com.microsoft.appcenter",
        )

        forbidden.forEach { coordinate -> assertFalse(coordinate, gradle.contains(coordinate)) }
        assertFalse(manifest.contains("com.microsoft.identity.client.BrowserTabActivity"))
        assertFalse(manifest.contains("androidx.identitycredentials.action.IMPORT_CREDENTIALS"))
        assertTrue(source("app/src/main/java/takagi/ru/monica/ui/scanner/QrCameraScanSession.kt")
            .contains("ZxingBarcodeDecoder(allowedFormats)"))
        assertTrue(source("app/src/main/java/takagi/ru/monica/utils/GoogleDriveAuthManager.kt")
            .contains("GoogleDriveNotSupportedException"))
        assertTrue(source("app/src/main/java/takagi/ru/monica/utils/OneDriveAuthManager.kt")
            .contains("OneDriveNotSupportedException"))
    }

    @Test
    fun `native runtimes are built from vendored source`() {
        val appGradle = source("app/build.gradle")
        val mdbxGradle = source("mdbx-engine/build.gradle")

        assertTrue(appGradle.contains("buildMonicaRustJniFromSource"))
        assertTrue(appGradle.contains("rust-jni"))
        assertTrue(File(root, "rust-jni/Cargo.lock").isFile)
        assertTrue(mdbxGradle.contains("90005c8c608c952093a4522ffa507a562e2e39a4"))
        assertTrue(mdbxGradle.contains("'--profile', 'mdbx3-release'"))
        assertFalse(File(root, "app/src/main/jniLibs").exists())
        assertFalse(File(root, "mdbx-engine/src/main/jniLibs").exists())
    }
}
