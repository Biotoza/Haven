package sh.haven.core.scan

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the bundled RapidOCR (PP-OCRv4) ONNX model files on internal storage.
 *
 * Extracts `rapidocr/ch_PP-OCRv4_det_infer.onnx` and
 * `rapidocr/ch_PP-OCRv4_rec_infer.onnx` from app assets into
 * `filesDir/rapidocr/` on first OCR use, and removes any legacy
 * `filesDir/tessdata/` directory left behind by earlier releases.
 */
@Singleton
class TrainedDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class RapidOcrModelPaths(
        val detModel: File,
        val recModel: File,
    )

    private val mutex = Mutex()
    private val modelDir: File = File(context.filesDir, "rapidocr")

    suspend fun ensureModels(): RapidOcrModelPaths = withContext(Dispatchers.IO) {
        val detFile = File(modelDir, DET_MODEL_NAME)
        val recFile = File(modelDir, REC_MODEL_NAME)
        if (detFile.exists() && detFile.length() > 0 && recFile.exists() && recFile.length() > 0) {
            return@withContext RapidOcrModelPaths(detFile, recFile)
        }
        mutex.withLock {
            // Reclaim ~4 MB if an older release extracted tessdata/.
            runCatching {
                val legacyTessDir = File(context.filesDir, "tessdata")
                if (legacyTessDir.exists()) legacyTessDir.deleteRecursively()
            }
            if (!modelDir.exists() && !modelDir.mkdirs()) {
                throw TrainedDataException("Could not create rapidocr directory at ${modelDir.absolutePath}")
            }
            extractIfNeeded(DET_MODEL_NAME, detFile)
            extractIfNeeded(REC_MODEL_NAME, recFile)
        }
        RapidOcrModelPaths(detFile, recFile)
    }

    private fun extractIfNeeded(name: String, target: File) {
        if (target.exists() && target.length() > 0) return
        val assetPath = "rapidocr/$name"
        try {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            target.delete()
            throw TrainedDataException("Failed to extract RapidOCR asset '$assetPath': ${e.message}", e)
        }
        Log.i(TAG, "Extracted $assetPath (${target.length()} bytes) -> $target")
    }

    companion object {
        private const val TAG = "TrainedDataManager"
        const val DET_MODEL_NAME = "ch_PP-OCRv4_det_infer.onnx"
        const val REC_MODEL_NAME = "ch_PP-OCRv4_rec_infer.onnx"
    }
}

class TrainedDataException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
