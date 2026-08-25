package takagi.ru.monica.utils

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneDriveMdbxProviderTest {
    @Test
    fun createOnlyUsesGraphConflictFailAndBearerToken() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(driveItemJson("etag-created", 16))
        )
        server.start()
        val cacheDirectory = tempDirectory()
        val sourceFile = tempFile("one-drive-create")
        try {
            val result = source(server, cacheDirectory).writeFrom(
                sourceFile,
                MdbxRemoteWriteMode.CREATE_ONLY
            )
            assertEquals("etag-created", result.versionToken)
            val metadata = server.takeRequest(5, TimeUnit.SECONDS)!!
            val upload = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", metadata.method)
            assertEquals("PUT", upload.method)
            assertEquals(
                "fail",
                upload.requestUrl?.queryParameter("@microsoft.graph.conflictBehavior")
            )
            assertEquals("Bearer test-token", upload.getHeader("Authorization"))
            assertArrayEquals(sourceFile.readBytes(), upload.body.readByteArray())
        } finally {
            sourceFile.delete()
            cacheDirectory.deleteRecursively()
            server.shutdown()
        }
    }

    @Test
    fun ifMatchUsesExactEtagAndRejectsStaleMetadata() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(driveItemJson("etag-current", 7)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(driveItemJson("etag-next", 7)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(driveItemJson("etag-current", 7)))
        server.start()
        val cacheDirectory = tempDirectory()
        val sourceFile = tempFile("replace")
        try {
            val source = source(server, cacheDirectory)
            source.writeFrom(
                sourceFile,
                MdbxRemoteWriteMode.IF_MATCH,
                expectedVersion = "etag-current"
            )
            server.takeRequest(5, TimeUnit.SECONDS)!!
            val upload = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("etag-current", upload.getHeader("If-Match"))

            val stale = runCatching {
                source.writeFrom(
                    sourceFile,
                    MdbxRemoteWriteMode.IF_MATCH,
                    expectedVersion = "etag-stale"
                )
            }.exceptionOrNull()
            assertTrue(stale is java.io.IOException)
            val staleMetadata = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", staleMetadata.method)
            assertEquals(3, server.requestCount)
        } finally {
            sourceFile.delete()
            cacheDirectory.deleteRecursively()
            server.shutdown()
        }
    }

    @Test
    fun existingIdenticalImmutableObjectIsIdempotent() = runBlocking {
        val bytes = "same-object".toByteArray()
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(driveItemJson("etag-same", bytes.size.toLong()))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(bytes))
        )
        server.start()
        val cacheDirectory = tempDirectory()
        val sourceFile = File.createTempFile("onedrive-identical-", ".bin").apply {
            writeBytes(bytes)
        }
        try {
            val result = source(server, cacheDirectory).writeFrom(
                sourceFile,
                MdbxRemoteWriteMode.CREATE_ONLY
            )
            assertEquals("etag-same", result.versionToken)
            val metadata = server.takeRequest(5, TimeUnit.SECONDS)!!
            val download = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", metadata.method)
            assertEquals("GET", download.method)
            assertTrue(download.path?.endsWith("/content") == true)
            assertEquals(2, server.requestCount)
        } finally {
            sourceFile.delete()
            cacheDirectory.deleteRecursively()
            server.shutdown()
        }
    }

    @Test
    fun non404MetadataFailureIsPropagated() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("denied"))
        server.start()
        val cacheDirectory = tempDirectory()
        try {
            val error = runCatching { source(server, cacheDirectory).stat() }.exceptionOrNull()
            assertTrue(error is OneDriveHttpException)
            assertEquals(401, (error as OneDriveHttpException).statusCode)
        } finally {
            cacheDirectory.deleteRecursively()
            server.shutdown()
        }
    }

    @Test
    fun pathSegmentsUseRfc3986EncodingWithoutAndroidRuntime() {
        assertEquals(
            "%E8%B4%A6%E6%88%B7%20A%21",
            OneDriveKeePassFileSource.encodePathSegment("账户 A!")
        )
    }

    private fun source(
        server: MockWebServer,
        cacheDirectory: File
    ): OneDriveKeePassFileSource = OneDriveKeePassFileSource(
        accountIdentifier = "test-account",
        remotePath = "main.mdbx",
        accessTokenProvider = OneDriveAccessTokenProvider { "test-token" },
        httpClient = OkHttpClient(),
        graphBaseUrl = server.url("/v1.0").toString().trimEnd('/'),
        cacheDirectory = cacheDirectory
    )

    private fun tempFile(content: String): File =
        File.createTempFile("onedrive-provider-", ".bin").apply { writeText(content) }

    private fun tempDirectory(): File =
        kotlin.io.path.createTempDirectory("onedrive-provider-cache-").toFile()

    private fun driveItemJson(etag: String, size: Long): String =
        """{"id":"item-1","name":"main.mdbx","size":$size,"eTag":"$etag","file":{}}"""
}
