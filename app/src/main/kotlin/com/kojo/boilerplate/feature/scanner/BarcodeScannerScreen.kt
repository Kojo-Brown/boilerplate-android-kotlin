package com.kojo.boilerplate.feature.scanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeScannerScreen(
    onNavigateUp: () -> Unit,
    viewModel: BarcodeScannerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isFlashEnabled by viewModel.isFlashEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) viewModel.onPermissionDenied()
    }

    LaunchedEffect(Unit) {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permission)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Barcode Scanner") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate up",
                        )
                    }
                },
                actions = {
                    if (uiState is BarcodeScannerUiState.Scanning) {
                        IconButton(onClick = viewModel::toggleFlash) {
                            Icon(
                                imageVector = if (isFlashEnabled) Icons.Default.FlashOff else Icons.Default.FlashOn,
                                contentDescription = if (isFlashEnabled) "Disable flash" else "Enable flash",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is BarcodeScannerUiState.Scanning -> {
                    CameraPreview(
                        isFlashEnabled = isFlashEnabled,
                        onBarcodeDetected = { rawValue, format ->
                            viewModel.onBarcodeDetected(rawValue, format)
                        },
                        onError = viewModel::onError,
                        modifier = Modifier.fillMaxSize(),
                    )
                    ScannerOverlay(modifier = Modifier.fillMaxSize())
                }

                is BarcodeScannerUiState.BarcodeDetected -> {
                    BarcodeResultContent(
                        state = state,
                        onResumeScanning = viewModel::resumeScanning,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    )
                }

                is BarcodeScannerUiState.PermissionDenied -> {
                    PermissionDeniedContent(
                        message = state.message,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    )
                }

                is BarcodeScannerUiState.Error -> {
                    ErrorContent(
                        message = state.message,
                        onRetry = viewModel::resumeScanning,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    isFlashEnabled: Boolean,
    onBarcodeDetected: (rawValue: String, format: BarcodeFormat) -> Unit,
    onError: (message: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(isFlashEnabled) {
        camera?.cameraControl?.enableTorch(isFlashEnabled)
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            val executor = Executors.newSingleThreadExecutor()

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(executor, MlKitBarcodeAnalyzer(onBarcodeDetected))
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                runCatching {
                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis,
                    )
                }.onFailure { e ->
                    onError(e.message ?: "Failed to start camera")
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier,
    )
}

@Composable
private fun ScannerOverlay(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(260.dp)
                .align(Alignment.Center)
                .border(
                    width = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(12.dp),
                ),
        )
        Text(
            text = "Point camera at a barcode",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BarcodeResultContent(
    state: BarcodeScannerUiState.BarcodeDetected,
    onResumeScanning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Barcode Detected",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.format.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(20.dp),
        ) {
            Text(
                text = state.rawValue,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("barcode", state.rawValue))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy to Clipboard")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onResumeScanning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Scan Another")
        }
    }
}

@Composable
private fun PermissionDeniedContent(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Camera Permission Required",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Please grant camera permission in device settings to use barcode scanning.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Camera Error",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private class MlKitBarcodeAnalyzer(
    private val onBarcodeDetected: (rawValue: String, format: BarcodeFormat) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build(),
    )

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull()?.rawValue?.let { rawValue ->
                    onBarcodeDetected(rawValue, barcodes.first().format.toBarcodeFormat())
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}

private fun Int.toBarcodeFormat(): BarcodeFormat = when (this) {
    Barcode.FORMAT_QR_CODE -> BarcodeFormat.QR_CODE
    Barcode.FORMAT_EAN_13 -> BarcodeFormat.EAN_13
    Barcode.FORMAT_EAN_8 -> BarcodeFormat.EAN_8
    Barcode.FORMAT_CODE_128 -> BarcodeFormat.CODE_128
    Barcode.FORMAT_CODE_39 -> BarcodeFormat.CODE_39
    Barcode.FORMAT_DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
    Barcode.FORMAT_PDF417 -> BarcodeFormat.PDF_417
    Barcode.FORMAT_AZTEC -> BarcodeFormat.AZTEC
    Barcode.FORMAT_ITF -> BarcodeFormat.ITF
    else -> BarcodeFormat.UNKNOWN
}
