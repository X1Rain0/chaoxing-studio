package com.x1rain0.chaoxingstudio.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@ExperimentalGetImage
@Composable
internal fun QrCameraScanner(
    modifier: Modifier = Modifier,
    onResult: (String) -> Unit,
    onCameraError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val providerFuture = remember(context) { ProcessCameraProvider.getInstance(context) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(modifier = modifier, factory = { previewView })

    DisposableEffect(lifecycleOwner, previewView, providerFuture) {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        val busy = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val disposed = AtomicBoolean(false)
        val providerRef = AtomicReference<ProcessCameraProvider?>(null)
        val analysisRef = AtomicReference<ImageAnalysis?>(null)

        val bindCamera = Runnable {
            if (disposed.get()) return@Runnable
            try {
                val provider = providerFuture.get()
                providerRef.set(provider)
                if (disposed.get()) return@Runnable

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysisRef.set(analysis)
                analysis.setAnalyzer(analyzerExecutor) { proxy ->
                    val mediaImage = proxy.image
                    if (mediaImage == null || !busy.compareAndSet(false, true)) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    try {
                        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener(mainExecutor) { barcodes ->
                                val rawValue = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                    ?.rawValue
                                    ?.takeIf(String::isNotBlank)
                                if (rawValue != null && delivered.compareAndSet(false, true)) {
                                    currentOnResult(rawValue)
                                }
                            }
                            .addOnCompleteListener(mainExecutor) {
                                busy.set(false)
                                proxy.close()
                            }
                    } catch (error: Exception) {
                        busy.set(false)
                        proxy.close()
                        mainExecutor.execute {
                            currentOnCameraError(error.message ?: "二维码识别失败")
                        }
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (error: Exception) {
                mainExecutor.execute {
                    currentOnCameraError(error.message ?: "相机启动失败，请改用粘贴二维码内容")
                }
            }
        }
        providerFuture.addListener(bindCamera, mainExecutor)

        onDispose {
            disposed.set(true)
            analysisRef.get()?.clearAnalyzer()
            providerRef.get()?.unbindAll()
            scanner.close()
            analyzerExecutor.shutdownNow()
        }
    }
}
