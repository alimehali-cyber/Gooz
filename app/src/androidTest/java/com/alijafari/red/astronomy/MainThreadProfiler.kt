package com.alijafari.red.astronomy

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Watches the app's main thread from inside the app process (the instrumentation shares it), without
 * root and without ever waiting for an idle looper:
 *
 * - a sampler reads the main thread's stack every [SAMPLE_MS] ms and counts where it is busy (anything
 *   other than waiting for work in `MessageQueue.nativePollOnce`);
 * - a pinger puts an asynchronous message at the front of the main queue every [PING_GAP_MS] ms and
 *   measures how long the thread takes to run it. That is how long an input or focus event would wait;
 *   Android reports "Application Not Responding" when it exceeds 5 s. The stack is sampled while waiting.
 *
 * Reports go to logcat (tag [TAG]) and to the instrumentation output (status key `ArSmoke`), also on a
 * timer, so they arrive even when the test thread itself is stuck.
 */
class MainThreadProfiler(private val label: String) : AutoCloseable {

    private val looper = Looper.getMainLooper()
    private val main: Thread = looper.thread
    private val handler = Handler(looper)
    private val startMs = SystemClock.uptimeMillis()

    @Volatile
    private var running = true

    private val lock = Any()
    private val hotStacks = HashMap<String, Int>()
    private val hotAppFrames = HashMap<String, Int>()
    private val hotTopFrames = HashMap<String, Int>()
    private var samples = 0
    private var busySamples = 0
    private var pings = 0
    private var longestBlockMs = 0L
    private var longestBlockAtS = 0.0
    private var longestBlockStack = ""
    private var blocksOver250 = 0
    private var blocksOver1s = 0
    private var blocksOver5s = 0

    /** Longest time the main thread needed to run a message put at the front of its queue. */
    val maxBlockMs: Long get() = synchronized(lock) { longestBlockMs }

    private val threads = ArrayList<Thread>()

    fun start(): MainThreadProfiler {
        threads += Thread({
            while (running) {
                sample()
                SystemClock.sleep(SAMPLE_MS)
            }
        }, "ArSmokeSampler")
        threads += Thread({
            while (running) {
                ping()
                SystemClock.sleep(PING_GAP_MS)
            }
        }, "ArSmokePinger")
        threads += Thread({
            for (atS in INTERIM_REPORTS_S) {
                val due = startMs + atS * 1000L
                while (running && SystemClock.uptimeMillis() < due) SystemClock.sleep(250L)
                if (!running) break
                emit("interim t=${atS}s")
            }
        }, "ArSmokeReporter")
        threads.forEach { it.isDaemon = true; it.start() }
        return this
    }

    private fun elapsedS(): Double = (SystemClock.uptimeMillis() - startMs) / 1000.0

    private fun sample() {
        val trace = main.stackTrace
        val idle = trace.isEmpty() || (trace[0].methodName == "nativePollOnce")
        synchronized(lock) {
            samples++
            if (idle) return
            busySamples++
            val stack = trace.take(STACK_DEPTH).joinToString("\n") { "    at $it" }
            hotStacks[stack] = (hotStacks[stack] ?: 0) + 1
            val app = trace.firstOrNull { it.className.startsWith(APP_PACKAGE) && !it.className.contains("MainThreadProfiler") }
            val appKey = app?.let { "${it.className.removePrefix("$APP_PACKAGE.")}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                ?: "(no app frame)"
            hotAppFrames[appKey] = (hotAppFrames[appKey] ?: 0) + 1
            val top = trace[0]
            val topKey = "${top.className}.${top.methodName}"
            hotTopFrames[topKey] = (hotTopFrames[topKey] ?: 0) + 1
        }
    }

    private fun ping() {
        val done = CountDownLatch(1)
        val msg = Message.obtain(handler) { done.countDown() }
        msg.isAsynchronous = true
        val t0 = SystemClock.uptimeMillis()
        val atS = elapsedS()
        handler.sendMessageAtFrontOfQueue(msg)
        var stackAt1s: String? = null
        var warned5s = false
        while (!done.await(50L, TimeUnit.MILLISECONDS)) {
            if (!running) return
            val waited = SystemClock.uptimeMillis() - t0
            if (stackAt1s == null && waited >= 1000L) {
                stackAt1s = main.stackTrace.take(BLOCK_STACK_DEPTH).joinToString("\n") { "    at $it" }
            }
            if (!warned5s && waited >= 5000L) {
                warned5s = true
                Log.e(TAG, "$label: main thread has not run a front-of-queue message for $waited ms (ANR threshold 5 s); stack after 1 s:\n$stackAt1s")
            }
        }
        val block = SystemClock.uptimeMillis() - t0
        synchronized(lock) {
            pings++
            if (block >= 250L) blocksOver250++
            if (block >= 1000L) blocksOver1s++
            if (block >= 5000L) blocksOver5s++
            if (block > longestBlockMs) {
                longestBlockMs = block
                longestBlockAtS = atS
                longestBlockStack = stackAt1s ?: "(shorter than 1 s, not sampled)"
            }
        }
        if (block >= 1000L) {
            Log.w(TAG, "$label: main thread blocked $block ms at t=${fmt(atS)} s; stack after 1 s:\n$stackAt1s")
        }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)

    private fun pct(n: Int, of: Int) = if (of <= 0) "0" else String.format(Locale.US, "%.0f", 100.0 * n / of)

    private fun report(stage: String): String = synchronized(lock) {
        val sb = StringBuilder()
        sb.append("[ArSmoke] ").append(label).append(' ').append(stage).append(": t=").append(fmt(elapsedS())).append(" s; ")
            .append("main thread busy ").append(pct(busySamples, samples)).append(" % of ").append(samples).append(" samples; ")
            .append("longest block ").append(longestBlockMs).append(" ms at t=").append(fmt(longestBlockAtS)).append(" s; ")
            .append("blocks >=0.25 s: ").append(blocksOver250).append(", >=1 s: ").append(blocksOver1s)
            .append(", >=5 s: ").append(blocksOver5s).append(" of ").append(pings).append(" pings\n")
        sb.append("hot app frames (share of busy samples):\n")
        hotAppFrames.entries.sortedByDescending { it.value }.take(18).forEach {
            sb.append("  ").append(pct(it.value, busySamples)).append(" %  ").append(it.key).append('\n')
        }
        sb.append("hot top frames:\n")
        hotTopFrames.entries.sortedByDescending { it.value }.take(10).forEach {
            sb.append("  ").append(pct(it.value, busySamples)).append(" %  ").append(it.key).append('\n')
        }
        sb.append("hottest stacks:\n")
        hotStacks.entries.sortedByDescending { it.value }.take(4).forEach {
            sb.append("  [").append(it.value).append(" samples]\n").append(it.key).append('\n')
        }
        sb.append("longest block stack:\n").append(longestBlockStack).append('\n')
        sb.toString()
    }

    /** Logs and reports the current statistics; returns the report. */
    fun emit(stage: String): String {
        val text = report(stage)
        text.chunked(3800).forEach { Log.i(TAG, it) }
        try {
            InstrumentationRegistry.getInstrumentation().sendStatus(STATUS_CODE, Bundle().apply { putString("ArSmoke", text) })
        } catch (t: Throwable) {
            Log.w(TAG, "could not send instrumentation status", t)
        }
        return text
    }

    override fun close() {
        running = false
    }

    companion object {
        const val TAG = "ArSmoke"
        private const val APP_PACKAGE = "com.alijafari.red.astronomy"
        private const val SAMPLE_MS = 20L
        private const val PING_GAP_MS = 200L
        private const val STACK_DEPTH = 22
        private const val BLOCK_STACK_DEPTH = 45
        /** Not used by AndroidJUnitRunner's own status codes (1, 0, -1 … -4). */
        private const val STATUS_CODE = 2
        private val INTERIM_REPORTS_S = intArrayOf(20, 45, 75, 110)
    }
}
