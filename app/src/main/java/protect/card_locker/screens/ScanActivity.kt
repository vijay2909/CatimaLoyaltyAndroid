package protect.card_locker.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.DecodeHintType
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.CaptureManager
import kotlinx.coroutines.launch
import protect.card_locker.BarcodeSelectorActivity
import protect.card_locker.CatimaAppCompatActivity
import protect.card_locker.CatimaCaptureManager
import protect.card_locker.LoyaltyCard
import protect.card_locker.LoyaltyCardEditActivity
import protect.card_locker.ParseResult
import protect.card_locker.ParseResultListDisambiguatorCallback
import protect.card_locker.ParseResultType
import protect.card_locker.PermissionUtils
import protect.card_locker.R
import protect.card_locker.Utils
import protect.card_locker.databinding.AlertdialogRowWithIconBinding
import protect.card_locker.databinding.CustomBarcodeScannerBinding
import protect.card_locker.databinding.ScanActivityBinding
import protect.card_locker.viewmodels.AddCardOption
import protect.card_locker.viewmodels.ScanViewModel

class ScanActivity : CatimaAppCompatActivity() {
    private lateinit var binding: ScanActivityBinding
    private val customBarcodeScannerBinding by lazy {
        CustomBarcodeScannerBinding.bind(binding.zxingBarcodeScanner)
    }
    private val barcodeScannerView by lazy { binding.zxingBarcodeScanner }

    private val viewModel: ScanViewModel by viewModels()

    private val manualAddLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            handleActivityResult(
                Utils.SELECT_BARCODE_REQUEST,
                it.resultCode,
                it.data,
            )
        }
    private val photoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            handleActivityResult(
                Utils.BARCODE_IMPORT_FROM_IMAGE_FILE,
                it.resultCode,
                it.data,
            )
        }
    private val pdfPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            handleActivityResult(
                Utils.BARCODE_IMPORT_FROM_PDF_FILE,
                it.resultCode,
                it.data,
            )
        }
    private val pkpassPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            handleActivityResult(
                Utils.BARCODE_IMPORT_FROM_PKPASS_FILE,
                it.resultCode,
                it.data,
            )
        }

    private lateinit var capture: CaptureManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ScanActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.processIntent(intent)
        savedInstanceState?.let { viewModel.onRestoreInstanceState(it) }

        setupToolbar()
        setupViews()
        setupCaptureManager(savedInstanceState)
        collectFlows()
    }

    private fun setupToolbar() {
        title = getString(R.string.scanCardBarcode)
        Utils.applyWindowInsets(binding.root)
        setSupportActionBar(binding.toolbar)
        enableToolbarBackButton()
    }

    private fun setupViews() {
        customBarcodeScannerBinding.fabOtherOptions.setOnClickListener { showOtherOptionsDialog() }

        barcodeScannerView.decodeSingle { result ->
            viewModel.onBarcodeResult(result)
        }
    }

    private fun showOtherOptionsDialog() {
        viewModel.setScannerActive(false)

        val options = listOf(
            AddCardOption(getString(R.string.addWithoutBarcode), R.drawable.baseline_block_24),
            AddCardOption(getString(R.string.addManually), R.drawable.ic_edit),
            AddCardOption(getString(R.string.addFromImage), R.drawable.baseline_image_24),
            AddCardOption(
                getString(R.string.addFromPdfFile),
                R.drawable.baseline_picture_as_pdf_24
            ),
            AddCardOption(getString(R.string.addFromPkpass), R.drawable.local_activity_24px)
        )

        val adapter = AddCardOptionAdapter(
            context = this,
            options = options,
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.add_a_card_in_a_different_way))
            .setAdapter(adapter) { dialog, which ->
                when (which) {
                    0 -> onAddCardWithoutBarcode()
                    1 -> viewModel.onAddOptionSelected(1) // Delegate to ViewModel
                    2 -> viewModel.onAddOptionSelected(2)
                    3 -> viewModel.onAddOptionSelected(3)
                    4 -> viewModel.onAddOptionSelected(4)
                    else -> throw IllegalArgumentException("Unknown dialog option")
                }
            }
            .setOnCancelListener { viewModel.setScannerActive(true) }
            .show()
    }

    private fun onAddCardWithoutBarcode() {
        // The EditText and the Dialog need to be declared before the listener
        // so they can be referenced inside it.
        lateinit var input: EditText
        lateinit var dialog: AlertDialog

        val builder = MaterialAlertDialogBuilder(this).apply {
            setTitle(R.string.addWithoutBarcode)

            // This listener is called when the user presses back or taps outside the dialog
            setOnCancelListener {
                viewModel.setScannerActive(true)
            }

            // --- Build the custom layout programmatically ---
            val contentPadding =
                resources.getDimensionPixelSize(R.dimen.alert_dialog_content_padding)
            val layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(contentPadding, contentPadding / 2, contentPadding, 0)
            }

            val layout = LinearLayout(this@ScanActivity).apply {
                orientation = LinearLayout.VERTICAL

                // Description TextView
                addView(TextView(this@ScanActivity).apply {
                    text = getString(R.string.enter_card_id)
                    this.layoutParams = layoutParams
                })

                // Input EditText
                input = EditText(this@ScanActivity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    this.layoutParams = layoutParams
                    // Use the KTX library for a cleaner text watcher
                    addTextChangedListener { text ->
                        val isOkButtonEnabled = !text.isNullOrBlank()
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = isOkButtonEnabled

                        // Set or clear the error message based on input
                        error = if (isOkButtonEnabled) {
                            null
                        } else {
                            getString(R.string.card_id_must_not_be_empty)
                        }
                    }
                }
                addView(input)
            }

            setView(layout)

            // --- Set up Buttons ---
            setPositiveButton(R.string.ok) { _, _ ->
                val loyaltyCard = LoyaltyCard().apply {
                    cardId = input.text.toString()
                }
                // Delegate the result to the ViewModel
                viewModel.onParseResultChosen(
                    ParseResult(
                        ParseResultType.BARCODE_ONLY,
                        loyaltyCard
                    )
                )
            }
            setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.cancel() // This will trigger the setOnCancelListener
            }
        }

        dialog = builder.create()
        dialog.show()

        // Disable the OK button initially, after the dialog is shown
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

        // Request focus and show the keyboard
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun setupCaptureManager(savedInstanceState: Bundle?) {
        barcodeScannerView.initializeFromIntent(
            Intent().apply {
                putExtras(
                    Bundle().apply {
                        putBoolean(DecodeHintType.ALSO_INVERTED.name, true)
                    }
                )
            }
        )

        // Even though we do the actual decoding with the barcodeScannerView
        // CaptureManager needs to be running to show the camera and scanning bar
        capture = CatimaCaptureManager(this, barcodeScannerView, viewModel::onCaptureManagerError)
        val captureIntent = Intent().apply { putExtra(Intents.Scan.BEEP_ENABLED, false) }
        capture.initializeFromIntent(captureIntent, savedInstanceState)
    }

    private fun collectFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isScannerActive.collect { isActive ->
                        if (isActive) barcodeScannerView.resume() else barcodeScannerView.pause()
                    }
                }
                launch {
                    viewModel.isTorchOn.collect { isOn ->
                        if (isOn) barcodeScannerView.setTorchOn() else barcodeScannerView.setTorchOff()
                        invalidateOptionsMenu()
                    }
                }
                launch {
                    viewModel.cameraError.collect(::handleCameraError)
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ScanViewModel.ScanEvent.FinishWithResult -> returnResult(event.result)
                            is ScanViewModel.ScanEvent.RequestPermission -> requestPermission(event.permissionCode)

                            is ScanViewModel.ScanEvent.LaunchIntent.ManualAdd -> addManually()
                            is ScanViewModel.ScanEvent.LaunchIntent.ImagePicker -> addFromImageOrFileAfterPermission(
                                "image/*",
                                photoPickerLauncher,
                                R.string.addFromImage,
                                R.string.failedLaunchingPhotoPicker
                            )

                            is ScanViewModel.ScanEvent.LaunchIntent.PdfPicker -> addFromImageOrFileAfterPermission(
                                "application/pdf",
                                pdfPickerLauncher,
                                R.string.addFromPdfFile,
                                R.string.failedLaunchingFileManager
                            )

                            is ScanViewModel.ScanEvent.LaunchIntent.PkpassPicker -> addFromImageOrFileAfterPermission(
                                "application/*",
                                pkpassPickerLauncher,
                                R.string.addFromPkpass,
                                R.string.failedLaunchingFileManager
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.isScannerActive.value) {
            capture.onResume()
        }
        checkCameraStatus()
        scaleScreen()
    }

    private fun scaleScreen() {
        val displayMetrics = DisplayMetrics()
        val screenHeight = displayMetrics.heightPixels
        val mediumSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            MEDIUM_SCALE_FACTOR_DIP.toFloat(),
            getResources().displayMetrics
        )
        val shouldScaleSmaller = screenHeight < mediumSizePx
        customBarcodeScannerBinding.cameraErrorLayout.apply {
            cameraErrorIcon.visibility = if (shouldScaleSmaller) View.GONE else View.VISIBLE
            cameraErrorTitle.visibility = if (shouldScaleSmaller) View.GONE else View.VISIBLE
        }
    }

    private fun checkCameraStatus() {
        when {
            !Utils.deviceHasCamera(this) -> viewModel.onCaptureManagerError(getString(R.string.noCameraFoundGuideText))
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED -> viewModel.onCameraPermissionMissing()

            else -> viewModel.onCameraPermissionGranted()
        }
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
        viewModel.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            menuInflater.inflate(R.menu.scan_menu, menu)
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            menu.findItem(R.id.action_toggle_flashlight)?.let { item ->
                if (viewModel.isTorchOn.value) {
                    item.setTitle(R.string.turn_flashlight_off)
                    item.setIcon(R.drawable.ic_flashlight_on_white_24dp)
                } else {
                    item.setTitle(R.string.turn_flashlight_on)
                    item.setIcon(R.drawable.ic_flashlight_off_white_24dp)
                }
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                setResult(RESULT_CANCELED)
                finish()
                true
            }

            R.id.action_toggle_flashlight -> {
                viewModel.toggleTorch()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun returnResult(parseResult: ParseResult) {
        val resultIntent = Intent().apply {
            val bundle = parseResult.toLoyaltyCardBundle(this@ScanActivity)
            viewModel.addGroup?.let {
                bundle.putString(
                    LoyaltyCardEditActivity.BUNDLE_ADDGROUP,
                    it
                )
            }
            putExtras(bundle)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun requestPermission(permissionCode: Int) {
        PermissionUtils.requestStorageReadPermission(this, permissionCode)
    }

    private fun handleCameraError(error: ScanViewModel.CameraError?) {
        if (error == null) {
            setCameraErrorState(visible = false, setOnClick = false, message = null)
        } else if (error.isPermissionError) {
            setCameraErrorState(
                visible = true,
                setOnClick = true,
                message = getString(R.string.noCameraPermissionDirectToSystemSetting)
            )
        } else {
            setCameraErrorState(visible = true, setOnClick = false, message = error.message)
        }
    }

    private fun setCameraErrorState(visible: Boolean, setOnClick: Boolean, message: String?) {
        customBarcodeScannerBinding.cameraErrorLayout.root.visibility =
            if (visible) View.VISIBLE else View.GONE
        if (visible) {
            customBarcodeScannerBinding.cameraErrorLayout.cameraErrorMessage.text = message
        }
        customBarcodeScannerBinding.cardInputContainer.setBackgroundColor(
            if (visible) obtainThemeAttribute(
                com.google.android.material.R.attr.colorSurface
            ) else Color.TRANSPARENT
        )
        val clickListener =
            if (visible && setOnClick) View.OnClickListener { navigateToSystemPermissionSetting() } else null
        customBarcodeScannerBinding.cameraErrorLayout.cameraErrorClickableArea.setOnClickListener(
            clickListener
        )
    }

    private fun obtainThemeAttribute(attribute: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attribute, typedValue, true)
        return typedValue.data
    }

    private fun navigateToSystemPermissionSetting() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
    }

    private fun addManually() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_manually_warning_title)
            .setMessage(R.string.add_manually_warning_message)
            .setPositiveButton(R.string.continue_) { dialog, which ->
                val i = Intent(applicationContext, BarcodeSelectorActivity::class.java)
                if (viewModel.cardId != null) {
                    i.putExtras(
                        Bundle().apply {
                            putString(LoyaltyCard.BUNDLE_LOYALTY_CARD_CARD_ID, viewModel.cardId)
                        }
                    )
                }
                manualAddLauncher.launch(i)
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> viewModel.setScannerActive(true) }
            .setOnCancelListener { viewModel.setScannerActive(true) }
            .show()
    }

    private fun addFromImageOrFileAfterPermission(
        mimeType: String,
        launcher: ActivityResultLauncher<Intent>,
        chooserText: Int,
        errorMessage: Int
    ) {
        val photoPickerIntent = Intent(Intent.ACTION_PICK).apply { type = mimeType }
        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply { type = mimeType }
        val chooserIntent = Intent.createChooser(photoPickerIntent, getString(chooserText)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(contentIntent))
        }
        try {
            launcher.launch(chooserIntent)
        } catch (_: ActivityNotFoundException) {
            viewModel.setScannerActive(true)
            Toast.makeText(applicationContext, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        val parseResultList =
            Utils.parseSetBarcodeActivityResult(requestCode, resultCode, intent, this)

        if (parseResultList.isEmpty()) {
            viewModel.setScannerActive(true)
            return
        }

        Utils.makeUserChooseParseResultFromList(
            this,
            parseResultList,
            object : ParseResultListDisambiguatorCallback {
                override fun onUserChoseParseResult(parseResult: ParseResult) {
                    returnResult(parseResult)
                }

                override fun onUserDismissedSelector() {
                    viewModel.setScannerActive(true)
                }
            })
    }

    private fun showCameraPermissionMissingText() {
        setCameraErrorState(visible = true, true, getString(R.string.noCameraPermissionDirectToSystemSetting))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        onMockedRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onMockedRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED

        if (requestCode == CaptureManager.getCameraPermissionReqCode()) {
            if (granted) {
                setCameraErrorState(visible = false, setOnClick = false, message = null)
            } else {
                showCameraPermissionMissingText()
            }
        } else if (requestCode == PERMISSION_SCAN_ADD_FROM_IMAGE || requestCode == PERMISSION_SCAN_ADD_FROM_PDF || requestCode == PERMISSION_SCAN_ADD_FROM_PKPASS) {
            if (granted) {
                when (requestCode) {
                    PERMISSION_SCAN_ADD_FROM_IMAGE -> {
                        addFromImageOrFileAfterPermission(
                            "image/*",
                            photoPickerLauncher,
                            R.string.addFromImage,
                            R.string.failedLaunchingPhotoPicker
                        )
                    }
                    PERMISSION_SCAN_ADD_FROM_PDF -> {
                        addFromImageOrFileAfterPermission(
                            "application/pdf",
                            pdfPickerLauncher,
                            R.string.addFromPdfFile,
                            R.string.failedLaunchingFileManager
                        )
                    }
                    else -> {
                        addFromImageOrFileAfterPermission(
                            "application/*",
                            pkpassPickerLauncher,
                            R.string.addFromPkpass,
                            R.string.failedLaunchingFileManager
                        )
                    }
                }
            } else {
                viewModel.setScannerActive(true)
                Toast.makeText(this, R.string.storageReadPermissionRequired, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    companion object {
        const val PERMISSION_SCAN_ADD_FROM_IMAGE = 100
        const val PERMISSION_SCAN_ADD_FROM_PDF = 101
        const val PERMISSION_SCAN_ADD_FROM_PKPASS = 102

        const val MEDIUM_SCALE_FACTOR_DIP = 460
    }
}

class AddCardOptionAdapter(
    context: Context,
    private val options: List<AddCardOption>
) : ArrayAdapter<AddCardOption>(context, 0, options) {

    @SuppressLint("ViewHolder")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = AlertdialogRowWithIconBinding.inflate(LayoutInflater.from(context))

        val option = options[position]

        view.imageView.setImageResource(option.iconResId)
        view.textView.text = option.text

        return view.root
    }
}