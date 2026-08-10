package com.kojo.boilerplate.feature.textrecognition

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
sealed class TextRecognitionUiState {
    data object Scanning : TextRecognitionUiState()

    /**
     * [blocks] is an [ImmutableList] for the same reason `HomeUiState.Success.items` is, and
     * with a sharper edge: the source of these blocks is an ML Kit callback that fires once
     * per analysed camera frame, so a `List` here would hand Compose a fresh `ArrayList`
     * several times a second and defeat skipping on the whole result pane.
     */
    @Immutable
    data class TextDetected(
        val fullText: String,
        val blocks: ImmutableList<RecognizedTextBlock>,
    ) : TextRecognitionUiState()

    @Immutable
    data class PermissionDenied(val message: String) : TextRecognitionUiState()

    @Immutable
    data class Error(val message: String) : TextRecognitionUiState()
}

@Immutable
data class RecognizedTextBlock(
    val text: String,
    val confidence: Float,
)
