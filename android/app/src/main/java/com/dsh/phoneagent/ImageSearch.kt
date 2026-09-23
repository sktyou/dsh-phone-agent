package com.dsh.phoneagent

import android.graphics.Bitmap
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pixel-level lookup on the phone, so the caller does not have to pull a full
 * screenshot down and post-process it.
 *
 * Two capabilities, with deliberately different costs:
 *
 * - [findColor] is cheap and exact. It is the right tool when the target has a
 *   stable colour (a badge, a progress bar, a game sprite) and the accessibility
 *   tree cannot see it — canvases, games, custom-drawn views.
 * - [findImage] is a bounded template match. A naive full-resolution search over
 *   a 1080x2400 screen with a 100x100 template is ~2.2e10 comparisons, which is
 *   tens of seconds; this implementation downsamples first and only refines
 *   around the best candidates.
 */
object ImageSearch {

    private const val MAX_COLOR_HITS = 5_000
    private const val COARSE_TEMPLATE_TARGET = 28
    private const val MAX_TEMPLATE_EDGE = 512

    // ------------------------------------------------------------------ colour

    /**
     * Multi-point colour search — a "feature" made of an anchor plus offsets.
     *
     * A single colour is rarely distinctive: on a real screen thousands of pixels
     * share any given tone, so one-colour search tells you where a colour is, not
     * where a *thing* is. The classic fix is to describe the thing by several points
     * that keep a fixed spatial relationship to the anchor. A match requires the
     * anchor to hit **and every offset to hit its own colour**, which is specific
     * enough to pick out one icon among hundreds.
     *
     * `points` is a list of `[dx, dy, colour]` relative to the anchor. Anchors are
     * scanned at every pixel; offsets are checked against the bitmap directly, so
     * the cost is (matching anchors) × (number of points) rather than a full rescan.
     */
    fun findMultiColor(
        bitmap: Bitmap,
        anchorColor: Int,
        points: List<Triple<Int, Int, Int>>,
        tolerance: Int,
        region: Rect?,
        maxMatches: Int,
    ): JSONObject {
        val width = bitmap.width
        val height = bitmap.height
        val box = clampRegion(region, width, height)
        val rw = box.width()
        val rh = box.height()
        if (rw <= 0 || rh <= 0 || points.isEmpty()) {
            return JSONObject()
                .put("count", 0)
                .put("scanned", 0)
                .put("matches", JSONArray())
        }

        val pixels = IntArray(rw * rh)
        bitmap.getPixels(pixels, 0, rw, box.left, box.top, rw, rh)

        val tol = tolerance.coerceIn(0, 255)
        val aR = (anchorColor shr 16) and 0xFF
        val aG = (anchorColor shr 8) and 0xFF
        val aB = anchorColor and 0xFF

        // Pre-split offset colours so the inner loop stays integer-only.
        val offX = IntArray(points.size)
        val offY = IntArray(points.size)
        val offR = IntArray(points.size)
        val offG = IntArray(points.size)
        val offB = IntArray(points.size)
        points.forEachIndexed { i, p ->
            offX[i] = p.first
            offY[i] = p.second
            offR[i] = (p.third shr 16) and 0xFF
            offG[i] = (p.third shr 8) and 0xFF
            offB[i] = p.third and 0xFF
        }

        val matches = JSONArray()
        var scanned = 0
        var anchorsTried = 0

        for (y in 0 until rh) {
            val rowBase = y * rw
            for (x in 0 until rw) {
                scanned++
                val c = pixels[rowBase + x]
                if (abs(((c shr 16) and 0xFF) - aR) > tol) continue
                if (abs(((c shr 8) and 0xFF) - aG) > tol) continue
                if (abs((c and 0xFF) - aB) > tol) continue
                anchorsTried++

                var all = true
                for (i in offX.indices) {
                    val px = x + offX[i]
                    val py = y + offY[i]
                    if (px < 0 || py < 0 || px >= rw || py >= rh) {
                        all = false
                        break
                    }
                    val q = pixels[py * rw + px]
                    if (abs(((q shr 16) and 0xFF) - offR[i]) > tol ||
                        abs(((q shr 8) and 0xFF) - offG[i]) > tol ||
                        abs((q and 0xFF) - offB[i]) > tol
                    ) {
                        all = false
                        break
                    }
                }
                if (!all) continue

                // De-duplicate: anchors a pixel apart describe the same feature, and
                // without this a single icon would report hundreds of matches.
                var near = false
                for (m in 0 until matches.length()) {
                    val prev = matches.getJSONObject(m).getJSONArray("center")
                    if (abs(prev.getInt(0) - (x + box.left)) <= 8 &&
                        abs(prev.getInt(1) - (y + box.top)) <= 8
                    ) {
                        near = true
                        break
                    }
                }
                if (near) continue

                matches.put(
                    JSONObject()
                        .put("center", JSONArray(listOf(x + box.left, y + box.top)))
                        .put(
                            "bounds",
                            JSONArray(
                                listOf(
                                    x + box.left + offX.min(), y + box.top + offY.min(),
                                    x + box.left + offX.max(), y + box.top + offY.max(),
                                ),
                            ),
                        ),
                )
                if (matches.length() >= maxMatches) {
                    return JSONObject()
                        .put("count", matches.length())
                        .put("scanned", scanned)
                        .put("anchorsTried", anchorsTried)
                        .put("truncated", true)
                        .put("matches", matches)
                }
            }
        }

        return JSONObject()
            .put("count", matches.length())
            .put("scanned", scanned)
            .put("anchorsTried", anchorsTried)
            .put("truncated", false)
            .put("matches", matches)
    }

    /**
     * Find pixels matching [color] within [tolerance] per channel.
     *
     * Matches are returned as clusters rather than raw pixels: a solid region
     * yields thousands of adjacent hits, and a caller almost always wants "where
     * is that region", not every one of them.
     */
    fun findColor(
        bitmap: Bitmap,
        color: Int,
        tolerance: Int,
        region: Rect?,
        maxClusters: Int,
        clusterSize: Int,
    ): JSONObject {
        val width = bitmap.width
        val height = bitmap.height
        val box = clampRegion(region, width, height)
        val rw = box.width()
        val rh = box.height()
        if (rw <= 0 || rh <= 0) return emptyColorResult()

        val pixels = IntArray(rw * rh)
        bitmap.getPixels(pixels, 0, rw, box.left, box.top, rw, rh)

        val targetR = (color shr 16) and 0xFF
        val targetG = (color shr 8) and 0xFF
        val targetB = color and 0xFF
        val tol = tolerance.coerceIn(0, 255)

        // Grid buckets: key = (cellX shl 16) or cellY. One pass, no sorting.
        val cell = clusterSize.coerceIn(2, 256)
        val buckets = HashMap<Int, IntArray>(256) // key -> [sumX, sumY, count, minX, minY, maxX, maxY]
        var totalHits = 0
        var truncated = false

        var index = 0
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                val pixel = pixels[index++]
                val dr = ((pixel shr 16) and 0xFF) - targetR
                val dg = ((pixel shr 8) and 0xFF) - targetG
                val db = (pixel and 0xFF) - targetB
                if (dabs(dr) > tol || dabs(dg) > tol || dabs(db) > tol) continue

                totalHits++
                if (totalHits > MAX_COLOR_HITS) {
                    truncated = true
                    break
                }

                val gx = box.left + x
                val gy = box.top + y
                val key = ((gx / cell) shl 16) or ((gy / cell) and 0xFFFF)
                val bucket = buckets[key]
                if (bucket == null) {
                    buckets[key] = intArrayOf(gx, gy, 1, gx, gy, gx, gy)
                } else {
                    bucket[0] += gx
                    bucket[1] += gy
                    bucket[2] += 1
                    if (gx < bucket[3]) bucket[3] = gx
                    if (gy < bucket[4]) bucket[4] = gy
                    if (gx > bucket[5]) bucket[5] = gx
                    if (gy > bucket[6]) bucket[6] = gy
                }
            }
            if (truncated) break
        }

        val ordered = buckets.values.sortedByDescending { it[2] }.take(maxClusters.coerceIn(1, 200))
        val clusters = JSONArray()
        for (bucket in ordered) {
            val count = bucket[2]
            clusters.put(
                JSONObject()
                    .put("center", JSONArray(listOf(bucket[0] / count, bucket[1] / count)))
                    .put("bounds", JSONArray(listOf(bucket[3], bucket[4], bucket[5], bucket[6])))
                    .put("pixels", count),
            )
        }

        return JSONObject()
            .put("count", totalHits)
            .put("clusterCount", clusters.length())
            .put("truncated", truncated)
            .put("region", JSONArray(listOf(box.left, box.top, box.right, box.bottom)))
            .put("clusters", clusters)
    }

    // ----------------------------------------------------------------- template

    /**
     * Locate [template] inside [bitmap] by normalised sum-of-absolute-differences.
     *
     * Strategy: scale both images down until the template is ~28 px on its long
     * edge, sweep that small space exhaustively, then verify the best candidates
     * at full resolution. The coarse pass is what keeps this interactive.
     */
    fun findImage(
        bitmap: Bitmap,
        template: Bitmap,
        threshold: Double,
        region: Rect?,
        maxResults: Int,
    ): JSONObject {
        val box = clampRegion(region, bitmap.width, bitmap.height)
        if (template.width > MAX_TEMPLATE_EDGE || template.height > MAX_TEMPLATE_EDGE) {
            throw IllegalArgumentException(
                "template must be at most ${MAX_TEMPLATE_EDGE}px per edge " +
                    "(got ${template.width}x${template.height})",
            )
        }
        if (template.width > box.width() || template.height > box.height()) {
            return JSONObject().put("count", 0).put("matches", JSONArray())
        }

        val source = if (box.width() == bitmap.width && box.height() == bitmap.height) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
        }

        val longEdge = max(template.width, template.height)
        val scale = max(1, longEdge / COARSE_TEMPLATE_TARGET)
        val smallSource = scaleDown(source, scale)
        val smallTemplate = scaleDown(template, scale)

        val coarse = sweep(smallSource, smallTemplate, topK = max(8, maxResults * 2))
        if (coarse.isEmpty()) {
            return JSONObject()
                .put("count", 0)
                .put("matches", JSONArray())
                .put("coarseScale", scale)
                .put("reason", "coarse-sweep-empty")
                .put("smallSource", "${smallSource.width}x${smallSource.height}")
                .put("smallTemplate", "${smallTemplate.width}x${smallTemplate.height}")
        }

        // Diagnostic: what the coarse pass actually thought, and what refinement made of
        // it. Without this, "count: 0" says nothing about whether the template was ever
        // visible to the search or merely scored badly at the end.
        val coarseBest = JSONArray()
        for (c in coarse.take(5)) {
            coarseBest.put(
                JSONObject()
                    .put("x", c.first.first)
                    .put("y", c.first.second)
                    .put("score", c.second),
            )
        }

        val matches = JSONArray()
        val seen = HashSet<Long>()
        val refinedScores = ArrayList<Double>()
        for (candidate in coarse) {
            // Refine on the original pixels, with the coarse result as the centre.
            val refined = refine(source, template, candidate, scale)
            val score = refined.second
            refinedScores.add(score)
            // `score` is a *difference* (0 = identical, 1 = nothing alike); `threshold`
            // is the similarity a caller asks for. Comparing them directly inverted the
            // test, so a pixel-perfect template was discarded and only dissimilar
            // regions could ever be reported — which is why every threshold returned
            // nothing at all.
            if (1.0 - score < threshold) continue
            val key = ((refined.first.first / 8).toLong() shl 32) or ((refined.first.second / 8).toLong() and 0xFFFFFFFFL)
            if (!seen.add(key)) continue
            matches.put(
                JSONObject()
                    .put("center", JSONArray(listOf(refined.first.first + template.width / 2, refined.first.second + template.height / 2)))
                    .put("topLeft", JSONArray(listOf(refined.first.first + box.left, refined.first.second + box.top)))
                    .put("bounds", JSONArray(
                        listOf(
                            refined.first.first + box.left,
                            refined.first.second + box.top,
                            refined.first.first + box.left + template.width,
                            refined.first.second + box.top + template.height,
                        ),
                    ))
                    .put("similarity", (1.0 - score)),
            )
            if (matches.length() >= maxResults.coerceIn(1, 50)) break
        }

        return JSONObject()
            .put("count", matches.length())
            .put("coarseScale", scale)
            .put("smallSource", "${smallSource.width}x${smallSource.height}")
            .put("smallTemplate", "${smallTemplate.width}x${smallTemplate.height}")
            .put("coarseBest", coarseBest)
            .put(
                "refineBest",
                JSONArray(
                    refinedScores.sorted().take(5).map { JSONObject().put("score", it) },
                ),
            )
            .put("matches", matches)
    }

    /** Exhaustive SAD sweep over a small image; returns the best [topK] offsets. */
    private fun sweep(source: Bitmap, template: Bitmap, topK: Int): List<Pair<Pair<Int, Int>, Double>> {
        val sw = source.width
        val sh = source.height
        val tw = template.width
        val th = template.height
        if (tw <= 0 || th <= 0 || tw > sw || th > sh) return emptyList()

        val src = IntArray(sw * sh)
        source.getPixels(src, 0, sw, 0, 0, sw, sh)
        val tpl = IntArray(tw * th)
        template.getPixels(tpl, 0, tw, 0, 0, tw, th)

        val results = ArrayList<Pair<Pair<Int, Int>, Double>>(sw * sh / 4)
        for (y in 0..(sh - th)) {
            for (x in 0..(sw - tw)) {
                var diff = 0L
                var i = 0
                for (ty in 0 until th) {
                    val srcRow = (y + ty) * sw + x
                    for (tx in 0 until tw) {
                        val a = src[srcRow + tx]
                        val b = tpl[i++]
                        diff += dabs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)).toLong()
                        diff += dabs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)).toLong()
                        diff += dabs((a and 0xFF) - (b and 0xFF)).toLong()
                    }
                }
                val score = diff.toDouble() / (tw.toDouble() * th * 3 * 255)
                results.add((x to y) to score)
            }
        }
        return results.sortedBy { it.second }.take(topK)
    }

    /** Re-check one coarse candidate at full resolution in a small neighbourhood. */
    private fun refine(
        source: Bitmap,
        template: Bitmap,
        coarse: Pair<Pair<Int, Int>, Double>,
        scale: Int,
    ): Pair<Pair<Int, Int>, Double> {
        val cx = coarse.first.first * scale
        val cy = coarse.first.second * scale
        val radius = max(scale * 2, 4)
        val sw = source.width
        val sh = source.height
        val tw = template.width
        val th = template.height

        val src = IntArray(sw * sh)
        source.getPixels(src, 0, sw, 0, 0, sw, sh)
        val tpl = IntArray(tw * th)
        template.getPixels(tpl, 0, tw, 0, 0, tw, th)

        var bestX = cx
        var bestY = cy
        var best = Double.MAX_VALUE
        for (y in max(0, cy - radius)..min(sh - th, cy + radius)) {
            for (x in max(0, cx - radius)..min(sw - tw, cx + radius)) {
                var diff = 0L
                var i = 0
                for (ty in 0 until th) {
                    val srcRow = (y + ty) * sw + x
                    for (tx in 0 until tw) {
                        val a = src[srcRow + tx]
                        val b = tpl[i++]
                        diff += dabs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)).toLong()
                        diff += dabs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)).toLong()
                        diff += dabs((a and 0xFF) - (b and 0xFF)).toLong()
                    }
                }
                val score = diff.toDouble() / (tw.toDouble() * th * 3 * 255)
                if (score < best) {
                    best = score
                    bestX = x
                    bestY = y
                }
            }
        }
        return (bestX to bestY) to best
    }

    private fun scaleDown(source: Bitmap, factor: Int): Bitmap {
        if (factor <= 1) return source
        val w = max(1, source.width / factor)
        val h = max(1, source.height / factor)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun clampRegion(region: Rect?, width: Int, height: Int): Rect {
        val r = region ?: Rect(0, 0, width, height)
        val left = r.left.coerceIn(0, width)
        val top = r.top.coerceIn(0, height)
        val right = r.right.coerceIn(0, width)
        val bottom = r.bottom.coerceIn(0, height)
        return Rect(left, top, max(left, right), max(top, bottom))
    }

    private fun emptyColorResult() = JSONObject()
        .put("count", 0)
        .put("clusterCount", 0)
        .put("truncated", false)
        .put("clusters", JSONArray())

    private fun dabs(value: Int): Int = if (value < 0) -value else value

    /** Parse `#RRGGBB`, `#AARRGGBB` or a bare integer into a colour int. */
    fun parseColor(text: String): Int {
        val cleaned = text.trim().removePrefix("#")
        val value = cleaned.toLongOrNull(16)
            ?: throw IllegalArgumentException("colour must be hex like #FF5722 (got \"$text\")")
        return when (cleaned.length) {
            6 -> (0xFF000000L or value).toInt()
            8 -> value.toInt()
            else -> throw IllegalArgumentException("colour must be #RRGGBB or #AARRGGBB (got \"$text\")")
        }
    }
}
