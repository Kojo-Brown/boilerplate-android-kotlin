package com.kojo.boilerplate.feature.scanner

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class BarcodeScannerViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow<BarcodeScannerUiState>(BarcodeScannerUiState.Scanning)
    val uiState: StateFlow<BarcodeScannerUiState> = _uiState.asStateFlow()

    private val _isFlashEnabled = MutableStateFlow(false)
    val isFlashEnabled: StateFlow<Boolean> = _isFlashEnabled.asStateFlow()

    fun onBarcodeDetected(rawValue: String, format: BarcodeFormat) {
        if (_uiState.value is BarcodeScannerUiState.Scanning) {
            _uiState.value = BarcodeScannerUiState.BarcodeDetected(rawValue, format)
        }
    }

    fun onPermissionDenied() {
        _uiState.value = BarcodeScannerUiState.PermissionDenied(
            "Camera permission is required to scan barcodes",
        )
    }

    fun onError(message: String) {
        _uiState.value = BarcodeScannerUiState.Error(message)
    }

    fun resumeScanning() {
        _uiState.value = BarcodeScannerUiState.Scanning
    }

    fun toggleFlash() {
        _isFlashEnabled.value = !_isFlashEnabled.value
    }
}
