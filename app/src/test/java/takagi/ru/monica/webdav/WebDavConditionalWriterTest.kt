package takagi.ru.monica.webdav

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.utils.MdbxRemoteWriteMode

class WebDavConditionalWriterTest {
    @Test fun transient503RetainsRetryAfterAndConditionalHeaderOnRetry() = kotlinx.coroutines.runBlocking {
        val server = MockWebServer().apply { start() }
        val source = tempFile("immutable retry payload")
        val waits = mutableListOf<Long>()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "2"))
            server.enqueue(MockResponse().setResponseCode(201))
            val writer = WebDavConditionalWriter(OkHttpClient())
            MdbxWebDavRetry("fixture", { 0L }, { waits += it }).run {
                writer.write(server.url("/segment").toString(), source, MdbxRemoteWriteMode.CREATE_ONLY, null)
            }
            assertEquals(listOf(2000L), waits)
            repeat(2) {
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("*", request.getHeader("If-None-Match"))
                assertArrayEquals(source.readBytes(), request.body.readByteArray())
            }
        } finally { source.delete(); server.shutdown() }
    }
    @Test
    fun createOnlyUsesAtomicIfNoneMatchAndStreamsFile() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.start()
        val source = tempFile("immutable payload")
        try {
            WebDavConditionalWriter(OkHttpClient()).write(
                targetUrl = server.url("/dav/object").toString(),
                source = source,
                mode = MdbxRemoteWriteMode.CREATE_ONLY,
                expectedVersion = null
            )
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("PUT", request.method)
            assertEquals("*", request.getHeader("If-None-Match"))
            assertArrayEquals(source.readBytes(), request.body.readByteArray())
        } finally {
            source.delete()
            server.shutdown()
        }
    }

    @Test
    fun conditionalReplacementUsesExactEtag() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        val source = tempFile("replacement")
        try {
            WebDavConditionalWriter(OkHttpClient()).write(
                targetUrl = server.url("/dav/object").toString(),
                source = source,
                mode = MdbxRemoteWriteMode.IF_MATCH,
                expectedVersion = "\"etag-7\""
            )
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("\"etag-7\"", request.getHeader("If-Match"))
        } finally {
            source.delete()
            server.shutdown()
        }
    }

    @Test
    fun preconditionFailureIsNotDowngradedToSuccess() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(412))
        server.start()
        val source = tempFile("collision")
        try {
            val error = runCatching {
                WebDavConditionalWriter(OkHttpClient()).write(
                    targetUrl = server.url("/dav/object").toString(),
                    source = source,
                    mode = MdbxRemoteWriteMode.CREATE_ONLY,
                    expectedVersion = null
                )
            }.exceptionOrNull()
            assertTrue(error is WebDavPreconditionException)
            assertEquals(412, (error as WebDavPreconditionException).statusCode)
        } finally {
            source.delete()
            server.shutdown()
        }
    }

    @Test
    fun ifMatchRequiresARealProviderToken() {
        val source = tempFile("replacement")
        try {
            assertTrue(
                runCatching {
                    WebDavConditionalWriter(OkHttpClient()).write(
                        targetUrl = "http://127.0.0.1/unused",
                        source = source,
                        mode = MdbxRemoteWriteMode.IF_MATCH,
                        expectedVersion = null
                    )
                }.exceptionOrNull() is IllegalArgumentException
            )
        } finally {
            source.delete()
        }
    }

    private fun tempFile(content: String): File =
        File.createTempFile("webdav-conditional-", ".bin").apply { writeText(content) }
}
