package takagi.ru.monica.ui.scanner

import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.zxing.BarcodeFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class QrCameraScanSession(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    formats: Collection<BarcodeFormat>,
    private val generation: Int,
    private val diagnostics: QrScannerDiagnostics?,
    private val onCandidates: (List<String>, barcodeCount: Int, durationMs: Long) -> Boolean,
    private val onRestartRequested: (QrScanRestartReason) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val controller = LifecycleCameraController(appContext)
    private val decoder = ZxingBarcodeDecoder(formats)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val healthPolicy = QrScanHealthPolicy()
    private val active = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val processingFrame = AtomicBoolean(false)
    private val previewStreaming = AtomicBoolean(false)
    private val previewObserver = Observer<PreviewView.StreamState> { state ->
        val streaming = state == PreviewView.StreamState.STREAMING
        val changed = previewStreaming.getAndSet(streaming) != streaming
        if (changed) diagnostics?.logPreviewState(streaming)
        if (streaming) {
            requestCenterFocus(reason = "preview_streaming")
        }
    }

    fun start() {
        check(active.compareAndSet(false, true)) { "QR camera session already started" }
        val startedAt = SystemClock.elapsedRealtime()
        healthPolicy.onSessionStarted(startedAt)
        diagnostics?.logSessionStarted(generation)
        diagnostics?.logCameraControllerRequested()

        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        controller.setImageAnalysisResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        )
        controller.setTapToFocusEnabled(true)
        controller.setImageAnalysisAnalyzer(analysisExecutor, ::analyzeFrame)

        previewView.controller = controller
        previewView.previewStreamState.observeForever(previewObserver)

        runCatching {
            controller.bindToLifecycle(lifecycleOwner)
        }.onFailure { error ->
            diagnostics?.logCameraBindFailed(error)
            requestRestart(QrScanRestartReason.FrameStreamStopped)
            return
        }

        controller.initializationFuture.addListener(
            {
                if (!active.get()) return@addListener
                runCatching { controller.initializationFuture.get() }
                    .onSuccess {
                        diagnostics?.logCameraBindSuccess(SystemClock.elapsedRealtime() - startedAt)
                        previewView.post { requestCenterFocus(reason = "session_start") }
                    }
                    .onFailure { error ->
                        diagnostics?.logCameraProviderFailed(error)
                        requestRestart(QrScanRestartReason.FrameStreamStopped)
                    }
            },
            mainExecutor
        )
    }

    fun tick(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!active.get()) return
        when (val action = healthPolicy.nextAction(nowMs, previewStreaming.get())) {
            QrScanHealthAction.None -> Unit
            QrScanHealthAction.Refocus -> {
                if (requestCenterFocus(reason = "periodic")) {
                    healthPolicy.onRefocusRequested(nowMs)
                }
            }
            is QrScanHealthAction.Restart -> requestRestart(action.reason)
        }
    }

    fun isProcessingFrame(): Boolean = processingFrame.get()

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!active.get()) {
            imageProxy.close()
            return
        }
        if (!processingFrame.compareAndSet(false, true)) {
            diagnostics?.logFrameSkipped()
            imageProxy.close()
            return
        }

        val frameStartedAt = SystemClock.elapsedRealtime()
        healthPolicy.onFrameStarted(frameStartedAt)
        diagnostics?.logFrameStarted(imageProxy.imageInfo.rotationDegrees)
        if (imageProxy.image == null) {
            diagnostics?.logFrameMissingImage()
            healthPolicy.onFrameCompleted(SystemClock.elapsedRealtime(), succeeded = false)
            processingFrame.set(false)
            imageProxy.close()
            return
        }

        val frameFinished = AtomicBoolean(false)
        fun finishFrame(succeeded: Boolean) {
            if (!frameFinished.compareAndSet(false, true)) return
            healthPolicy.onFrameCompleted(SystemClock.elapsedRealtime(), succeeded)
            processingFrame.set(false)
            runCatching { imageProxy.close() }
        }

        val candidates = runCatching { decoder.decodeFrame(imageProxy) }
            .onFailure { error ->
                diagnostics?.logFrameFailure(error)
            }
            .getOrDefault(emptyList())
        val durationMs = SystemClock.elapsedRealtime() - frameStartedAt
        val matched = runCatching {
            onCandidates(candidates, candidates.size, durationMs)
        }.getOrDefault(false)
        diagnostics?.logFrameSuccess(
            durationMs = durationMs,
            barcodeCount = candidates.size,
            candidateCount = candidates.size,
            matched = matched
        )
        finishFrame(succeeded = true)
    }

    private fun requestCenterFocus(reason: String): Boolean {
        if (!active.get() || !previewStreaming.get()) return false
        if (previewView.width <= 0 || previewView.height <= 0) return false

        return runCatching {
            val point = previewView.meteringPointFactory.createPoint(
                previewView.width / 2f,
                previewView.height / 2f,
                FOCUS_POINT_SIZE
            )
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or
                    FocusMeteringAction.FLAG_AE or
                    FocusMeteringAction.FLAG_AWB
            )
                .setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
                .build()
            val cameraControl = controller.cameraControl ?: return@runCatching false
            cameraControl.startFocusAndMetering(action)
            diagnostics?.logRefocusRequested(reason)
            true
        }.onFailure { error ->
            diagnostics?.logRefocusFailed(error)
        }.getOrDefault(false)
    }

    private fun requestRestart(reason: QrScanRestartReason) {
        if (!active.compareAndSet(true, false)) return
        diagnostics?.logSessionRestartRequested(reason)
        closeResources()
        mainExecutor.execute { onRestartRequested(reason) }
    }

    override fun close() {
        active.set(false)
        closeResources()
    }

    private fun closeResources() {
        if (!closed.compareAndSet(false, true)) return
        diagnostics?.logDispose(processingFrame.get())
        previewStreaming.set(false)
        runCatching { previewView.previewStreamState.removeObserver(previewObserver) }
        runCatching { controller.clearImageAnalysisAnalyzer() }
        runCatching { previewView.controller = null }
        runCatching { controller.unbind() }
        runCatching { decoder.close() }
        analysisExecutor.shutdown()
    }

    private companion object {
        private const val ANALYSIS_WIDTH = 1280
        private const val ANALYSIS_HEIGHT = 960
        private const val FOCUS_POINT_SIZE = 0.24f
        private const val FOCUS_AUTO_CANCEL_SECONDS = 3L
    }
}
