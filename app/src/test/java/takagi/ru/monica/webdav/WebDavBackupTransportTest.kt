package takagi.ru.monica.webdav

import android.app.Application
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.transfer.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebDavBackupTransportTest {
    private val server = MockWebServer()
    private val file = File.createTempFile("backup-transport-", ".zip")
    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()
    private var previouslyAllowed = false
    @Before fun prepare() {
        previouslyAllowed = takagi.ru.monica.utils.WebDavHelper.isInsecureHttpAllowed(context)
        WebDavGateway.attach(context)
        takagi.ru.monica.utils.WebDavHelper.setInsecureHttpAllowed(context, true)
        WebDavBackoffState.resetForTest(); server.start()
    }
    @After fun cleanup() { takagi.ru.monica.utils.WebDavHelper.setInsecureHttpAllowed(context, previouslyAllowed); server.shutdown(); file.delete(); WebDavBackoffState.resetForTest() }
    private fun transport() = WebDavBackupTransport(WebDavGateway.buildHttpClient(WebDavCredentials("synthetic", "test")))

    @Test fun archiveUploadOutlivesMetadataDeadlineAndStreamsExactBytes() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setHeadersDelay(16, TimeUnit.SECONDS))
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        file.writeBytes(bytes)
        val metadata = WebDavGateway.buildHttpClient(WebDavCredentials("synthetic", "test"))
        assertEquals(15_000, metadata.callTimeoutMillis)
        val phases = mutableListOf<TransferProgress>()
        withTimeout(25_000) { transport().upload(server.url("/backup.zip").toString(), file, TransferProgressReporter { phases += it }) }
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("*", request.getHeader("If-None-Match"))
        assertArrayEquals(bytes, request.body.readByteArray())
        assertEquals(bytes.size.toLong(), phases.last().completed)
        assertEquals(1f, phases.last().fraction)
        assertEquals(1, server.requestCount)
    }

    @Test fun truncatedDownloadPreservesPreviousCompleteFile() = runBlocking {
        file.writeText("previous complete archive")
        server.enqueue(MockResponse().setBody("x".repeat(100_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val result = runCatching { transport().download(server.url("/broken.zip").toString(), file, TransferProgressReporter.None) }
        assertTrue(result.isFailure)
        assertEquals("previous complete archive", file.readText())
    }

    @Test fun cancellationStopsTheActualHttpCallWithoutRetrying() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        file.writeBytes(ByteArray(1024))
        val transport = transport()
        val job = launch(Dispatchers.IO) { transport.upload(server.url("/cancel.zip").toString(), file, TransferProgressReporter.None) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        withTimeout(3_000) { job.cancelAndJoin() }
        withTimeout(3_000) { while (transport.client.dispatcher.runningCallsCount() > 0) delay(10) }
        assertEquals(1, server.requestCount)
    }

    @Test fun rateLimitRemainsSharedAndPreventsAnotherUpload() = runBlocking {
        file.writeText("archive")
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "60"))
        val first = runCatching { transport().upload(server.url("/one.zip").toString(), file, TransferProgressReporter.None) }
        assertEquals(WebDavErrorKind.RateLimited, WebDavErrorClassifier.classify(first.exceptionOrNull()).kind)
        val second = runCatching { transport().upload(server.url("/two.zip").toString(), file, TransferProgressReporter.None) }
        assertEquals(WebDavErrorKind.RateLimited, WebDavErrorClassifier.classify(second.exceptionOrNull()).kind)
        assertEquals(1, server.requestCount)
    }
}
