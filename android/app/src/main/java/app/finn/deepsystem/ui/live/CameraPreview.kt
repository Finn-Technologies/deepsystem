package app.finn.deepsystem.ui.live

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.finn.deepsystem.ui.theme.FinnCyan
import app.finn.deepsystem.ui.theme.FinnIndigo

@Composable
fun LiveCameraPip(
    modifier: Modifier = Modifier,
    onFrame: ((ByteArray) -> Unit)? = null,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var cameraAvailable by remember { mutableStateOf(true) }

    // Simulated frame generator when running in headless/mock environment
    LaunchedEffect(cameraAvailable, onFrame) {
        if (!cameraAvailable && onFrame != null) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                try {
                    val simBitmap = android.graphics.Bitmap.createBitmap(160, 160, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(simBitmap)
                    canvas.drawColor(android.graphics.Color.DKGRAY)
                    val out = java.io.ByteArrayOutputStream()
                    simBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
                    onFrame(out.toByteArray())
                } catch (_: Throwable) {}
            }
        }
    }

    Box(
        modifier = modifier
            .size(width = 160.dp, height = 220.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black)
            .border(2.dp, FinnCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
    ) {
        if (cameraAvailable) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val cameraSelector = CameraSelector.Builder()
                                .requireLensFacing(lensFacing)
                                .build()

                            val analysis = androidx.camera.core.ImageAnalysis.Builder()
                                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                try {
                                    if (onFrame != null) {
                                        val bmp = imageProxy.toBitmap()
                                        val out = java.io.ByteArrayOutputStream()
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                                        onFrame(out.toByteArray())
                                    }
                                } catch (_: Throwable) {
                                } finally {
                                    imageProxy.close()
                                }
                            }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
                        } catch (e: Exception) {
                            Log.w("LiveCameraPip", "CameraX bind failed, using simulated preview: ${e.message}")
                            cameraAvailable = false
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Simulated Preview for Emulators
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = FinnCyan,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Camera Active",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Simulated Feed",
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp
                    )
                }
            }
        }

        // Lens switch and close button overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopEnd)
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .semantics {
                        role = Role.Button
                        contentDescription = "Switch camera lens"
                    }
            ) {
                Icon(
                    Icons.Default.Cameraswitch,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .semantics {
                        role = Role.Button
                        contentDescription = "Close camera preview"
                    }
            ) {
                Icon(
                    Icons.Default.VideocamOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
