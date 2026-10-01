package takagi.ru.monica.webdav

import com.thegrizzlylabs.sardineandroid.impl.SardineException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import takagi.ru.monica.transfer.*
import takagi.ru.monica.utils.writeBackupAtomically

/** A separate policy for archives; metadata probes keep the Gateway's short deadline. */
internal class WebDavBackupTransport(client: OkHttpClient) {
    internal val client = client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES)
        // An uncertain PUT is not automatically replayed. Preserve the server's Retry-After.
        .retryOnConnectionFailure(false)
        .build()

    suspend fun upload(url: String, file: File, progress: TransferProgressReporter) {
        val size = file.length()
        val body = object : RequestBody() {
            override fun contentType() = "application/zip".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                progress.report(TransferProgress(TransferPhase.UPLOADING, 0, size, bytes = true))
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        written += count
                        progress.report(TransferProgress(TransferPhase.UPLOADING, written, size, bytes = true))
                    }
                    if (written != size) throw IOException("Backup file changed during upload")
                }
            }
        }
        execute(Request.Builder().url(url).header("If-None-Match", "*").put(body).build()) { }
    }

    suspend fun download(url: String, file: File, progress: TransferProgressReporter) {
        execute(Request.Builder().url(url).build()) { response ->
            val body = response.body ?: throw IOException("Missing backup body")
            val total = body.contentLength().takeIf { it >= 0 }
            writeBackupAtomically(file) { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    progress.report(TransferProgress(TransferPhase.DOWNLOADING, 0, total, bytes = true))
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        progress.report(TransferProgress(TransferPhase.DOWNLOADING, read, total, bytes = true))
                    }
                    if (total != null && read != total) throw IOException("Incomplete backup download")
                }
            }
        }
    }

    private suspend fun execute(request: Request, consume: (Response) -> Unit): Unit =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (call.isCanceled()) throw IOException("Canceled")
                            if (!it.isSuccessful) throw SardineException("Backup transfer failed", it.code, it.message)
                            consume(it)
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
}
