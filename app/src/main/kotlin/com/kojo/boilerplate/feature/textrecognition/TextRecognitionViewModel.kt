package com.kojo.boilerplate.feature.textrecognition

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class TextRecognitionViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow<TextRecognitionUiState>(TextRecognitionUiState.Scanning)
    val uiState: StateFlow<TextRecognitionUiState> = _uiState.asStateFlow()

    private val _isFlashEnabled = MutableStateFlow(false)
    val isFlashEnabled: StateFlow<Boolean> = _isFlashEnabled.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    fun onTextDetected(fullText: String, blocks: List<RecognizedTextBlock>) {
        if (_uiState.value is TextRecognitionUiState.Scanning && !_isPaused.value) {
            _uiState.value = TextRecognitionUiState.TextDetected(fullText, blocks)
            _isPaused.value = true
        }
    }

    fun onPermissionDenied() {
        _uiState.value = TextRecognitionUiState.PermissionDenied(
            "Camera permission is required to recognize text",
        )
    }

    fun onError(message: String) {
        _uiState.value = TextRecognitionUiState.Error(message)
    }

    fun resumeScanning() {
        _uiState.value = TextRecognitionUiState.Scanning
        _isPaused.value = false
    }

    fun toggleFlash() {
        _isFlashEnabled.value = !_isFlashEnabled.value
    }
}
