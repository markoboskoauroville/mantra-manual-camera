package com.mantraproductions.ndi

import android.view.Surface

/**
 * v197: what draws into the GPU stage's input when the phone's own lens does not: the USB camera (the Sony, v193) or
 * the TEST pattern (a machine with no camera, such as the cloud Pixel 7 the overnight tests run on).
 */
interface FrameSource {
    val width: Int
    val height: Int
    val name: String
    /** The monotonic time (System.nanoTime) of the newest frame, so the take and the stream follow the source. */
    val postedFrameNs: Long
    /** Starts drawing into [surface]; null when it runs, else why not. */
    fun start(surface: Surface): String?
    fun stop()
}
