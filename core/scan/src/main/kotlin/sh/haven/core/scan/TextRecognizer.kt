package sh.haven.core.scan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs RapidOCR (PP-OCRv4 DBNet detector + SVTR/CRNN recognizer via ONNX Runtime)
 * over a [Bitmap], producing either plain text ([recognize]) or a full spatial
 * [OcrScreenIndex] backed by a 2D k-d tree ([recognizeIndex]).
 *
 * The ONNX sessions are initialized lazily on the first OCR call and reused across
 * subsequent calls behind a [Mutex]. [recognizeIndex] also caches the most recent
 * [OcrScreenIndex] by frame SHA-256 so repeated MCP coordinate/text lookups on an
 * unchanged remote desktop frame complete in under a millisecond.
 */
@Singleton
class TextRecognizer @Inject constructor(
    private val trainedData: TrainedDataManager,
    private val engineFactory: OcrEngineFactory,
) {
    private val mutex = Mutex()
    @Volatile private var engine: OcrEngine? = null

    @Volatile private var cachedHash: String? = null
    @Volatile private var cachedMinScore: Float = -1f
    @Volatile private var cachedIndex: OcrScreenIndex? = null

    /**
     * Recognize text in [bitmap] and return lines joined by `\n`, or `null`
     * when no text was detected. Used by the terminal attach camera/photo OCR flow.
     */
    suspend fun recognize(bitmap: Bitmap): String? = withContext(Dispatchers.Default) {
        val index = recognizeIndex(bitmap, forceRefresh = true)
        val text = index.blocks.asSequence()
            .flatMap { it.lines.asSequence() }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
        text.takeIf { it.isNotEmpty() }
    }

    /**
     * Run RapidOCR over [bitmap] and build a spatial [OcrScreenIndex] with a
     * 2D k-d tree over all detected words and lines.
     *
     * If [forceRefresh] is false and [bitmap] has the same pixel SHA-256 as the
     * previous call with the same [minScore], returns the cached [OcrScreenIndex]
     * immediately without re-running ONNX inference.
     */
    suspend fun recognizeIndex(
        bitmap: Bitmap,
        minScore: Float = 0.45f,
        forceRefresh: Boolean = false,
    ): OcrScreenIndex = withContext(Dispatchers.Default) {
        val frameHash = hashBitmap(bitmap)
        if (!forceRefresh) {
            val existing = cachedIndex
            if (existing != null && cachedHash == frameHash && cachedMinScore == minScore) {
                return@withContext existing
            }
        }

        val paths = trainedData.ensureModels()
        val (detections, elapsedMs) = mutex.withLock {
            if (!forceRefresh) {
                val existing = cachedIndex
                if (existing != null && cachedHash == frameHash && cachedMinScore == minScore) {
                    return@withContext existing
                }
            }
            val eng = engine ?: try {
                engineFactory.create(paths.detModel, paths.recModel).also { engine = it }
            } catch (t: Throwable) {
                throw TextRecognizeException("RapidOCR init failed: ${t.message}", t)
            }
            val t0 = System.nanoTime()
            val lines = try {
                eng.detectAndRecognize(bitmap)
            } catch (t: Throwable) {
                throw TextRecognizeException("RapidOCR recognise failed: ${t.message}", t)
            }
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            lines to ms
        }

        val built = OcrScreenIndex.build(
            detections = detections,
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            frameHash = frameHash,
            ocrMs = elapsedMs,
            minScore = minScore,
        )
        cachedHash = frameHash
        cachedMinScore = minScore
        cachedIndex = built
        built
    }

    /** Release the native ONNX sessions. */
    suspend fun shutdown() {
        mutex.withLock {
            engine?.let { runCatching { it.close() } }
            engine = null
            cachedHash = null
            cachedIndex = null
        }
    }

    interface OcrEngine : AutoCloseable {
        fun detectAndRecognize(bitmap: Bitmap): List<OcrLineDetection>
    }

    fun interface OcrEngineFactory {
        fun create(detModel: File, recModel: File): OcrEngine
    }

    companion object {
        internal fun hashBitmap(bitmap: Bitmap): String {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return "empty"
            // Sample a deterministic grid of up to 128x72 pixels plus dimensions
            // so hashing a 2560x1440 frame takes <0.3ms instead of copying 14MB.
            val digest = MessageDigest.getInstance("SHA-256")
            val stepX = max(1, w / 128)
            val stepY = max(1, h / 72)
            val row = IntArray(w)
            val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(w).putInt(h)
            digest.update(buf.array(), 0, 8)
            var y = 0
            while (y < h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                var x = 0
                while (x < w) {
                    val px = row[x]
                    buf.putInt(0, px)
                    digest.update(buf.array(), 0, 4)
                    x += stepX
                }
                y += stepY
            }
            return digest.digest().take(6).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Production [TextRecognizer.OcrEngineFactory] backed by ONNX Runtime and the
 * PP-OCRv4 detection (`ch_PP-OCRv4_det_infer.onnx`) and recognition
 * (`ch_PP-OCRv4_rec_infer.onnx`) models.
 */
@Singleton
class DefaultOcrEngineFactory @Inject constructor() : TextRecognizer.OcrEngineFactory {
    override fun create(detModel: File, recModel: File): TextRecognizer.OcrEngine {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        }
        val detSession = env.createSession(detModel.absolutePath, opts)
        val recSession = env.createSession(recModel.absolutePath, opts)
        val charMeta = recSession.metadata.customMetadata["character"]
            ?: error("PP-OCRv4 rec model at ${recModel.absolutePath} is missing 'character' metadata")
        val vocab = buildList {
            add("") // index 0 = CTC blank token
            addAll(charMeta.lines())
            add(" ")
        }
        Log.i("TextRecognizer", "RapidOCR ONNX sessions initialised (vocab=${vocab.size})")
        return OnnxRapidOcrEngine(env, opts, detSession, recSession, vocab)
    }
}

internal class OnnxRapidOcrEngine(
    private val env: OrtEnvironment,
    private val sessionOptions: OrtSession.SessionOptions,
    private val detSession: OrtSession,
    private val recSession: OrtSession,
    private val vocab: List<String>,
) : TextRecognizer.OcrEngine {

    private val detInputName: String = detSession.inputNames.first()
    private val recInputName: String = recSession.inputNames.first()

    override fun detectAndRecognize(bitmap: Bitmap): List<OcrLineDetection> {
        val origW = bitmap.width
        val origH = bitmap.height
        if (origW < 4 || origH < 4) return emptyList()

        val srcBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        // 1. Resize for DBNet detection (multiple of 32, max side <= 1920).
        val maxSide = max(origW, origH)
        val ratio = if (maxSide > DET_MAX_SIDE) DET_MAX_SIDE.toDouble() / maxSide else 1.0
        val detW = max(32, ((origW * ratio) / 32.0).roundToInt() * 32)
        val detH = max(32, ((origH * ratio) / 32.0).roundToInt() * 32)

        val detBitmap = if (detW == origW && detH == origH) {
            srcBitmap
        } else {
            Bitmap.createScaledBitmap(srcBitmap, detW, detH, true)
        }

        val planeSize = detW * detH
        val pixels = IntArray(planeSize)
        detBitmap.getPixels(pixels, 0, detW, 0, 0, detW, detH)
        if (detBitmap !== srcBitmap && detBitmap !== bitmap) {
            detBitmap.recycle()
        }

        val detInputBuf = ByteBuffer.allocateDirect(3 * planeSize * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (i in 0 until planeSize) {
            val px = pixels[i]
            val b = ((px and 0xFF) / 255f - MEAN_B) / STD_B
            val g = (((px ushr 8) and 0xFF) / 255f - MEAN_G) / STD_G
            val r = (((px ushr 16) and 0xFF) / 255f - MEAN_R) / STD_R
            detInputBuf.put(i, b)
            detInputBuf.put(planeSize + i, g)
            detInputBuf.put(2 * planeSize + i, r)
        }
        detInputBuf.rewind()

        val probMap = FloatArray(planeSize)
        OnnxTensor.createTensor(env, detInputBuf, longArrayOf(1L, 3L, detH.toLong(), detW.toLong())).use { tensor ->
            detSession.run(mapOf(detInputName to tensor)).use { result ->
                val outTensor = result[0] as OnnxTensor
                outTensor.floatBuffer.get(probMap)
            }
        }

        // 2. Threshold + 2x2 dilation + 8-connected components + DBNet unclip.
        val rawBoxes = extractBoxes(probMap, detW, detH, origW, origH)
        if (rawBoxes.isEmpty()) return emptyList()

        // 3. Recognize each detected line box with SVTR/CRNN.
        val detections = ArrayList<OcrLineDetection>(rawBoxes.size)
        for (box in rawBoxes) {
            if (box.width < 4 || box.height < 4) continue
            val crop = Bitmap.createBitmap(srcBitmap, box.x, box.y, box.width, box.height)
            val cropW = max(16, (REC_HEIGHT.toDouble() * box.width / box.height).roundToInt())
            val scaledCrop = if (crop.width == cropW && crop.height == REC_HEIGHT) {
                crop
            } else {
                Bitmap.createScaledBitmap(crop, cropW, REC_HEIGHT, true)
            }
            if (scaledCrop !== crop) crop.recycle()

            val cropPlane = cropW * REC_HEIGHT
            val cropPixels = IntArray(cropPlane)
            scaledCrop.getPixels(cropPixels, 0, cropW, 0, 0, cropW, REC_HEIGHT)
            scaledCrop.recycle()

            val recBuf = ByteBuffer.allocateDirect(3 * cropPlane * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            for (i in 0 until cropPlane) {
                val px = cropPixels[i]
                val b = ((px and 0xFF) / 255f - 0.5f) / 0.5f
                val g = (((px ushr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                val r = (((px ushr 16) and 0xFF) / 255f - 0.5f) / 0.5f
                recBuf.put(i, b)
                recBuf.put(cropPlane + i, g)
                recBuf.put(2 * cropPlane + i, r)
            }
            recBuf.rewind()

            OnnxTensor.createTensor(env, recBuf, longArrayOf(1L, 3L, REC_HEIGHT.toLong(), cropW.toLong())).use { recTensor ->
                recSession.run(mapOf(recInputName to recTensor)).use { recResult ->
                    val outTensor = recResult[0] as OnnxTensor
                    val shape = outTensor.info.shape // [1, T, V]
                    val timeSteps = shape[1].toInt()
                    val vocabSize = shape[2].toInt()
                    val fb = outTensor.floatBuffer
                    val sb = StringBuilder()
                    var scoreSum = 0f
                    var charCount = 0
                    var prevIdx = 0
                    for (t in 0 until timeSteps) {
                        val baseOffset = t * vocabSize
                        var bestIdx = 0
                        var bestProb = -1f
                        for (v in 0 until vocabSize) {
                            val p = fb.get(baseOffset + v)
                            if (p > bestProb) {
                                bestProb = p
                                bestIdx = v
                            }
                        }
                        if (bestIdx != 0 && bestIdx != prevIdx && bestIdx < vocab.size) {
                            sb.append(vocab[bestIdx])
                            scoreSum += bestProb
                            charCount++
                        }
                        prevIdx = bestIdx
                    }
                    if (charCount > 0) {
                        val meanScore = scoreSum / charCount
                        val text = sb.toString()
                        if (text.isNotBlank()) {
                            detections.add(OcrLineDetection(box, text, meanScore))
                        }
                    }
                }
            }
        }

        if (srcBitmap !== bitmap) {
            srcBitmap.recycle()
        }
        return detections
    }

    private fun extractBoxes(
        probMap: FloatArray,
        detW: Int,
        detH: Int,
        origW: Int,
        origH: Int,
    ): List<OcrBbox> {
        val planeSize = detW * detH
        val seg = BooleanArray(planeSize)
        for (i in 0 until planeSize) {
            seg[i] = probMap[i] > DET_THRESH
        }
        // 2x2 dilation (matches RapidOCR's use_dilation = True).
        val mask = seg.copyOf()
        for (y in 1 until detH) {
            val row = y * detW
            val prevRow = (y - 1) * detW
            for (x in 1 until detW) {
                val idx = row + x
                if (seg[prevRow + x] || seg[row + x - 1] || seg[prevRow + x - 1]) {
                    mask[idx] = true
                }
            }
        }

        val visited = BooleanArray(planeSize)
        val queue = IntArray(planeSize)
        val boxes = ArrayList<OcrBbox>()

        for (sy in 0 until detH) {
            val rowOffset = sy * detW
            for (sx in 0 until detW) {
                val startIdx = rowOffset + sx
                if (!mask[startIdx] || visited[startIdx]) continue

                var head = 0
                var tail = 0
                queue[tail++] = startIdx
                visited[startIdx] = true

                var minX = sx
                var maxX = sx
                var minY = sy
                var maxY = sy

                while (head < tail) {
                    val curr = queue[head++]
                    val cy = curr / detW
                    val cx = curr - cy * detW
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    val y0 = max(0, cy - 1)
                    val y1 = min(detH - 1, cy + 1)
                    val x0 = max(0, cx - 1)
                    val x1 = min(detW - 1, cx + 1)
                    for (ny in y0..y1) {
                        val nRow = ny * detW
                        for (nx in x0..x1) {
                            val nIdx = nRow + nx
                            if (mask[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }

                val bw = maxX - minX
                val bh = maxY - minY
                if (min(bw, bh) < 3) continue

                // box_score_fast: mean probability inside [minY..maxY, minX..maxX].
                var probSum = 0.0
                var count = 0
                for (yy in minY..maxY) {
                    val rOff = yy * detW
                    for (xx in minX..maxX) {
                        probSum += probMap[rOff + xx]
                        count++
                    }
                }
                if (count == 0 || (probSum / count) < BOX_THRESH) continue

                val d = (bw.toDouble() * bh.toDouble()) * UNCLIP_RATIO / (2.0 * (bw + bh))
                val rx0 = ((minX - d) * origW / detW).roundToInt().coerceIn(0, origW - 1)
                val ry0 = ((minY - d) * origH / detH).roundToInt().coerceIn(0, origH - 1)
                val rx1 = ((maxX + d) * origW / detW).roundToInt().coerceIn(0, origW)
                val ry1 = ((maxY + d) * origH / detH).roundToInt().coerceIn(0, origH)
                if (rx1 - rx0 >= 4 && ry1 - ry0 >= 4) {
                    boxes.add(OcrBbox(rx0, ry0, rx1 - rx0, ry1 - ry0))
                }
            }
        }
        return boxes
    }

    override fun close() {
        runCatching { detSession.close() }
        runCatching { recSession.close() }
        runCatching { sessionOptions.close() }
    }

    private companion object {
        const val DET_MAX_SIDE = 1920
        const val DET_THRESH = 0.3f
        const val BOX_THRESH = 0.5
        const val UNCLIP_RATIO = 1.6
        const val REC_HEIGHT = 48

        const val MEAN_B = 0.485f
        const val MEAN_G = 0.456f
        const val MEAN_R = 0.406f
        const val STD_B = 0.229f
        const val STD_G = 0.224f
        const val STD_R = 0.225f
    }
}

class TextRecognizeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
