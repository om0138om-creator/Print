package com.networkprinter.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * Service class for sending print jobs to network printers
 * Supports IPP, RAW, and Socket protocols
 */
class NetworkPrinterService {

    companion object {
        private const val TAG = "NetworkPrinterService"
        private const val SOCKET_TIMEOUT = 30000 // 30 seconds
        private const val BUFFER_SIZE = 8192
    }

    /**
     * Print an image to the specified printer
     */
    suspend fun printImage(
        printer: PrinterInfo,
        imageUri: Uri,
        context: android.content.Context,
        options: PrintOptions = PrintOptions()
    ): PrintResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting print job to ${printer.address}")
            
            // Load and prepare image
            val bitmap = loadImage(context, imageUri, options)
                ?: return@withContext PrintResult.Error("Failed to load image")
            
            // Convert to printable format based on protocol
            when (printer.protocol) {
                PrintProtocol.IPP -> printViaIPP(printer, bitmap, options)
                PrintProtocol.RAW -> printViaRaw(printer, bitmap, options)
                PrintProtocol.SOCKET -> printViaSocket(printer, bitmap, options)
                PrintProtocol.LPD -> printViaLPD(printer, bitmap, options)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Print failed", e)
            PrintResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Load and prepare image for printing
     */
    private fun loadImage(
        context: android.content.Context,
        uri: Uri,
        options: PrintOptions
    ): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val original = BitmapFactory.decodeStream(inputStream)
                
                // Scale to paper size if needed
                val scaledBitmap = scaleBitmapToPaperSize(original, options.paperSize)
                
                // Apply orientation
                if (options.orientation == PrintOrientation.LANDSCAPE) {
                    rotateBitmap(scaledBitmap, 90f)
                } else {
                    scaledBitmap
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load image", e)
            null
        }
    }

    /**
     * Scale bitmap to fit paper size
     */
    private fun scaleBitmapToPaperSize(bitmap: Bitmap, paperSize: PaperSize): Bitmap {
        val paperWidth = paperSize.widthPx
        val paperHeight = paperSize.heightPx
        
        val scaleX = paperWidth.toFloat() / bitmap.width
        val scaleY = paperHeight.toFloat() / bitmap.height
        val scale = minOf(scaleX, scaleY)
        
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Rotate bitmap
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Print via IPP (Internet Printing Protocol)
     */
    private suspend fun printViaIPP(
        printer: PrinterInfo,
        bitmap: Bitmap,
        options: PrintOptions
    ): PrintResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(printer.ippUrl)
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = SOCKET_TIMEOUT
                readTimeout = SOCKET_TIMEOUT
                setRequestProperty("Content-Type", "application/ipp")
            }

            // Build IPP request
            val ippRequest = buildIPPRequest(bitmap, options)
            
            connection.outputStream.use { output ->
                output.write(ippRequest)
                output.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "IPP Response code: $responseCode")

            if (responseCode in 200..299) {
                PrintResult.Success("Print job sent successfully")
            } else {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                PrintResult.Error("IPP Error: $responseCode - $error")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "IPP print failed", e)
            PrintResult.Error("IPP Error: ${e.message}")
        }
    }

    /**
     * Build IPP request data
     */
    private fun buildIPPRequest(bitmap: Bitmap, options: PrintOptions): ByteArray {
        val baos = ByteArrayOutputStream()
        val dataOut = DataOutputStream(baos)

        // IPP version 1.1
        dataOut.writeByte(1)  // Major version
        dataOut.writeByte(1)  // Minor version
        
        // Operation: Print-Job (0x0002)
        dataOut.writeShort(0x0002)
        
        // Request ID
        dataOut.writeInt(1)
        
        // Operation attributes tag
        dataOut.writeByte(0x01)
        
        // charset
        writeIPPAttribute(dataOut, "attributes-charset", "utf-8")
        
        // natural-language
        writeIPPAttribute(dataOut, "attributes-natural-language", "en-us")
        
        // printer-uri
        writeIPPAttribute(dataOut, "printer-uri", "ipp://localhost/ipp/print")
        
        // document-format
        writeIPPAttribute(dataOut, "document-format", "image/jpeg")
        
        // Job attributes tag
        dataOut.writeByte(0x02)
        
        // copies
        writeIPPIntAttribute(dataOut, "copies", options.copies)
        
        // End of attributes
        dataOut.writeByte(0x03)
        
        // Document data (JPEG)
        val imageData = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality, imageData)
        dataOut.write(imageData.toByteArray())
        
        return baos.toByteArray()
    }

    /**
     * Write IPP string attribute
     */
    private fun writeIPPAttribute(dataOut: DataOutputStream, name: String, value: String) {
        dataOut.writeByte(0x47) // keyword tag
        dataOut.writeShort(name.length)
        dataOut.writeBytes(name)
        dataOut.writeShort(value.length)
        dataOut.writeBytes(value)
    }

    /**
     * Write IPP integer attribute
     */
    private fun writeIPPIntAttribute(dataOut: DataOutputStream, name: String, value: Int) {
        dataOut.writeByte(0x21) // integer tag
        dataOut.writeShort(name.length)
        dataOut.writeBytes(name)
        dataOut.writeShort(4) // integer size
        dataOut.writeInt(value)
    }

    /**
     * Print via RAW/JetDirect protocol (port 9100)
     */
    private suspend fun printViaRaw(
        printer: PrinterInfo,
        bitmap: Bitmap,
        options: PrintOptions
    ): PrintResult = withContext(Dispatchers.IO) {
        try {
            Socket(printer.host, printer.port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT
                
                val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)
                
                // Send PCL commands for image
                val pclData = convertToPCL(bitmap, options)
                output.write(pclData)
                output.flush()
                
                PrintResult.Success("Print job sent via RAW protocol")
            }
        } catch (e: Exception) {
            Log.e(TAG, "RAW print failed", e)
            PrintResult.Error("RAW Error: ${e.message}")
        }
    }

    /**
     * Convert bitmap to PCL format
     */
    private fun convertToPCL(bitmap: Bitmap, options: PrintOptions): ByteArray {
        val baos = ByteArrayOutputStream()
        
        // PCL Reset
        baos.write(27) // ESC
        baos.write('E'.code)
        
        // Set resolution (300 DPI)
        baos.write("${27.toChar()}*t300R".toByteArray())
        
        // Start raster graphics
        baos.write("${27.toChar()}*r1A".toByteArray())
        
        // Convert bitmap to raster data
        val width = bitmap.width
        val height = bitmap.height
        
        for (y in 0 until height) {
            val rowData = ByteArray(width / 8 + 1)
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val gray = (0.299 * ((pixel shr 16) and 0xFF) +
                           0.587 * ((pixel shr 8) and 0xFF) +
                           0.114 * (pixel and 0xFF)).toInt()
                
                if (gray < 128) {
                    rowData[x / 8] = (rowData[x / 8].toInt() or (128 shr (x % 8))).toByte()
                }
            }
            
            // Send row
            baos.write("${27.toChar()}*b${rowData.size}W".toByteArray())
            baos.write(rowData)
        }
        
        // End raster graphics
        baos.write("${27.toChar()}*rB".toByteArray())
        
        // Form feed
        baos.write(12)
        
        // PCL Reset
        baos.write(27)
        baos.write('E'.code)
        
        return baos.toByteArray()
    }

    /**
     * Print via Socket (direct connection)
     */
    private suspend fun printViaSocket(
        printer: PrinterInfo,
        bitmap: Bitmap,
        options: PrintOptions
    ): PrintResult = withContext(Dispatchers.IO) {
        try {
            Socket(printer.host, printer.port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT
                
                val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)
                
                // Send as JPEG
                bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality, output)
                output.flush()
                
                PrintResult.Success("Print job sent via Socket")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Socket print failed", e)
            PrintResult.Error("Socket Error: ${e.message}")
        }
    }

    /**
     * Print via LPD protocol (port 515)
     */
    private suspend fun printViaLPD(
        printer: PrinterInfo,
        bitmap: Bitmap,
        options: PrintOptions
    ): PrintResult = withContext(Dispatchers.IO) {
        try {
            Socket(printer.host, printer.port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT
                
                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())
                
                // LPD print command (0x02 = receive printer job)
                output.writeByte(0x02)
                output.writeBytes("lp\n")
                output.flush()
                
                // Wait for acknowledgment
                val ack = input.readByte()
                if (ack != 0.toByte()) {
                    return@withContext PrintResult.Error("LPD printer not ready")
                }
                
                // Send data file
                val imageData = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality, imageData)
                val data = imageData.toByteArray()
                
                // Control file
                output.writeByte(0x02)
                output.writeBytes("${data.size} cfA001${printer.host}\n")
                output.flush()
                
                if (input.readByte() != 0.toByte()) {
                    return@withContext PrintResult.Error("LPD error sending control file")
                }
                
                // Data file
                output.writeByte(0x03)
                output.writeBytes("${data.size} dfA001${printer.host}\n")
                output.flush()
                
                if (input.readByte() != 0.toByte()) {
                    return@withContext PrintResult.Error("LPD error preparing data file")
                }
                
                output.write(data)
                output.writeByte(0)
                output.flush()
                
                if (input.readByte() != 0.toByte()) {
                    return@withContext PrintResult.Error("LPD error sending data")
                }
                
                PrintResult.Success("Print job sent via LPD")
            }
        } catch (e: Exception) {
            Log.e(TAG, "LPD print failed", e)
            PrintResult.Error("LPD Error: ${e.message}")
        }
    }

    /**
     * Print using Android Print Framework
     */
    fun printWithAndroidFramework(
        context: android.content.Context,
        bitmap: Bitmap,
        jobName: String = "Photo Print"
    ) {
        val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE) 
            as android.print.PrintManager
        
        val printAdapter = ImagePrintDocumentAdapter(context, bitmap, jobName)
        printManager.print(jobName, printAdapter, null)
    }
}

/**
 * Print result sealed class
 */
sealed class PrintResult {
    data class Success(val message: String) : PrintResult()
    data class Error(val message: String) : PrintResult()
    object Cancelled : PrintResult()
}

/**
 * Print options data class
 */
data class PrintOptions(
    val copies: Int = 1,
    val quality: Int = 90,
    val paperSize: PaperSize = PaperSize.A4,
    val orientation: PrintOrientation = PrintOrientation.PORTRAIT,
    val colorMode: ColorMode = ColorMode.COLOR,
    val fitToPage: Boolean = true
)

/**
 * Paper sizes with pixel dimensions (at 300 DPI)
 */
enum class PaperSize(val widthPx: Int, val heightPx: Int) {
    A4(2480, 3508),
    A5(1748, 2480),
    LETTER(2550, 3300),
    PHOTO_4X6(1200, 1800),
    PHOTO_5X7(1500, 2100)
}

/**
 * Print orientation
 */
enum class PrintOrientation {
    PORTRAIT,
    LANDSCAPE
}

/**
 * Color mode
 */
enum class ColorMode {
    COLOR,
    MONOCHROME
}
