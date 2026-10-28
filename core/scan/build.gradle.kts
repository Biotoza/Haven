import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.scan"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Do not recompress .onnx weight files inside the APK so AssetManager can
    // stream/read them without double-decompression overhead.
    androidResources {
        noCompress += "onnx"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // ZXing core is pure Java — no Android plumbing, decodes a luminance
    // array. We adapt a Bitmap → RGBLuminanceSource inside BarcodeDecoder.
    implementation(libs.zxing.core)

    // ONNX Runtime Android (MIT license, Maven Central) runs the RapidOCR
    // PP-OCRv4 text-detection (DBNet) and text-recognition (SVTR/CRNN) ONNX
    // models for both terminal attach OCR and remote-desktop MCP OCR.
    implementation(libs.onnxruntime.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockk)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// RapidOCR (PP-OCRv4) ONNX model fetcher.
//
// Extracts `ch_PP-OCRv4_det_infer.onnx` (~4.7 MB, DBNet detector) and
// `ch_PP-OCRv4_rec_infer.onnx` (~10.8 MB, SVTR/CRNN recognizer with embedded
// character dictionary) from the immutable PyPI `rapidocr_onnxruntime` wheel
// (Apache 2.0) into `src/main/assets/rapidocr/` with pinned SHA-256 checks.
// ---------------------------------------------------------------------------
val rapidOcrWheelUrl =
    "https://files.pythonhosted.org/packages/ba/12/1e5497183bdbe782dbb91bad1d0d2297dba4d2831b2652657f7517bfc6df/rapidocr_onnxruntime-1.4.4-py3-none-any.whl"
val rapidOcrWheelSha256 = "971d7d5f223a7a808662229df1ef69893809d8457d834e6373d3854bc1782cbf"

val detModelName = "ch_PP-OCRv4_det_infer.onnx"
val detModelSha256 = "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9"

val recModelName = "ch_PP-OCRv4_rec_infer.onnx"
val recModelSha256 = "48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b"

val rapidOcrAssetsDir = layout.projectDirectory.dir("src/main/assets/rapidocr")

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(65536)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { b -> "%02x".format(b) }
}

val fetchRapidOcrModels = tasks.register("fetchRapidOcrModels") {
    description = "Downloads and extracts RapidOCR PP-OCRv4 ONNX models into assets/rapidocr/."
    val outDir = rapidOcrAssetsDir.asFile
    val detTarget = File(outDir, detModelName)
    val recTarget = File(outDir, recModelName)
    inputs.property("wheelUrl", rapidOcrWheelUrl)
    inputs.property("wheelSha256", rapidOcrWheelSha256)
    inputs.property("detSha256", detModelSha256)
    inputs.property("recSha256", recModelSha256)
    outputs.files(detTarget, recTarget)

    doLast {
        if (detTarget.exists() && recTarget.exists() &&
            sha256Hex(detTarget) == detModelSha256 &&
            sha256Hex(recTarget) == recModelSha256
        ) {
            logger.lifecycle("RapidOCR models up to date (${detTarget.name}, ${recTarget.name})")
            return@doLast
        }

        outDir.mkdirs()
        val tmpWheel = File.createTempFile("rapidocr_onnxruntime-", ".whl", temporaryDir)
        try {
            logger.lifecycle("Downloading $rapidOcrWheelUrl …")
            val maxAttempts = 4
            for (attempt in 1..maxAttempts) {
                try {
                    URI(rapidOcrWheelUrl).toURL().openStream().use { input ->
                        tmpWheel.outputStream().use { output -> input.copyTo(output) }
                    }
                    break
                } catch (e: IOException) {
                    if (attempt == maxAttempts) throw e
                    val delaySec = 10L shl (attempt - 1)
                    logger.lifecycle("RapidOCR wheel download failed ($attempt/$maxAttempts): ${e.message} — retrying in ${delaySec}s")
                    Thread.sleep(delaySec * 1000)
                }
            }

            val wheelActual = sha256Hex(tmpWheel)
            if (wheelActual != rapidOcrWheelSha256) {
                throw GradleException("RapidOCR wheel SHA-256 mismatch: got $wheelActual, expected $rapidOcrWheelSha256")
            }

            ZipFile(tmpWheel).use { zip ->
                listOf(
                    detModelName to detModelSha256,
                    recModelName to recModelSha256,
                ).forEach { (modelName, expectedSha) ->
                    val entryPath = "rapidocr_onnxruntime/models/$modelName"
                    val entry = zip.getEntry(entryPath)
                        ?: throw GradleException("Missing '$entryPath' inside RapidOCR wheel")
                    val target = File(outDir, modelName)
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    val actualSha = sha256Hex(target)
                    if (actualSha != expectedSha) {
                        target.delete()
                        throw GradleException("$modelName SHA-256 mismatch: got $actualSha, expected $expectedSha")
                    }
                }
            }
            logger.lifecycle("RapidOCR models extracted to $outDir")
        } finally {
            tmpWheel.delete()
        }
    }
}

afterEvaluate {
    tasks.matching {
        it.name == "preBuild" ||
            it.name.startsWith("merge") && it.name.endsWith("Assets")
    }.configureEach {
        dependsOn(fetchRapidOcrModels)
    }
}
