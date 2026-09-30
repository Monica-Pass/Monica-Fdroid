package takagi.ru.monica.ui.vaultv2

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.ExpressiveLazyListScrollbar
import takagi.ru.monica.ui.components.GroupedItemDefaults

/** In-memory fixtures: never changes a vault, master password, or application settings. */
@RunWith(AndroidJUnit4::class)
class VaultScrollbarPerformanceTest {

    @Test fun recordRealCardDragFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("scrollPhase") ?: "current"
        val count = 3000
        val items = buildVaultV2PasswordItems(List(count) { i ->
            PasswordEntry(id = i + 1L, title = "Entry ${i.toString().padStart(4, '0')}",
                username = "synthetic-user-$i", password = "synthetic-only", website = "")
        })
        val security = SecurityManager(context)
        val settings = AppSettings()
        val state = LazyListState()
        val labelCalls = AtomicInteger()
        val rowCompositions = AtomicInteger()
        var activity: ComponentActivity? = null
        val ready = java.util.concurrent.CountDownLatch(1)
        var x = 0f
        var top = 0f
        var bottom = 0f
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
        scenario.onActivity { current ->
            activity = current
            current.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(vertical = 64.dp)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 28.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing)) {
                        items(count, key = { items[it].key }, contentType = { "password" }) { index ->
                            SideEffect { rowCompositions.incrementAndGet() }
                            Column {
                                VaultV2ItemCard(items[index], null, settings, security, false,
                                    GroupedItemDefaults.shape(index % 5, 5), {}, {})
                                // Deliberately mix heights without timers or network traffic.
                                if (index % 4 == 0) Spacer(Modifier.height(32.dp))
                            }
                        }
                    }
                    ExpressiveLazyListScrollbar(state,
                        Modifier.align(Alignment.CenterEnd).onGloballyPositioned { coordinates ->
                            val position = coordinates.positionInWindow()
                            val location = IntArray(2).also { current.window.decorView.getLocationOnScreen(it) }
                            x = position.x + coordinates.size.width / 2f + location[0]
                            top = position.y + location[1] + 40f
                            bottom = position.y + coordinates.size.height + location[1] - 40f
                            ready.countDown()
                        },
                        labelForIndex = { index ->
                            labelCalls.incrementAndGet()
                            "2026/09/${(index / 100 % 28 + 1).toString().padStart(2, '0')}"
                        })
                }
            }
        }
        }
        assertTrue("List must be laid out", ready.await(15, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val window = checkNotNull(activity).window
        fun gesture(reverse: Boolean) {
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, fraction: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    x, top + (bottom - top) * fraction, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { instrumentation.uiAutomation.injectInputEvent(event, true) }
                finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, if (reverse) 1f else 0f)
            try {
                repeat(120) { step ->
                    SystemClock.sleep(16)
                    val progress = (step + 1) / 120f
                    send(MotionEvent.ACTION_MOVE, if (reverse) 1f - progress else progress)
                }
            } finally { send(MotionEvent.ACTION_UP, if (reverse) 0f else 1f) }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(160)
        }
        gesture(false) // Warm up layout, rendering and JIT before recording.
        val results = JSONArray()
        val handlerThread = HandlerThread("scroll-frame-metrics").apply { start() }
        try {
            repeat(3) { run ->
                val frames = Collections.synchronizedList(mutableListOf<Double>())
                val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6)
                }
                labelCalls.set(0)
                rowCompositions.set(0)
                instrumentation.runOnMainSync {
                    window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper))
                }
                try { gesture(true); gesture(false) }
                finally { instrumentation.runOnMainSync { window.removeOnFrameMetricsAvailableListener(listener) } }
                val sorted = synchronized(frames) { frames.sorted() }
                android.util.Log.i("VaultScrollPerformance", "run=$run frames=${sorted.size} labels=${labelCalls.get()} rows=${rowCompositions.get()} index=${state.firstVisibleItemIndex}")
                assertTrue("Must collect actual rendered frames: ${sorted.size}", sorted.size >= 100)
                results.put(JSONObject().put("run", run).put("frames", sorted.size)
                    .put("p50Ms", sorted[sorted.size / 2]).put("p95Ms", sorted[(sorted.size * .95).toInt()])
                    .put("over32ms", sorted.count { it > 32 }).put("labelCalls", labelCalls.get())
                    .put("rowCompositions", rowCompositions.get()))
            }
        } finally { handlerThread.quitSafely() }
        val report = JSONObject().put("phase", phase).put("entries", count).put("runs", results)
        File(context.getExternalFilesDir("scroll-performance"), "frames-$phase.json").writeText(report.toString(2))
        android.util.Log.i("VaultScrollPerformance", report.toString())
        instrumentation.runOnMainSync { assertTrue("Drag must reach the end of the real-card list", state.firstVisibleItemIndex > count * .9) }
        }
    }
}
