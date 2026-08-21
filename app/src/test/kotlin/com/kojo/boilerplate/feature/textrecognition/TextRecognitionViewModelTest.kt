package com.kojo.boilerplate.feature.textrecognition

import com.kojo.boilerplate.core.coroutines.MainDispatcherExtension
import io.mockk.junit5.MockKExtension
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * `state.value` is read directly here, with no collector: this view model backs its state with
 * a plain `MutableStateFlow` rather than a `stateIn` pipeline, so there is no upstream that
 * needs a subscriber and no `WhileSubscribed` window to fall outside of.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class TextRecognitionViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension()

    private lateinit var viewModel: TextRecognitionViewModel

    @BeforeEach
    fun setUp() {
        viewModel = TextRecognitionViewModel()
    }

    private fun detect(
        fullText: String,
        blocks: ImmutableList<RecognizedTextBlock> = persistentListOf(),
    ) = viewModel.onEvent(TextRecognitionUiEvent.TextDetected(fullText, blocks))

    @Test
    fun `the screen starts scanning, unpaused, with the flash off`() = runTest {
        assertEquals(TextRecognitionUiState(), viewModel.state.value)
        assertTrue(viewModel.state.value.scan is TextScanState.Scanning)
        assertFalse(viewModel.state.value.isFlashEnabled)
        assertFalse(viewModel.state.value.isPaused)
    }

    @Test
    fun `recognised text is shown`() = runTest {
        detect("Hello World", persistentListOf(RecognizedTextBlock("Hello World", 0.95f)))

        val scan = viewModel.state.value.scan
        assertTrue(scan is TextScanState.TextDetected)
        val detected = scan as TextScanState.TextDetected
        assertEquals("Hello World", detected.fullText)
        assertEquals(1, detected.blocks.size)
        assertEquals("Hello World", detected.blocks.first().text)
        assertEquals(0.95f, detected.blocks.first().confidence)
    }

    @Test
    fun `text recognised while a result is on screen is ignored`() = runTest {
        detect("First", persistentListOf(RecognizedTextBlock("First", 0.9f)))
        detect("Second", persistentListOf(RecognizedTextBlock("Second", 0.8f)))

        // ML Kit analyses several frames a second; without the guard the result would be
        // replaced under the user as fast as the camera can produce one.
        val scan = viewModel.state.value.scan as TextScanState.TextDetected
        assertEquals("First", scan.fullText)
    }

    /**
     * `isPaused` is derived from the scan state rather than stored beside it. The two were
     * separate flags kept in step by hand, and the detection guard had to consult both because
     * neither could be trusted alone.
     */
    @Test
    fun `paused is exactly while a result is on screen`() = runTest {
        detect("Hello")
        assertTrue(viewModel.state.value.isPaused)

        viewModel.onEvent(TextRecognitionUiEvent.ResumeScanningClicked)
        assertFalse(viewModel.state.value.isPaused)
    }

    @Test
    fun `a denied permission is explained`() = runTest {
        viewModel.onEvent(TextRecognitionUiEvent.CameraPermissionDenied)

        val scan = viewModel.state.value.scan
        assertTrue(scan is TextScanState.PermissionDenied)
        assertTrue((scan as TextScanState.PermissionDenied).message.isNotBlank())
    }

    @Test
    fun `a camera failure carries its message`() = runTest {
        viewModel.onEvent(TextRecognitionUiEvent.CameraFailed("Camera failed to bind"))

        val scan = viewModel.state.value.scan
        assertTrue(scan is TextScanState.Error)
        assertEquals("Camera failed to bind", (scan as TextScanState.Error).message)
    }

    @Test
    fun `resuming after a result goes back to scanning`() = runTest {
        detect("Some text")
        viewModel.onEvent(TextRecognitionUiEvent.ResumeScanningClicked)

        assertTrue(viewModel.state.value.scan is TextScanState.Scanning)
    }

    @Test
    fun `resuming after an error goes back to scanning`() = runTest {
        viewModel.onEvent(TextRecognitionUiEvent.CameraFailed("Camera error"))
        viewModel.onEvent(TextRecognitionUiEvent.ResumeScanningClicked)

        assertTrue(viewModel.state.value.scan is TextScanState.Scanning)
    }

    @Test
    fun `the flash toggles on and back off`() = runTest {
        viewModel.onEvent(TextRecognitionUiEvent.FlashToggled)
        assertTrue(viewModel.state.value.isFlashEnabled)

        viewModel.onEvent(TextRecognitionUiEvent.FlashToggled)
        assertFalse(viewModel.state.value.isFlashEnabled)
    }

    @Test
    fun `the flash survives a result being shown and dismissed`() = runTest {
        viewModel.onEvent(TextRecognitionUiEvent.FlashToggled)
        detect("Some text")
        assertTrue(viewModel.state.value.isFlashEnabled)

        viewModel.onEvent(TextRecognitionUiEvent.ResumeScanningClicked)
        assertTrue(viewModel.state.value.isFlashEnabled)
    }

    @Test
    fun `text blocks are carried through with their confidence`() = runTest {
        val blocks = persistentListOf(
            RecognizedTextBlock("Line one", 0.98f),
            RecognizedTextBlock("Line two", 0.75f),
            // ML Kit reports -1 when it has no confidence figure for a block, and the screen
            // has to be able to tell that from a genuinely low one.
            RecognizedTextBlock("Line three", -1f),
        )
        detect("Line one\nLine two\nLine three", blocks)

        val scan = viewModel.state.value.scan as TextScanState.TextDetected
        assertEquals(3, scan.blocks.size)
        assertEquals(0.98f, scan.blocks[0].confidence)
        assertEquals(0.75f, scan.blocks[1].confidence)
        assertEquals(-1f, scan.blocks[2].confidence)
    }

    @Test
    fun `scanning again after a result replaces it`() = runTest {
        detect("First scan")
        viewModel.onEvent(TextRecognitionUiEvent.ResumeScanningClicked)
        detect("Second scan")

        val scan = viewModel.state.value.scan as TextScanState.TextDetected
        assertEquals("Second scan", scan.fullText)
    }
}
