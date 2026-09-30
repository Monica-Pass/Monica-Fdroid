package takagi.ru.monica.attachments

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import takagi.ru.monica.data.model.EmbeddedWalletContent.AssetRole
import java.io.IOException

class EmbeddedWalletAssetDraftTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun completeEncryptedCopyCanBeReopenedForEveryTargetAndSurvivesSourceMutation() = runBlocking<Unit> {
        val bytes = ByteArray(1024 * 1024 + 37) { (it % 251).toByte() }
        val expected = bytes.copyOf()
        val draft = EmbeddedWalletAssetDraft.prepare(temp.root, listOf(
            EmbeddedWalletAssetDraft.Source("document.bin", "application/octet-stream", AssetRole.ATTACHMENT,
                expectedSize = bytes.size.toLong()) { it.write(bytes) }))
        bytes.fill(0)
        repeat(2) { assertArrayEquals(expected, draft.open(draft.assets.single().name).use { it.readBytes() }) }
        val disk = temp.root.walkTopDown().filter { it.isFile }.single().readBytes()
        assertFalse(disk.contentEquals(expected))
        assertEquals(expected.size.toLong(), draft.assets.single().size)
        draft.close()
        draft.close()
        assertTrue(temp.root.listFiles()!!.isEmpty())
        assertThrows(IllegalStateException::class.java) { draft.open(draft.assets.single().name) }
    }

    @Test fun failureAfterFirstAssetRollsBackEveryStagedFile() = runBlocking {
        val failure = runCatching { EmbeddedWalletAssetDraft.prepare(temp.root, listOf(
            EmbeddedWalletAssetDraft.Source("first", "image/png", AssetRole.CARD_FACE) { it.write(byteArrayOf(1, 2, 3)) },
            EmbeddedWalletAssetDraft.Source("missing", "application/pdf", AssetRole.ATTACHMENT) {
                it.write(byteArrayOf(4)); throw IOException("Source unavailable")
            })) }.exceptionOrNull()
        assertTrue(failure is EmbeddedWalletAssetDraft.CopyFailed)
        assertEquals("missing", (failure as EmbeddedWalletAssetDraft.CopyFailed).displayName)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun truncationAndHashMismatchNeverProduceSuccessfulDrafts() = runBlocking {
        for (source in listOf(
            EmbeddedWalletAssetDraft.Source("truncated", "image/png", AssetRole.FRONT, expectedSize = 9) { it.write(1) },
            EmbeddedWalletAssetDraft.Source("bad-hash", "image/png", AssetRole.BACK, expectedSha256 = "0".repeat(64)) { it.write(1) }
        )) {
            assertTrue(runCatching { EmbeddedWalletAssetDraft.prepare(temp.root, listOf(source)) }.isFailure)
            assertTrue(temp.root.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellationIsPropagatedAndCleansStaging() = runBlocking {
        val failure = runCatching { EmbeddedWalletAssetDraft.prepare(temp.root, listOf(
            EmbeddedWalletAssetDraft.Source("cancel", "image/png", AssetRole.CARD_FACE) { throw CancellationException("Cancelled") }
        )) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun encryptedDraftRejectsTampering() = runBlocking {
        val draft = EmbeddedWalletAssetDraft.prepare(temp.root, listOf(
            EmbeddedWalletAssetDraft.Source("test", "image/png", AssetRole.CARD_FACE) { it.write(ByteArray(128) { 42 }) }
        ))
        val file = temp.root.walkTopDown().filter { it.isFile }.single()
        val changed = file.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        file.writeBytes(changed)
        assertTrue(runCatching { draft.open(draft.assets.single().name).use { it.readBytes() } }.isFailure)
        draft.close()
    }

    @Test fun cancellationWhileReturningPreparedDraftAlsoCleansFiles() = runBlocking {
        val job = kotlinx.coroutines.Job()
        val dispatches = java.util.concurrent.atomic.AtomicInteger()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (dispatches.incrementAndGet() > 1) job.cancel()
                block.run()
            }
        }
        var copied = false
        val failure = runCatching {
            kotlinx.coroutines.withContext(dispatcher + job) {
                EmbeddedWalletAssetDraft.prepare(temp.root, listOf(
                    EmbeddedWalletAssetDraft.Source("ready", "image/png", AssetRole.CARD_FACE) {
                        it.write(byteArrayOf(1, 2, 3)); copied = true
                    }
                ))
            }
        }.exceptionOrNull()
        assertTrue(copied)
        assertTrue(failure is CancellationException)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
}
