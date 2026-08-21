package com.kojo.boilerplate.feature.scanner

import com.kojo.boilerplate.core.coroutines.MainDispatcherExtension
import io.mockk.junit5.MockKExtension
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
class BarcodeScannerViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension()

    private lateinit var viewModel: BarcodeScannerViewModel

    @BeforeEach
    fun setUp() {
        viewModel = BarcodeScannerViewModel()
    }

    private fun detect(rawValue: String, format: BarcodeFormat) =
        viewModel.onEvent(BarcodeScannerUiEvent.BarcodeDetected(rawValue, format))

    @Test
    fun `the screen starts scanning, with the flash off`() = runTest {
        assertEquals(BarcodeScannerUiState(), viewModel.state.value)
        assertTrue(viewModel.state.value.scan is BarcodeScanState.Scanning)
        assertFalse(viewModel.state.value.isFlashEnabled)
    }

    @Test
    fun `a detected barcode is shown`() = runTest {
        detect("https://example.com", BarcodeFormat.QR_CODE)

        val scan = viewModel.state.value.scan
        assertTrue(scan is BarcodeScanState.Detected)
        val detected = scan as BarcodeScanState.Detected
        assertEquals("https://example.com", detected.rawValue)
        assertEquals(BarcodeFormat.QR_CODE, detected.format)
    }

    @Test
    fun `a barcode detected while a result is on screen is ignored`() = runTest {
        detect("first", BarcodeFormat.QR_CODE)
        detect("second", BarcodeFormat.EAN_13)

        // The analyser keeps running for a frame or two after the first hit, and the result
        // the user is reading must not be replaced underneath them.
        val scan = viewModel.state.value.scan as BarcodeScanState.Detected
        assertEquals("first", scan.rawValue)
    }

    @Test
    fun `a denied permission is explained`() = runTest {
        viewModel.onEvent(BarcodeScannerUiEvent.CameraPermissionDenied)

        val scan = viewModel.state.value.scan
        assertTrue(scan is BarcodeScanState.PermissionDenied)
        assertTrue((scan as BarcodeScanState.PermissionDenied).message.isNotBlank())
    }

    @Test
    fun `a camera failure carries its message`() = runTest {
        viewModel.onEvent(BarcodeScannerUiEvent.CameraFailed("Camera failed to bind"))

        val scan = viewModel.state.value.scan
        assertTrue(scan is BarcodeScanState.Error)
        assertEquals("Camera failed to bind", (scan as BarcodeScanState.Error).message)
    }

    @Test
    fun `resuming after a result goes back to scanning`() = runTest {
        detect("https://example.com", BarcodeFormat.QR_CODE)
        viewModel.onEvent(BarcodeScannerUiEvent.ResumeScanningClicked)

        assertTrue(viewModel.state.value.scan is BarcodeScanState.Scanning)
    }

    @Test
    fun `resuming after an error goes back to scanning`() = runTest {
        viewModel.onEvent(BarcodeScannerUiEvent.CameraFailed("Camera error"))
        viewModel.onEvent(BarcodeScannerUiEvent.ResumeScanningClicked)

        assertTrue(viewModel.state.value.scan is BarcodeScanState.Scanning)
    }

    @Test
    fun `the flash toggles on and back off`() = runTest {
        viewModel.onEvent(BarcodeScannerUiEvent.FlashToggled)
        assertTrue(viewModel.state.value.isFlashEnabled)

        viewModel.onEvent(BarcodeScannerUiEvent.FlashToggled)
        assertFalse(viewModel.state.value.isFlashEnabled)
    }

    /**
     * The flash and the scan state live in one value now, and this is the property that made
     * two flows the wrong shape: the torch is on or off *underneath* whatever the scan is
     * doing, and a result arriving must not turn it off.
     */
    @Test
    fun `the flash survives a result being shown and dismissed`() = runTest {
        viewModel.onEvent(BarcodeScannerUiEvent.FlashToggled)
        detect("https://example.com", BarcodeFormat.QR_CODE)
        assertTrue(viewModel.state.value.isFlashEnabled)

        viewModel.onEvent(BarcodeScannerUiEvent.ResumeScanningClicked)
        assertTrue(viewModel.state.value.isFlashEnabled)
    }

    @Test
    fun `every barcode format is carried through unchanged`() = runTest {
        BarcodeFormat.entries.forEach { format ->
            viewModel.onEvent(BarcodeScannerUiEvent.ResumeScanningClicked)
            detect("value", format)
            val scan = viewModel.state.value.scan as BarcodeScanState.Detected
            assertEquals(format, scan.format)
        }
    }
}
