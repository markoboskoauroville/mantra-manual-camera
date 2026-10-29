package com.mantraproductions.ndi

/**
 * v160: THE TRACKER, libmv's TrackRegion (the tracker of Blender's Movie Clip Editor, MIT) with Ceres and Eigen,
 * through JNI (cpp/mantra_tracker_jni.cpp, cpp/third_party/libmv/mantra_tracker.cc).
 *
 * MEASURED on the Mac before it came here (27.10.2026, his swinging tennis ball, live NDI picture, 24 fps): LOC held
 * 477 of 477 frames, median 1.25 px from the ball's real centre, worst 3.25 px, correlation 0.98, 0.41 ms a frame;
 * the camera's own NCC tracker lost the same ball for the whole swing. AFFINE on that small, smooth ball diverged
 * (six parameters on 27 px), so LOC is the default and the richer models are for larger, textured subjects, as in
 * Blender.
 *
 * Coordinates are pixels of the grey frame handed in; one instance follows one subject.
 */
class LibmvTracker private constructor(private var handle: Long) {
    private val out = DoubleArray(17)
    private val box = IntArray(4)

    /** A grey frame, w x h bytes. */
    constructor(grey: ByteArray, w: Int, h: Int, cx: Double, cy: Double, half: Double,
                model: Int = MODEL_LOC, minCorrelation: Double = 0.75, searchHalf: Int = 40) :
        this(nativeStart(grey, w, h, cx, cy, half, model, minCorrelation, searchHalf))

    /**
     * v160: an RGBA crop (a direct buffer, grey in the first byte) placed at (ox, oy) in the frame, straight from the
     * GPU stage's read-back; only the part around the subject is ever read.
     */
    constructor(rgba: java.nio.ByteBuffer, w: Int, h: Int, ox: Int, oy: Int, cx: Double, cy: Double, half: Double,
                model: Int, minCorrelation: Double, searchHalf: Int) :
        this(nativeStartRgba(rgba, w, h, ox, oy, cx, cy, half, model, minCorrelation, searchHalf))

    /** v160: the frame area the next [trackRgba] must be given (x0, y0, x1, y1; may reach past the frame). */
    fun searchBox(): IntArray { nativeSearchBox(handle, box); return box }

    fun trackRgba(rgba: java.nio.ByteBuffer, w: Int, h: Int, ox: Int, oy: Int): Result {
        nativeTrackRgba(handle, rgba, w, h, ox, oy, out)
        return Result(out[0], out[1], out[2], out[3] > 0.5, out[4], out.copyOfRange(5, 13))
    }

    class Result(val cx: Double, val cy: Double, val correlation: Double, val usable: Boolean, val ms: Double,
                 val corners: DoubleArray)

    fun track(grey: ByteArray, w: Int, h: Int): Result {
        nativeTrack(handle, grey, w, h, out)
        return Result(out[0], out[1], out[2], out[3] > 0.5, out[4], out.copyOfRange(5, 13))
    }

    fun release() { if (handle != 0L) { nativeRelease(handle); handle = 0L } }

    private external fun nativeTrack(handle: Long, grey: ByteArray, w: Int, h: Int, out: DoubleArray)
    private external fun nativeTrackRgba(handle: Long, rgba: java.nio.ByteBuffer, w: Int, h: Int, ox: Int, oy: Int, out: DoubleArray)
    private external fun nativeSearchBox(handle: Long, out: IntArray)
    private external fun nativeRelease(handle: Long)

    companion object {
        @JvmStatic private external fun nativeStart(grey: ByteArray, w: Int, h: Int, cx: Double, cy: Double, half: Double,
                                                    model: Int, minCorrelation: Double, searchHalf: Int): Long
        @JvmStatic private external fun nativeStartRgba(rgba: java.nio.ByteBuffer, w: Int, h: Int, ox: Int, oy: Int,
                                                        cx: Double, cy: Double, half: Double, model: Int,
                                                        minCorrelation: Double, searchHalf: Int): Long

        /** libmv's motion models, in its order (the MODEL key cycles them, as Blender's Motion Model menu). */
        const val MODEL_LOC = 0
        const val MODEL_LOC_ROT = 1
        const val MODEL_LOC_SCALE = 2
        const val MODEL_LOC_ROT_SCALE = 3
        const val MODEL_AFFINE = 4
        const val MODEL_PERSPECTIVE = 5
        val MODEL_NAMES = listOf("LOC", "LOC ROT", "LOC SCALE", "LOC ROT SCALE", "AFFINE", "PERSP")

        val available: Boolean = runCatching { System.loadLibrary("mantra_tracker"); true }
            .onFailure { Trace.fault("libmv tracker", it) }.getOrDefault(false)
    }
}
