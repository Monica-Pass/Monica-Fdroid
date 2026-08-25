package takagi.ru.monica.repository

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.MdbxBlobTransferState
import takagi.ru.monica.data.MdbxPendingSegmentState
import takagi.ru.monica.data.MdbxRemoteStreamState
import takagi.ru.monica.data.MdbxSyncCheckpointState
import takagi.ru.monica.data.MdbxSyncStateSnapshot
import takagi.ru.monica.data.MdbxSyncStateStore
import takagi.ru.monica.utils.MdbxRemoteObject
import takagi.ru.monica.utils.MdbxRemoteSyncPaths
import takagi.ru.monica.utils.MdbxRemoteTransport
import takagi.ru.monica.utils.MdbxRemoteWriteMode
import takagi.ru.monica.utils.MdbxSyncSidecarBlob
import takagi.ru.monica.utils.MdbxSyncSidecarManifest
import takagi.ru.monica.utils.MdbxSyncSidecarStore

/**
 * Android transport coordinator for the Rust-owned MDBX2 incremental format.
 *
 * It never writes engine tables. Checkpoints advance only after immutable
 * remote publication or an atomic Rust apply has completed.
 */
internal class Mdbx2RemoteSyncCoordinator(
    private val rootDirectory: File,
    private val sessions: Mdbx2SyncSessionProvider,
    private val stateStore: MdbxSyncStateStore,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val pendingDirectory = File(rootDirectory, "pending")
    private val incomingDirectory = File(rootDirectory, "incoming")
    private val blobDirectory = File(rootDirectory, "blob-transfer")
    private val sidecarStore = MdbxSyncSidecarStore(rootDirectory)
    private val locks = ConcurrentHashMap<Long, Mutex>()

    /**
     * Downloads the immutable bootstrap into [destination] and publishes it
     * atomically. State is intentionally registered separately, after the
     * caller has created the local database row and verified its credentials.
     */
    suspend fun downloadBootstrapTo(
        remoteVaultPath: String,
        transport: MdbxRemoteTransport,
        destination: File
    ): Long = withContext(Dispatchers.IO) {
        // Per-vault operations are serialized by [lock] once a database id
        // exists. Bootstrap download happens before that row exists.
        ensureLocalDirectories()
        transport.testConnection()
        val normalizedPath = MdbxRemoteSyncPaths.normalizePath(remoteVaultPath)
        val parent = destination.parentFile
            ?: throw IOException("MDBX2 bootstrap destination has no parent")
        check(parent.exists() || parent.mkdirs()) {
            "Cannot create MDBX2 bootstrap destination directory"
        }
        val temporary = File.createTempFile("bootstrap-download-", ".mdbx", parent)
        try {
            transport.readTo(normalizedPath, temporary)
            require(temporary.isFile && temporary.length() > 0L) {
                "MDBX2 remote bootstrap is empty"
            }
            atomicReplace(temporary, destination)
            destination.length()
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    suspend fun publishBootstrap(
        databaseId: Long,
        remoteVaultPath: String,
        transport: MdbxRemoteTransport
    ): Mdbx2RemoteBootstrapResult = lock(databaseId) {
        transport.testConnection()
        ensureLocalDirectories()
        sessions.withSession(databaseId) { engine ->
            val bootstrapFile = File(
                rootDirectory,
                "bootstrap-$databaseId-${UUID.randomUUID()}.mdbx"
            )
            check(!bootstrapFile.exists()) {
                "MDBX2 bootstrap destination already exists"
            }
            try {
                val bootstrap = engine.createBootstrap(bootstrapFile)
                require(bootstrap.vaultId == engine.vaultId) { "MDBX2 bootstrap vault identity mismatch" }
                ensureRemoteParent(transport, remoteVaultPath)
                transport.writeFrom(
                    path = MdbxRemoteSyncPaths.normalizePath(remoteVaultPath),
                    source = bootstrapFile,
                    mode = MdbxRemoteWriteMode.CREATE_ONLY
                )
                transport.ensureDirectory(MdbxRemoteSyncPaths.streamsRoot(remoteVaultPath))
                transport.ensureDirectory(MdbxRemoteSyncPaths.blobsRoot(remoteVaultPath))
                stateStore.write(
                    databaseId,
                    MdbxSyncStateSnapshot(
                        vaultId = engine.vaultId,
                        bootstrapCheckpoint = bootstrap.checkpoint,
                        exportCheckpoint = bootstrap.checkpoint
                    )
                )
                initializeSidecar(databaseId, engine, remoteVaultPath)
                Mdbx2RemoteBootstrapResult(
                    vaultId = engine.vaultId,
                    checkpoint = bootstrap.checkpoint,
                    fileSizeBytes = bootstrap.fileSizeBytes
                )
            } finally {
                bootstrapFile.delete()
            }
        }
    }

    suspend fun registerDownloadedBootstrap(
        databaseId: Long,
        remoteVaultPath: String
    ): MdbxSyncStateSnapshot =
        lock(databaseId) {
            sessions.withSession(databaseId) { engine ->
                val checkpoint = engine.checkpoint()
                MdbxSyncStateSnapshot(
                    vaultId = engine.vaultId,
                    bootstrapCheckpoint = checkpoint,
                    exportCheckpoint = checkpoint
                ).also {
                    stateStore.write(databaseId, it)
                    initializeSidecar(databaseId, engine, remoteVaultPath)
                }
            }
        }

    suspend fun clearLocalState(databaseId: Long) = lock(databaseId) {
        stateStore.delete(databaseId)
        val sidecar = sidecarFile(databaseId)
        if (sidecar.exists() && !sidecar.delete()) {
            throw IOException("Cannot delete MDBX2 sync sidecar")
        }
        listOf(pendingDirectory, incomingDirectory, blobDirectory).forEach { directory ->
            directory.listFiles()?.forEach { file ->
                if (file.name.startsWith("segment-$databaseId-") ||
                    file.name.startsWith("segment-receive-$databaseId-") ||
                    file.name.startsWith("blob-upload-$databaseId-") ||
                    file.name.startsWith("blob-download-$databaseId-") ||
                    file.name.startsWith("blob-verify-$databaseId-")) {
                    file.delete()
                }
            }
        }
    }

    suspend fun synchronize(
        databaseId: Long,
        remoteVaultPath: String,
        transport: MdbxRemoteTransport
    ): Mdbx2RemoteSyncReport = lock(databaseId) {
        transport.testConnection()
        ensureLocalDirectories()
        sessions.withSession(databaseId) { engine ->
            var state = stateStore.read(databaseId)
            require(state.vaultId == engine.vaultId) {
                "MDBX2 remote sync state belongs to another vault"
            }
            require(state.bootstrapCheckpoint != null && state.exportCheckpoint != null) {
                "MDBX2 remote sync bootstrap state is missing"
            }
            var report = Mdbx2RemoteSyncReport()
            val publication = publishLocalSegments(
                databaseId = databaseId,
                remoteVaultPath = remoteVaultPath,
                transport = transport,
                engine = engine,
                initialState = state
            )
            state = publication.state
            report = report.copy(
                uploadedSegments = publication.uploadedSegments,
                uploadedBlobs = publication.uploadedBlobs
            )

            val receive = receiveRemoteSegments(
                databaseId = databaseId,
                remoteVaultPath = remoteVaultPath,
                transport = transport,
                engine = engine,
                initialState = state
            )
            state = receive.state.copy(exportCheckpoint = engine.checkpoint())
            stateStore.write(databaseId, state)
            report.copy(
                downloadedSegments = receive.downloadedSegments,
                downloadedBlobs = receive.downloadedBlobs,
                appliedCommits = receive.appliedCommits,
                skippedCommits = receive.skippedCommits,
                conflicts = receive.conflicts,
                blockedStreams = receive.blockedStreams
            )
        }
    }

    private suspend fun publishLocalSegments(
        databaseId: Long,
        remoteVaultPath: String,
        transport: MdbxRemoteTransport,
        engine: Mdbx2SyncEngine,
        initialState: MdbxSyncStateSnapshot
    ): PublicationResult {
        var state = initialState
        var uploadedSegments = 0
        var uploadedBlobs = 0
        repeat(MAX_SEGMENTS_PER_SYNC) {
            val current = engine.checkpoint()
            val base = state.exportCheckpoint
                ?: throw IllegalStateException("MDBX2 export checkpoint is missing")
            if (current == base && state.pendingSegment == null) {
                return PublicationResult(state, uploadedSegments, uploadedBlobs)
            }

            val (pendingFile, info) = restoreOrCreatePendingSegment(
                databaseId = databaseId,
                engine = engine,
                state = state,
                base = base
            )
            if (state.pendingSegment == null) {
                state = state.copy(pendingSegment = info.toPendingState(pendingFile))
                stateStore.write(databaseId, state)
            }

            uploadedBlobs += uploadReferencedBlobs(
                databaseId = databaseId,
                remoteVaultPath = remoteVaultPath,
                transport = transport,
                engine = engine,
                generationId = info.transferId
            )
            if (info.commitCount > 0u || info.deltaCount > 0u) {
                val remotePath = MdbxRemoteSyncPaths.segmentPath(
                    remoteVaultPath = remoteVaultPath,
                    deviceId = info.sourceDeviceId,
                    generationId = info.transferId,
                    sequence = info.segmentIndex,
                    digestHex = info.payloadSha256Hex
                )
                transport.ensureDirectory(parentPath(remotePath))
                transport.writeFrom(
                    path = remotePath,
                    source = pendingFile,
                    mode = MdbxRemoteWriteMode.CREATE_ONLY
                )
                uploadedSegments += 1
            }
            state = state.copy(
                generationId = info.transferId,
                exportCheckpoint = info.result,
                pendingSegment = null
            )
            stateStore.write(databaseId, state)
            pendingFile.delete()
        }
        throw IOException("MDBX2 generated too many incremental segments in one synchronization")
    }

    private fun restoreOrCreatePendingSegment(
        databaseId: Long,
        engine: Mdbx2SyncEngine,
        state: MdbxSyncStateSnapshot,
        base: MdbxSyncCheckpointState
    ): Pair<File, Mdbx2SegmentInfo> {
        val pending = state.pendingSegment
        if (pending != null) {
            val file = requirePendingFile(pending.path)
            if (!file.isFile || file.length() != pending.fileSizeBytes) {
                throw IOException("MDBX2 pending segment file is missing or truncated")
            }
            val info = engine.inspectSegment(file)
            require(info.vaultId == pending.vaultId &&
                info.sourceDeviceId == pending.sourceDeviceId &&
                info.transferId == pending.transferId &&
                info.segmentIndex.toLong() == pending.streamSequence &&
                info.payloadSha256Hex == pending.payloadSha256Hex &&
                info.base == pending.base && info.result == pending.result
            ) { "MDBX2 pending segment state does not match its authenticated file" }
            return file to info
        }
        val destination = File(
            pendingDirectory,
            "segment-$databaseId-${UUID.randomUUID()}.mdbxsync"
        )
        val info = engine.exportSegment(
            destination = destination,
            base = base,
            resume = null,
            pageSize = SEGMENT_PAGE_SIZE
        )
        return destination to info
    }

    private suspend fun uploadReferencedBlobs(
        databaseId: Long,
        remoteVaultPath: String,
        transport: MdbxRemoteTransport,
        engine: Mdbx2SyncEngine,
        generationId: String
    ): Int {
        val normalizedRemotePath = MdbxRemoteSyncPaths.normalizePath(remoteVaultPath)
        var sidecar = loadSidecar(databaseId, engine, normalizedRemotePath, generationId)
        var cursor: String? = null
        var uploaded = 0
        do {
            val page = engine.listBlobReferences(cursor, BLOB_PAGE_SIZE)
            page.items.forEach { reference ->
                val totalSize = reference.totalSize
                    ?: throw IOException("MDBX2 Blob ${reference.blobId} has no size")
                if (reference.availability != Mdbx2BlobAvailability.AVAILABLE) {
                    throw IOException("MDBX2 Blob ${reference.blobId} is unavailable locally")
                }
                val remotePath = MdbxRemoteSyncPaths.blobPath(remoteVaultPath, reference.blobId)
                val existing = transport.stat(remotePath)
                if (existing != null) {
                    if (existing.isDirectory ||
                        existing.sizeBytes?.let { it != totalSize.toLong() } == true
                    ) {
                        throw IOException("MDBX2 remote Blob metadata mismatch: ${reference.blobId}")
                    }
                    val cached = sidecar.blobs.firstOrNull { blob ->
                        blob.blobId == reference.blobId &&
                            blob.totalSize == totalSize.toLong() &&
                            blob.uploaded
                    }
                    if (cached != null) return@forEach
                    val verification = File.createTempFile("blob-verify-$databaseId-", ".bin", blobDirectory)
                    try {
                        transport.readTo(remotePath, verification)
                        require(verification.length() == totalSize.toLong() &&
                            MdbxRemoteSyncPaths.sha256Hex(verification) == reference.blobId
                        ) { "MDBX2 remote Blob digest mismatch: ${reference.blobId}" }
                        sidecar = sidecar.withVerifiedBlob(
                            blobId = reference.blobId,
                            totalSize = totalSize.toLong(),
                            downloaded = false
                        )
                        sidecarStore.write(sidecarFile(databaseId), sidecar)
                        return@forEach
                    } finally {
                        verification.delete()
                    }
                }
                val temporary = File.createTempFile("blob-upload-$databaseId-", ".bin", blobDirectory)
                try {
                    writeLocalBlobToFile(engine, reference.blobId, totalSize, temporary)
                    transport.ensureDirectory(parentPath(remotePath))
                    transport.writeFrom(remotePath, temporary, MdbxRemoteWriteMode.CREATE_ONLY)
                    sidecar = sidecar.withVerifiedBlob(
                        blobId = reference.blobId,
                        totalSize = totalSize.toLong(),
                        downloaded = false
                    )
                    sidecarStore.write(sidecarFile(databaseId), sidecar)
                    uploaded += 1
                } finally {
                    temporary.delete()
                }
            }
            cursor = page.nextCursor
        } while (cursor != null)
        return uploaded
    }

    private fun writeLocalBlobToFile(
        engine: Mdbx2SyncEngine,
        blobId: String,
        totalSize: ULong,
        destination: File
    ) {
        destination.outputStream().buffered().use { output ->
            var offset = 0uL
            while (offset < totalSize) {
                val chunk = engine.readBlobChunk(
                    blobId = blobId,
                    totalSize = totalSize,
                    offset = offset,
                    maxBytes = BLOB_CHUNK_SIZE
                )
                require(chunk.blobId == blobId && chunk.totalSize == totalSize && chunk.offset == offset)
                require(chunk.ciphertext.isNotEmpty()) { "MDBX2 returned an empty Blob chunk" }
                output.write(chunk.ciphertext)
                offset += chunk.ciphertext.size.toULong()
                require(chunk.isLast == (offset == totalSize)) { "MDBX2 Blob chunk boundary mismatch" }
            }
        }
        require(destination.length() == totalSize.toLong()) { "MDBX2 Blob export size mismatch" }
        require(MdbxRemoteSyncPaths.sha256Hex(destination) == blobId) {
            "MDBX2 Blob export digest mismatch"
        }
    }

    private suspend fun receiveRemoteSegments(
        databaseId: Long,
        remoteVaultPath: String,
        transport: MdbxRemoteTransport,
        engine: Mdbx2SyncEngine,
        initialState: MdbxSyncStateSnapshot
    ): ReceiveResult {
        val descriptors = listRemoteSegments(remoteVaultPath, transport)
            .filterNot { it.deviceId == engine.deviceId }
        var state = initialState
        var downloadedSegments = 0
        var downloadedBlobs = 0
        var appliedCommits = 0
        var skippedCommits = 0
        var conflicts = 0

        repeat(MAX_RECEIVE_PASSES) {
            var progressed = false
            descriptors.groupBy { it.streamKey }.toSortedMap().forEach { (streamKey, streamFiles) ->
                val ordered = streamFiles.sortedBy(RemoteSegmentDescriptor::sequence)
                var stream = state.remoteStreams.firstOrNull { it.streamId == streamKey }
                for (descriptor in ordered) {
                    val expectedSequence = stream?.nextSequence ?: 0L
                    if (descriptor.sequence < expectedSequence) {
                        if (stream != null &&
                            descriptor.sequence == expectedSequence - 1L &&
                            stream.lastAppliedDigestHex != null &&
                            !stream.lastAppliedDigestHex.equals(descriptor.digestHex, ignoreCase = true)
                        ) {
                            stream = stream.copy(blockedReason = "conflicting digest for segment ${descriptor.sequence}")
                            state = state.withRemoteStream(stream)
                            stateStore.write(databaseId, state)
                            break
                        }
                        continue
                    }
                    if (descriptor.sequence > expectedSequence) {
                        stream = (stream ?: descriptor.initialStreamState(initialState.bootstrapCheckpoint)).copy(
                            blockedReason = "missing segment $expectedSequence"
                        )
                        state = state.withRemoteStream(stream)
                        stateStore.write(databaseId, state)
                        break
                    }
                    val temporary = File.createTempFile("segment-receive-$databaseId-", ".mdbxsync", incomingDirectory)
                    try {
                        transport.readTo(descriptor.path, temporary)
                        downloadedSegments += 1
                        val info = engine.inspectSegment(temporary)
                        require(info.vaultId == engine.vaultId) { "MDBX2 remote segment belongs to another vault" }
                        require(info.sourceDeviceId == descriptor.deviceId &&
                            info.transferId == descriptor.generationId &&
                            info.segmentIndex.toLong() == descriptor.sequence &&
                            info.payloadSha256Hex.equals(descriptor.digestHex, ignoreCase = true)
                        ) { "MDBX2 remote segment path does not match its authenticated metadata" }
                        val currentStream = stream ?: descriptor.initialStreamState(info.base)
                        val apply = engine.applySegment(
                            source = temporary,
                            expectedBase = currentStream.checkpoint,
                            expectedResume = currentStream.resume
                        )
                        appliedCommits += apply.appliedCommits.toInt()
                        skippedCommits += apply.skippedCommits.toInt()
                        conflicts += apply.conflictCount.toInt()
                        if (apply.missingParentCount > 0u) {
                            stream = currentStream.copy(
                                blockedReason = "waiting for ${apply.missingParentCount} parent commit(s)"
                            )
                            state = state.withRemoteStream(stream)
                            stateStore.write(databaseId, state)
                            break
                        }
                        // The Rust apply already advanced the local engine.
                        // Persist that checkpoint immediately so a later Blob
                        // transfer failure cannot make the received change
                        // look like a new local change and echo it back out.
                        state = state.copy(exportCheckpoint = engine.checkpoint())
                        stateStore.write(databaseId, state)
                        val nextStream = currentStream.copy(
                            nextSequence = descriptor.sequence + 1,
                            checkpoint = apply.result,
                            resume = apply.nextResume,
                            lastAppliedDigestHex = descriptor.digestHex,
                            blockedReason = null
                        )
                        // Do not acknowledge the remote cursor until every
                        // Blob referenced by the applied segment is present.
                        // If Blob transfer is interrupted, the old cursor
                        // causes an idempotent re-apply and resumes the same
                        // durable Blob transfer on the next run.
                        val blobResult = downloadMissingBlobs(
                            databaseId,
                            remoteVaultPath,
                            transport,
                            engine,
                            state
                        )
                        state = blobResult.state.withRemoteStream(nextStream)
                        stateStore.write(databaseId, state)
                        downloadedBlobs += blobResult.downloadedBlobs
                        stream = nextStream
                        progressed = true
                    } finally {
                        temporary.delete()
                    }
                }
            }
            if (!progressed) return@repeat
        }
        return ReceiveResult(
            state = state,
            downloadedSegments = downloadedSegments,
            downloadedBlobs = downloadedBlobs,
            appliedCommits = appliedCommits,
            skippedCommits = skippedCommits,
            conflicts = conflicts,
            blockedStreams = state.remoteStreams.count { it.blockedReason != null }
        )
    }

    private suspend fun downloadMissingBlobs(
        databaseId: Long,
        remoteVaultPath: String,
        transport: MdbxRemoteTransport,
        engine: Mdbx2SyncEngine,
        initialState: MdbxSyncStateSnapshot
    ): BlobDownloadResult {
        var state = initialState
        var cursor: String? = null
        var downloaded = 0
        do {
            val page = engine.listBlobReferences(cursor, BLOB_PAGE_SIZE)
            page.items.forEach { reference ->
                val localSize = reference.totalSize
                if (reference.availability == Mdbx2BlobAvailability.AVAILABLE &&
                    localSize != null &&
                    engine.hasBlob(reference.blobId, localSize)
                ) {
                    return@forEach
                }
                val remotePath = MdbxRemoteSyncPaths.blobPath(remoteVaultPath, reference.blobId)
                val remote = transport.stat(remotePath)
                    ?: throw IOException("MDBX2 remote Blob is missing: ${reference.blobId}")
                val remoteSize = remote.sizeBytes
                    ?: throw IOException("MDBX2 remote Blob has no bounded size: ${reference.blobId}")
                require(remoteSize in 1..MAX_REMOTE_BLOB_BYTES) {
                    "MDBX2 remote Blob exceeds the transfer limit"
                }
                require(localSize == null || remoteSize == localSize.toLong()) {
                    "MDBX2 remote Blob size mismatch"
                }
                val totalSize = remoteSize.toULong()
                val temporary = File.createTempFile("blob-download-$databaseId-", ".bin", blobDirectory)
                try {
                    transport.readTo(remotePath, temporary)
                    require(temporary.length() == totalSize.toLong()) { "MDBX2 downloaded Blob size mismatch" }
                    require(MdbxRemoteSyncPaths.sha256Hex(temporary) == reference.blobId) {
                        "MDBX2 downloaded Blob digest mismatch"
                    }
                    val existingTransfer = state.blobTransfers.firstOrNull {
                        it.blobId == reference.blobId && it.direction == DIRECTION_DOWNLOAD
                    }
                    val ownerId = existingTransfer?.ownerId ?: "android-$databaseId-${UUID.randomUUID()}"
                    var offset = existingTransfer?.nextOffset?.coerceIn(0L, totalSize.toLong()) ?: 0L
                    if (existingTransfer == null) {
                        runCatching { engine.abortBlobTransfer(reference.blobId, ownerId) }
                    }
                    engine.acquireBlobLease(
                        blobId = reference.blobId,
                        ownerId = ownerId,
                        nowUnixSecs = nowMillis() / 1000L,
                        ttlSecs = BLOB_LEASE_TTL_SECONDS
                    )
                    try {
                        RandomAccessFile(temporary, "r").use { input ->
                            input.seek(offset)
                            while (offset < totalSize.toLong()) {
                                val count = minOf(BLOB_CHUNK_SIZE.toLong(), totalSize.toLong() - offset).toInt()
                                val bytes = ByteArray(count)
                                input.readFully(bytes)
                                val nextOffset = offset + count
                                engine.writeBlobChunk(
                                    blobId = reference.blobId,
                                    totalSize = totalSize,
                                    offset = offset.toULong(),
                                    ciphertext = bytes,
                                    finalize = nextOffset == totalSize.toLong()
                                )
                                offset = nextOffset
                                state = state.withBlobTransfer(
                                    MdbxBlobTransferState(
                                        blobId = reference.blobId,
                                        totalSize = totalSize.toLong(),
                                        ownerId = ownerId,
                                        nextOffset = offset,
                                        direction = DIRECTION_DOWNLOAD,
                                        updatedAt = nowMillis()
                                    )
                                )
                                stateStore.write(databaseId, state)
                            }
                        }
                    } finally {
                        runCatching { engine.releaseBlobLease(reference.blobId, ownerId) }
                    }
                    require(engine.hasBlob(reference.blobId, totalSize)) {
                        "MDBX2 Blob transfer did not finalize"
                    }
                    val sidecar = loadSidecar(
                        databaseId = databaseId,
                        engine = engine,
                        remoteVaultPath = remoteVaultPath,
                        generationId = state.generationId ?: INITIAL_GENERATION_ID
                    ).withVerifiedBlob(
                        blobId = reference.blobId,
                        totalSize = totalSize.toLong(),
                        downloaded = true
                    )
                    sidecarStore.write(sidecarFile(databaseId), sidecar)
                    state = state.removeBlobTransfer(reference.blobId, DIRECTION_DOWNLOAD)
                    stateStore.write(databaseId, state)
                    downloaded += 1
                } finally {
                    temporary.delete()
                }
            }
            cursor = page.nextCursor
        } while (cursor != null)
        return BlobDownloadResult(state, downloaded)
    }

    private suspend fun listRemoteSegments(
        remoteVaultPath: String,
        transport: MdbxRemoteTransport
    ): List<RemoteSegmentDescriptor> {
        val streamsRoot = MdbxRemoteSyncPaths.streamsRoot(remoteVaultPath)
        if (transport.stat(streamsRoot)?.isDirectory != true) return emptyList()
        return buildList {
            transport.list(streamsRoot).filter(MdbxRemoteObject::isDirectory).forEach { device ->
                transport.list(device.path).filter(MdbxRemoteObject::isDirectory).forEach { generation ->
                    transport.list(generation.path)
                        .filter { it.isDirectory && it.path.substringAfterLast('/') == SEGMENTS_DIRECTORY }
                        .forEach { segmentsDirectory ->
                            transport.list(segmentsDirectory.path)
                                .filterNot(MdbxRemoteObject::isDirectory)
                                .mapNotNull { remote -> parseSegmentDescriptor(device, generation, remote) }
                                .forEach(::add)
                        }
                }
            }
        }
    }

    private fun parseSegmentDescriptor(
        device: MdbxRemoteObject,
        generation: MdbxRemoteObject,
        remote: MdbxRemoteObject
    ): RemoteSegmentDescriptor? {
        val name = remote.path.substringAfterLast('/')
        if (!name.endsWith(SEGMENT_SUFFIX)) return null
        val sequence = name.substringBefore('-').toLongOrNull() ?: return null
        if (sequence < 0L) return null
        val digest = name.substringAfter('-', "").removeSuffix(SEGMENT_SUFFIX)
        if (digest.length != 64 || digest.any { it.lowercase() !in "0123456789abcdef" }) return null
        return RemoteSegmentDescriptor(
            deviceId = device.path.substringAfterLast('/'),
            generationId = generation.path.substringAfterLast('/'),
            sequence = sequence,
            path = remote.path,
            digestHex = digest
        )
    }

    private fun Mdbx2SegmentInfo.toPendingState(file: File): MdbxPendingSegmentState =
        MdbxPendingSegmentState(
            path = file.absolutePath,
            streamId = "$sourceDeviceId/$transferId",
            streamSequence = segmentIndex.toLong(),
            vaultId = vaultId,
            sourceDeviceId = sourceDeviceId,
            transferId = transferId,
            segmentIndex = segmentIndex,
            isLast = isLast,
            base = base,
            result = result,
            nextResume = nextResume,
            payloadSha256Hex = payloadSha256Hex,
            fileSizeBytes = fileSizeBytes.toLong()
        )

    private fun MdbxSyncStateSnapshot.withRemoteStream(
        stream: MdbxRemoteStreamState
    ): MdbxSyncStateSnapshot = copy(
        remoteStreams = (remoteStreams.filterNot { it.streamId == stream.streamId } + stream)
            .sortedBy(MdbxRemoteStreamState::streamId)
    )

    private fun MdbxSyncStateSnapshot.withBlobTransfer(
        transfer: MdbxBlobTransferState
    ): MdbxSyncStateSnapshot = copy(
        blobTransfers = blobTransfers.filterNot {
            it.blobId == transfer.blobId && it.direction == transfer.direction
        } + transfer
    )

    private fun MdbxSyncStateSnapshot.removeBlobTransfer(
        blobId: String,
        direction: String
    ): MdbxSyncStateSnapshot = copy(
        blobTransfers = blobTransfers.filterNot { it.blobId == blobId && it.direction == direction }
    )

    private fun RemoteSegmentDescriptor.initialStreamState(
        checkpoint: MdbxSyncCheckpointState? = null
    ): MdbxRemoteStreamState = MdbxRemoteStreamState(
        streamId = streamKey,
        generationId = generationId,
        nextSequence = 0L,
        checkpoint = checkpoint
            ?: throw IllegalStateException("MDBX2 stream checkpoint is not initialized")
    )

    private fun requirePendingFile(path: String): File {
        val candidate = File(path).canonicalFile
        require(candidate.parentFile == pendingDirectory.canonicalFile) {
            "MDBX2 pending segment escaped its app-private directory"
        }
        return candidate
    }

    private fun ensureLocalDirectories() {
        listOf(rootDirectory, pendingDirectory, incomingDirectory, blobDirectory).forEach { directory ->
            check(directory.exists() || directory.mkdirs()) { "Cannot create MDBX2 sync directory" }
        }
    }

    private suspend fun initializeSidecar(
        databaseId: Long,
        engine: Mdbx2SyncEngine,
        remoteVaultPath: String
    ) {
        val normalizedRemotePath = MdbxRemoteSyncPaths.normalizePath(remoteVaultPath)
        sidecarStore.write(
            sidecarFile(databaseId),
            MdbxSyncSidecarManifest(
                vaultId = engine.vaultId,
                generationId = INITIAL_GENERATION_ID,
                streamId = engine.deviceId,
                remoteVaultPath = normalizedRemotePath
            )
        )
    }

    private suspend fun loadSidecar(
        databaseId: Long,
        engine: Mdbx2SyncEngine,
        remoteVaultPath: String,
        generationId: String
    ): MdbxSyncSidecarManifest {
        val current = sidecarStore.read(sidecarFile(databaseId))
        require(current == null || current.vaultId == engine.vaultId) {
            "MDBX2 sync sidecar belongs to another vault"
        }
        val normalizedRemotePath = MdbxRemoteSyncPaths.normalizePath(remoteVaultPath)
        val base = current?.takeIf { it.remoteVaultPath == normalizedRemotePath }
        return (base ?: MdbxSyncSidecarManifest(
            vaultId = engine.vaultId,
            generationId = generationId,
            streamId = engine.deviceId,
            remoteVaultPath = normalizedRemotePath
        )).copy(
            generationId = generationId,
            streamId = engine.deviceId,
            remoteVaultPath = normalizedRemotePath
        )
    }

    private fun MdbxSyncSidecarManifest.withVerifiedBlob(
        blobId: String,
        totalSize: Long,
        downloaded: Boolean
    ): MdbxSyncSidecarManifest {
        val previous = blobs.firstOrNull { it.blobId == blobId }
        val next = MdbxSyncSidecarBlob(
            blobId = blobId,
            totalSize = totalSize,
            uploaded = true,
            downloaded = downloaded || previous?.downloaded == true
        )
        return copy(
            blobs = (blobs.filterNot { it.blobId == blobId } + next)
                .sortedBy(MdbxSyncSidecarBlob::blobId)
        )
    }

    private fun sidecarFile(databaseId: Long): File =
        File(rootDirectory, "sync-state-$databaseId.json")

    private fun atomicReplace(source: File, destination: File) {
        try {
            java.nio.file.Files.move(
                source.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            if (!source.renameTo(destination)) {
                throw IOException("Cannot publish MDBX2 bootstrap")
            }
        }
    }

    private suspend fun ensureRemoteParent(
        transport: MdbxRemoteTransport,
        path: String
    ) {
        val parent = parentPath(MdbxRemoteSyncPaths.normalizePath(path))
        if (parent.isNotBlank()) transport.ensureDirectory(parent)
    }

    private fun parentPath(path: String): String = path.substringBeforeLast('/', "")

    private suspend fun <T> lock(databaseId: Long, block: suspend () -> T): T =
        locks.getOrPut(databaseId) { Mutex() }.withLock { block() }

    private data class PublicationResult(
        val state: MdbxSyncStateSnapshot,
        val uploadedSegments: Int,
        val uploadedBlobs: Int
    )

    private data class ReceiveResult(
        val state: MdbxSyncStateSnapshot,
        val downloadedSegments: Int,
        val downloadedBlobs: Int,
        val appliedCommits: Int,
        val skippedCommits: Int,
        val conflicts: Int,
        val blockedStreams: Int
    )

    private data class BlobDownloadResult(
        val state: MdbxSyncStateSnapshot,
        val downloadedBlobs: Int
    )

    private data class RemoteSegmentDescriptor(
        val deviceId: String,
        val generationId: String,
        val sequence: Long,
        val path: String,
        val digestHex: String
    ) {
        val streamKey: String get() = "$deviceId/$generationId"
    }

    companion object {
        private const val SEGMENT_SUFFIX = ".mdbxsync"
        private const val SEGMENTS_DIRECTORY = "segments"
        private const val DIRECTION_DOWNLOAD = "download"
        private const val INITIAL_GENERATION_ID = "bootstrap"
        private const val MAX_SEGMENTS_PER_SYNC = 10_000
        private const val MAX_RECEIVE_PASSES = 4
        private const val BLOB_LEASE_TTL_SECONDS = 15 * 60L
        private const val MAX_REMOTE_BLOB_BYTES = 64L * 1024L * 1024L + 128L * 1024L
        private val SEGMENT_PAGE_SIZE = 128u
        private val BLOB_PAGE_SIZE = 256u
        private val BLOB_CHUNK_SIZE = 512u * 1024u
    }
}

internal data class Mdbx2RemoteBootstrapResult(
    val vaultId: String,
    val checkpoint: MdbxSyncCheckpointState,
    val fileSizeBytes: ULong
)

internal data class Mdbx2RemoteSyncReport(
    val uploadedSegments: Int = 0,
    val downloadedSegments: Int = 0,
    val uploadedBlobs: Int = 0,
    val downloadedBlobs: Int = 0,
    val appliedCommits: Int = 0,
    val skippedCommits: Int = 0,
    val conflicts: Int = 0,
    val blockedStreams: Int = 0
)
