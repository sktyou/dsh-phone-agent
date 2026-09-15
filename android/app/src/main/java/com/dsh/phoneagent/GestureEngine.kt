package com.dsh.phoneagent

import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Human-like gesture synthesis.
 *
 * `adb shell input tap/swipe` emits a geometrically perfect stroke at constant
 * speed with a fixed inter-event gap. A finger does none of that: it lands
 * off-centre, traces a shallow arc around a wrist pivot, accelerates then
 * decelerates, and carries an 8–12 Hz physiological tremor on top.
 *
 * Everything below exists to reproduce those four traits on the
 * AccessibilityService gesture channel, which is the only injection path that
 * does not require root.
 */
object GestureEngine {

    /** Land scatter (px, 1σ). Real taps are never pixel-exact. */
    private const val TAP_SIGMA = 5.5

    /** Perpendicular tremor amplitude (px, 1σ) along a swipe. */
    private const val SWIPE_TREMOR = 0.95

    /**
     * How long the finger rests on the target before lifting, in milliseconds.
     *
     * Measured from the user's own hand: after a fast move they hold still for
     * roughly 200ms and only then lift. That pause is what stops the framework from
     * treating the gesture as a fling, and it costs nothing in perceived speed
     * because the travel itself is unchanged.
     */
    private const val HOLD_MS = 200.0

    /** Distance covered during the hold, as a fraction — small enough to read as still. */
    private const val HOLD_DIST = 0.015

    /**
     * Sum-of-three-uniforms approximation of N(0,1).
     *
     * Cheaper than a true normal sampler and, more importantly, bounded — a
     * pathological tail value would put the pointer outside the screen.
     */
    private fun gaussian(rng: Random): Double {
        var sum = 0.0
        repeat(3) { sum += rng.nextDouble() }
        return (sum - 1.5) / 0.5
    }

    /** Point on the quadratic Bézier (p0, control, p2) at parameter `t`. */
    private fun quad(
        x0: Float, y0: Float,
        cx: Float, cy: Float,
        x2: Float, y2: Float,
        t: Double,
    ): Pair<Double, Double> {
        val mt = 1.0 - t
        val a = mt * mt
        val b = 2.0 * mt * t
        val c = t * t
        return (a * x0 + b * cx + c * x2) to (a * y0 + b * cy + c * y2)
    }

    /**
     * A tap: off-centre landing, a short contact dwell, and a sub-pixel slip so
     * the stroke is never the degenerate zero-length path that automation
     * frameworks usually produce.
     */
    fun tap(x: Float, y: Float, rng: Random = Random.Default): GestureDescription {
        val px = x + (gaussian(rng) * TAP_SIGMA).toFloat()
        val py = y + (gaussian(rng) * TAP_SIGMA).toFloat()

        val slip = (0.5 + rng.nextDouble() * 1.9).toFloat()
        val angle = rng.nextDouble() * Math.PI * 2.0
        val path = Path().apply {
            moveTo(px, py)
            lineTo(px + (cos(angle) * slip).toFloat(), py + (sin(angle) * slip).toFloat())
        }

        // Contact dwell: 52–139 ms, matching a relaxed human press.
        val duration = (52L + rng.nextInt(88)).coerceAtLeast(1L)
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
    }

    /**
     * How far the content actually moved, by matching the screen against itself.
     *
     * A gesture's finger distance says nothing about what the screen did with it:
     * a list stops following past its own limits, a fling keeps going after lift-off,
     * and a page that does not scroll at all still receives the whole stroke. The
     * only honest measure is to compare the screen before and after.
     *
     * This samples a few columns and finds the vertical offset that best aligns
     * `before` with `after`. Columns are taken from the middle, away from status
     * bars and navigation buttons that never move and would bias the match toward
     * zero.
     *
     * Returns the shift in pixels: positive means the content moved up (the usual
     * "scrolled down" case). Returns null when no offset fits well, which is what a
     * screen change — a page flip, a dialog — looks like.
     */
    fun measureVerticalShift(before: Bitmap, after: Bitmap, maxShift: Int = 1600): ShiftResult {
        val h = min(before.height, after.height)
        val w = min(before.width, after.width)
        if (h < 200 || w < 100) return ShiftResult(null, 0.0, false)

        // Sample a few columns spread across the middle band. Columns come from the
        // middle of the screen, away from status bars and navigation buttons that
        // never move and would otherwise bias the match toward zero shift.
        val columns = intArrayOf(w * 3 / 10, w * 4 / 10, w / 2, w * 6 / 10, w * 7 / 10)

        val beforeCols = columns.map { col ->
            IntArray(h).also { before.getPixels(it, 0, 1, col, 0, 1, h) }
        }
        val afterCols = columns.map { col ->
            IntArray(h).also { after.getPixels(it, 0, 1, col, 0, 1, h) }
        }

        // A fixed window in `before`, mapped through each candidate shift. Without a
        // fixed window the number of compared rows shrinks as the shift grows, so a
        // mean-difference score is diluted most at small shifts and the search drifts
        // toward "barely moved" — which is exactly the wrong answer it produced
        // before this was fixed (272px for a gesture that really scrolled ~1130px).
        val winStart = h / 5
        val winEnd = h * 4 / 5
        val step = 3

        var bestShift = 0
        var bestScore = -2.0
        val limit = min(maxShift, h - 200)

        for (shift in -limit..limit) {
            var sumAB = 0.0
            var sumAA = 0.0
            var sumBB = 0.0
            var n = 0

            for (c in columns.indices) {
                val b = beforeCols[c]
                val a = afterCols[c]
                var y = winStart
                while (y < winEnd) {
                    val y2 = y + shift
                    if (y2 in 0 until h) {
                        // Luminance, then normalised cross-correlation: insensitive to
                        // the brightness and contrast drift that lazy image loading
                        // introduces, and independent of how many rows overlap.
                        val u = ((b[y] shr 16 and 0xFF) + (b[y] shr 8 and 0xFF) + (b[y] and 0xFF)).toDouble()
                        val v = ((a[y2] shr 16 and 0xFF) + (a[y2] shr 8 and 0xFF) + (a[y2] and 0xFF)).toDouble()
                        sumAB += u * v
                        sumAA += u * u
                        sumBB += v * v
                        n++
                    }
                    y += step
                }
            }

            if (n < 40) continue
            val denom = kotlin.math.sqrt(sumAA) * kotlin.math.sqrt(sumBB)
            val score = if (denom > 0) sumAB / denom else 0.0
            if (score > bestScore) {
                bestScore = score
                bestShift = shift
            }
        }

        // Correlation runs 0..1 for screens that match; anything below ~0.5 means the
        // two screenshots never really lined up, which is what a page change looks like.
        val confident = bestScore >= 0.5
        return ShiftResult(if (confident) -bestShift else null, bestScore, confident)
    }

    /** Outcome of a screen-matching pass, with the score kept for diagnosis. */
    data class ShiftResult(val shift: Int?, val score: Double, val confident: Boolean)

    /**
     * A swipe built as one continuous stroke (down → moves → up) split into
     * three speed phases, with an arced path and perpendicular tremor.
     *
     * Distance and time fractions each sum to 1, so the whole gesture lands on
     * the requested end point in roughly `durationMs`.
     *
     * ## Speed cannot live in the point spacing
     *
     * An earlier version tried to express the accelerate/cruise/decelerate profile by
     * spacing the samples unevenly, on the assumption that a stroke walks its path
     * point by point. It does not: **a StrokeDescription interpolates along the path
     * by length and spreads the duration evenly over that length**. Densely packed
     * points therefore change nothing about the speed, and an "end hold" written as
     * 1.5% of the distance over 60% of the time silently became 1.5% of the time too.
     * The gesture stayed a constant-speed drag, which is why a 300ms swipe overshot
     * while a 1000ms one landed — the only variable was average speed.
     *
     * ## So the stop is a separate continued stroke
     *
     * `buildSwipePath` produces the travel, and `buildBrakePath` produces a
     * near-stationary segment that `continueStroke` appends as one continuous touch.
     * That gives the framework a genuine stretch of near-zero velocity before
     * lift-off, which is what it looks at when deciding between drag and fling.
     *
     * The caller dispatches the two descriptions in sequence — see
     * `ControlServer.swipeSegmented`.
     */
    fun buildSwipePath(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        rng: Random = Random.Default,
        trace: MutableList<FloatArray>? = null,
    ): Path {
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        val dist = max(hypot(dx, dy), 1.0)

        // Unit normal: the direction the arc bows and the tremor pushes.
        val nx = -dy / dist
        val ny = dx / dist

        // Shallow bow around a randomised point along the stroke.
        val bow = gaussian(rng) * dist * 0.022
        val tControl = 0.35 + rng.nextDouble() * 0.30
        val cx = (x1 + dx * tControl + nx * bow).toFloat()
        val cy = (y1 + dy * tControl + ny * bow).toFloat()

        val samples = max(12, min(140, (dist / 12.0).toInt()))
        val path = Path()
        for (i in 0..samples) {
            val progress = i.toDouble() / samples
            val base = quad(x1, y1, cx, cy, x2, y2, progress)

            // Tremor fades in and out: the finger is planted on the glass at
            // touch-down and at lift-off, so it cannot wobble there.
            val taper = sin(progress * PI)
            val tremor = gaussian(rng) * SWIPE_TREMOR * taper

            val px = (base.first + nx * tremor).toFloat()
            val py = (base.second + ny * tremor).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            trace?.add(floatArrayOf(px, py, 0f))
        }
        return path
    }

    /**
     * The braking segment: the finger stays on the target and barely moves.
     *
     * Length is what matters here, not shape. The framework spreads the brake
     * duration across this path, so a path of roughly a pixel across keeps the
     * velocity near zero for the whole segment — which is what stops the gesture
     * from being read as a fling at lift-off.
     *
     * It is deliberately not a zero-length path: some builds reject a degenerate
     * stroke, and a sub-pixel wobble is closer to what a resting finger does anyway.
     */
    fun buildBrakePath(x: Float, y: Float, rng: Random = Random.Default): Path {
        val jitterX = x + (gaussian(rng) * 0.6).toFloat()
        val jitterY = y + (gaussian(rng) * 0.6).toFloat()
        return Path().apply {
            moveTo(x, y)
            lineTo(jitterX, jitterY)
        }
    }

    fun swipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 320L,
        rng: Random = Random.Default,
        trace: MutableList<FloatArray>? = null,
    ): GestureDescription {
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        val dist = max(hypot(dx, dy), 1.0)

        // Unit normal: the direction the arc bows and the tremor pushes.
        val nx = -dy / dist
        val ny = dx / dist

        // Shallow bow around a randomised point along the stroke.
        val bow = gaussian(rng) * dist * 0.022
        val tControl = 0.35 + rng.nextDouble() * 0.30
        val cx = (x1 + dx * tControl + nx * bow).toFloat()
        val cy = (y1 + dy * tControl + ny * bow).toFloat()

        val samples = max(12, min(140, (dist / 12.0).toInt()))

        val path = Path()
        for (i in 0..samples) {
            val progress = i.toDouble() / samples
            val t = easeProfile(progress, durationMs)
            val base = quad(x1, y1, cx, cy, x2, y2, t)

            // Tremor fades in and out: the finger is planted on the glass at
            // touch-down and at lift-off, so it cannot wobble there.
            val taper = sin(progress * PI)
            val tremor = gaussian(rng) * SWIPE_TREMOR * taper

            val px = (base.first + nx * tremor).toFloat()
            val py = (base.second + ny * tremor).toFloat()

            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            // Time is uniform across the samples by construction, so the timestamp is
            // simply the progress fraction of the duration.
            trace?.add(floatArrayOf(px, py, (durationMs * progress).toFloat()))
        }

        // ONE stroke. Adding three strokes (as this used to) does not chain them —
        // `addStroke` collects strokes that run simultaneously, which is how
        // multi-finger gestures are built — so the gesture became three fingers
        // dragging at once and the framework discarded most of it.
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
    }

    /**
     * Map uniform time progress onto the fraction of the distance covered by then.
     *
     * The last ~200ms are a **hold**: the finger reaches the target and stays there
     * before lifting off. This is not an invention — it is what the user reported
     * doing by hand ("到达目的地以后会停留约 200 毫秒,然后离开手指,这样就刹住车了"),
     * and it is precisely what makes a swipe stop where it was told to.
     *
     * The framework decides between drag and fling from the velocity at lift-off,
     * measured over roughly the last 100ms of samples. A pure ease-out cannot satisfy
     * that on a short swipe: covering 1000px in 300ms and still crawling through the
     * final 100ms is arithmetically impossible. The hold sidesteps the arithmetic —
     * the samples in the final window are coincident, so the measured velocity is
     * zero however fast the travel was.
     *
     * That is also why duration used to change the outcome: a long swipe happened to
     * have enough time to decelerate below the threshold, a short one did not.
     *
     * Profile: accelerate (0-22% of the moving part), cruise (22-55%), ease-out
     * deceleration (55-100%), then the hold. The hold is capped at 60% of the total
     * so an extremely short swipe still has time to travel.
     */
    private fun easeProfile(p: Double, durationMs: Long): Double {
        if (p <= 0.0) return 0.0
        if (p >= 1.0) return 1.0

        val hold = (HOLD_MS / durationMs.coerceAtLeast(1L).toDouble()).coerceIn(0.0, 0.60)
        val moveEnd = 1.0 - hold
        val moveDist = 1.0 - HOLD_DIST

        if (p >= moveEnd) {
            val u = if (hold > 0.0) (p - moveEnd) / hold else 1.0
            // Linear, but over ~1.5% of the distance: the finger is effectively still.
            return moveDist + HOLD_DIST * u.coerceIn(0.0, 1.0)
        }

        val q = if (moveEnd > 0.0) p / moveEnd else 1.0
        return when {
            q < 0.22 -> {
                val u = q / 0.22
                0.10 * (u * u * (3.0 - 2.0 * u))
            }
            q < 0.55 -> 0.10 + 0.55 * ((q - 0.22) / 0.33)
            else -> {
                val u = ((q - 0.55) / 0.45).coerceIn(0.0, 1.0)
                val inv = 1.0 - u
                0.65 + (moveDist - 0.65) * (1.0 - inv * inv * inv)
            }
        }
    }

    /** Long press: hold without leaving the target, with the drift a real finger has. */
    fun longPress(x: Float, y: Float, holdMs: Long = 650L, rng: Random = Random.Default): GestureDescription {
        val startX = x + (gaussian(rng) * TAP_SIGMA).toFloat()
        val startY = y + (gaussian(rng) * TAP_SIGMA).toFloat()

        // A finger is never perfectly still while holding — it drifts a fraction
        // of a pixel. Splitting the hold into segments expresses that drift as
        // movement, instead of one stroke frozen on a single point for 650 ms.
        val segments = 3
        val builder = GestureDescription.Builder()
        var stroke: GestureDescription.StrokeDescription? = null
        var elapsedMs = 0L
        var cursorX = startX
        var cursorY = startY

        for (i in 0 until segments) {
            val isLast = i == segments - 1
            val segDuration = max(1L, holdMs / segments)
            val driftX = (gaussian(rng) * 0.35).toFloat()
            val driftY = (gaussian(rng) * 0.35).toFloat()

            val path = Path().apply {
                moveTo(cursorX, cursorY)
                lineTo(cursorX + driftX, cursorY + driftY)
            }
            val segment = stroke?.continueStroke(path, elapsedMs, segDuration, !isLast)
                ?: GestureDescription.StrokeDescription(path, elapsedMs, segDuration, !isLast)
            builder.addStroke(segment)
            stroke = segment

            elapsedMs += segDuration
            cursorX += driftX
            cursorY += driftY
        }
        return builder.build()
    }

    /**
     * A double tap, emitted as one gesture holding two independent strokes.
     *
     * Both the gap and the second landing point vary: a finger does not return to
     * the same pixel, and a perfectly repeated interval is one of the easier
     * automation tells. Emitting it as a single description rather than two round
     * trips also keeps both contacts inside one input sequence, which is what the
     * framework uses to recognise a double tap at all.
     */
    fun doubleTap(x: Float, y: Float, rng: Random = Random.Default): GestureDescription {
        val firstPath = tapPath(x, y, rng)
        val secondPath = tapPath(x, y, rng)
        val firstDuration = 42L + rng.nextInt(34)
        val secondDuration = 42L + rng.nextInt(34)
        val gap = 88L + rng.nextInt(122)

        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(firstPath, 0L, firstDuration))
            .addStroke(
                GestureDescription.StrokeDescription(
                    secondPath,
                    firstDuration + gap,
                    secondDuration,
                ),
            )
            .build()
    }

    /** The contact path shared by [tap] and each half of [doubleTap]. */
    private fun tapPath(x: Float, y: Float, rng: Random): Path {
        val px = x + (gaussian(rng) * TAP_SIGMA).toFloat()
        val py = y + (gaussian(rng) * TAP_SIGMA).toFloat()
        val slip = (0.5 + rng.nextDouble() * 1.9).toFloat()
        val angle = rng.nextDouble() * Math.PI * 2.0
        return Path().apply {
            moveTo(px, py)
            lineTo(px + (cos(angle) * slip).toFloat(), py + (sin(angle) * slip).toFloat())
        }
    }

    /**
     * A two-finger pinch.
     *
     * Both fingers live in one gesture description, so they descend together —
     * which is what the framework uses to distinguish a pinch from two separate
     * swipes. `startSpread` larger than `endSpread` zooms out, smaller zooms in.
     */
    fun pinch(
        centreX: Float,
        centreY: Float,
        startSpread: Float,
        endSpread: Float,
        durationMs: Long = 400L,
        rng: Random = Random.Default,
    ): GestureDescription {
        // A wrist-driven pinch is rarely axis-aligned, so tilt the axis randomly.
        val angle = rng.nextDouble() * Math.PI * 2.0
        val axisX = cos(angle).toFloat()
        val axisY = sin(angle).toFloat()
        val perpX = -axisY
        val perpY = axisX

        val halfStart = (startSpread / 2f).coerceAtLeast(1f)
        val halfEnd = (endSpread / 2f).coerceAtLeast(1f)

        val segments = 3
        val segmentDuration = max(1L, durationMs / segments)
        val builder = GestureDescription.Builder()

        var leftStroke: GestureDescription.StrokeDescription? = null
        var rightStroke: GestureDescription.StrokeDescription? = null
        var elapsedMs = 0L

        for (i in 0 until segments) {
            val from = i.toDouble() / segments
            val to = (i + 1).toDouble() / segments
            val isLast = i == segments - 1

            val pathLeft = Path()
            val pathRight = Path()
            val samples = 6
            for (s in 0..samples) {
                val u = s.toDouble() / samples
                val t = from + (to - from) * u
                // Smoothstep along the pinch, plus a little tremor.
                val eased = t * t * (3 - 2 * t)
                val half = halfStart + (halfEnd - halfStart) * eased
                val taper = if (s == 0 || s == samples) 0.0 else 1.0
                val wobble = gaussian(rng) * 0.5 * taper

                val leftX = (centreX + axisX * half + perpX * wobble).toFloat()
                val leftY = (centreY + axisY * half + perpY * wobble).toFloat()
                val rightX = (centreX - axisX * half - perpX * wobble).toFloat()
                val rightY = (centreY - axisY * half - perpY * wobble).toFloat()

                if (s == 0) {
                    pathLeft.moveTo(leftX, leftY)
                    pathRight.moveTo(rightX, rightY)
                } else {
                    pathLeft.lineTo(leftX, leftY)
                    pathRight.lineTo(rightX, rightY)
                }
            }

            val nextLeft = leftStroke?.continueStroke(pathLeft, elapsedMs, segmentDuration, !isLast)
                ?: GestureDescription.StrokeDescription(pathLeft, elapsedMs, segmentDuration, !isLast)
            val nextRight = rightStroke?.continueStroke(pathRight, elapsedMs, segmentDuration, !isLast)
                ?: GestureDescription.StrokeDescription(pathRight, elapsedMs, segmentDuration, !isLast)
            builder.addStroke(nextLeft)
            builder.addStroke(nextRight)
            leftStroke = nextLeft
            rightStroke = nextRight
            elapsedMs += segmentDuration
        }
        return builder.build()
    }

    /**
     * A flick: short and fast, so the surface keeps moving under its own momentum.
     *
     * The same distance delivered slowly drags; delivered fast it throws. Apps
     * genuinely behave differently for the two, which is why this is a separate
     * gesture rather than a short `swipe`.
     */
    fun flick(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 90L,
        rng: Random = Random.Default,
    ): GestureDescription {
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        val dist = max(hypot(dx, dy), 1.0)
        val nx = -dy / dist
        val ny = dx / dist

        // A flick is nearly straight — the finger snaps rather than arcs — so the
        // bow is an order of magnitude smaller than a deliberate swipe's.
        val bow = gaussian(rng) * dist * 0.006
        val cx = (x1 + dx * 0.5 + nx * bow).toFloat()
        val cy = (y1 + dy * 0.5 + ny * bow).toFloat()

        val samples = max(6, (dist / 24.0).toInt())
        val path = Path()
        for (s in 0..samples) {
            val u = s.toDouble() / samples
            // Ease-out: the finger is fastest at the start and coasts to a stop.
            val eased = 1 - (1 - u) * (1 - u)
            val base = quad(x1, y1, cx, cy, x2, y2, eased)
            val taper = if (s == 0 || s == samples) 0.0 else 1.0
            val tremor = gaussian(rng) * 0.5 * taper
            val px = (base.first + nx * tremor).toFloat()
            val py = (base.second + ny * tremor).toFloat()
            if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        return GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)),
            )
            .build()
    }
}
