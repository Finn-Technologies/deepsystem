package app.finn.deepsystem

import app.finn.deepsystem.model.KnownModels
import app.finn.deepsystem.network.ModelDownloader
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelDownloaderTest {

    @Test
    fun testKnownModelsMetadata() {
        val baseModel = KnownModels.TEXT_MODEL
        assertEquals("LFM3-Omni-2B-Q4_K_M.gguf", baseModel.filename)
        assertEquals(1099630080L, baseModel.expectedBytes)
        assertEquals("9f8d30e9d4fd7a9349d586e077988229b3ca0a0e4ad730897563d1eccc9893e2", baseModel.sha256Hex)

        val mmproj = KnownModels.MMPROJ_MODEL
        assertEquals("mmproj-LFM3-Omni-2B-Q4_K_M.gguf", mmproj.filename)
        assertEquals(291586720L, mmproj.expectedBytes)
        assertEquals("0a746c0794782a16d4456b230c2a0b5888a2d94a6677211edbed7d7a4177a39a", mmproj.sha256Hex)
    }

    @Test
    fun testComputeSha256() {
        val tempFile = File.createTempFile("test_sha", ".txt")
        tempFile.writeText("Finn DeepSystem")
        val sha = ModelDownloader.computeSha256(tempFile)
        assertNotNull(sha)
        assertEquals(64, sha.length)
        tempFile.delete()
    }
}
