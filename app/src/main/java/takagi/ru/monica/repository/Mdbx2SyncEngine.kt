package takagi.ru.monica.repository

import java.io.File
import java.util.Locale
import takagi.ru.monica.data.MdbxSyncCheckpointState
import takagi.ru.monica.data.MdbxSyncResumeState
import uniffi.mdbx_ffi.MdbxExternalBlobState
import uniffi.mdbx_ffi.MdbxIncrementalSyncCheckpoint
import uniffi.mdbx_ffi.MdbxIncrementalSyncResume
import uniffi.mdbx_ffi.MdbxVault

internal interface Mdbx2SyncSessionProvider {
    suspend fun <T> withSession(
        databaseId: Long,
        block: suspend (Mdbx2SyncEngine) -> T
    ): T
}

internal class Mdbx2RepositorySyncSessionProvider(
    private val repository: Mdbx2Repository
) : Mdbx2SyncSessionProvider {
    override suspend fun <T> withSession(
        databaseId: Long,
        block: suspend (Mdbx2SyncEngine) -> T
    ): T = repository.withVaultForSync(databaseId) { _, vault ->
        block(NativeMdbx2SyncEngine(vault))
    }
}

internal interface Mdbx2SyncEngine {
    val vaultId: String
    val deviceId: String

    fun checkpoint(): MdbxSyncCheckpointState
    fun createBootstrap(destination: File): Mdbx2BootstrapInfo
    fun exportSegment(
        destination: File,
        base: MdbxSyncCheckpointState,
        resume: MdbxSyncResumeState?,
        pageSize: UInt
    ): Mdbx2SegmentInfo

    fun inspectSegment(source: File): Mdbx2SegmentInfo
    fun applySegment(
        source: File,
        expectedBase: MdbxSyncCheckpointState,
        expectedResume: MdbxSyncResumeState?
    ): Mdbx2SegmentApplyResult

    fun listBlobReferences(cursor: String?, pageSize: UInt): Mdbx2BlobReferencePage
    fun hasBlob(blobId: String, totalSize: ULong): Boolean
    fun readBlobChunk(blobId: String, totalSize: ULong, offset: ULong, maxBytes: UInt): Mdbx2BlobChunk
    fun writeBlobChunk(
        blobId: String,
        totalSize: ULong,
        offset: ULong,
        ciphertext: ByteArray,
        finalize: Boolean
    )

    fun acquireBlobLease(blobId: String, ownerId: String, nowUnixSecs: Long, ttlSecs: Long)
    fun releaseBlobLease(blobId: String, ownerId: String)
    fun abortBlobTransfer(blobId: String, ownerId: String)
}

internal data class Mdbx2BootstrapInfo(
    val vaultId: String,
    val checkpoint: MdbxSyncCheckpointState,
    val fileSizeBytes: ULong
)

internal data class Mdbx2SegmentInfo(
    val vaultId: String,
    val sourceDeviceId: String,
    val transferId: String,
    val segmentIndex: UInt,
    val isLast: Boolean,
    val base: MdbxSyncCheckpointState,
    val result: MdbxSyncCheckpointState,
    val nextResume: MdbxSyncResumeState?,
    val commitCount: UInt,
    val deltaCount: UInt,
    val payloadSha256Hex: String,
    val fileSizeBytes: ULong
)

internal data class Mdbx2SegmentApplyResult(
    val result: MdbxSyncCheckpointState,
    val nextResume: MdbxSyncResumeState?,
    val appliedCommits: UInt,
    val skippedCommits: UInt,
    val conflictCount: UInt,
    val missingParentCount: UInt
)

internal enum class Mdbx2BlobAvailability {
    AVAILABLE,
    MISSING,
    SIZE_MISMATCH
}

internal data class Mdbx2BlobReference(
    val blobId: String,
    val totalSize: ULong?,
    val availability: Mdbx2BlobAvailability
)

internal data class Mdbx2BlobReferencePage(
    val items: List<Mdbx2BlobReference>,
    val nextCursor: String?
)

internal data class Mdbx2BlobChunk(
    val blobId: String,
    val totalSize: ULong,
    val offset: ULong,
    val ciphertext: ByteArray,
    val isLast: Boolean
)

private class NativeMdbx2SyncEngine(
    private val vault: MdbxVault
) : Mdbx2SyncEngine {
    private val info by lazy(vault::info)

    override val vaultId: String get() = info.vaultId
    override val deviceId: String get() = info.deviceId

    override fun checkpoint(): MdbxSyncCheckpointState = vault.incrementalSyncCheckpoint().toState()

    override fun createBootstrap(destination: File): Mdbx2BootstrapInfo {
        val result = vault.createIncrementalSyncBootstrap(destination.absolutePath)
        return Mdbx2BootstrapInfo(
            vaultId = result.backup.vaultId,
            checkpoint = result.checkpoint.toState(),
            fileSizeBytes = result.backup.fileSizeBytes
        )
    }

    override fun exportSegment(
        destination: File,
        base: MdbxSyncCheckpointState,
        resume: MdbxSyncResumeState?,
        pageSize: UInt
    ): Mdbx2SegmentInfo = vault.exportIncrementalSyncSegment(
        destination = destination.absolutePath,
        base = base.toFfi(),
        resume = resume?.toFfi(),
        pageSize = pageSize
    ).toEngineInfo()

    override fun inspectSegment(source: File): Mdbx2SegmentInfo =
        vault.inspectIncrementalSyncSegment(source.absolutePath).toEngineInfo()

    override fun applySegment(
        source: File,
        expectedBase: MdbxSyncCheckpointState,
        expectedResume: MdbxSyncResumeState?
    ): Mdbx2SegmentApplyResult {
        val result = vault.applyIncrementalSyncSegment(
            source = source.absolutePath,
            expectedBase = expectedBase.toFfi(),
            expectedResume = expectedResume?.toFfi()
        )
        return Mdbx2SegmentApplyResult(
            result = result.result.toState(),
            nextResume = result.nextResume?.toState(),
            appliedCommits = result.appliedCommits,
            skippedCommits = result.skippedCommits,
            conflictCount = result.conflictCount,
            missingParentCount = result.missingParentCount
        )
    }

    override fun listBlobReferences(cursor: String?, pageSize: UInt): Mdbx2BlobReferencePage {
        val page = vault.listExternalBlobReferences(cursor, pageSize)
        return Mdbx2BlobReferencePage(
            items = page.items.map { item ->
                Mdbx2BlobReference(
                    blobId = item.blobId,
                    totalSize = item.totalSize,
                    availability = when (item.state) {
                        MdbxExternalBlobState.AVAILABLE -> Mdbx2BlobAvailability.AVAILABLE
                        MdbxExternalBlobState.MISSING -> Mdbx2BlobAvailability.MISSING
                        MdbxExternalBlobState.SIZE_MISMATCH -> Mdbx2BlobAvailability.SIZE_MISMATCH
                    }
                )
            },
            nextCursor = page.nextCursor
        )
    }

    override fun hasBlob(blobId: String, totalSize: ULong): Boolean =
        vault.hasExternalBlob(blobId, totalSize)

    override fun readBlobChunk(
        blobId: String,
        totalSize: ULong,
        offset: ULong,
        maxBytes: UInt
    ): Mdbx2BlobChunk {
        val chunk = vault.readExternalBlobChunk(blobId, totalSize, offset, maxBytes)
        return Mdbx2BlobChunk(
            blobId = chunk.blobId,
            totalSize = chunk.totalSize,
            offset = chunk.offset,
            ciphertext = chunk.ciphertext,
            isLast = chunk.isLast
        )
    }

    override fun writeBlobChunk(
        blobId: String,
        totalSize: ULong,
        offset: ULong,
        ciphertext: ByteArray,
        finalize: Boolean
    ) = vault.writeExternalBlobChunk(blobId, totalSize, offset, ciphertext, finalize)

    override fun acquireBlobLease(blobId: String, ownerId: String, nowUnixSecs: Long, ttlSecs: Long) {
        vault.acquireExternalBlobLease(blobId, ownerId, nowUnixSecs, ttlSecs)
    }

    override fun releaseBlobLease(blobId: String, ownerId: String) {
        vault.releaseExternalBlobLease(blobId, ownerId)
    }

    override fun abortBlobTransfer(blobId: String, ownerId: String) {
        vault.abortExternalBlobTransfer(blobId, ownerId)
    }
}

private fun MdbxIncrementalSyncCheckpoint.toState(): MdbxSyncCheckpointState =
    MdbxSyncCheckpointState(commitInventory, deltaInventory)

private fun MdbxSyncCheckpointState.toFfi(): MdbxIncrementalSyncCheckpoint =
    MdbxIncrementalSyncCheckpoint(commitInventory, deltaInventory)

private fun MdbxIncrementalSyncResume.toState(): MdbxSyncResumeState = MdbxSyncResumeState(
    transferId = transferId,
    nextSegmentIndex = nextSegmentIndex,
    previousSegmentSha256Hex = previousSegmentSha256.toHex()
)

private fun MdbxSyncResumeState.toFfi(): MdbxIncrementalSyncResume = MdbxIncrementalSyncResume(
    transferId = transferId,
    nextSegmentIndex = nextSegmentIndex,
    previousSegmentSha256 = previousSegmentSha256Hex.hexToBytes()
)

private fun uniffi.mdbx_ffi.MdbxIncrementalSyncSegmentInfo.toEngineInfo(): Mdbx2SegmentInfo =
    Mdbx2SegmentInfo(
        vaultId = vaultId,
        sourceDeviceId = sourceDeviceId,
        transferId = transferId,
        segmentIndex = segmentIndex,
        isLast = isLast,
        base = base.toState(),
        result = result.toState(),
        nextResume = nextResume?.toState(),
        commitCount = commitCount,
        deltaCount = deltaCount,
        payloadSha256Hex = payloadSha256.toHex(),
        fileSizeBytes = fileSizeBytes
    )

private fun ByteArray.toHex(): String = joinToString("") {
    "%02x".format(Locale.ROOT, it.toInt() and 0xff)
}

private fun String.hexToBytes(): ByteArray {
    val normalized = lowercase(Locale.ROOT)
    require(normalized.length == 64 && normalized.all { it in "0123456789abcdef" }) {
        "MDBX2 resume digest must be a SHA-256 hex value"
    }
    return ByteArray(normalized.length / 2) { index ->
        normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
