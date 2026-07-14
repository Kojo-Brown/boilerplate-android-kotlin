package com.kojo.boilerplate.feature.scanner

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
class BarcodeScannerViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension()

    private lateinit var viewModel: BarcodeScannerViewModel

    @BeforeEach
    fun setUp() {
        viewModel = BarcodeScannerViewModel()
    }

    @Test
    fun `initial uiState is Scanning`() = runTest {
        assertTrue(viewModel.uiState.first() is BarcodeScannerUiState.Scanning)
    }

    @Test
    fun `initial isFlashEnabled is false`() = runTest {
        assertFalse(viewModel.isFlashEnabled.first())
    }

    @Test
    fun `onBarcodeDetected transitions to BarcodeDetected state`() = runTest {
        viewModel.onBarcodeDetected("https://example.com", BarcodeFormat.QR_CODE)

        val state = viewModel.uiState.first()
        assertTrue(state is BarcodeScannerUiState.BarcodeDetected)
        val detected = state as BarcodeScannerUiState.BarcodeDetected
        assertEquals("https://example.com", detected.rawValue)
        assertEquals(BarcodeFormat.QR_CODE, detected.format)
    }

    @Test
    fun `onBarcodeDetected is ignored when not in Scanning state`() = runTest {
        viewModel.onBarcodeDetected("first", BarcodeFormat.QR_CODE)
        viewModel.onBarcodeDetected("second", BarcodeFormat.EAN_13)

        val state = viewModel.uiState.first() as BarcodeScannerUiState.BarcodeDetected
        assertEquals("first", state.rawValue)
    }

    @Test
    fun `onPermissionDenied transitions to PermissionDenied state`() = runTest {
        viewModel.onPermissionDenied()

        val state = viewModel.uiState.first()
        assertTrue(state is BarcodeScannerUiState.PermissionDenied)
        assertTrue((state as BarcodeScannerUiState.PermissionDenied).message.isNotBlank())
    }

    @Test
    fun `onError transitions to Error state with message`() = runTest {
        viewModel.onError("Camera failed to bind")

        val state = viewModel.uiState.first()
        assertTrue(state is BarcodeScannerUiState.Error)
        assertEquals("Camera failed to bind", (state as BarcodeScannerUiState.Error).message)
    }

    @Test
    fun `resumeScanning resets uiState to Scanning`() = runTest {
        viewModel.onBarcodeDetected("https://example.com", BarcodeFormat.QR_CODE)
        viewModel.resumeScanning()

        assertTrue(viewModel.uiState.first() is BarcodeScannerUiState.Scanning)
    }

    @Test
    fun `resumeScanning after error resets to Scanning`() = runTest {
        viewModel.onError("Camera error")
        viewModel.resumeScanning()

        assertTrue(viewModel.uiState.first() is BarcodeScannerUiState.Scanning)
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
    fun `multiple barcode formats are stored correctly`() = runTest {
        val formats = listOf(
            BarcodeFormat.EAN_13,
            BarcodeFormat.CODE_128,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.AZTEC,
        )
        formats.forEach { format ->
            viewModel.resumeScanning()
            viewModel.onBarcodeDetected("value", format)
            val state = viewModel.uiState.first() as BarcodeScannerUiState.BarcodeDetected
            assertEquals(format, state.format)
        }
    }
}
