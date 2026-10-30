package com.mantraproductions.ndi

import android.opengl.GLES20
import android.opengl.GLES30
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v186 (Marko, 30.10.2026: *"move all the tracking, processing, and calculation to the GPU"*): THE GPU TRACKER.
 *
 * What made libmv hold the swinging ball (sub-pixel refinement, a pattern that never drifts, LOC only) is kept, and
 * everything runs as fragment shaders on the GPU stage's own thread, every frame (momentaryupdates.md, the plan of
 * 30.10.2026, points 1–8):
 *
 * 1. **A pyramid.** The luma copy (640 wide) is drawn into one of [HISTORY] textures with three mipmap levels below
 *    it (1/2, 1/4, 1/8); a search starts on the coarsest level the pattern still has [MIN_COARSE_HALF] pixels on.
 * 2. **Coarse to fine.** Normalised cross-correlation, one fragment per place: the whole search zone on the top
 *    level, ±[FINE_RADIUS] around the place found one level up on each finer level. The place found is passed
 *    from level to level in a 1x1 texture, never through the CPU.
 * 3. **A max reduction.** The score grid (score, x, y) is reduced to its best in two passes (rows, then the column).
 * 4. **Lucas–Kanade.** [LK_ITERATIONS] inverse-compositional steps, translation only, on the full-resolution level,
 *    brightness and contrast normalised (the pattern's gradients and Hessian are worked out once, at the tap).
 * 5. **One pixel read back** a frame: the refined place, and the NCC best beside it as its check.
 * 6. **Prediction.** The search is centred where constant velocity puts the subject.
 * 7. **Re-finding.** Once LOST (and at a tap on a moving subject), the next frame is searched whole on the top level.
 * 8. **The pattern from the frame of the tap.** The last [HISTORY] frames are kept with the moment they arrived; a
 *    tap takes its pattern from the frame that was on the screen when the finger came down, not the one after it.
 *
 * Coordinates are texels of the luma copy (GL rows, 0 at the bottom), a texel's centre at its index, as libmv's.
 * Everything here runs on the GPU stage's thread with its context current.
 */
class GpuTracker(
    private val lw: Int,
    private val lh: Int,
    private val link: (String, String) -> Int?,
    private val drawQuad: (Int) -> Unit,
) {
    class Step(val x: Float, val y: Float, val score: Float, val found: Boolean, val refind: Boolean,
               val nccX: Float, val nccY: Float, val ms: Double, val wholeTexelScore: Float = score)

    private val frames = IntArray(HISTORY)
    private val frameFbos = IntArray(HISTORY)
    private val arrivedAt = LongArray(HISTORY)
    private var head = -1
    private var filled = 0

    private var refTex = 0
    private var refFbo = 0
    private var scoreTex = 0
    private var scoreFbo = 0
    private var rowTex = 0
    private var rowFbo = 0
    private val bestTex = IntArray(MAX_LEVEL + 1)
    private val bestFbo = IntArray(MAX_LEVEL + 1)
    private val posTex = IntArray(2)
    private val posFbo = IntArray(2)

    private var copyProgram = 0
    private var nccProgram = 0
    private var reduceProgram = 0
    private var lkRowsProgram = 0
    private var lkUpdateProgram = 0

    /** The frame just drawn: its texture and framebuffer (level 0 is the luma copy). */
    val currentFbo get() = frameFbos[head]
    val refFramebuffer get() = refFbo

    // the subject
    private var half = 8
    private var top = 0
    private var topRadius = 8
    private var refAt = IntArray(2)
    private var confidence = 0.6f
    private var cx = 0f
    private var cy = 0f
    private var vx = 0f
    private var vy = 0f
    private var refind = false
    private var active = false
    private var steps = 0L
    private var pending: FloatArray? = null
    private var pendingAt = 0L
    // the pattern's constants for Lucas–Kanade: Σgx, Σgy, Σgx(T-mT), Σgy(T-mT); Gxx, Gxy, Gyy; mT, σT; n
    private val tSums = FloatArray(4)
    private val hessian = FloatArray(3)
    private var meanT = 0f
    private var sigmaT = 0f
    private var count = 1f

    private val pixel: ByteBuffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
    private var patch: ByteBuffer? = null

    /** Null when ready, else why not (the stage then keeps libmv). */
    fun setUp(): String? {
        val version = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        if (!version.contains("OpenGL ES 3.2") && !extensions.contains("GL_EXT_color_buffer_float"))
            return "no float render targets ($version)"
        copyProgram = link(VERTEX, COPY) ?: return "copy shader"
        nccProgram = link(VERTEX, NCC) ?: return "NCC shader"
        reduceProgram = link(VERTEX, REDUCE) ?: return "reduce shader"
        lkRowsProgram = link(VERTEX, LK_ROWS) ?: return "LK rows shader"
        lkUpdateProgram = link(VERTEX, LK_UPDATE) ?: return "LK update shader"

        val t = IntArray(HISTORY + 1)
        GLES20.glGenTextures(HISTORY + 1, t, 0)
        val f = IntArray(HISTORY + 1)
        GLES20.glGenFramebuffers(HISTORY + 1, f, 0)
        for (i in 0..HISTORY) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[i])
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, MAX_LEVEL + 1, GLES30.GL_RGBA8, lw, lh)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            if (!attach(f[i], t[i])) return "luma framebuffer $i incomplete"
        }
        System.arraycopy(t, 0, frames, 0, HISTORY); System.arraycopy(f, 0, frameFbos, 0, HISTORY)
        refTex = t[HISTORY]; refFbo = f[HISTORY]

        fun float(w: Int, h: Int): IntArray {
            val tt = IntArray(1); val ff = IntArray(1)
            GLES20.glGenTextures(1, tt, 0); GLES20.glGenFramebuffers(1, ff, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tt[0])
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, w, h)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            return if (attach(ff[0], tt[0])) intArrayOf(tt[0], ff[0]) else intArrayOf(0, 0)
        }
        float(lw, lh).let { scoreTex = it[0]; scoreFbo = it[1] }
        float(1, maxOf(lw, lh)).let { rowTex = it[0]; rowFbo = it[1] }
        for (k in 0..MAX_LEVEL) float(1, 1).let { bestTex[k] = it[0]; bestFbo[k] = it[1] }
        for (k in 0..1) float(1, 1).let { posTex[k] = it[0]; posFbo[k] = it[1] }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (scoreFbo == 0 || rowFbo == 0 || bestFbo.any { it == 0 } || posFbo.any { it == 0 })
            return "float framebuffer incomplete ($version)"
        Trace.state("gpu tracker ready: ${lw}x$lh, $HISTORY frames kept, levels to 1/${1 shl MAX_LEVEL}, $version")
        return null
    }

    private fun attach(fbo: Int, tex: Int): Boolean {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        return GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
    }

    /** The next frame's luma: [drawLuma] draws it into the bound framebuffer; its pyramid follows. */
    fun newFrame(drawLuma: () -> Unit) {
        head = (head + 1) % HISTORY
        filled = minOf(filled + 1, HISTORY)
        arrivedAt[head] = SystemClock.uptimeMillis()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbos[head])
        GLES20.glViewport(0, 0, lw, lh)
        drawLuma()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frames[head])
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
    }

    /**
     * A new subject at ([x], [y]) (0..1, y from the top), a square pattern [halfPx] texels each side of its centre;
     * [atUptime] is when the finger came down (0: now). Taken on the next [step], from the kept frame of that moment.
     */
    fun start(x: Float, y: Float, halfPx: Int, search: Float, confidence: Float, atUptime: Long) {
        half = halfPx.coerceIn(4, MAX_HALF)
        top = (MAX_LEVEL downTo 0).first { k -> half shr k >= MIN_COARSE_HALF || k == 0 }
        topRadius = Math.ceil((half * (search - 1f) + 8f) / (1 shl top).toDouble()).toInt().coerceIn(2, 64)
        this.confidence = confidence
        pending = floatArrayOf(x, y)
        pendingAt = atUptime
        active = true
    }

    fun stop() { active = false; pending = null }

    /** True when the pattern was just taken (the step after [start]); the stage then starts libmv from [refFbo]. */
    var tookPattern = false
        private set

    /** One step on the frame just drawn by [newFrame]. Null while there is nothing to follow. */
    fun step(): Step? {
        if (!active || head < 0) return null
        tookPattern = false
        pending?.let { (x, y) ->
            takePattern(x, y)
            pending = null
            tookPattern = true
            return Step(cx / lw, 1f - cy / lh, 1f, true, refind, cx / lw, 1f - cy / lh, 0.0)
        }
        val t0 = SystemClock.elapsedRealtimeNanos()
        val luma = frames[head]
        // the top level: the predicted place ± the search radius, or the whole level when re-finding
        val s = 1 shl top
        val levelW = maxOf(1, lw shr top)
        val levelH = maxOf(1, lh shr top)
        val hk = levelHalf(top)
        val ox: Int; val oy: Int; val gw: Int; val gh: Int
        if (refind) {
            ox = hk; oy = hk; gw = maxOf(1, levelW - 2 * hk); gh = maxOf(1, levelH - 2 * hk)
        } else {
            val px = cx + vx; val py = cy + vy
            ox = Math.round((px - (s - 1) * 0.5f) / s) - topRadius
            oy = Math.round((py - (s - 1) * 0.5f) / s) - topRadius
            gw = 2 * topRadius + 1; gh = gw
        }
        ncc(luma, top, ox, oy, gw, gh, fromPrev = false, radius = 0)
        reduce(gw, gh, top)
        for (k in top - 1 downTo 0) {
            ncc(luma, k, 0, 0, 2 * FINE_RADIUS + 1, 2 * FINE_RADIUS + 1, fromPrev = true, radius = FINE_RADIUS)
            reduce(2 * FINE_RADIUS + 1, 2 * FINE_RADIUS + 1, k)
        }
        // Lucas–Kanade on level 0, starting from the NCC best
        var from = bestTex[0]
        var into = 0
        for (i in 0 until LK_ITERATIONS) {
            lkRows(luma, from)
            lkUpdate(from, into)
            from = posTex[into]; into = 1 - into
        }
        // the score where it settled, sub-pixel, as libmv's correlation is (whole texels scored a fine, faint texture
        // 0.60 where libmv saw 0.80, 30.10.2026)
        lkRows(luma, from, score = true)
        lkUpdate(from, into, score = true)
        val lk = read(posFbo[into])
        val ncc = read(bestFbo[0])
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6

        // the refinement is trusted within 1.5 texels of the NCC best; further means it slid off
        val useLk = !lk[1].isNaN() && !lk[0].isNaN() &&
            Math.hypot((lk[1] - ncc[1]).toDouble(), (lk[2] - ncc[2]).toDouble()) <= 1.5
        val score = if (useLk) maxOf(lk[0], ncc[0]) else ncc[0]
        val nx = if (useLk) lk[1] else ncc[1]
        val ny = if (useLk) lk[2] else ncc[2]
        val wasRefind = refind
        val found = score >= confidence
        if (found) {
            if (!wasRefind) {
                vx = (nx - cx).coerceIn(-MAX_V, MAX_V); vy = (ny - cy).coerceIn(-MAX_V, MAX_V)
            } else { vx = 0f; vy = 0f }
            cx = nx; cy = ny
            refind = false
        } else {
            refind = true
            vx = 0f; vy = 0f
        }
        steps++
        return Step(cx / lw, 1f - cy / lh, score, found, wasRefind, ncc[1] / lw, 1f - ncc[2] / lh, ms, ncc[0])
    }

    private fun levelHalf(k: Int) = maxOf(2, Math.round(half / (1 shl k).toFloat()))

    /** The kept frame on the screen when the finger came down; copied, with its pyramid, as the reference. */
    private fun takePattern(x: Float, y: Float) {
        var pick = head
        var back = 0
        if (pendingAt > 0) {
            val want = pendingAt - SCREEN_LAG_MS
            for (i in 0 until filled) {
                val j = ((head - i) % HISTORY + HISTORY) % HISTORY
                pick = j; back = i
                if (arrivedAt[j] <= want) break
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, refFbo)
        GLES20.glViewport(0, 0, lw, lh)
        GLES20.glUseProgram(copyProgram)
        bind(1, frames[pick], copyProgram, "uSrc")
        drawQuad(copyProgram)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, refTex)
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D)

        cx = x * lw
        cy = (1f - y) * lh
        val rx = Math.round(cx).coerceIn(half + 1, lw - half - 2)
        val ry = Math.round(cy).coerceIn(half + 1, lh - half - 2)
        refAt = intArrayOf(rx, ry)
        cx = rx.toFloat(); cy = ry.toFloat()
        vx = 0f; vy = 0f
        // a pattern from an older frame: the subject has moved since, so the first step searches the whole frame
        refind = back > 0
        // the pattern's own numbers for Lucas–Kanade, read once
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, refFbo)
        val side = 2 * half + 3
        val buf = patch?.takeIf { it.capacity() >= side * side * 4 }
            ?: ByteBuffer.allocateDirect(side * side * 4).order(ByteOrder.nativeOrder()).also { patch = it }
        buf.clear()
        GLES20.glReadPixels(rx - half - 1, ry - half - 1, side, side, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fun t(i: Int, j: Int) = (buf.get(((j + half + 1) * side + (i + half + 1)) * 4).toInt() and 0xFF) / 255f
        var st = 0.0; var st2 = 0.0; var sgx = 0.0; var sgy = 0.0; var sgxt = 0.0; var sgyt = 0.0
        var gxx = 0.0; var gxy = 0.0; var gyy = 0.0
        for (j in -half..half) for (i in -half..half) {
            val v = t(i, j).toDouble()
            val gx = 0.5 * (t(i + 1, j) - t(i - 1, j)); val gy = 0.5 * (t(i, j + 1) - t(i, j - 1))
            st += v; st2 += v * v; sgx += gx; sgy += gy; sgxt += gx * v; sgyt += gy * v
            gxx += gx * gx; gxy += gx * gy; gyy += gy * gy
        }
        val n = ((2 * half + 1) * (2 * half + 1)).toDouble()
        val mT = st / n
        count = n.toFloat()
        meanT = mT.toFloat()
        sigmaT = Math.sqrt(maxOf(st2 / n - mT * mT, 1e-12)).toFloat()
        tSums[0] = sgx.toFloat(); tSums[1] = sgy.toFloat()
        tSums[2] = (sgxt - mT * sgx).toFloat(); tSums[3] = (sgyt - mT * sgy).toFloat()
        hessian[0] = gxx.toFloat(); hessian[1] = gxy.toFloat(); hessian[2] = gyy.toFloat()
        Trace.state(String.format(java.util.Locale.ROOT,
            "trk pattern: %d,%d half %d of %dx%d, top level 1/%d (half %d, radius %d), from the frame %d back (%d ms before the touch), contrast %.3f%s",
            rx, ry, half, lw, lh, 1 shl top, levelHalf(top), topRadius, back,
            if (pendingAt > 0) pendingAt - arrivedAt[pick] else 0L, sigmaT, if (refind) ", whole-frame search next" else ""))
    }

    private fun bind(unit: Int, tex: Int, program: Int, name: String) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), unit)
    }

    private fun ncc(luma: Int, level: Int, ox: Int, oy: Int, gw: Int, gh: Int, fromPrev: Boolean, radius: Int) {
        val p = nccProgram
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, scoreFbo)
        GLES20.glViewport(0, 0, gw, gh)
        GLES20.glUseProgram(p)
        bind(1, luma, p, "uLuma")
        bind(2, refTex, p, "uRef")
        // the level above's best; nothing on the top level (its own best is written next, never read while drawn)
        bind(3, if (fromPrev) bestTex[level + 1] else 0, p, "uPrev")
        val s = 1 shl level
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uLevel"), level)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uHalf"), levelHalf(level))
        GLES30.glUniform2i(GLES20.glGetUniformLocation(p, "uRefAt"),
            Math.round((refAt[0] - (s - 1) * 0.5f) / s), Math.round((refAt[1] - (s - 1) * 0.5f) / s))
        GLES30.glUniform2i(GLES20.glGetUniformLocation(p, "uOrigin"), ox, oy)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uFromPrev"), if (fromPrev) 1 else 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uRadius"), radius)
        drawQuad(p)
    }

    /** The best of a [gw] x [gh] score grid into bestTex[level]: the best of each row, then of the column. */
    private fun reduce(gw: Int, gh: Int, level: Int) {
        val p = reduceProgram
        GLES20.glUseProgram(p)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, rowFbo)
        GLES20.glViewport(0, 0, 1, gh)
        bind(1, scoreTex, p, "uIn")
        GLES30.glUniform2i(GLES20.glGetUniformLocation(p, "uKeep"), 0, 1)
        GLES30.glUniform2i(GLES20.glGetUniformLocation(p, "uStep"), 1, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uCount"), gw)
        drawQuad(p)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bestFbo[level])
        GLES20.glViewport(0, 0, 1, 1)
        bind(1, rowTex, p, "uIn")
        GLES30.glUniform2i(GLES20.glGetUniformLocation(p, "uKeep"), 0, 0)
        GLES30.glUniform2i(GLES20.glGetUniformLocation(p, "uStep"), 0, 1)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uCount"), gh)
        drawQuad(p)
    }

    private fun lkRows(luma: Int, pos: Int, score: Boolean = false) {
        val p = lkRowsProgram
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, rowFbo)
        GLES20.glViewport(0, 0, 1, 2 * half + 1)
        GLES20.glUseProgram(p)
        bind(1, luma, p, "uLuma")
        bind(2, refTex, p, "uRef")
        bind(3, pos, p, "uPos")
        GLES30.glUniform2i(GLES20.glGetUniformLocation(p, "uRefAt"), refAt[0], refAt[1])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uHalf"), half)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uSize"), lw.toFloat(), lh.toFloat())
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uScore"), if (score) 1 else 0)
        drawQuad(p)
    }

    private fun lkUpdate(pos: Int, into: Int, score: Boolean = false) {
        val p = lkUpdateProgram
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, posFbo[into])
        GLES20.glViewport(0, 0, 1, 1)
        GLES20.glUseProgram(p)
        bind(1, rowTex, p, "uRows")
        bind(2, pos, p, "uPos")
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uCount"), 2 * half + 1)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(p, "uTSums"), tSums[0], tSums[1], tSums[2], tSums[3])
        GLES20.glUniform3f(GLES20.glGetUniformLocation(p, "uH"), hessian[0], hessian[1], hessian[2])
        GLES20.glUniform3f(GLES20.glGetUniformLocation(p, "uT"), meanT, sigmaT, count)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uScore"), if (score) 1 else 0)
        drawQuad(p)
    }

    private fun read(fbo: Int): FloatArray {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        pixel.clear()
        GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_FLOAT, pixel)
        return FloatArray(4) { pixel.getFloat(it * 4) }
    }

    fun release() {
        val t = (frames + refTex + scoreTex + rowTex + bestTex + posTex).filter { it != 0 }.toIntArray()
        val f = (frameFbos + refFbo + scoreFbo + rowFbo + bestFbo + posFbo).filter { it != 0 }.toIntArray()
        if (t.isNotEmpty()) GLES20.glDeleteTextures(t.size, t, 0)
        if (f.isNotEmpty()) GLES20.glDeleteFramebuffers(f.size, f, 0)
        for (p in intArrayOf(copyProgram, nccProgram, reduceProgram, lkRowsProgram, lkUpdateProgram))
            if (p != 0) GLES20.glDeleteProgram(p)
    }

    companion object {
        /** Frames kept for the pattern of a tap: 16 at 24 fps is two thirds of a second. */
        const val HISTORY = 16
        /** From a frame's arrival to its being on the screen, about one frame (24 fps). */
        const val SCREEN_LAG_MS = 40L
        const val MAX_LEVEL = 3
        const val MIN_COARSE_HALF = 5
        const val MAX_HALF = 64
        const val FINE_RADIUS = 2
        const val LK_ITERATIONS = 4
        /** The largest move a frame the prediction believes, in texels of the 640 copy. */
        const val MAX_V = 40f

        private const val VERTEX = """#version 300 es
in vec4 aPosition;
in vec2 aTexCoord;
void main() {
    gl_Position = aPosition;
}
"""

        private const val COPY = """#version 300 es
precision mediump float;
uniform sampler2D uSrc;
out vec4 outColour;
void main() {
    outColour = texelFetch(uSrc, ivec2(gl_FragCoord.xy), 0);
}
"""

        /**
         * NCC of the pattern (the reference frame around uRefAt, at this level) at one place: the place is
         * uOrigin + this fragment, or, with uFromPrev, the best of the level above (uPrev, in level-0 texels)
         * brought to this level, - uRadius + this fragment. Out: (score, x, y) with x, y in level-0 texels.
         */
        private const val NCC = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uLuma;
uniform sampler2D uRef;
uniform sampler2D uPrev;
uniform int uLevel;
uniform int uHalf;
uniform ivec2 uRefAt;
uniform ivec2 uOrigin;
uniform int uFromPrev;
uniform int uRadius;
out vec4 outColour;
void main() {
    float s = exp2(float(uLevel));
    ivec2 origin = uOrigin;
    if (uFromPrev == 1) {
        vec4 prev = texelFetch(uPrev, ivec2(0), 0);
        if (prev.x < -1.5) { outColour = vec4(-2.0, 0.0, 0.0, 0.0); return; }
        origin = ivec2(floor((prev.yz - (s - 1.0) * 0.5) / s + 0.5)) - ivec2(uRadius);
    }
    ivec2 size = textureSize(uLuma, uLevel);
    ivec2 q = origin + ivec2(gl_FragCoord.xy);
    if (any(lessThan(q, ivec2(uHalf))) || any(greaterThanEqual(q, size - ivec2(uHalf)))) {
        outColour = vec4(-2.0, 0.0, 0.0, 0.0);
        return;
    }
    float sp = 0.0; float sp2 = 0.0; float sw = 0.0; float sw2 = 0.0; float spw = 0.0;
    for (int y = -uHalf; y <= uHalf; y++) {
        for (int x = -uHalf; x <= uHalf; x++) {
            float p = texelFetch(uRef, uRefAt + ivec2(x, y), uLevel).r;
            float w = texelFetch(uLuma, q + ivec2(x, y), uLevel).r;
            sp += p; sp2 += p * p; sw += w; sw2 += w * w; spw += p * w;
        }
    }
    float n = float((2 * uHalf + 1) * (2 * uHalf + 1));
    float cov = spw - sp * sw / n;
    float vp = sp2 - sp * sp / n;
    float vw = sw2 - sw * sw / n;
    float ncc = (vp > 1e-6 && vw > 1e-6) ? clamp(cov / sqrt(vp * vw), -1.0, 1.0) : 0.0;
    outColour = vec4(ncc, vec2(q) * s + (s - 1.0) * 0.5, 1.0);
}
"""

        /** The best (highest .x) of uCount texels from ivec2(fragment) * uKeep, uStep apart. */
        private const val REDUCE = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uIn;
uniform ivec2 uKeep;
uniform ivec2 uStep;
uniform int uCount;
out vec4 outColour;
void main() {
    ivec2 b = ivec2(gl_FragCoord.xy) * uKeep;
    vec4 best = vec4(-3.0, 0.0, 0.0, 0.0);
    for (int k = 0; k < uCount; k++) {
        vec4 v = texelFetch(uIn, b + k * uStep, 0);
        if (v.x > best.x) best = v;
    }
    outColour = best;
}
"""

        /**
         * One row of the pattern for a Lucas–Kanade step: the pattern's gradient times the frame, bilinear, at
         * the place in uPos (.yz) + the offset; out: (Σ gx·I, Σ gy·I, Σ I, Σ I²); with uScore (Σ T·I, Σ I, Σ I², 0).
         */
        private const val LK_ROWS = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uLuma;
uniform sampler2D uRef;
uniform sampler2D uPos;
uniform ivec2 uRefAt;
uniform int uHalf;
uniform vec2 uSize;
uniform int uScore;
out vec4 outColour;
void main() {
    int j = int(gl_FragCoord.y) - uHalf;
    vec2 p = texelFetch(uPos, ivec2(0), 0).yz;
    vec4 acc = vec4(0.0);
    for (int i = -uHalf; i <= uHalf; i++) {
        ivec2 r = uRefAt + ivec2(i, j);
        float v = textureLod(uLuma, (p + vec2(float(i), float(j)) + 0.5) / uSize, 0.0).r;
        if (uScore == 1) {
            acc += vec4(texelFetch(uRef, r, 0).r * v, v, v * v, 0.0);
        } else {
            float gx = 0.5 * (texelFetch(uRef, r + ivec2(1, 0), 0).r - texelFetch(uRef, r - ivec2(1, 0), 0).r);
            float gy = 0.5 * (texelFetch(uRef, r + ivec2(0, 1), 0).r - texelFetch(uRef, r - ivec2(0, 1), 0).r);
            acc += vec4(gx * v, gy * v, v, v * v);
        }
    }
    outColour = acc;
}
"""

        /**
         * The rows summed, then the inverse-compositional update for a translation, the frame's window brought to
         * the pattern's mean and contrast: e = a (I - mI) - (T - mT), a = σT / σI, Δ = H⁻¹ Σ ∇T e, p ← p − Δ.
         * Out: (the NCC score carried, the new place, |Δ|). With uScore, the NCC at the place instead (LK_ROWS too).
         */
        private const val LK_UPDATE = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uRows;
uniform sampler2D uPos;
uniform int uCount;
uniform vec4 uTSums;
uniform vec3 uH;
uniform vec3 uT;
uniform int uScore;
out vec4 outColour;
void main() {
    vec4 s = vec4(0.0);
    for (int k = 0; k < uCount; k++) s += texelFetch(uRows, ivec2(0, k), 0);
    vec4 pos = texelFetch(uPos, ivec2(0), 0);
    float n = uT.z;
    if (uScore == 1) {
        // the NCC of the pattern at the refined place: (Σ T·I − n mT mI) / (n σT σI)
        float mI1 = s.y / n;
        float sI = sqrt(max(s.z / n - mI1 * mI1, 1e-10));
        outColour = vec4(clamp((s.x / n - uT.x * mI1) / (uT.y * sI), -1.0, 1.0), pos.yz, 0.0);
        return;
    }
    float mI = s.z / n;
    float vI = max(s.w / n - mI * mI, 1e-10);
    float a = uT.y / sqrt(vI);
    vec2 b = a * (s.xy - mI * uTSums.xy) - uTSums.zw;
    float det = uH.x * uH.z - uH.y * uH.y;
    vec2 d = det > 1e-12 ? vec2(uH.z * b.x - uH.y * b.y, uH.x * b.y - uH.y * b.x) / det : vec2(0.0);
    d = clamp(d, -2.0, 2.0);
    outColour = vec4(pos.x, pos.yz - d, length(d));
}
"""
    }
}
