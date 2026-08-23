package com.kojo.boilerplate.feature.scanner

import com.kojo.boilerplate.core.ui.udf.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@HiltViewModel
class BarcodeScannerViewModel @Inject constructor() :
    UdfViewModel<BarcodeScannerUiState, BarcodeScannerUiEvent, Nothing>() {

    private val _state = MutableStateFlow(BarcodeScannerUiState())
    override val state: StateFlow<BarcodeScannerUiState> = _state.asStateFlow()

    /**
     * `Nothing` as the effect type. Copying a result to the clipboard is the one thing on this
     * screen that happens once, and it is done by the composable that owns the
     * `ClipboardManager` — routing it through here would mean an event whose only handler
     * emits an effect saying the same thing back, with the view model deciding nothing on the
     * way past.
     */
    override fun onEvent(event: BarcodeScannerUiEvent) {
        when (event) {
            is BarcodeScannerUiEvent.BarcodeDetected -> onBarcodeDetected(event)
            BarcodeScannerUiEvent.CameraPermissionDenied -> _state.update {
                it.copy(scan = BarcodeScanState.PermissionDenied(PERMISSION_MESSAGE))
            }
            is BarcodeScannerUiEvent.CameraFailed -> _state.update {
                it.copy(scan = BarcodeScanState.Error(event.message))
            }
            BarcodeScannerUiEvent.ResumeScanningClicked -> _state.update {
                it.copy(scan = BarcodeScanState.Scanning)
            }
            // The torch survives a result being shown and dismissed, which is why it is a
            // field alongside `scan` rather than a property of the scanning state.
            BarcodeScannerUiEvent.FlashToggled -> _state.update {
                it.copy(isFlashEnabled = !it.isFlashEnabled)
            }
        }
    }

    /**
     * The analyser goes on running for a frame or two after a code is recognised, and every
     * one of those frames arrives as another [BarcodeScannerUiEvent.BarcodeDetected]. Only the
     * first is taken: `update` reads and writes atomically, so two frames analysed on
     * different threads cannot both pass the guard, and the second is a no-op rather than a
     * result that replaces the one already on screen.
     */
    private fun onBarcodeDetected(event: BarcodeScannerUiEvent.BarcodeDetected) {
        _state.update { current ->
            if (current.scan is BarcodeScanState.Scanning) {
                current.copy(scan = BarcodeScanState.Detected(event.rawValue, event.format))
            } else {
                current
            }
        }
    }

    private companion object {
        const val PERMISSION_MESSAGE = "Camera permission is required to scan barcodes"
    }
}
