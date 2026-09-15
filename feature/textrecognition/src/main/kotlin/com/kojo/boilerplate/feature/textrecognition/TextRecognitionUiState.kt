package com.kojo.boilerplate.feature.textrecognition

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * Everything the text-recognition screen renders, in one value.
 *
 * This screen is where two flows disagreeing stopped being hypothetical. It used to expose the
 * scan state, `isFlashEnabled` and `isPaused` separately, and the detection guard read two of
 * them — `uiState is Scanning && !isPaused` — because neither on its own could be trusted.
 * `isPaused` was true exactly when the scan state was [TextScanState.TextDetected], so it was
 * never information; it was a second copy of one, kept in step by hand. Folding the screen
 * into one value deleted it, and with it the question of what to do when the two disagree.
 */
@Immutable
data class TextRecognitionUiState(
    val scan: TextScanState = TextScanState.Scanning,
    val isFlashEnabled: Boolean = false,
) {
    /**
     * Derived, not stored. The analyser is paused precisely while a result is on screen, which
     * is what [TextScanState.TextDetected] already says.
     */
    val isPaused: Boolean get() = scan is TextScanState.TextDetected
}

@Immutable
sealed interface TextScanState {

    /** The camera is running and nothing has been recognised yet. */
    data object Scanning : TextScanState

    /**
     * [blocks] is an [ImmutableList] for the same reason `HomeUiState` uses one, and with a
     * sharper edge: the source of these blocks is an ML Kit callback that fires once per
     * analysed camera frame, so a `List` here would hand Compose a fresh `ArrayList` several
     * times a second and defeat skipping on the whole result pane.
     */
    @Immutable
    data class TextDetected(
        val fullText: String,
        val blocks: ImmutableList<RecognizedTextBlock>,
        /**
         * Whether the reader has asked to see all of [fullText] rather than its first few lines.
         *
         * Here rather than in a `remember` inside the result pane for the reason every other flag
         * on this screen is here: the pane lives in a `LazyColumn` item, and a `remember` in a
         * lazy slot is discarded the moment the slot is scrolled out and re-composed from its
         * default when it comes back — so a long result would silently re-collapse itself behind
         * the reader. It is a field of [TextDetected] rather than of [TextRecognitionUiState]
         * because it is a fact about *this* result: the next scan replaces the whole state and the
         * flag goes with it, which is what it should do.
         *
         * It is a request rather than an instruction. Nothing here knows whether this text
         * overflows at the width and font size it is about to be drawn at, so whether it is
         * honoured is settled in the measure pass — see `ExpandableText`.
         */
        val isFullTextExpanded: Boolean = false,
    ) : TextScanState

    @Immutable
    data class PermissionDenied(val message: String) : TextScanState

    @Immutable
    data class Error(val message: String) : TextScanState
}

@Immutable
data class RecognizedTextBlock(
    val text: String,
    val confidence: Float,
)
