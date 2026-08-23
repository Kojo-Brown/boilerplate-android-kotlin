package com.kojo.boilerplate.feature.textrecognition

import com.kojo.boilerplate.core.ui.udf.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@HiltViewModel
class TextRecognitionViewModel @Inject constructor() :
    UdfViewModel<TextRecognitionUiState, TextRecognitionUiEvent, Nothing>() {

    private val _state = MutableStateFlow(TextRecognitionUiState())
    override val state: StateFlow<TextRecognitionUiState> = _state.asStateFlow()

    /**
     * `Nothing` as the effect type, for the same reason as the barcode screen: copying the
     * recognised text is the composable's own one-shot, with no decision here on the way past.
     */
    override fun onEvent(event: TextRecognitionUiEvent) {
        when (event) {
            is TextRecognitionUiEvent.TextDetected -> onTextDetected(event)
            TextRecognitionUiEvent.CameraPermissionDenied -> _state.update {
                it.copy(scan = TextScanState.PermissionDenied(PERMISSION_MESSAGE))
            }
            is TextRecognitionUiEvent.CameraFailed -> _state.update {
                it.copy(scan = TextScanState.Error(event.message))
            }
            TextRecognitionUiEvent.ResumeScanningClicked -> _state.update {
                it.copy(scan = TextScanState.Scanning)
            }
            TextRecognitionUiEvent.FlashToggled -> _state.update {
                it.copy(isFlashEnabled = !it.isFlashEnabled)
            }
        }
    }

    /**
     * ML Kit analyses several frames a second and every one of them arrives here, so the guard
     * is what makes a result hold still long enough to be read. It is now one read of one
     * value inside an atomic `update`: the pair of flags this used to consult could be written
     * independently, and the window between them was a second recognition overwriting the
     * result the user was looking at.
     */
    private fun onTextDetected(event: TextRecognitionUiEvent.TextDetected) {
        _state.update { current ->
            if (current.scan is TextScanState.Scanning) {
                current.copy(scan = TextScanState.TextDetected(event.fullText, event.blocks))
            } else {
                current
            }
        }
    }

    private companion object {
        const val PERMISSION_MESSAGE = "Camera permission is required to recognize text"
    }
}
