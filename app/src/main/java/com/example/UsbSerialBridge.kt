package com.example

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UsbSerialBridge(
    private val context: Context,
    private val onDataReceived: (ByteArray) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    companion object {
        private const val TAG = "UsbSerialBridge"
        private const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"
        
        // standard USB CDC ACM control requests
        private const val USB_RT_ACM = 0x21
        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var readJob: Job? = null
    private var permissionIntent: PendingIntent? = null

    private var activeDevice: UsbDevice? = null

    fun getConnectedDeviceName(): String? {
        return activeDevice?.let { "Device: [${it.deviceName}] VID:${String.format("0x%04X", it.vendorId)} PID:${String.format("0x%04X", it.productId)}" }
    }

    fun findDevice(): UsbDevice? {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            // Check for HackRF or common serial chips
            // HackRF One: VID 1d50, PID 6089
            Log.d(TAG, "Found USB device: VID=${device.vendorId}, PID=${device.productId}")
            if (device.vendorId == 0x1D50 && device.productId == 0x6089) {
                return device
            }
        }
        // Fallback: return first device that has a serial interface (at least 2 bulk endpoints)
        for (device in deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                var hasIn = false
                var hasOut = false
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                        if (ep.direction == UsbConstants.USB_DIR_OUT) hasOut = true
                    }
                }
                if (hasIn && hasOut) {
                    return device
                }
            }
        }
        return null
    }

    fun hasDevice(): Boolean {
        return findDevice() != null
    }

    fun getPortInfo(): String {
        val device = findDevice() ?: return "{}"
        return "{\"vendorId\": ${device.vendorId}, \"productId\": ${device.productId}}"
    }

    fun requestPermission(onPermissionResult: (Boolean) -> Unit) {
        try {
            Log.d(TAG, "requestPermission() called")
            val device = findDevice()
            if (device == null) {
                Log.e(TAG, "requestPermission: No device found")
                onPermissionResult(false)
                return
            }

            if (usbManager.hasPermission(device)) {
                Log.d(TAG, "requestPermission: Already has permission")
                activeDevice = device
                onPermissionResult(true)
                return
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            val intent = Intent(ACTION_USB_PERMISSION)
            intent.setPackage(context.packageName) // explicit package for receiver safety
            permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    try {
                        Log.d(TAG, "onReceive() broadcast received action: ${intent.action}")
                        if (ACTION_USB_PERMISSION == intent.action) {
                            synchronized(this) {
                                val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                                }
                                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                                Log.d(TAG, "onReceive() USB permission granted status: $granted for device: $usbDevice")
                                if (granted) {
                                    if (usbDevice != null) {
                                        activeDevice = usbDevice
                                        onPermissionResult(true)
                                    } else {
                                        Log.e(TAG, "onReceive() USB device was null in intent extras")
                                        onPermissionResult(false)
                                    }
                                } else {
                                    onPermissionResult(false)
                                }
                            }
                            try {
                                context.unregisterReceiver(this)
                                Log.d(TAG, "onReceive() Unregistered receiver successfully")
                            } catch (e: Exception) {
                                Log.e(TAG, "onReceive() Error unregistering receiver", e)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "CRITICAL: Exception inside BroadcastReceiver.onReceive", e)
                        onPermissionResult(false)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Log.d(TAG, "Registering receiver as RECEIVER_EXPORTED for Android 13/14+")
                context.registerReceiver(
                    receiver,
                    IntentFilter(ACTION_USB_PERMISSION),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                Log.d(TAG, "Registering receiver standard")
                context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
            }
            
            Log.d(TAG, "Calling usbManager.requestPermission()")
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Throwable) {
            Log.e(TAG, "CRITICAL: Exception during requestPermission setup", e)
            onPermissionResult(false)
        }
    }

    fun open(baudRate: Int): Boolean {
        try {
            Log.d(TAG, "open() called with baudRate: $baudRate")
            val device = activeDevice ?: findDevice() ?: run {
                Log.e(TAG, "open: No active device or found device")
                return false
            }
            activeDevice = device

            if (!usbManager.hasPermission(device)) {
                Log.e(TAG, "No permission for device")
                return false
            }

            close() // Close any existing connection

            Log.d(TAG, "Opening device connection...")
            val conn = usbManager.openDevice(device) ?: run {
                Log.e(TAG, "openDevice returned null!")
                return false
            }
            connection = conn

            // Find bulk interfaces and endpoints
            var found = false
            Log.d(TAG, "Searching endpoints across ${device.interfaceCount} interfaces")
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            epIn = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            epOut = ep
                        }
                    }
                }
                if (epIn != null && epOut != null) {
                    Log.d(TAG, "Found candidate interface ${intf.id} with bulk IN/OUT endpoints")
                    if (conn.claimInterface(intf, true)) {
                        usbInterface = intf
                        endpointIn = epIn
                        endpointOut = epOut
                        found = true
                        Log.d(TAG, "Successfully claimed interface ${intf.id}")
                        break
                    } else {
                        Log.e(TAG, "Failed to claim interface ${intf.id}")
                    }
                }
            }

            if (!found) {
                Log.e(TAG, "Could not find bulk in/out endpoints or claim interface")
                conn.close()
                connection = null
                return false
            }

            // Configure Baudrate/Line Coding for CDC ACM
            val lineCoding = ByteArray(7)
            lineCoding[0] = (baudRate and 0xFF).toByte()
            lineCoding[1] = ((baudRate shr 8) and 0xFF).toByte()
            lineCoding[2] = ((baudRate shr 16) and 0xFF).toByte()
            lineCoding[3] = ((baudRate shr 24) and 0xFF).toByte()
            lineCoding[4] = 0 // 1 stop bit
            lineCoding[5] = 0 // no parity
            lineCoding[6] = 8 // 8 data bits

            val controlInterfaceId = if (device.interfaceCount > 1 && (usbInterface?.id ?: 0) > 0) {
                0
            } else {
                usbInterface?.id ?: 0
            }
            Log.d(TAG, "Using control interface ID: $controlInterfaceId for CDC ACM commands")

            // SET_LINE_CODING
            val ctrl1 = conn.controlTransfer(USB_RT_ACM, SET_LINE_CODING, 0, controlInterfaceId, lineCoding, lineCoding.size, 1000)
            Log.d(TAG, "controlTransfer SET_LINE_CODING result: $ctrl1")

            // SET_CONTROL_LINE_STATE (DTR=1, RTS=1)
            val ctrl2 = conn.controlTransfer(USB_RT_ACM, SET_CONTROL_LINE_STATE, 0x03, controlInterfaceId, null, 0, 1000)
            Log.d(TAG, "controlTransfer SET_CONTROL_LINE_STATE result: $ctrl2")

            onStatusChanged("Connected to ${device.deviceName} at $baudRate baud")

            // Start reading thread
            startReading()
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "CRITICAL: Exception inside open()", e)
            return false
        }
    }

    private fun startReading() {
        val conn = connection ?: return
        val epIn = endpointIn ?: return

        readJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(4096)
            while (connection != null && endpointIn != null) {
                try {
                    val readBytes = conn.bulkTransfer(epIn, buffer, buffer.size, 200)
                    if (readBytes > 0) {
                        val received = buffer.copyOfRange(0, readBytes)
                        onDataReceived(received)
                    } else if (readBytes < 0) {
                        // Timeout or error; short delay to avoid tight loop
                        delay(10)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in USB read loop", e)
                    delay(100)
                }
            }
        }
    }

    fun write(data: ByteArray): Boolean {
        val conn = connection ?: return false
        val epOut = endpointOut ?: return false
        
        try {
            var bytesWritten = 0
            val timeout = 1000
            while (bytesWritten < data.size) {
                val chunk = data.copyOfRange(bytesWritten, data.size)
                val result = conn.bulkTransfer(epOut, chunk, chunk.size, timeout)
                if (result <= 0) {
                    Log.e(TAG, "Bulk transfer out failed: $result")
                    return false
                }
                bytesWritten += result
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception writing data to USB", e)
            return false
        }
    }

    fun close() {
        try {
            Log.d(TAG, "close() called")
            readJob?.cancel()
            readJob = null

            val wasConnected = connection != null
            connection?.let { conn ->
                usbInterface?.let { intf ->
                    try {
                        conn.releaseInterface(intf)
                        Log.d(TAG, "Released interface successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing interface", e)
                    }
                }
                try {
                    conn.close()
                    Log.d(TAG, "Closed USB Connection successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing connection", e)
                }
            }
            connection = null
            usbInterface = null
            endpointIn = null
            endpointOut = null
            if (wasConnected) {
                onStatusChanged("Disconnected")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in close()", e)
        }
    }
}
