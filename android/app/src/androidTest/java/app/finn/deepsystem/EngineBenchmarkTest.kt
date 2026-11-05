package app.finn.deepsystem

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.finn.deepsystem.engine.NativeFinnEngine
import app.finn.deepsystem.model.KnownModels
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EngineBenchmarkTest {

    private fun getModelFile(context: Context, filename: String): File {
        val modelsDir = File(context.filesDir, "models")
        return File(modelsDir, filename).takeIf { it.exists() }
            ?: File("/data/local/tmp", filename)
    }

    @Test(timeout = 180000)
    fun benchmarkTextGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelFile = getModelFile(context, KnownModels.TEXT_MODEL.filename)
        assertTrue("Model file must exist at ${modelFile.absolutePath}", modelFile.exists())

        Log.i(TAG, "Initializing real NativeFinnEngine for text benchmark...")
        val engine = NativeFinnEngine(isFakeEngine = false)

        val loadStart = System.currentTimeMillis()
        val loaded = engine.load(
            modelPath = modelFile.absolutePath,
            mmprojPath = "",
            nCtx = 1024,
            nThreads = 4,
            nGpuLayers = 0,
            useMmap = true
        )
        val loadTimeMs = System.currentTimeMillis() - loadStart
        assertTrue("Native engine model load must succeed", loaded)
        Log.i(TAG, "FINN_BENCH: TEXT_MODEL_LOAD_MS=$loadTimeMs")

        val prompt = "Explain quantum computing in one short sentence."
        val tokens = StringBuilder()
        Log.i(TAG, "Running Text Benchmark: '$prompt'")

        val stats = engine.generate(
            prompt = prompt,
            imageBytes = null,
            temperature = 0.0f,
            maxTokens = 32
        ) { token ->
            tokens.append(token)
            true
        }

        assertNotNull("Stats must not be null", stats)
        Log.i(TAG, "FINN_BENCH: TEXT_TTFT_MS=${stats!!.ttftMs}")
        Log.i(TAG, "FINN_BENCH: TEXT_TOKENS_PER_SEC=${stats.tokensPerSecond}")
        Log.i(TAG, "FINN_BENCH: TEXT_PROMPT_TOKENS=${stats.promptTokens}")
        Log.i(TAG, "FINN_BENCH: TEXT_COMPLETION_TOKENS=${stats.completionTokens}")
        Log.i(TAG, "FINN_BENCH: TEXT_TOTAL_TIME_MS=${stats.totalTimeMs}")
        Log.i(TAG, "FINN_BENCH: TEXT_OUTPUT='$tokens'")

        engine.close()
    }

    @Test(timeout = 300000)
    fun benchmarkVisionGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelFile = getModelFile(context, KnownModels.TEXT_MODEL.filename)
        val mmprojFile = getModelFile(context, KnownModels.MMPROJ_MODEL.filename)
        val imageFile = File("/data/local/tmp/test_image.png")

        if (!imageFile.exists() || !mmprojFile.exists()) {
            Log.w(TAG, "Skipping vision benchmark: image or mmproj not found")
            return
        }

        Log.i(TAG, "Initializing real NativeFinnEngine for vision benchmark...")
        val engine = NativeFinnEngine(isFakeEngine = false)

        val loadStart = System.currentTimeMillis()
        val loaded = engine.load(
            modelPath = modelFile.absolutePath,
            mmprojPath = mmprojFile.absolutePath,
            nCtx = 1024,
            nThreads = 4,
            nGpuLayers = 0,
            useMmap = true
        )
        val loadTimeMs = System.currentTimeMillis() - loadStart
        assertTrue("Native engine multimodal load must succeed", loaded)
        Log.i(TAG, "FINN_BENCH: VISION_MODEL_LOAD_MS=$loadTimeMs")

        val imgBytes = imageFile.readBytes()
        val visionPrompt = "Describe what is in this image briefly."
        val visionTokens = StringBuilder()
        Log.i(TAG, "Running Multimodal Vision Benchmark (${imgBytes.size} bytes)...")

        val visionStats = engine.generate(
            prompt = visionPrompt,
            imageBytes = imgBytes,
            temperature = 0.0f,
            maxTokens = 24
        ) { token ->
            visionTokens.append(token)
            true
        }

        assertNotNull("Vision stats must not be null", visionStats)
        Log.i(TAG, "FINN_BENCH: VISION_TTFT_MS=${visionStats!!.ttftMs}")
        Log.i(TAG, "FINN_BENCH: VISION_TOKENS_PER_SEC=${visionStats.tokensPerSecond}")
        Log.i(TAG, "FINN_BENCH: VISION_COMPLETION_TOKENS=${visionStats.completionTokens}")
        Log.i(TAG, "FINN_BENCH: VISION_TOTAL_TIME_MS=${visionStats.totalTimeMs}")
        Log.i(TAG, "FINN_BENCH: VISION_OUTPUT='$visionTokens'")

        engine.close()
    }

    companion object {
        private const val TAG = "EngineBenchmarkTest"
    }
}
