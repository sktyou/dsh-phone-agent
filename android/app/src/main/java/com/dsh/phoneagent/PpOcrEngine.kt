package com.dsh.phoneagent

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * PP-OCR (PaddleOCR) inference, running locally through ONNX Runtime.
 *
 * The bundled ML Kit recogniser reads clean black-on-white text well but garbles
 * small coloured text over photographs — measured on a real menu, 28% of product
 * titles came back wrong or unreadable, and the same frame gave different results on
 * different runs. PP-OCR reads those correctly and is deterministic, which matters
 * more than the average accuracy: a caller that re-runs a sweep and gets a different
 * answer cannot tell a real change from noise.
 *
 * Three models, all PP-OCRv6 small:
 *  - **det** — DBNet. Produces a per-pixel text probability map.
 *  - **rec** — CRNN with a CTC head. Reads one cropped line into characters.
 *  - **dict** — the character set `rec` emits indices into.
 *
 * There is no direction-classifier model: this is used on screenshots, which are
 * upright by construction, and the classifier only exists to handle rotated photos.
 *
 * Models load once and live for the process lifetime — session construction reads
 * tens of megabytes and takes hundreds of milliseconds.
 */
object PpOcrEngine {

    private const val TAG = "DSHPpOcr"

    // Detection input is letterboxed to a fixed long side. 960 is the value the
    // upstream mobile presets use; larger inputs cost time roughly quadratically and
    // gain nothing on a 1080-wide screenshot that is being shrunk anyway.
    private const val DET_LIMIT_SIDE = 960
    private const val DET_MEAN = 0.5f
    private const val DET_STD = 0.5f
    /**
     * Probability above which a pixel counts as text.
     *
     * Lower than the 0.3 the upstream presets use. A missed line is invisible — the
     * caller never learns the row existed — whereas a spurious box costs one extra
     * recognition pass and comes back with low confidence that a caller can filter.
     * Measured on a menu, 0.3 dropped around 15% of lines that 0.2 kept.
     */
    private const val DET_BOX_THRESH = 0.2f
    /** Fraction of the box height every side grows by when expanding to the text edge. */
    private const val DET_UNCLIP_RATIO = 1.6f

    /** Recognition crops are normalised to this height; width follows the aspect ratio. */
    private const val REC_HEIGHT = 48
    private const val REC_MEAN = 0.5f
    private const val REC_STD = 0.5f

    /**
     * Longest crop fed to the recogniser, in pixels.
     *
     * The CRNN's cost is linear in width — every 8-pixel step becomes one timestep —
     * so this is the single biggest lever on recognition time. 320 is PP-OCR's own
     * training width and reads a full menu line comfortably; allowing 1200 made
     * recognition take 1.9s instead of a fraction of that, for text that is identical.
     *
     * Lines wider than this are squeezed rather than truncated, which distorts glyphs
     * slightly but keeps every character present — a truncated line would silently lose
     * the end of a product name.
     */
    private const val REC_MAX_WIDTH = 640

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var dictionary: List<String> = emptyList()
    private var loadError: String? = null

    @Volatile
    private var loaded = false

    /** Whether the models are present and usable. */
    fun isReady(): Boolean = loaded

    fun loadError(): String? = loadError

    /**
     * Load the models. Safe to call repeatedly; only the first call does work.
     *
     * [assets] is the app's asset manager, passed in rather than reached statically so
     * this object stays testable and does not need a Context.
     */
    @Synchronized
    fun ensureLoaded(assets: android.content.res.AssetManager): Boolean {
        if (loaded) return true
        if (loadError != null) return false
        return try {
            val e = OrtEnvironment.getEnvironment()
            env = e

            val opts = OrtSession.SessionOptions().apply {
                // Four threads: the K30 has eight cores, but recognition here is memory
                // bound and more threads stopped helping past this in practice.
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            val detBytes = assets.open("ocr/PP-OCRv6_det_small.onnx").use { it.readBytes() }
            detSession = e.createSession(detBytes, opts)

            val recBytes = assets.open("ocr/PP-OCRv6_rec_small.onnx").use { it.readBytes() }
            recSession = e.createSession(recBytes, opts)

            dictionary = assets.open("ocr/ppocrv6_dict.txt").use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readLines()
                    .map { it.trimEnd('\r', '\n') }
            }

            loaded = true
            android.util.Log.i(TAG, "PP-OCR ready: det+rec loaded, dict ${dictionary.size} entries")
            true
        } catch (t: Throwable) {
            loadError = t.message ?: t.toString()
            android.util.Log.e(TAG, "PP-OCR load failed: $loadError", t)
            false
        }
    }

    /**
     * Recognise [bitmap], restricted to [region] when given.
     *
     * Returns the same shape the ML Kit path returns, so callers and the PC-side
     * scripts do not care which engine produced it.
     */
    fun recognize(
        assets: android.content.res.AssetManager,
        bitmap: Bitmap,
        region: Rect?,
    ): JSONObject {
        if (!ensureLoaded(assets)) {
            throw IllegalStateException("PP-OCR 模型未就绪: ${loadError ?: "未知原因"}")
        }
        val e = env ?: throw IllegalStateException("ONNX 环境未初始化")
        val det = detSession ?: throw IllegalStateException("det 模型未加载")
        val rec = recSession ?: throw IllegalStateException("rec 模型未加载")

        val box = region?.let {
            Rect(
                it.left.coerceIn(0, bitmap.width),
                it.top.coerceIn(0, bitmap.height),
                it.right.coerceIn(0, bitmap.width),
                it.bottom.coerceIn(0, bitmap.height),
            )
        } ?: Rect(0, 0, bitmap.width, bitmap.height)
        val cr = Rect(
            box.left, box.top,
            box.right.coerceAtLeast(box.left + 1),
            box.bottom.coerceAtLeast(box.top + 1),
        )

        val started = System.currentTimeMillis()
        val boxes = detect(e, det, bitmap, cr)
        val boxesMs = System.currentTimeMillis() - started

        // Recognition is per-line and independent, so it parallelises cleanly.
        //
        // Sequentially this is the dominant cost — measured 21 lines in 2.7s, about
        // 127ms each — and a menu screenful routinely has 40+. OrtSession.run is
        // documented as thread-safe, and each call touches its own crop, so the only
        // shared state is the (read-only) model.
        val lines = JSONArray()
        val recStarted = System.currentTimeMillis()
        // Recogniser threads scale with the device rather than a fixed number: the work
        // is per-line and independent, and a phone with eight cores finishes a menu
        // screenful in about a third less time than one with four.
        val workers = minOf(
            maxOf(2, Runtime.getRuntime().availableProcessors() - 1),
            maxOf(1, boxes.size),
        )
        if (boxes.isEmpty()) {
            // nothing to do
        } else if (workers == 1) {
            for (b in boxes) {
                appendLine(lines, readLine(e, rec, bitmap, b), b)?.let { }
            }
        } else {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
            try {
                val futures = boxes.map { b ->
                    pool.submit(java.util.concurrent.Callable { b to readLine(e, rec, bitmap, b) })
                }
                // Collected in submission order so the output stays deterministic — the
                // whole reason for choosing this engine is that the same frame gives the
                // same answer, and that would be lost by emitting in completion order.
                for (f in futures) {
                    val (b, read) = f.get()
                    appendLine(lines, read, b)
                }
            } finally {
                pool.shutdown()
            }
        }
        val recMs = System.currentTimeMillis() - recStarted

        val fullText = buildString {
            for (i in 0 until lines.length()) {
                if (i > 0) append('\n')
                append(lines.getJSONObject(i).optString("text"))
            }
        }

        return JSONObject()
            .put("engine", "ppocr")
            .put("blockCount", boxes.size)
            .put("lineCount", lines.length())
            .put("elementCount", lines.length())
            .put("fullText", fullText)
            .put("ocrLines", lines)
            .put("blocks", JSONArray())
            .put("detMs", boxesMs)
            .put("recMs", recMs)
    }

    /** Append one recognised line, skipping blanks. */
    private fun appendLine(lines: JSONArray, read: Pair<String, Double>?, b: Rect) {
        if (read == null || read.first.isBlank()) return
        lines.put(
            JSONObject()
                .put("text", normalizeGlyphs(read.first))
                .put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom)))
                .put("center", JSONArray(listOf(b.centerX(), b.centerY())))
                .put("height", b.height())
                .put("confidence", read.second),
        )
    }

    // ------------------------------------------------------------------ detection

    /**
     * DBNet: resize, run, threshold the probability map, group pixels into boxes.
     *
     * The post-processing is the fussy part. A probability map has no notion of
     * "a line" — it is per-pixel — so text regions are found by flood-filling
     * connected components and taking each one's bounding box, then growing the box
     * back out because a shrunk training target means the map's region is thinner than
     * the glyphs it represents.
     */
    private fun detect(
        e: OrtEnvironment,
        session: OrtSession,
        bitmap: Bitmap,
        region: Rect,
    ): List<Rect> {
        val srcW = region.width()
        val srcH = region.height()
        if (srcW < 8 || srcH < 8) return emptyList()

        // Letterbox: both sides scale by the same factor so glyph shapes survive, and
        // the result is padded to a multiple of 32 (the network's total stride).
        val scale = minOf(
            DET_LIMIT_SIDE.toDouble() / srcW,
            DET_LIMIT_SIDE.toDouble() / srcH,
        )
        val netW = (srcW * scale).toInt().let { alignUp(it, 32) }.coerceAtLeast(32)
        val netH = (srcH * scale).toInt().let { alignUp(it, 32) }.coerceAtLeast(32)

        val scaled = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(bitmap, region.left, region.top, srcW, srcH),
            netW, netH, true,
        )

        val pixels = IntArray(netW * netH)
        scaled.getPixels(pixels, 0, netW, 0, 0, netW, netH)
        scaled.recycle()

        // NCHW float, normalised the way the training config specifies.
        val input = FloatBuffer.allocate(3 * netW * netH)
        for (c in 0 until 3) {
            for (i in pixels.indices) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> ((p shr 16) and 0xFF) / 255f
                    1 -> ((p shr 8) and 0xFF) / 255f
                    else -> (p and 0xFF) / 255f
                }
                input.put((v - DET_MEAN) / DET_STD)
            }
        }
        input.rewind()

        val tensor = OnnxTensor.createTensor(
            e, input,
            longArrayOf(1, 3, netH.toLong(), netW.toLong()),
        )
        val name = session.inputNames.iterator().next()
        val output = tensor.use { session.run(mapOf(name to it)) }
        val map = output.use { result ->
            val t = result[0] as OnnxTensor
            val shape = t.info.shape
            val h = shape[2].toInt()
            val w = shape[3].toInt()
            val buf = t.floatBuffer
            val arr = FloatArray(h * w)
            buf.get(arr)
            arr to (w to h)
        }

        val (prob, wh) = map
        val mapW = wh.first
        val mapH = wh.second

        // Map coordinates back to screen pixels: the letterbox scale, then the region
        // origin. The probability map has the same dimensions as the network input.
        val toScreenX = srcW.toDouble() / mapW
        val toScreenY = srcH.toDouble() / mapH

        val boxes = connectedBoxes(prob, mapW, mapH, DET_BOX_THRESH)
        val out = ArrayList<Rect>(boxes.size)
        for (b in boxes) {
            // Unclip: the training target is a shrunk version of the text, so grow the
            // box back. The ratio applies to the shorter side, which approximates the
            // glyph height.
            val pad = (minOf(b.width(), b.height()) * (DET_UNCLIP_RATIO - 1f) / 2f).toInt()
            val l = ((b.left - pad) * toScreenX).toInt() + region.left
            val t = ((b.top - pad) * toScreenY).toInt() + region.top
            val r = ((b.right + pad) * toScreenX).toInt() + region.left
            val bo = ((b.bottom + pad) * toScreenY).toInt() + region.top
            val rect = Rect(
                l.coerceIn(region.left, region.right),
                t.coerceIn(region.top, region.bottom),
                r.coerceIn(region.left, region.right),
                bo.coerceIn(region.top, region.bottom),
            )
            // Anything too small to hold a character is noise from the probability map.
            if (rect.width() >= 6 && rect.height() >= 8) out.add(rect)
        }
        return out
    }

    /**
     * Connected components over the thresholded map, returned as bounding boxes.
     *
     * An iterative flood fill rather than recursion: a full-width text line is tens of
     * thousands of pixels, and recursing over them would overflow the stack. Scanning
     * is row-major so boxes come out roughly in reading order, which keeps the output
     * stable between runs.
     */
    private fun connectedBoxes(
        prob: FloatArray,
        width: Int,
        height: Int,
        threshold: Float,
    ): List<Rect> {
        val visited = BooleanArray(width * height)
        val out = ArrayList<Rect>()
        // Every pixel is pushed at most once, so the stack can never exceed the image.
        val stack = IntArray(width * height)

        for (start in 0 until width * height) {
            if (visited[start]) continue
            visited[start] = true
            if (prob[start] < threshold) continue

            var sp = 0
            stack[sp++] = start

            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1

            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % width
                val y = idx / width

                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y

                // 4-connected, not 8. Diagonal connectivity merges neighbouring lines
                // on tight layouts — two menu rows a few pixels apart become one box —
                // which is precisely the failure being fixed here.
                if (x > 0) {
                    val n = idx - 1
                    if (!visited[n]) {
                        visited[n] = true
                        if (prob[n] >= threshold) stack[sp++] = n
                    }
                }
                if (x < width - 1) {
                    val n = idx + 1
                    if (!visited[n]) {
                        visited[n] = true
                        if (prob[n] >= threshold) stack[sp++] = n
                    }
                }
                if (y > 0) {
                    val n = idx - width
                    if (!visited[n]) {
                        visited[n] = true
                        if (prob[n] >= threshold) stack[sp++] = n
                    }
                }
                if (y < height - 1) {
                    val n = idx + width
                    if (!visited[n]) {
                        visited[n] = true
                        if (prob[n] >= threshold) stack[sp++] = n
                    }
                }
            }

            if (maxX >= 0) {
                out.add(Rect(minX, minY, maxX + 1, maxY + 1))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ recognition

    /**
     * Read one cropped line.
     *
     * The crop is resized to a fixed height with its aspect ratio preserved, then run
     * through the CRNN. Output is a per-timestep distribution over the dictionary;
     * CTC collapses repeats and drops blanks.
     */
    private fun readLine(
        e: OrtEnvironment,
        session: OrtSession,
        bitmap: Bitmap,
        box: Rect,
    ): Pair<String, Double>? {
        val w = box.width()
        val h = box.height()
        if (w < 4 || h < 4) return null

        val recW = ((REC_HEIGHT.toDouble() * w / h).toInt()).coerceIn(8, REC_MAX_WIDTH)
        val crop = Bitmap.createBitmap(bitmap, box.left, box.top, w, h)
        val scaled = Bitmap.createScaledBitmap(crop, recW, REC_HEIGHT, true)
        crop.recycle()

        val pixels = IntArray(recW * REC_HEIGHT)
        scaled.getPixels(pixels, 0, recW, 0, 0, recW, REC_HEIGHT)
        scaled.recycle()

        val input = FloatBuffer.allocate(3 * recW * REC_HEIGHT)
        for (c in 0 until 3) {
            for (i in pixels.indices) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> ((p shr 16) and 0xFF) / 255f
                    1 -> ((p shr 8) and 0xFF) / 255f
                    else -> (p and 0xFF) / 255f
                }
                input.put((v - REC_MEAN) / REC_STD)
            }
        }
        input.rewind()

        val tensor = OnnxTensor.createTensor(
            e, input,
            longArrayOf(1, 3, REC_HEIGHT.toLong(), recW.toLong()),
        )
        val name = session.inputNames.iterator().next()
        val result = tensor.use { session.run(mapOf(name to it)) }
        return result.use { r ->
            val t = r[0] as OnnxTensor
            val shape = t.info.shape
            val steps = shape[1].toInt()
            val classes = shape[2].toInt()
            val logits = FloatArray(steps * classes)
            t.floatBuffer.get(logits)
            decodeCtc(logits, steps, classes)
        }
    }

    /**
     * CTC greedy decode.
     *
     * Index 0 is the blank. Consecutive identical indices collapse to one character —
     * that is what lets the model emit a character across several timesteps without
     * stuttering — and blanks separate genuinely doubled characters.
     *
     * The network emits probabilities, not logits (PaddleOCR's CTC head is followed by
     * a softmax inside the exported graph), so the winning value is already the
     * confidence. Running softmax over it again produced a flat distribution and a
     * reported confidence of 0.00 — which silently disables every low-confidence
     * filter a caller might apply.
     */
    private fun decodeCtc(logits: FloatArray, steps: Int, classes: Int): Pair<String, Double> {
        val sb = StringBuilder()
        var lastIndex = -1
        var confidenceSum = 0.0
        var confidenceCount = 0

        for (t in 0 until steps) {
            val base = t * classes
            var best = 0
            var bestScore = logits[base]
            for (c in 1 until classes) {
                val v = logits[base + c]
                if (v > bestScore) {
                    bestScore = v
                    best = c
                }
            }
            if (best != 0 && best != lastIndex) {
                val ch = dictionaryChar(best)
                if (ch != null) {
                    sb.append(ch)
                    confidenceSum += bestScore.coerceIn(0f, 1f).toDouble()
                    confidenceCount++
                }
            }
            lastIndex = best
        }

        val confidence = if (confidenceCount > 0) confidenceSum / confidenceCount else 0.0
        return sb.toString() to confidence
    }

    /**
     * Dictionary lookup.
     *
     * PP-OCR's CTC indices are offset by one relative to the dictionary file: index 0
     * is the blank and index *i* corresponds to line *i-1*. Getting this wrong shifts
     * every character by one, which produces plausible-looking but entirely wrong
     * text — the failure mode that is hardest to notice.
     */
    private fun dictionaryChar(index: Int): String? {
        val i = index - 1
        if (i < 0 || i >= dictionary.size) return null
        val entry = dictionary[i]
        // A trailing space marker in the dictionary means "space".
        return if (entry.isEmpty()) " " else entry
    }

    /**
     * Normalise characters that differ only by width or by code point.
     *
     * PP-OCR's Chinese dictionary emits the full-width yen sign (U+FFE5) where ML Kit
     * and every caller expect the half-width one. Two strings that look identical then
     * fail to compare equal, and every downstream consumer ends up writing the same
     * substitution — which is what a normalisation layer is for.
     *
     * Applied to recognised text only. Deliberately not a general width-folding pass:
     * full-width Latin inside Chinese text is often intentional, and folding it would
     * rewrite text the caller may want verbatim.
     */
    private fun normalizeGlyphs(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '\uFFE5' -> append('\u00A5')   // ￥ → ¥
                '\uFF0D' -> append('-')        // － → -
                else -> append(c)
            }
        }
    }

    private fun alignUp(value: Int, multiple: Int): Int =
        ((value + multiple - 1) / multiple) * multiple
}
