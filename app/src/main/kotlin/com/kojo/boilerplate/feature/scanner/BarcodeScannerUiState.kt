package com.kojo.boilerplate.feature.scanner

import androidx.compose.runtime.Immutable

/**
 * Everything the barcode screen renders, in one value.
 *
 * The flash used to be a second `StateFlow` next to the scan state, and folding it in is not
 * cosmetic: two flows are two values that can disagree, and the screen had to collect both and
 * reason about the pair. One value cannot be half-updated, and a test that asserts on it
 * asserts on the whole screen rather than on one of its halves.
 *
 * What stays split is [scan] — the parts of the screen that are mutually exclusive belong in a
 * sealed type, because "scanning **and** permission denied" is not a state this screen has.
 * The flash is not one of those: it is on or off underneath whatever [scan] is doing, which is
 * exactly what a field next to a sealed one says.
 */
@Immutable
data class BarcodeScannerUiState(
    val scan: BarcodeScanState = BarcodeScanState.Scanning,
    val isFlashEnabled: Boolean = false,
)

@Immutable
sealed interface BarcodeScanState {

    /** The camera is running and nothing has been recognised yet. */
    data object Scanning : BarcodeScanState

    @Immutable
    data class Detected(
        val rawValue: String,
        val format: BarcodeFormat,
    ) : BarcodeScanState

    @Immutable
    data class PermissionDenied(val message: String) : BarcodeScanState

    @Immutable
    data class Error(val message: String) : BarcodeScanState
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
