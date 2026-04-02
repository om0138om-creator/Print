package com.networkprinter.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Manager class for discovering printers on the local network
 * Supports NSD (Bonjour/mDNS) and direct IP scanning
 */
class PrinterDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "PrinterDiscovery"
        
        // Service types for printer discovery
        private const val SERVICE_TYPE_IPP = "_ipp._tcp."
        private const val SERVICE_TYPE_IPPS = "_ipps._tcp."
        private const val SERVICE_TYPE_PDL = "_pdl-datastream._tcp."
        private const val SERVICE_TYPE_PRINTER = "_printer._tcp."
        
        // Common printer ports
        private const val PORT_IPP = 631
        private const val PORT_RAW = 9100
        private const val PORT_LPD = 515
        
        // Timeout values
        private const val RESOLVE_TIMEOUT = 5000L
        private const val SCAN_TIMEOUT = 3000
        private const val DISCOVERY_DURATION = 10000L
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveredPrinters = mutableSetOf<PrinterInfo>()
    
    private val _printersFlow = MutableStateFlow<List<PrinterInfo>>(emptyList())
    val printersFlow: StateFlow<List<PrinterInfo>> = _printersFlow.asStateFlow()
    
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    /**
     * Start discovering printers using NSD and network scanning
     */
    fun startDiscovery(): Flow<DiscoveryEvent> = callbackFlow {
        _isDiscovering.value = true
        discoveredPrinters.clear()
        _printersFlow.value = emptyList()
        
        trySend(DiscoveryEvent.Started)
        
        // Start NSD discovery for multiple service types
        val serviceTypes = listOf(
            SERVICE_TYPE_IPP,
            SERVICE_TYPE_IPPS,
            SERVICE_TYPE_PDL,
            SERVICE_TYPE_PRINTER
        )
        
        serviceTypes.forEach { serviceType ->
            startNsdDiscovery(serviceType) { printer ->
                if (discoveredPrinters.add(printer)) {
                    _printersFlow.value = discoveredPrinters.toList()
                    trySend(DiscoveryEvent.PrinterFound(printer))
                }
            }
        }
        
        // Also perform direct IP scan
        launch {
            scanLocalNetwork { printer ->
                if (discoveredPrinters.add(printer)) {
                    _printersFlow.value = discoveredPrinters.toList()
                    trySend(DiscoveryEvent.PrinterFound(printer))
                }
            }
        }
        
        // Auto-stop after duration
        delay(DISCOVERY_DURATION)
        stopDiscovery()
        trySend(DiscoveryEvent.Completed(discoveredPrinters.toList()))
        
        awaitClose {
            stopDiscovery()
        }
    }

    /**
     * Start NSD discovery for a specific service type
     */
    private fun startNsdDiscovery(
        serviceType: String,
        onPrinterFound: (PrinterInfo) -> Unit
    ) {
        val listener = object : NsdManager.DiscoveryListener {
            
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD Discovery started for: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                resolveService(serviceInfo, onPrinterFound)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                val lostPrinter = discoveredPrinters.find { 
                    it.name == serviceInfo.serviceName 
                }
                lostPrinter?.let {
                    discoveredPrinters.remove(it)
                    _printersFlow.value = discoveredPrinters.toList()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $serviceType, Error: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $serviceType, Error: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            discoveryListener = listener
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery", e)
        }
    }

    /**
     * Resolve a discovered NSD service to get its IP address
     */
    private fun resolveService(
        serviceInfo: NsdServiceInfo,
        onPrinterFound: (PrinterInfo) -> Unit
    ) {
        val resolveListener = object : NsdManager.ResolveListener {
            
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: ${serviceInfo.serviceName}, Error: $errorCode")
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${resolvedInfo.serviceName}")
                Log.d(TAG, "Host: ${resolvedInfo.host}, Port: ${resolvedInfo.port}")
                
                val printer = PrinterInfo(
                    id = "${resolvedInfo.host?.hostAddress}:${resolvedInfo.port}",
                    name = resolvedInfo.serviceName ?: "Unknown Printer",
                    host = resolvedInfo.host?.hostAddress ?: "",
                    port = resolvedInfo.port,
                    protocol = determineProtocol(resolvedInfo.serviceType),
                    status = PrinterStatus.ONLINE,
                    serviceType = resolvedInfo.serviceType
                )
                
                if (printer.host.isNotEmpty()) {
                    onPrinterFound(printer)
                }
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve service", e)
        }
    }

    /**
     * Scan local network for printers by checking common ports
     */
    private suspend fun scanLocalNetwork(
        onPrinterFound: (PrinterInfo) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val localIp = getLocalIpAddress()
            if (localIp == null) {
                Log.e(TAG, "Could not determine local IP address")
                return@withContext
            }

            val networkPrefix = localIp.substringBeforeLast(".")
            Log.d(TAG, "Scanning network: $networkPrefix.x")

            // Scan common IP range (1-254)
            val jobs = (1..254).map { host ->
                async {
                    val targetIp = "$networkPrefix.$host"
                    checkPrinterPorts(targetIp)
                }
            }

            jobs.awaitAll().filterNotNull().forEach { printer ->
                onPrinterFound(printer)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Network scan failed", e)
        }
    }

    /**
     * Check if a host has open printer ports
     */
    private fun checkPrinterPorts(host: String): PrinterInfo? {
        val ports = listOf(PORT_IPP, PORT_RAW, PORT_LPD)
        
        for (port in ports) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), SCAN_TIMEOUT)
                    if (socket.isConnected) {
                        Log.d(TAG, "Found printer at $host:$port")
                        return PrinterInfo(
                            id = "$host:$port",
                            name = "Printer @ $host",
                            host = host,
                            port = port,
                            protocol = when (port) {
                                PORT_IPP -> PrintProtocol.IPP
                                PORT_RAW -> PrintProtocol.RAW
                                PORT_LPD -> PrintProtocol.LPD
                                else -> PrintProtocol.SOCKET
                            },
                            status = PrinterStatus.ONLINE
                        )
                    }
                }
            } catch (e: Exception) {
                // Port not open, continue to next
            }
        }
        return null
    }

    /**
     * Get the device's local IP address
     */
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return null
    }

    /**
     * Determine the print protocol from service type
     */
    private fun determineProtocol(serviceType: String): PrintProtocol {
        return when {
            serviceType.contains("ipp") -> PrintProtocol.IPP
            serviceType.contains("pdl") -> PrintProtocol.RAW
            serviceType.contains("printer") -> PrintProtocol.LPD
            else -> PrintProtocol.SOCKET
        }
    }

    /**
     * Test connection to a specific printer
     */
    suspend fun testConnection(printer: PrinterInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(printer.host, printer.port), SCAN_TIMEOUT)
                socket.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed for ${printer.address}", e)
            false
        }
    }

    /**
     * Stop discovery process
     */
    fun stopDiscovery() {
        _isDiscovering.value = false
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop discovery", e)
            }
        }
        discoveryListener = null
    }

    /**
     * Manually add a printer by IP address
     */
    suspend fun addPrinterManually(
        host: String,
        port: Int = PORT_IPP,
        name: String = "Manual Printer"
    ): PrinterInfo? = withContext(Dispatchers.IO) {
        val printer = PrinterInfo(
            id = "$host:$port",
            name = name,
            host = host,
            port = port,
            protocol = when (port) {
                PORT_IPP -> PrintProtocol.IPP
                PORT_RAW -> PrintProtocol.RAW
                else -> PrintProtocol.SOCKET
            },
            status = PrinterStatus.UNKNOWN
        )
        
        if (testConnection(printer)) {
            val onlinePrinter = printer.copy(status = PrinterStatus.ONLINE)
            discoveredPrinters.add(onlinePrinter)
            _printersFlow.value = discoveredPrinters.toList()
            onlinePrinter
        } else {
            null
        }
    }
}

/**
 * Discovery event types
 */
sealed class DiscoveryEvent {
    object Started : DiscoveryEvent()
    data class PrinterFound(val printer: PrinterInfo) : DiscoveryEvent()
    data class PrinterLost(val printer: PrinterInfo) : DiscoveryEvent()
    data class Completed(val printers: List<PrinterInfo>) : DiscoveryEvent()
    data class Error(val message: String) : DiscoveryEvent()
}
