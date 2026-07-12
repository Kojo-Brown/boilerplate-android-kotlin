package com.kojo.boilerplate.feature.textrecognition

sealed class TextRecognitionUiState {
    data object Scanning : TextRecognitionUiState()

    data class TextDetected(
        val fullText: String,
        val blocks: List<RecognizedTextBlock>,
    ) : TextRecognitionUiState()

    data class PermissionDenied(val message: String) : TextRecognitionUiState()

    data class Error(val message: String) : TextRecognitionUiState()
}

data class RecognizedTextBlock(
    val text: String,
    val confidence: Float,
)
