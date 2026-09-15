package com.kojo.boilerplate.feature.textrecognition

import com.kojo.boilerplate.core.ui.udf.UiEvent
import kotlinx.collections.immutable.ImmutableList

/**
 * Everything the text-recognition screen can be told.
 *
 * Deliberately the same shape as `BarcodeScannerUiEvent` — the two camera screens are
 * near-duplicates and their contracts should make that visible rather than hide it behind
 * differently-named members. Sharing one type between them is the wrong fix while their
 * payloads differ (a barcode is a string and a format; a recognition is a string and its
 * blocks); extracting the common camera scaffolding is a refactor with real design choices in
 * it, and `config/detekt/detekt.yml` already records that debt.
 */
sealed interface TextRecognitionUiEvent : UiEvent {

    /** The analyser recognised text. Ignored unless the screen is still scanning. */
    data class TextDetected(
        val fullText: String,
        val blocks: ImmutableList<RecognizedTextBlock>,
    ) : TextRecognitionUiEvent

    /** The runtime permission request came back denied. */
    data object CameraPermissionDenied : TextRecognitionUiEvent

    /** CameraX could not bind or the analyser threw. */
    data class CameraFailed(val message: String) : TextRecognitionUiEvent

    /** "Scan again" on the result sheet, or "Retry" on the error state. */
    data object ResumeScanningClicked : TextRecognitionUiEvent

    /** The torch button in the app bar. */
    data object FlashToggled : TextRecognitionUiEvent

    /**
     * "Show all" / "Show less" under the recognised text.
     *
     * Carries the state it is moving *to* rather than being a bare toggle, because the control
     * that raises it is drawn from a measurement: a result that stopped overflowing is shown
     * collapsed whatever this flag says, so a bare toggle would flip a flag that no longer
     * describes what is on screen. The screen sends what the reader asked for and the layout
     * decides what that means.
     */
    data class FullTextExpansionChanged(val expanded: Boolean) : TextRecognitionUiEvent
}
