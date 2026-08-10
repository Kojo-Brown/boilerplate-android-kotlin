package com.kojo.boilerplate.feature.scanner

import androidx.compose.runtime.Immutable

@Immutable
sealed class BarcodeScannerUiState {
    data object Scanning : BarcodeScannerUiState()

    @Immutable
    data class BarcodeDetected(
        val rawValue: String,
        val format: BarcodeFormat,
    ) : BarcodeScannerUiState()

    @Immutable
    data class PermissionDenied(val message: String) : BarcodeScannerUiState()

    @Immutable
    data class Error(val message: String) : BarcodeScannerUiState()
}

/**
 * Carries no annotation on purpose: the Compose compiler treats every enum as stable, and
 * `displayName` is a `val` fixed at class-initialisation time.
 */
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
