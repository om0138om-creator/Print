package com.networkprinter.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.networkprinter.app.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main Activity - Entry point for the Network Printer app
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var printerDiscoveryManager: PrinterDiscoveryManager
    private lateinit var printerService: NetworkPrinterService
    private lateinit var printerAdapter: PrinterAdapter

    private var selectedImageUri: Uri? = null
    private var selectedPrinter: PrinterInfo? = null

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startPrinterDiscovery()
        } else {
            showPermissionRationale()
        }
    }

    // Image picker launcher
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeComponents()
        setupUI()
        setupClickListeners()
        observePrinters()
        
        // Check permissions and start discovery
        checkPermissionsAndDiscover()
    }

    /**
     * Initialize components
     */
    private fun initializeComponents() {
        printerDiscoveryManager = PrinterDiscoveryManager(this)
        printerService = NetworkPrinterService()
        printerAdapter = PrinterAdapter { printer ->
            onPrinterSelected(printer)
        }
    }

    /**
     * Setup UI components
     */
    private fun setupUI() {
        // Setup toolbar
        setSupportActionBar(binding.toolbar)

        // Setup RecyclerView
        binding.recyclerPrinters.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = printerAdapter
            setHasFixedSize(false)
        }

        // Initial state
        showEmptyState()
        updatePrintButtonState()
    }

    /**
     * Setup click listeners
     */
    private fun setupClickListeners() {
        // Select image button
        binding.btnSelectImage.setOnClickListener {
            openImagePicker()
        }

        // Image preview click
        binding.cardSelectImage.setOnClickListener {
            openImagePicker()
        }

        // Scan printers button
        binding.btnScanPrinters.setOnClickListener {
            checkPermissionsAndDiscover()
        }

        // Print button
        binding.btnPrint.setOnClickListener {
            performPrint()
        }
    }

    /**
     * Observe printers flow
     */
    private fun observePrinters() {
        lifecycleScope.launch {
            printerDiscoveryManager.printersFlow.collectLatest { printers ->
                printerAdapter.submitList(printers)
                
                if (printers.isEmpty()) {
                    if (!printerDiscoveryManager.isDiscovering.value) {
                        showEmptyState()
                    }
                } else {
                    showPrintersList()
                }
            }
        }

        lifecycleScope.launch {
            printerDiscoveryManager.isDiscovering.collectLatest { isDiscovering ->
                if (isDiscovering) {
                    showLoadingState()
                }
            }
        }
    }

    /**
     * Check permissions and start printer discovery
     */
    private fun checkPermissionsAndDiscover() {
        val permissions = mutableListOf<String>()

        // Location permission for NSD on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        // Storage permissions for images
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isEmpty()) {
            startPrinterDiscovery()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    /**
     * Start printer discovery
     */
    private fun startPrinterDiscovery() {
        lifecycleScope.launch {
            printerDiscoveryManager.startDiscovery().collect { event ->
                when (event) {
                    is DiscoveryEvent.Started -> {
                        Log.d(TAG, "Discovery started")
                    }
                    is DiscoveryEvent.PrinterFound -> {
                        Log.d(TAG, "Printer found: ${event.printer.name}")
                        showSnackbar("تم العثور على: ${event.printer.name}")
                    }
                    is DiscoveryEvent.Completed -> {
                        val count = event.printers.size
                        if (count > 0) {
                            showSnackbar(getString(R.string.msg_printer_found, count))
                        } else {
                            showSnackbar(getString(R.string.msg_no_printers))
                        }
                    }
                    is DiscoveryEvent.Error -> {
                        showSnackbar("خطأ: ${event.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Open image picker
     */
    private fun openImagePicker() {
        imagePickerLauncher.launch("image/*")
    }

    /**
     * Handle selected image
     */
    private fun handleImageSelected(uri: Uri) {
        selectedImageUri = uri
        
        // Show image preview
        binding.placeholderContainer.visibility = View.GONE
        binding.imagePreview.visibility = View.VISIBLE
        binding.imagePreview.load(uri) {
            crossfade(true)
            transformations(RoundedCornersTransformation(12f))
        }

        updatePrintButtonState()
    }

    /**
     * Handle printer selection
     */
    private fun onPrinterSelected(printer: PrinterInfo) {
        selectedPrinter = printer
        Log.d(TAG, "Selected printer: ${printer.name}")
        updatePrintButtonState()
    }

    /**
     * Perform print operation
     */
    private fun performPrint() {
        val imageUri = selectedImageUri
        val printer = selectedPrinter

        if (imageUri == null) {
            showSnackbar(getString(R.string.msg_select_image_first))
            return
        }

        if (printer == null) {
            showSnackbar(getString(R.string.msg_select_printer_first))
            return
        }

        // Show print options dialog
        showPrintOptionsDialog(imageUri, printer)
    }

    /**
     * Show print options dialog
     */
    private fun showPrintOptionsDialog(imageUri: Uri, printer: PrinterInfo) {
        val printMethods = arrayOf(
            "طباعة مباشرة (IPP/RAW)",
            "استخدام نظام الطباعة الأندرويد"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("خيارات الطباعة")
            .setItems(printMethods) { _, which ->
                when (which) {
                    0 -> printDirectly(imageUri, printer)
                    1 -> printWithAndroidFramework(imageUri)
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * Print directly to network printer
     */
    private fun printDirectly(imageUri: Uri, printer: PrinterInfo) {
        showPrintingOverlay(true)

        lifecycleScope.launch {
            val result = printerService.printImage(
                printer = printer,
                imageUri = imageUri,
                context = this@MainActivity,
                options = PrintOptions(
                    copies = 1,
                    quality = 90,
                    paperSize = PaperSize.A4
                )
            )

            showPrintingOverlay(false)

            when (result) {
                is PrintResult.Success -> {
                    showSuccessDialog(result.message)
                }
                is PrintResult.Error -> {
                    showErrorDialog(result.message)
                }
                PrintResult.Cancelled -> {
                    showSnackbar("تم إلغاء الطباعة")
                }
            }
        }
    }

    /**
     * Print using Android Print Framework
     */
    private fun printWithAndroidFramework(imageUri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(imageUri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                printerService.printWithAndroidFramework(this, bitmap, "Photo Print")
            } else {
                showSnackbar("فشل في تحميل الصورة")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image", e)
            showSnackbar("خطأ: ${e.message}")
        }
    }

    /**
     * Update print button state
     */
    private fun updatePrintButtonState() {
        binding.btnPrint.isEnabled = selectedImageUri != null && selectedPrinter != null
    }

    /**
     * Show loading state
     */
    private fun showLoadingState() {
        binding.loadingContainer.visibility = View.VISIBLE
        binding.emptyContainer.visibility = View.GONE
        binding.recyclerPrinters.visibility = View.GONE
    }

    /**
     * Show empty state
     */
    private fun showEmptyState() {
        binding.loadingContainer.visibility = View.GONE
        binding.emptyContainer.visibility = View.VISIBLE
        binding.recyclerPrinters.visibility = View.GONE
    }

    /**
     * Show printers list
     */
    private fun showPrintersList() {
        binding.loadingContainer.visibility = View.GONE
        binding.emptyContainer.visibility = View.GONE
        binding.recyclerPrinters.visibility = View.VISIBLE
    }

    /**
     * Show printing overlay
     */
    private fun showPrintingOverlay(show: Boolean) {
        binding.printingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Show success dialog
     */
    private fun showSuccessDialog(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("✅ نجاح")
            .setMessage(message)
            .setPositiveButton("حسناً", null)
            .show()
    }

    /**
     * Show error dialog
     */
    private fun showErrorDialog(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("❌ خطأ")
            .setMessage(message)
            .setPositiveButton(R.string.btn_retry) { _, _ ->
                performPrint()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * Show permission rationale
     */
    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.msg_permission_required)
            .setMessage("التطبيق يحتاج إلى صلاحية الموقع للبحث عن الطابعات على الشبكة، وصلاحية الصور لاختيار الصور للطباعة.")
            .setPositiveButton(R.string.btn_retry) { _, _ ->
                checkPermissionsAndDiscover()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * Show snackbar message
     */
    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        printerDiscoveryManager.stopDiscovery()
    }
}
