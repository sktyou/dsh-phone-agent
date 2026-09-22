package com.dsh.phoneagent

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device text recognition — the complement to the accessibility tree.
 *
 * The two see different worlds:
 *
 * | surface                | a11y tree | OCR |
 * |------------------------|-----------|-----|
 * | native widgets         | exact     | redundant |
 * | WebView / H5           | partial   | good |
 * | games, Canvas, custom  | blind     | only option |
 * | text inside an image   | blind     | only option |
 *
 * So this is not a duplicate of [NodeQuery] — it is what covers the blind spots.
 * It is slower (hundreds of ms versus single-digit ms), which is why callers
 * should try the tree first and fall back to OCR.
 *
 * The recogniser is created once and reused: ML Kit client construction is
 * relatively expensive and the model load would otherwise cost on every call.
 */
object OcrEngine {

    private const val TIMEOUT_SECONDS = 20L

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * Recognise, optionally restricted to [region] and enlarged by [scale] first.
     *
     * ML Kit has no region parameter, so a restricted call crops the bitmap and shifts
     * the resulting boxes back into full-screen coordinates.
     *
     * When [enhance] is set, the crop is recognised twice — once as-is and once with
     * the contrast stretched — and the two results are merged. Small coloured text over
     * a busy photo is where recognition fails: on a real menu, orange price digits on a
     * food photograph were missed about a third of the time while white-on-solid titles
     * beside them read fine. Resampling alone barely moved the needle (13 → 12-13 price
     * rows across scale 1.0-2.5); stretching contrast first is what separates the
     * digits from the photo behind them.
     *
     * Coordinates are divided by [scale] on the way out, so callers always receive
     * screen pixels regardless of what the recogniser saw.
     */
    fun recognize(
        bitmap: Bitmap,
        region: Rect?,
        scale: Double = 1.0,
        enhance: Boolean = false,
    ): JSONObject {
        val box = region?.let {
            Rect(
                it.left.coerceIn(0, bitmap.width),
                it.top.coerceIn(0, bitmap.height),
                it.right.coerceIn(0, bitmap.width),
                it.bottom.coerceIn(0, bitmap.height),
            )
        }
        val cropped = box != null && box.width() > 0 && box.height() > 0 &&
            (box.width() != bitmap.width || box.height() != bitmap.height)
        val croppedBitmap = if (cropped) {
            Bitmap.createBitmap(bitmap, box!!.left, box.top, box.width(), box.height())
        } else {
            bitmap
        }
        val offsetX = if (cropped) box!!.left else 0
        val offsetY = if (cropped) box!!.top else 0

        val effective = scale.coerceIn(0.25, 4.0)
        val enlarged = if (effective != 1.0) {
            Bitmap.createScaledBitmap(
                croppedBitmap,
                (croppedBitmap.width * effective).toInt().coerceAtLeast(1),
                (croppedBitmap.height * effective).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            croppedBitmap
        }

        try {
            val base = runOnce(enlarged, offsetX, offsetY, effective)
            if (!enhance) return base

            val boosted = stretchContrast(enlarged)
            if (boosted == null) return base
            val second = try {
                runOnce(boosted, offsetX, offsetY, effective)
            } finally {
                if (boosted !== enlarged) boosted.recycle()
            }
            return merge(base, second, effective)
        } finally {
            if (enlarged !== croppedBitmap) enlarged.recycle()
            if (cropped) croppedBitmap.recycle()
        }
    }

    /** One recognition pass over an already-prepared bitmap. */
    private fun runOnce(
        source: Bitmap,
        offsetX: Int,
        offsetY: Int,
        scale: Double,
    ): JSONObject {
        val latch = CountDownLatch(1)
        var payload: JSONObject? = null
        var failure: Throwable? = null

        recognizer.process(InputImage.fromBitmap(source, 0))
            .addOnSuccessListener { text ->
                payload = serialize(text, offsetX, offsetY, scale)
                latch.countDown()
            }
            .addOnFailureListener { error ->
                failure = error
                latch.countDown()
            }
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException("OCR timed out after ${TIMEOUT_SECONDS}s")
        }
        failure?.let { throw IllegalStateException("OCR failed: ${it.message}", it) }
        return payload ?: emptyResult()
    }

    /**
     * Stretch contrast so coloured text separates from a photographic background.
     *
     * A linear scale about mid-grey: pixels already near the extremes stay put, the
     * midtones spread apart. Cheap, allocation-light, and enough to turn a digit that
     * was the same luminance as the food behind it into a readable edge.
     */
    private fun stretchContrast(source: Bitmap): Bitmap? = runCatching {
        val c = 1.8f
        val translate = 128f * (1f - c)
        val matrix = android.graphics.ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, translate,
                0f, c, 0f, 0f, translate,
                0f, 0f, c, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(
            source, 0f, 0f,
            android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(matrix) },
        )
        out
    }.getOrNull()

    /**
     * Merge two passes, keeping the better reading of each line.
     *
     * A line is the same line when the two passes put it in roughly the same place, so
     * the key is position-based rather than text-based: the enhanced pass may well read
     * a price the plain pass garbled, and that is the whole point. Where both agree on
     * placement, the higher-confidence reading wins.
     */
    private fun merge(first: JSONObject, second: JSONObject, scale: Double): JSONObject {
        val out = JSONArray()
        val taken = HashSet<String>()

        fun consider(source: JSONObject) {
            val lines = source.optJSONArray("ocrLines") ?: return
            for (i in 0 until lines.length()) {
                val entry = lines.optJSONObject(i) ?: continue
                val bounds = entry.optJSONArray("bounds")
                // Quantised to a few pixels: two passes rarely agree exactly.
                val key = if (bounds != null && bounds.length() >= 4) {
                    val qx = bounds.getInt(0) / 8
                    val qy = bounds.getInt(1) / 8
                    "$qx|$qy"
                } else {
                    entry.optString("text")
                }
                if (!taken.add(key)) {
                    // Already have this position; replace only if this reading is more
                    // confident.
                    for (j in 0 until out.length()) {
                        val existing = out.optJSONObject(j) ?: continue
                        val eb = existing.optJSONArray("bounds") ?: continue
                        val same = if (bounds != null && bounds.length() >= 4) {
                            eb.getInt(0) / 8 == bounds.getInt(0) / 8 &&
                                eb.getInt(1) / 8 == bounds.getInt(1) / 8
                        } else {
                            existing.optString("text") == entry.optString("text")
                        }
                        if (same) {
                            val a = existing.optDouble("confidence", 0.0)
                            val b = entry.optDouble("confidence", 0.0)
                            if (b > a) {
                                existing.put("text", entry.optString("text"))
                                existing.put("confidence", entry.opt("confidence"))
                                existing.put("source", "enhanced")
                            }
                            break
                        }
                    }
                    continue
                }
                out.put(JSONObject(entry.toString()))
            }
        }

        consider(first)
        consider(second)

        return JSONObject()
            .put("blockCount", first.optInt("blockCount"))
            .put("lineCount", out.length())
            .put("elementCount", first.optInt("elementCount"))
            .put("fullText", first.optString("fullText"))
            .put("ocrLines", out)
            .put("ocrScale", scale)
            .put("ocrEnhanced", true)
            .put("blocks", first.optJSONArray("blocks") ?: JSONArray())
    }

    private fun serialize(
        text: Text,
        offsetX: Int,
        offsetY: Int,
        scale: Double,
    ): JSONObject {
        val blocks = JSONArray()
        // Flat line list, in reading order.
        //
        // The nested `blocks` structure preserves ML Kit's own grouping, but a caller
        // pairing a price with the product above it needs geometry, not grouping — and
        // on a multi-column list the grouping follows the columns, which is exactly
        // the order that scrambles the pairing. A flat list with bounds lets the caller
        // do the spatial matching itself.
        val flat = JSONArray()
        var lineCount = 0
        var elementCount = 0

        for (block in text.textBlocks) {
            val lines = JSONArray()
            for (line in block.lines) {
                val elements = JSONArray()
                for (element in line.elements) {
                    elementCount++
                    elements.put(
                        describe(element.text, element.boundingBox, offsetX, offsetY, scale, element),
                    )
                }
                lineCount++

                val lineJson = describe(line.text, line.boundingBox, offsetX, offsetY, scale, null)
                if (elements.length() > 0) lineJson.put("elements", elements)
                lines.put(lineJson)

                // The flat entry mirrors the line, carrying the same coordinates so a
                // result from `ocr` and one from `findtext` land in one coordinate
                // system and can be compared directly.
                val flatEntry = JSONObject().put("text", line.text)
                line.boundingBox?.let { box ->
                    val l = toScreen(box.left, scale) + offsetX
                    val t = toScreen(box.top, scale) + offsetY
                    val r = toScreen(box.right, scale) + offsetX
                    val b = toScreen(box.bottom, scale) + offsetY
                    flatEntry.put("bounds", JSONArray(listOf(l, t, r, b)))
                    flatEntry.put("center", JSONArray(listOf((l + r) / 2, (t + b) / 2)))
                    flatEntry.put("height", b - t)
                }
                // Per-line confidence, averaged over its elements. ML Kit's Chinese
                // recogniser does not always populate this; absence is reported as
                // null rather than as a made-up number.
                val confidences = line.elements.mapNotNull { elementConfidence(it) }
                if (confidences.isNotEmpty()) {
                    flatEntry.put("confidence", confidences.average())
                } else {
                    flatEntry.put("confidence", JSONObject.NULL)
                }
                // `put`, not `add`: Android's platform org.json is not java.util.List,
                // and `add` only appears with the API 34 collection interface — using
                // it compiles against 34 and fails on the devices we support.
                flat.put(flatEntry)
            }
            val blockJson = describe(block.text, block.boundingBox, offsetX, offsetY, scale, null)
            if (lines.length() > 0) blockJson.put("lines", lines)
            blocks.put(blockJson)
        }

        return JSONObject()
            .put("blockCount", blocks.length())
            .put("lineCount", lineCount)
            .put("elementCount", elementCount)
            .put("fullText", text.text)
            .put("ocrLines", flat)
            .put("ocrScale", scale)
            .put("blocks", blocks)
    }

    /** Map a recogniser coordinate back to screen pixels. */
    private fun toScreen(value: Int, scale: Double): Int =
        if (scale == 1.0) value else (value / scale).toInt()

    /**
     * Element confidence, when the recogniser provides one.
     *
     * Guarded because the API is nullable and the Chinese model does not always fill
     * it — a missing value must not turn into a confident-looking 0.0.
     */
    private fun elementConfidence(element: Text.Element): Double? =
        runCatching { element.confidence?.toDouble() }.getOrNull()

    private fun describe(
        value: String,
        box: Rect?,
        offsetX: Int,
        offsetY: Int,
        scale: Double,
        element: Text.Element?,
    ): JSONObject {
        val out = JSONObject().put("text", value)
        if (box != null) {
            val left = toScreen(box.left, scale) + offsetX
            val top = toScreen(box.top, scale) + offsetY
            val right = toScreen(box.right, scale) + offsetX
            val bottom = toScreen(box.bottom, scale) + offsetY
            out.put("bounds", JSONArray(listOf(left, top, right, bottom)))
            out.put("center", JSONArray(listOf((left + right) / 2, (top + bottom) / 2)))
        }
        if (element != null) {
            val c = elementConfidence(element)
            out.put("confidence", c ?: JSONObject.NULL)
        }
        return out
    }

    private fun emptyResult() = JSONObject()
        .put("blockCount", 0)
        .put("lineCount", 0)
        .put("elementCount", 0)
        .put("fullText", "")
        .put("ocrLines", JSONArray())
        .put("blocks", JSONArray())
}
