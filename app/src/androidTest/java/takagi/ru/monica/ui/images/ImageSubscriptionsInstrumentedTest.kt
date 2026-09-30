package takagi.ru.monica.ui.images

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import takagi.ru.monica.ui.cardwallet.CardFaceImageProcessor
import takagi.ru.monica.ui.cardwallet.CardCropGeometry
import takagi.ru.monica.ui.icons.PasswordCustomIconStore

internal class ImageSourceFixture {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(base.cacheDir, "image-source-test-${UUID.randomUUID()}").apply { mkdirs() }
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "settings").apply { mkdirs() }
    }
    var fail = false
    var feed = """{"name":"Sample pack","icons":[{"name":"Purple card","url":"art.png"}]}"""
    var redirect: String? = null
    var requests = 0
    val png = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray()
    }
    val client = ImageSourceClient(context, OkHttpClient.Builder().addInterceptor { chain ->
        requests++
        val request = chain.request()
        check(request.header("Authorization") == null)
        val image = request.url.encodedPath.endsWith(".png")
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (fail) 503 else if (redirect != null) 302 else 200)
            .message("Fixture").apply { redirect?.let { header("Location", it) } }
            .body((if (image) png else feed.toByteArray()).toResponseBody((if (image) "image/png" else "application/json").toMediaType())).build()
    }.build())
    val store = ImageSubscriptionStore(context)
    fun close() { root.deleteRecursively() }
}

class ImageSubscriptionsInstrumentedTest {
    private lateinit var fixture: ImageSourceFixture
    @Before fun setup() { fixture = ImageSourceFixture() }
    @After fun cleanup() { fixture.close() }

    @Test fun relativeImagePackAndTopLevelArrayAreAccepted() {
        val (name, items) = ImageFeedParser.parse(fixture.feed, "https://example.test/packs/main.json")
        assertEquals("Sample pack", name); assertEquals("https://example.test/packs/art.png", items.single().url)
        assertEquals(1, ImageFeedParser.parse("""["https://example.test/a.png","https://example.test/a.png"]""", "https://example.test/").second.size)
    }
    @Test fun malformedOrUnsafeSourceDoesNotBecomeAnEmptyPack() {
        listOf("{}", "{\"icons\":[]}", "{\"icons\":[{\"url\":\"file:///private.png\"}]}", "<html>Page</html>").forEach {
            assertTrue(runCatching { ImageFeedParser.parse(it, "https://example.test/") }.isFailure)
        }
        listOf("http://example.test/a", "https://user:password@example.test/a", "file:///test").forEach {
            assertTrue(runCatching { ImageFeedParser.url(it) }.isFailure)
        }
    }
    @Test fun failedRefreshKeepsPreviousCatalogByteForByte() = runBlocking {
        val source = fixture.client.load("https://example.test/pack.json", "", ImageSourceKind.ICON)
        fixture.store.save(source)
        val file = File(fixture.context.noBackupFilesDir, "image-subscriptions.json")
        val before = file.readBytes(); fixture.fail = true
        assertTrue(runCatching { fixture.store.save(fixture.client.load(source.url, source.name, source.kind, source.id)) }.isFailure)
        assertArrayEquals(before, file.readBytes()); assertEquals(source, fixture.store.read().single())
    }
    @Test fun corruptedLocalCatalogIsNotOverwritten() = runBlocking {
        val file = File(fixture.context.noBackupFilesDir, "image-subscriptions.json"); file.writeText("broken catalog")
        val source = ImageSubscription(name = "X", url = "https://example.test/x", kind = ImageSourceKind.ICON, images = emptyList())
        assertTrue(runCatching { fixture.store.save(source) }.isFailure)
        assertEquals("broken catalog", file.readText())
    }
    @Test fun unsubscribeDoesNotDeleteImportedIconOrCroppedCard() = runBlocking {
        val source = fixture.client.load("https://example.test/art.png", "Art", ImageSourceKind.CARD)
        fixture.store.save(source)
        val image = fixture.client.image(source.images.single().url)
        val icon = PasswordCustomIconStore.importBitmap(fixture.context, image).getOrThrow()
        val crop = CardFaceImageProcessor.crop(image, CardCropGeometry.centered(image.width, image.height)).getOrThrow()
        val copy = crop.bytes.copyOf()
        fixture.store.remove(source.id)
        assertTrue(fixture.store.read().isEmpty())
        assertNotNull(PasswordCustomIconStore.resolveIconFile(fixture.context, icon))
        assertArrayEquals(copy, crop.bytes)
        assertTrue(crop.preview.width > crop.preview.height)
        image.recycle(); crop.preview.recycle()
    }
    @Test fun selectedImageWorksOfflineFromCache() = runBlocking {
        fixture.client.image("https://example.test/art.png").recycle()
        val count = fixture.requests; fixture.fail = true
        val cached = fixture.client.image("https://example.test/art.png")
        assertEquals(400, cached.width); assertEquals(count, fixture.requests); cached.recycle()
    }
    @Test fun httpDowngradeRedirectIsRejected() = runBlocking {
        fixture.redirect = "http://example.test/unsafe.png"
        assertTrue(runCatching { fixture.client.load("https://example.test/art.png", "", ImageSourceKind.ICON) }.isFailure)
        assertEquals(1, fixture.requests)
    }
    @Test fun duplicateSubscriptionUpdatesOnlyItsOwnCategory() = runBlocking {
        val source = fixture.client.load("https://example.test/pack.json", "", ImageSourceKind.ICON)
        fixture.store.save(source); fixture.store.save(source.copy(id = UUID.randomUUID().toString(), name = "Updated"))
        fixture.store.save(source.copy(id = UUID.randomUUID().toString(), kind = ImageSourceKind.CARD))
        assertEquals(2, fixture.store.read().size)
        assertEquals("Updated", fixture.store.read().first { it.kind == ImageSourceKind.ICON }.name)
    }
    @Test fun brokenImageIsNotCachedAsUsableArtwork() = runBlocking {
        fixture.feed = "not an image"
        assertTrue(runCatching { fixture.client.image("https://example.test/broken") }.isFailure)
        assertTrue(File(fixture.context.cacheDir, "subscribed-images").listFiles().orEmpty().none { it.extension == "image" })
    }
}
