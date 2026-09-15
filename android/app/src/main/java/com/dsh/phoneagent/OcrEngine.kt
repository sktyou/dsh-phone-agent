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
        var lineCount = 0
        var elementCount = 0

        for (block in text.textBlocks) {
            val lines = JSONArray()
            for (line in block.lines) {
                val elements = JSONArray()
                for (element in line.elements) {
                    elementCount++
                    elements.put(describe(element.text, element.boundingBox, offsetX, offsetY))
                }
                lineCount++
                val lineJson = describe(line.text, line.boundingBox, offsetX, offsetY)
                if (elements.length() > 0) lineJson.put("elements", elements)
                lines.put(lineJson)
            }
            val blockJson = describe(block.text, block.boundingBox, offsetX, offsetY)
            if (lines.length() > 0) blockJson.put("lines", lines)
            blocks.put(blockJson)
        }

        return JSONObject()
            .put("blockCount", blocks.length())
            .put("lineCount", lineCount)
            .put("elementCount", elementCount)
            .put("fullText", text.text)
            .put("blocks", blocks)
    }

    private fun describe(
        value: String,
        box: Rect?,
        offsetX: Int,
        offsetY: Int,
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
        return out
    }

    private fun emptyResult() = JSONObject()
        .put("blockCount", 0)
        .put("lineCount", 0)
        .put("elementCount", 0)
        .put("fullText", "")
        .put("blocks", JSONArray())
}
