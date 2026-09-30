package takagi.ru.monica.webdav

import android.app.Application
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebDavConcurrentBackoffTest {
    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()
    private var previouslyAllowedHttp = false

    @Before fun prepare() {
        WebDavBackoffState.resetForTest()
        previouslyAllowedHttp = takagi.ru.monica.utils.WebDavHelper.isInsecureHttpAllowed(context)
        WebDavGateway.attach(context)
        // Only this Robolectric sandbox uses loopback HTTP, never a user's cloud account.
        takagi.ru.monica.utils.WebDavHelper.setInsecureHttpAllowed(context, true)
    }
    @After fun cleanup() {
        WebDavBackoffState.resetForTest()
        takagi.ru.monica.utils.WebDavHelper.setInsecureHttpAllowed(context, previouslyAllowedHttp)
    }

    @Test
    fun successPreservesTemporaryDisableAndResetsAfterExpiry() {
        val host = "example.invalid"
        val now = 10_000L
        repeat(3) { WebDavBackoffState.recordRateLimit(host, now = now) }
        WebDavBackoffState.recordSuccess(host, now + 10_000L)
        assertTrue(WebDavBackoffState.isTemporarilyDisabled(host, now + 10_000L))
        assertEquals(290_000L, WebDavBackoffState.suggestedWaitMillis(host, now + 10_000L))

        val recovered = now + WebDavBackoffState.DISABLE_DURATION_MS
        assertFalse(WebDavBackoffState.shouldBlock(host, recovered))
        WebDavBackoffState.recordSuccess(host, recovered)
        WebDavBackoffState.recordRateLimit(host, now = recovered)
        assertEquals("A recovered host starts at the first retry interval", 1_000L,
            WebDavBackoffState.suggestedWaitMillis(host, recovered))
        assertFalse(WebDavBackoffState.isTemporarilyDisabled(host, recovered))
    }

    @Test
    fun retryAfterCanOutlastTheTemporaryDisable() {
        val host = "example.invalid"
        val now = 10_000L
        repeat(3) { WebDavBackoffState.recordRateLimit(host, 600_000L, now) }
        WebDavBackoffState.recordSuccess(host, now + 300_000L)
        assertEquals(300_000L, WebDavBackoffState.suggestedWaitMillis(host, now + 300_000L))
        assertFalse(WebDavBackoffState.shouldBlock("other.invalid", now))
        WebDavBackoffState.recordSuccess(host, now + 600_000L)
        assertFalse(WebDavBackoffState.shouldBlock(host, now + 600_000L))
    }

    @Test
    fun inFlightSuccessCannotClearAnotherClientsRetryAfter() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/sync/in-flight") {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(200)
                }
                return MockResponse().setResponseCode(503).setHeader("Retry-After", "60")
            }
        }
        server.start()
        // MDBX sync and full backup create separate clients, sharing the host cooldown.
        val syncClient = WebDavGateway.buildHttpClient(WebDavCredentials("test", "test"))
        val backupClient = WebDavGateway.buildHttpClient(WebDavCredentials("test", "test"))
        try {
            val inFlight = executor.submit<Int> {
                syncClient.newCall(Request.Builder().url(server.url("/sync/in-flight")).build())
                    .execute().use { it.code }
            }
            if (!started.await(5, TimeUnit.SECONDS)) {
                // Surface a transport failure instead of hiding it behind a latch timeout.
                inFlight.get(1, TimeUnit.SECONDS)
                error("The held request never reached the local server")
            }
            backupClient.newCall(Request.Builder().url(server.url("/backups")).build())
                .execute().use { assertEquals(503, it.code) }
            assertTrue(WebDavBackoffState.shouldBlock(server.hostName))
            release.countDown()
            assertEquals(200, inFlight.get(5, TimeUnit.SECONDS))
            assertTrue("An older successful request must not cancel Retry-After",
                WebDavBackoffState.shouldBlock(server.hostName))
            val failure = runCatching {
                backupClient.newCall(Request.Builder().url(server.url("/backups/retry")).build())
                    .execute().close()
            }.exceptionOrNull()
            assertTrue(failure is RateLimitedIOException)
            assertEquals("Retry must be blocked before reaching the server", 2, server.requestCount)
        } finally {
            release.countDown()
            executor.shutdownNow()
            syncClient.connectionPool.evictAll()
            backupClient.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
