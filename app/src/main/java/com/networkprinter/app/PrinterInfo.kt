package com.networkprinter.app

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Data class representing a network printer
 */
@Parcelize
data class PrinterInfo(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 631,           // IPP default port
    val protocol: PrintProtocol = PrintProtocol.IPP,
    val status: PrinterStatus = PrinterStatus.UNKNOWN,
    val serviceType: String = ""
) : Parcelable {
    
    val address: String
        get() = "$host:$port"
    
    val ippUrl: String
        get() = "ipp://$host:$port/ipp/print"
}

/**
 * Supported print protocols
 */
enum class PrintProtocol {
    IPP,        // Internet Printing Protocol (port 631)
    RAW,        // Raw/JetDirect (port 9100)
    LPD,        // Line Printer Daemon (port 515)
    SOCKET      // Direct socket connection
}

/**
 * Printer status states
 */
enum class PrinterStatus {
    ONLINE,
    OFFLINE,
    BUSY,
    ERROR,
    UNKNOWN
}
