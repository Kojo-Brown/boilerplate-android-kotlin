package com.kojo.boilerplate.feature.textrecognition

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kojo.boilerplate.core.ui.components.FlashToggle
import com.kojo.boilerplate.core.ui.layout.ExpandableText
import com.kojo.boilerplate.core.ui.udf.rememberEventSink
import java.util.concurrent.Executors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextRecognitionScreen(
    onNavigateUp: () -> Unit,
    viewModel: TextRecognitionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onEvent = rememberEventSink(viewModel)
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) onEvent(TextRecognitionUiEvent.CameraPermissionDenied)
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
                // See `ProfileScreen` for why the title says it is a heading itself.
                title = {
                    Text("Text Recognition", modifier = Modifier.semantics { heading() })
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate up",
                        )
                    }
                },
                actions = {
                    if (state.scan is TextScanState.Scanning) {
                        FlashToggle(
                            flashOn = state.isFlashEnabled,
                            onToggle = { onEvent(TextRecognitionUiEvent.FlashToggled) },
                        )
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
            when (val scan = state.scan) {
                is TextScanState.Scanning -> {
                    TextRecognitionCameraPreview(
                        isFlashEnabled = state.isFlashEnabled,
                        onTextDetected = { fullText, blocks ->
                            onEvent(TextRecognitionUiEvent.TextDetected(fullText, blocks))
                        },
                        onError = { message ->
                            onEvent(TextRecognitionUiEvent.CameraFailed(message))
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    TextScannerOverlay(modifier = Modifier.fillMaxSize())
                }

                is TextScanState.TextDetected -> {
                    TextDetectedContent(
                        scan = scan,
                        onResumeScanning = {
                            onEvent(TextRecognitionUiEvent.ResumeScanningClicked)
                        },
                        onFullTextExpansionChange = { expanded ->
                            onEvent(TextRecognitionUiEvent.FullTextExpansionChanged(expanded))
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                is TextScanState.PermissionDenied -> {
                    PermissionDeniedContent(
                        message = scan.message,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    )
                }

                is TextScanState.Error -> {
                    ErrorContent(
                        message = scan.message,
                        onRetry = {
                            onEvent(TextRecognitionUiEvent.ResumeScanningClicked)
                        },
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
private fun TextRecognitionCameraPreview(
    isFlashEnabled: Boolean,
    onTextDetected: (fullText: String, blocks: ImmutableList<RecognizedTextBlock>) -> Unit,
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
                        analysis.setAnalyzer(executor, MlKitTextAnalyzer(onTextDetected))
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
private fun TextScannerOverlay(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(200.dp)
                .align(Alignment.Center)
                .border(
                    width = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(12.dp),
                ),
        )
        Text(
            text = "Point camera at text to recognize",
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
private fun TextDetectedContent(
    scan: TextScanState.TextDetected,
    onResumeScanning: () -> Unit,
    onFullTextExpansionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = TextDetectedItem.Heading, contentType = TextDetectedItem.Heading) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Recognized Text",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        item(key = TextDetectedItem.FullText, contentType = TextDetectedItem.FullText) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(16.dp),
            ) {
                // A page of recognised text used to push the block list, both buttons and the
                // whole rest of this screen below the fold, with no way to get past it but to
                // scroll through it. Clipping it needs a way back, and offering one unconditionally
                // would put "Show all" under four words — which is why whether the control exists
                // is decided in the measure pass rather than here. See `ExpandableText`.
                ExpandableText(
                    text = scan.fullText.ifBlank { "No text detected" },
                    expanded = scan.isFullTextExpanded,
                    onExpandedChange = onFullTextExpansionChange,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        if (scan.blocks.isNotEmpty()) {
            item(key = TextDetectedItem.BlockHeading, contentType = TextDetectedItem.BlockHeading) {
                Text(
                    text = "Text Blocks (${scan.blocks.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Deliberately unkeyed: a block carries no identity of its own — two blocks in one
            // frame can hold the same text, and a key that repeats is an exception from the lazy
            // layout rather than a mis-render — so its index is the only identity there is, and
            // it is the right one for a list that is only ever replaced whole. See
            // `docs/lazy-lists.md`; `LazyListContractTest.EXPECTED_INDEX_KEYED` pins the claim.
            itemsIndexed(
                items = scan.blocks,
                contentType = { _, _ -> TextDetectedItem.Block },
            ) { index, block ->
                TextBlockCard(index = index + 1, block = block)
            }
        }

        item(key = TextDetectedItem.CopyAction, contentType = TextDetectedItem.CopyAction) {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("recognized_text", scan.fullText))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy to Clipboard")
            }
        }

        item(key = TextDetectedItem.ResumeAction, contentType = TextDetectedItem.ResumeAction) {
            OutlinedButton(
                onClick = onResumeScanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Scan Again")
            }
        }

        item(key = TextDetectedItem.BottomSpacer, contentType = TextDetectedItem.BottomSpacer) {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * The seven shapes [TextDetectedContent] puts in one `LazyColumn`, used as both the `key` and the
 * `contentType` of the slots that produce them.
 *
 * ## As `contentType`
 *
 * A lazy layout reuses a scrolled-off item's composition for an incoming one, but only when the
 * two declare the same content type — otherwise the subcomposition's slot table describes a
 * different tree and there is nothing to reuse. Every slot here left its content type at the
 * default of `null`, which is one type shared by all of them, so the reuse pool offered a
 * `Button`'s composition to a `Text` and to a bordered block card, and every "reuse" threw the
 * whole subtree away and built a new one. Naming the shapes puts blocks — the only slot there is
 * ever more than one of, and therefore the only one reuse can pay off on — in a pool of their own.
 *
 * ## As `key`
 *
 * A slot with no key is identified by its index in the item provider, and two of the seven are
 * emitted conditionally: when `scan.blocks` is empty the block heading and every card disappear,
 * so the copy button, the scan-again button and the bottom spacer all shift up by
 * `blocks.size + 1`. Under index identity that reads as three items being replaced by three
 * different ones — their `remember`ed state is discarded and a scroll anchored to one of them
 * jumps. Named keys make them the same three items that were always there.
 *
 * An `enum` rather than a `String` because a key must survive being written to a `Bundle` for
 * saved-item state to be restored after process death, and Compose's registry accepts a key that
 * is `Serializable`, which every enum entry is. It is also the narrower type: a typo in a string
 * key is a silent duplicate, and a duplicate key is an `IllegalArgumentException` from the lazy
 * layout at runtime.
 */
private enum class TextDetectedItem {
    Heading,
    FullText,
    BlockHeading,
    Block,
    CopyAction,
    ResumeAction,
    BottomSpacer,
}

@Composable
private fun TextBlockCard(
    index: Int,
    block: RecognizedTextBlock,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
    ) {
        Text(
            text = "Block $index",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Text(
            text = block.text,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (block.confidence >= 0f) {
            Text(
                text = "Confidence: ${(block.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
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
            text = "Please grant camera permission in device settings to use text recognition.",
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
private class MlKitTextAnalyzer(
    private val onTextDetected: (fullText: String, blocks: ImmutableList<RecognizedTextBlock>) -> Unit,
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (visionText.text.isNotBlank()) {
                    // Built straight into a persistent list: this runs once per analysed
                    // frame, and the view model's state type is what consumes it, so
                    // producing a `List` here only to convert it there would copy the
                    // whole thing again on the camera thread.
                    val blocks = visionText.textBlocks
                        .mapTo(persistentListOf<RecognizedTextBlock>().builder()) { block ->
                            RecognizedTextBlock(
                                text = block.text,
                                confidence = block.lines
                                    .mapNotNull { it.confidence }
                                    .let { confidences ->
                                        if (confidences.isEmpty()) -1f
                                        else confidences.average().toFloat()
                                    },
                            )
                        }
                        .build()
                    onTextDetected(visionText.text, blocks)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
