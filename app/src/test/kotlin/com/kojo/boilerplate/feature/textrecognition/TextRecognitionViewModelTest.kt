package com.kojo.boilerplate.feature.textrecognition

import com.kojo.boilerplate.core.coroutines.MainDispatcherExtension
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

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

    @Test
    fun `initial uiState is Scanning`() = runTest {
        assertTrue(viewModel.uiState.first() is TextRecognitionUiState.Scanning)
    }

    @Test
    fun `initial isFlashEnabled is false`() = runTest {
        assertFalse(viewModel.isFlashEnabled.first())
    }

    @Test
    fun `initial isPaused is false`() = runTest {
        assertFalse(viewModel.isPaused.first())
    }

    @Test
    fun `onTextDetected transitions to TextDetected state`() = runTest {
        val blocks = listOf(RecognizedTextBlock("Hello World", 0.95f))
        viewModel.onTextDetected("Hello World", blocks)

        val state = viewModel.uiState.first()
        assertTrue(state is TextRecognitionUiState.TextDetected)
        val detected = state as TextRecognitionUiState.TextDetected
        assertEquals("Hello World", detected.fullText)
        assertEquals(1, detected.blocks.size)
        assertEquals("Hello World", detected.blocks.first().text)
        assertEquals(0.95f, detected.blocks.first().confidence)
    }

    @Test
    fun `onTextDetected is ignored when paused`() = runTest {
        val firstBlocks = listOf(RecognizedTextBlock("First", 0.9f))
        val secondBlocks = listOf(RecognizedTextBlock("Second", 0.8f))
        viewModel.onTextDetected("First", firstBlocks)
        viewModel.onTextDetected("Second", secondBlocks)

        val state = viewModel.uiState.first() as TextRecognitionUiState.TextDetected
        assertEquals("First", state.fullText)
    }

    @Test
    fun `onTextDetected sets isPaused to true`() = runTest {
        viewModel.onTextDetected("Hello", emptyList())
        assertTrue(viewModel.isPaused.first())
    }

    @Test
    fun `onPermissionDenied transitions to PermissionDenied state`() = runTest {
        viewModel.onPermissionDenied()

        val state = viewModel.uiState.first()
        assertTrue(state is TextRecognitionUiState.PermissionDenied)
        assertTrue((state as TextRecognitionUiState.PermissionDenied).message.isNotBlank())
    }

    @Test
    fun `onError transitions to Error state with message`() = runTest {
        viewModel.onError("Camera failed to bind")

        val state = viewModel.uiState.first()
        assertTrue(state is TextRecognitionUiState.Error)
        assertEquals("Camera failed to bind", (state as TextRecognitionUiState.Error).message)
    }

    @Test
    fun `resumeScanning resets uiState to Scanning`() = runTest {
        viewModel.onTextDetected("Some text", emptyList())
        viewModel.resumeScanning()

        assertTrue(viewModel.uiState.first() is TextRecognitionUiState.Scanning)
    }

    @Test
    fun `resumeScanning resets isPaused to false`() = runTest {
        viewModel.onTextDetected("Some text", emptyList())
        viewModel.resumeScanning()

        assertFalse(viewModel.isPaused.first())
    }

    @Test
    fun `resumeScanning after error resets to Scanning`() = runTest {
        viewModel.onError("Camera error")
        viewModel.resumeScanning()

        assertTrue(viewModel.uiState.first() is TextRecognitionUiState.Scanning)
    }

    @Test
    fun `toggleFlash enables flash when off`() = runTest {
        viewModel.toggleFlash()
        assertTrue(viewModel.isFlashEnabled.first())
    }

    @Test
    fun `toggleFlash disables flash when on`() = runTest {
        viewModel.toggleFlash()
        viewModel.toggleFlash()
        assertFalse(viewModel.isFlashEnabled.first())
    }

    @Test
    fun `text blocks are stored with correct confidence`() = runTest {
        val blocks = listOf(
            RecognizedTextBlock("Line one", 0.98f),
            RecognizedTextBlock("Line two", 0.75f),
            RecognizedTextBlock("Line three", -1f),
        )
        viewModel.onTextDetected("Line one\nLine two\nLine three", blocks)

        val state = viewModel.uiState.first() as TextRecognitionUiState.TextDetected
        assertEquals(3, state.blocks.size)
        assertEquals(0.98f, state.blocks[0].confidence)
        assertEquals(0.75f, state.blocks[1].confidence)
        assertEquals(-1f, state.blocks[2].confidence)
    }

    @Test
    fun `can scan again after previous result`() = runTest {
        viewModel.onTextDetected("First scan", emptyList())
        viewModel.resumeScanning()
        viewModel.onTextDetected("Second scan", emptyList())

        val state = viewModel.uiState.first() as TextRecognitionUiState.TextDetected
        assertEquals("Second scan", state.fullText)
    }
}
