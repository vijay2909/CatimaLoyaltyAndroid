package protect.card_locker.viewmodels

import android.content.Intent
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.journeyapps.barcodescanner.BarcodeResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import protect.card_locker.CatimaBarcode
import protect.card_locker.LoyaltyCard
import protect.card_locker.LoyaltyCardEditActivity
import protect.card_locker.ParseResult
import protect.card_locker.ParseResultType
import protect.card_locker.screens.ScanActivity


class ScanViewModel : ViewModel() {

    // Intent data
    var cardId: String? = null
    var addGroup: String? = null

    // --- StateFlow for State ---
    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn = _isTorchOn.asStateFlow()

    private val _isScannerActive = MutableStateFlow(true)
    val isScannerActive = _isScannerActive.asStateFlow()

    private val _cameraError = MutableStateFlow<CameraError?>(null)
    val cameraError = _cameraError.asStateFlow()

    // --- Channel for one-time Events ---
    private val _eventChannel = Channel<ScanEvent>()
    val events = _eventChannel.receiveAsFlow()

    fun processIntent(intent: Intent?) {
        intent?.extras?.let {
            cardId = it.getString(LoyaltyCard.BUNDLE_LOYALTY_CARD_CARD_ID)
            addGroup = it.getString(LoyaltyCardEditActivity.BUNDLE_ADDGROUP)
        }
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("scannerActive", _isScannerActive.value)
    }

    fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        _isScannerActive.value = savedInstanceState?.getBoolean("scannerActive", true) ?: true
    }

    // --- User Actions ---
    fun toggleTorch() {
        _isTorchOn.value = !_isTorchOn.value
    }

    fun onBarcodeResult(result: BarcodeResult) {
        val loyaltyCard = LoyaltyCard().apply {
            cardId = result.text
            barcodeType = CatimaBarcode.fromBarcode(result.barcodeFormat)
        }
        onParseResultChosen(ParseResult(ParseResultType.BARCODE_ONLY, loyaltyCard))
    }

    fun onAddOptionSelected(index: Int) = viewModelScope.launch {
        when (index) {
            1 -> _eventChannel.send(ScanEvent.LaunchIntent.ManualAdd)
            2 -> _eventChannel.send(ScanEvent.RequestPermission(ScanActivity.PERMISSION_SCAN_ADD_FROM_IMAGE))
            3 -> _eventChannel.send(ScanEvent.RequestPermission(ScanActivity.PERMISSION_SCAN_ADD_FROM_PDF))
            4 -> _eventChannel.send(ScanEvent.RequestPermission(ScanActivity.PERMISSION_SCAN_ADD_FROM_PKPASS))
        }
    }

    fun onParseResultChosen(parseResult: ParseResult) = viewModelScope.launch {
        _eventChannel.send(ScanEvent.FinishWithResult(parseResult))
    }

    // --- UI State Management ---
    fun setScannerActive(isActive: Boolean) {
        _isScannerActive.value = isActive
    }

    fun onCaptureManagerError(errorMessage: String) {
        _cameraError.value = CameraError(errorMessage, isPermissionError = false)
    }

    fun onCameraPermissionMissing() {
        _cameraError.value = CameraError(message = null, isPermissionError = true)
    }

    fun onCameraPermissionGranted() {
        _cameraError.value = null // Clear error
    }

    // --- Sealed class for Events for type safety ---
    sealed class ScanEvent {
        data class FinishWithResult(val result: ParseResult) : ScanEvent()
        data class RequestPermission(val permissionCode: Int) : ScanEvent()
        sealed class LaunchIntent : ScanEvent() {
            object ManualAdd : LaunchIntent()
            object ImagePicker : LaunchIntent()
            object PdfPicker : LaunchIntent()
            object PkpassPicker : LaunchIntent()
        }
    }

    // --- Data class for error state ---
    data class CameraError(val message: String?, val isPermissionError: Boolean)
}

data class AddCardOption(
    val text: String,
    @DrawableRes val iconResId: Int
)