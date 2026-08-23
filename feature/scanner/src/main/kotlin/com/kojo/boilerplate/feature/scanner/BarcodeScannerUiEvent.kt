package com.kojo.boilerplate.feature.scanner

import com.kojo.boilerplate.core.ui.udf.UiEvent

/**
 * Everything the barcode screen can be told.
 *
 * Three of these come from the camera stack rather than from a finger — the analyser
 * recognising a code, CameraX failing to bind, the permission dialog coming back denied — and
 * they are events all the same. "The user did something" is the wrong test; "the view model
 * needs to know" is the right one, and a screen that reported the analyser's results by
 * calling a method while reporting taps through `onEvent` would have two ways in again.
 */
sealed interface BarcodeScannerUiEvent : UiEvent {

    /** The analyser recognised a code. Ignored unless the screen is still scanning. */
    data class BarcodeDetected(
        val rawValue: String,
        val format: BarcodeFormat,
    ) : BarcodeScannerUiEvent

    /** The runtime permission request came back denied. */
    data object CameraPermissionDenied : BarcodeScannerUiEvent

    /** CameraX could not bind or the analyser threw. */
    data class CameraFailed(val message: String) : BarcodeScannerUiEvent

    /** "Scan another" on the result sheet, or "Retry" on the error state. */
    data object ResumeScanningClicked : BarcodeScannerUiEvent

    /** The torch button in the app bar. */
    data object FlashToggled : BarcodeScannerUiEvent
}
