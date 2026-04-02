package com.networkprinter.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException

/**
 * Print document adapter for printing images via Android Print Framework
 */
class ImagePrintDocumentAdapter(
    private val context: Context,
    private val bitmap: Bitmap,
    private val jobName: String
) : PrintDocumentAdapter() {

    companion object {
        private const val TAG = "ImagePrintAdapter"
    }

    private var pageWidth: Int = 0
    private var pageHeight: Int = 0

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }

        // Get page dimensions
        val mediaSize = newAttributes.mediaSize
        if (mediaSize != null) {
            pageWidth = (mediaSize.widthMils * 72 / 1000f).toInt()
            pageHeight = (mediaSize.heightMils * 72 / 1000f).toInt()
        }

        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
            .setPageCount(1)
            .build()

        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onWriteCancelled()
            return
        }

        try {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)

            drawBitmapOnPage(page.canvas)

            pdfDocument.finishPage(page)

            FileOutputStream(destination.fileDescriptor).use { outputStream ->
                pdfDocument.writeTo(outputStream)
            }

            pdfDocument.close()
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))

        } catch (e: IOException) {
            Log.e(TAG, "Error writing print document", e)
            callback.onWriteFailed(e.message)
        }
    }

    /**
     * Draw the bitmap centered and scaled on the page canvas
     */
    private fun drawBitmapOnPage(canvas: Canvas) {
        // Calculate scaling to fit the page while maintaining aspect ratio
        val scaleX = pageWidth.toFloat() / bitmap.width
        val scaleY = pageHeight.toFloat() / bitmap.height
        val scale = minOf(scaleX, scaleY)

        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale

        // Center the image on the page
        val left = (pageWidth - scaledWidth) / 2
        val top = (pageHeight - scaledHeight) / 2

        val destRect = RectF(left, top, left + scaledWidth, top + scaledHeight)

        val matrix = Matrix()
        matrix.setRectToRect(
            RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
            destRect,
            Matrix.ScaleToFit.CENTER
        )

        canvas.drawBitmap(bitmap, matrix, null)
    }

    override fun onFinish() {
        super.onFinish()
        Log.d(TAG, "Print job finished")
    }
}
