package com.kojo.boilerplate.feature.scanner

sealed class BarcodeScannerUiState {
    data object Scanning : BarcodeScannerUiState()

    data class BarcodeDetected(
        val rawValue: String,
        val format: BarcodeFormat,
    ) : BarcodeScannerUiState()

    data class PermissionDenied(val message: String) : BarcodeScannerUiState()

    data class Error(val message: String) : BarcodeScannerUiState()
}

enum class BarcodeFormat(val displayName: String) {
    QR_CODE("QR Code"),
    EAN_13("EAN-13"),
    EAN_8("EAN-8"),
    CODE_128("Code 128"),
    CODE_39("Code 39"),
    DATA_MATRIX("Data Matrix"),
    PDF_417("PDF-417"),
    AZTEC("Aztec"),
    ITF("ITF"),
    UNKNOWN("Unknown"),
}
