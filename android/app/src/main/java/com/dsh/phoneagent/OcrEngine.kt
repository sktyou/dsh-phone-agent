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
     * Recognise text in [bitmap], optionally restricted to [region].
     *
     * ML Kit has no region parameter, so a restricted call crops the bitmap and
     * shifts the resulting boxes back into full-screen coordinates. It also does
     * not expose per-element confidence, so none is reported.
     */
    fun recognize(bitmap: Bitmap, region: Rect?): JSONObject {
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
        val source = if (cropped) {
            Bitmap.createBitmap(bitmap, box!!.left, box.top, box.width(), box.height())
        } else {
            bitmap
        }
        val offsetX = if (cropped) box!!.left else 0
        val offsetY = if (cropped) box!!.top else 0

        val latch = CountDownLatch(1)
        var payload: JSONObject? = null
        var failure: Throwable? = null

        try {
            recognizer.process(InputImage.fromBitmap(source, 0))
                .addOnSuccessListener { text ->
                    payload = serialize(text, offsetX, offsetY)
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
        } finally {
            if (cropped) source.recycle()
        }
    }

    private fun serialize(
        text: Text,
        offsetX: Int,
        offsetY: Int,
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
                    elements.put(describe(element.text, element.boundingBox, offsetX, offsetY, element))
                }
                lineCount++

                val lineJson = describe(line.text, line.boundingBox, offsetX, offsetY, null)
                if (elements.length() > 0) lineJson.put("elements", elements)
                lines.put(lineJson)

                // The flat entry mirrors the line, carrying the same coordinates so a
                // result from `ocr` and one from `findtext` land in one coordinate
                // system and can be compared directly.
                val flatEntry = JSONObject().put("text", line.text)
                line.boundingBox?.let { box ->
                    val l = box.left + offsetX
                    val t = box.top + offsetY
                    val r = box.right + offsetX
                    val b = box.bottom + offsetY
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
            val blockJson = describe(block.text, block.boundingBox, offsetX, offsetY, null)
            if (lines.length() > 0) blockJson.put("lines", lines)
            blocks.put(blockJson)
        }

        return JSONObject()
            .put("blockCount", blocks.length())
            .put("lineCount", lineCount)
            .put("elementCount", elementCount)
            .put("fullText", text.text)
            .put("ocrLines", flat)
            .put("blocks", blocks)
    }

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
        element: Text.Element?,
    ): JSONObject {
        val out = JSONObject().put("text", value)
        if (box != null) {
            val left = box.left + offsetX
            val top = box.top + offsetY
            val right = box.right + offsetX
            val bottom = box.bottom + offsetY
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
