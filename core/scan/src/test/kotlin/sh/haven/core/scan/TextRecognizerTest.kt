package sh.haven.core.scan

import android.graphics.Bitmap
import android.graphics.Color
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Validates the orchestration [TextRecognizer] wraps around the native ONNX
 * engine without touching JNI. Here we substitute a fake
 * [TextRecognizer.OcrEngineFactory] and verify model extraction, lazy engine
 * lifecycle, frame-hash index caching, and shutdown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextRecognizerTest {

    private val bitmap: Bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    private val modelPaths = TrainedDataManager.RapidOcrModelPaths(
        detModel = File("/tmp/det.onnx"),
        recModel = File("/tmp/rec.onnx"),
    )

    @Test
    fun `recognize delegates to the engine and returns newline-joined text`() = runTest {
        val engine = mockk<TextRecognizer.OcrEngine>(relaxed = true) {
            every { detectAndRecognize(any()) } returns listOf(
                OcrLineDetection(OcrBbox(10, 10, 100, 20), "  hello world  ", 0.96f),
                OcrLineDetection(OcrBbox(10, 40, 80, 20), "second line", 0.91f),
            )
        }
        val factory = TextRecognizer.OcrEngineFactory { _, _ -> engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureModels() } returns modelPaths
        }

        val recognizer = TextRecognizer(trainedData, factory)
        val result = recognizer.recognize(bitmap)

        assertEquals("hello world\nsecond line", result)
        val bmpSlot = slot<Bitmap>()
        verify { engine.detectAndRecognize(capture(bmpSlot)) }
        assertEquals(bitmap, bmpSlot.captured)
    }

    @Test
    fun `recognize returns null when the engine produces no detections`() = runTest {
        val engine = mockk<TextRecognizer.OcrEngine>(relaxed = true) {
            every { detectAndRecognize(any()) } returns emptyList()
        }
        val factory = TextRecognizer.OcrEngineFactory { _, _ -> engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureModels() } returns modelPaths
        }

        val recognizer = TextRecognizer(trainedData, factory)

        assertNull(recognizer.recognize(bitmap))
    }

    @Test
    fun `recognizeIndex caches by frame hash until bitmap pixels change or forceRefresh is true`() = runTest {
        var engineCalls = 0
        val engine = mockk<TextRecognizer.OcrEngine>(relaxed = true) {
            every { detectAndRecognize(any()) } answers {
                engineCalls++
                listOf(OcrLineDetection(OcrBbox(5, 5, 40, 12), "Save", 0.95f))
            }
        }
        val factory = TextRecognizer.OcrEngineFactory { _, _ -> engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureModels() } returns modelPaths
        }

        val recognizer = TextRecognizer(trainedData, factory)
        val bmpA = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val idx1 = recognizer.recognizeIndex(bmpA, forceRefresh = false)
        val idx2 = recognizer.recognizeIndex(bmpA, forceRefresh = false)

        assertSame(idx1, idx2)
        assertEquals(1, engineCalls)

        // Mutating a sampled pixel invalidates the frame hash and re-runs OCR.
        val bmpB = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.RED)
        }
        recognizer.recognizeIndex(bmpB, forceRefresh = false)
        assertEquals(2, engineCalls)

        // forceRefresh bypasses cache even on identical pixels.
        recognizer.recognizeIndex(bmpB, forceRefresh = true)
        assertEquals(3, engineCalls)
    }

    @Test
    fun `engine is initialised once across calls and recreated after shutdown`() = runTest {
        var created = 0
        val engine = mockk<TextRecognizer.OcrEngine>(relaxed = true) {
            every { detectAndRecognize(any()) } returns listOf(
                OcrLineDetection(OcrBbox(0, 0, 20, 10), "x", 0.9f),
            )
        }
        val factory = TextRecognizer.OcrEngineFactory { _, _ -> created++; engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureModels() } returns modelPaths
        }

        val recognizer = TextRecognizer(trainedData, factory)
        recognizer.recognize(bitmap)
        recognizer.recognize(bitmap)
        assertEquals(1, created)
        coVerify(exactly = 2) { trainedData.ensureModels() }

        recognizer.shutdown()
        recognizer.recognize(bitmap)

        verify { engine.close() }
        assertEquals(2, created)
    }
}
