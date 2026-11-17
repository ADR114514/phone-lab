package com.mobilelab.powertrace.data

import android.view.Choreographer

/**
 * 屏幕帧率测量：仅在 App 处于前台时由 MainActivity 启动（Choreographer 回调）。
 *
 * 数据字典 1.1 对 fps 的约定是"仅 App 在前台时可测（Choreographer），否则为空"，
 * 因此后台采样（例如熄屏待机工况）fps 写空字段，不做任何推测。
 */
object FpsMeter {

    private const val WINDOW_NS = 1_000_000_000L
    private const val FRESH_MS = 3_000L

    @Volatile private var attached = false
    @Volatile private var windowStartNs = 0L
    @Volatile private var frames = 0
    @Volatile private var measuredFps = 0.0
    @Volatile private var measuredAtMs = 0L

    private var choreographer: Choreographer? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!attached) return
            if (windowStartNs == 0L) windowStartNs = frameTimeNanos
            frames++
            val span = frameTimeNanos - windowStartNs
            if (span >= WINDOW_NS) {
                measuredFps = frames * 1_000_000_000.0 / span
                measuredAtMs = System.currentTimeMillis()
                frames = 0
                windowStartNs = frameTimeNanos
            }
            choreographer?.postFrameCallback(this)
        }
    }

    /** 必须在主线程调用（Activity.onResume）。 */
    fun attach() {
        if (attached) return
        choreographer = Choreographer.getInstance()
        windowStartNs = 0L
        frames = 0
        attached = true
        choreographer?.postFrameCallback(frameCallback)
    }

    /** 必须在主线程调用（Activity.onPause）。 */
    fun detach() {
        attached = false
        choreographer?.removeFrameCallback(frameCallback)
        choreographer = null
    }

    /** 最近 3 s 内的帧率；App 不在前台或数据过期时返回 null（CSV 写空）。 */
    fun currentFps(): Double? {
        if (!attached) return null
        if (measuredFps <= 0.0) return null
        if (System.currentTimeMillis() - measuredAtMs > FRESH_MS) return null
        return measuredFps
    }
}
