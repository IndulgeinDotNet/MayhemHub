package com.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var usbBridge: UsbSerialBridge? = null
    private var webViewInstance: WebView? = null

    // State variables for Compose UI update
    private val usbStatus = mutableStateOf("No USB Device")
    private val connectedDeviceName = mutableStateOf<String?>(null)
    private val isDeviceConnected = mutableStateOf(false)
    private val hasUsbPermission = mutableStateOf(false)
    private val isDeviceDetected = mutableStateOf(false)

    // High-performance data buffering & coroutine scope
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var flushJob: Job? = null
    private var bufferedBytes = ByteArray(0)
    private val bufferLock = Any()

    companion object {
        private const val TAG = "MayhemHubMainActivity"
        private const val APP_URL = "https://hackrf.app/"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize USB Bridge with high-performance buffering and Base64 dispatching
        usbBridge = UsbSerialBridge(
            context = this,
            onDataReceived = { bytes ->
                synchronized(bufferLock) {
                    bufferedBytes = bufferedBytes + bytes
                }
                
                flushJob?.cancel()
                
                var shouldDispatchImmediately = false
                synchronized(bufferLock) {
                    if (bufferedBytes.size >= 2048) {
                        shouldDispatchImmediately = true
                    }
                }
                
                if (shouldDispatchImmediately) {
                    val bytesToDispatch = synchronized(bufferLock) {
                        val temp = bufferedBytes
                        bufferedBytes = ByteArray(0)
                        temp
                    }
                    if (bytesToDispatch.isNotEmpty()) {
                        val base64Data = android.util.Base64.encodeToString(bytesToDispatch, android.util.Base64.NO_WRAP)
                        mainScope.launch {
                            webViewInstance?.evaluateJavascript(
                                "if (window.onAndroidSerialDataBase64) window.onAndroidSerialDataBase64('$base64Data');",
                                null
                            )
                        }
                    }
                } else {
                    flushJob = mainScope.launch {
                        delay(12) // buffer small chunks for 12ms to merge them and avoid WebView flooding
                        val bytesToDispatch = synchronized(bufferLock) {
                            val temp = bufferedBytes
                            bufferedBytes = ByteArray(0)
                            temp
                        }
                        if (bytesToDispatch.isNotEmpty()) {
                            val base64Data = android.util.Base64.encodeToString(bytesToDispatch, android.util.Base64.NO_WRAP)
                            webViewInstance?.evaluateJavascript(
                                "if (window.onAndroidSerialDataBase64) window.onAndroidSerialDataBase64('$base64Data');",
                                null
                            )
                        }
                    }
                }
            },
            onStatusChanged = { status ->
                runOnUiThread {
                    usbStatus.value = status
                    updateUsbState()
                }
            }
        )

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        usbStatus = usbStatus.value,
                        deviceName = connectedDeviceName.value,
                        isDetected = isDeviceDetected.value,
                        isConnected = isDeviceConnected.value,
                        hasPermission = hasUsbPermission.value,
                        onConnectClick = { handleConnectSequence() },
                        onRefreshClick = { checkConnectedDevices() }
                    )
                }
            }
        }

        // Initial check for devices
        checkConnectedDevices()
    }

    override fun onResume() {
        super.onResume()
        checkConnectedDevices()
    }

    override fun onDestroy() {
        flushJob?.cancel()
        evaluateJavascriptInWebView("if (window.onAndroidSerialDisconnected) window.onAndroidSerialDisconnected();")
        usbBridge?.close()
        super.onDestroy()
    }

    private fun checkConnectedDevices() {
        val hasDevice = usbBridge?.hasDevice() ?: false
        isDeviceDetected.value = hasDevice
        
        if (hasDevice) {
            val device = usbBridge?.findDevice()
            if (device != null) {
                val usbManager = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
                val permitted = usbManager.hasPermission(device)
                hasUsbPermission.value = permitted
                if (permitted) {
                    connectedDeviceName.value = "HackRF / PortaPack [VID:${String.format("0x%04X", device.vendorId)} PID:${String.format("0x%04X", device.productId)}]"
                    if (usbStatus.value == "Disconnected" || usbStatus.value == "No USB Device") {
                        usbStatus.value = "Device Authorized"
                    }
                } else {
                    connectedDeviceName.value = "HackRF / PortaPack (Requires Permission)"
                    usbStatus.value = "Authorization Needed"
                }
            }
        } else {
            connectedDeviceName.value = null
            hasUsbPermission.value = false
            isDeviceConnected.value = false
            usbStatus.value = "No USB Device Detected"
            usbBridge?.close()
        }
    }

    private fun handleConnectSequence() {
        val hasDevice = usbBridge?.hasDevice() ?: false
        if (!hasDevice) {
            Toast.makeText(this, "Please plug in your HackRF/PortaPack device", Toast.LENGTH_SHORT).show()
            checkConnectedDevices()
            return
        }

        usbBridge?.requestPermission { granted ->
            runOnUiThread {
                hasUsbPermission.value = granted
                if (granted) {
                    Toast.makeText(this, "USB Authorization Granted", Toast.LENGTH_SHORT).show()
                    val opened = usbBridge?.open(115200) ?: false
                    isDeviceConnected.value = opened
                    if (opened) {
                        Toast.makeText(this, "Connected to Portapack!", Toast.LENGTH_SHORT).show()
                        evaluateJavascriptInWebView("if (window._requestPortResolve) window._requestPortResolve(true);")
                        evaluateJavascriptInWebView("if (window.onAndroidSerialConnected) window.onAndroidSerialConnected();")
                    } else {
                        Toast.makeText(this, "Failed to open device", Toast.LENGTH_SHORT).show()
                        evaluateJavascriptInWebView("if (window._requestPortResolve) window._requestPortResolve(false);")
                    }
                } else {
                    Toast.makeText(this, "USB Authorization Denied", Toast.LENGTH_SHORT).show()
                    evaluateJavascriptInWebView("if (window._requestPortResolve) window._requestPortResolve(false);")
                }
                checkConnectedDevices()
            }
        }
    }

    private fun updateUsbState() {
        val activeName = usbBridge?.getConnectedDeviceName()
        if (activeName != null) {
            connectedDeviceName.value = activeName
            isDeviceConnected.value = true
            evaluateJavascriptInWebView("if (window.onAndroidSerialConnected) window.onAndroidSerialConnected();")
        } else {
            isDeviceConnected.value = false
            evaluateJavascriptInWebView("if (window.onAndroidSerialDisconnected) window.onAndroidSerialDisconnected();")
            checkConnectedDevices()
        }
    }

    private fun evaluateJavascriptInWebView(script: String) {
        webViewInstance?.post {
            webViewInstance?.evaluateJavascript(script, null)
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val chars = "0123456789abcdef"
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = chars[v ushr 4]
            hexChars[j * 2 + 1] = chars[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun MainScreen(
        modifier: Modifier = Modifier,
        usbStatus: String,
        deviceName: String?,
        isDetected: Boolean,
        isConnected: Boolean,
        hasPermission: Boolean,
        onConnectClick: () -> Unit,
        onRefreshClick: () -> Unit
    ) {
        val context = LocalContext.current
        var isLoadingWebPage by remember { mutableStateOf(true) }
        var showTroubleshooting by remember { mutableStateOf(false) }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // High-contrast, beautifully styled Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MayhemHub",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = FontFamily.SansSerif
                    )
                    
                    Text(
                        text = if (isConnected) "ACTIVE: $usbStatus" else if (isDetected) "FOUND: $usbStatus" else "USB OFFLINE",
                        color = if (isConnected) MaterialTheme.colorScheme.primary else if (isDetected) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )

                    Text(
                        text = "ported to android by IndulgeInDotNet | android v.01 7/6/2026",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }

                // Connect/Disconnect Button
                if (isDetected) {
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnected) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary,
                            contentColor = if (isConnected) Color.White else MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            text = if (isConnected) "Disconnect" else if (hasPermission) "Connect" else "Authorize",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    IconButton(
                        onClick = onRefreshClick,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Scan USB",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Connection notification banner if disconnected
            if (!isConnected) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isDetected) Icons.Default.Usb else Icons.Default.UsbOff,
                            contentDescription = "USB",
                            tint = if (isConnected) MaterialTheme.colorScheme.primary else if (isDetected) Color(0xFFFFB300) else Color(0xFFD32F2F),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = if (isDetected) "HackRF / Portapack Detected" else "No HackRF / Portapack connected",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (isDetected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isDetected) "Click Connect to open the USB communication tunnel." else "Connect device via USB OTG cable to use live screen stream and controls.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (isConnected) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF2C1E1E), // Dark reddish-brown alert background
                        contentColor = Color(0xFFFFCDD2)    // Light red alert text
                    ),
                    border = BorderStroke(1.dp, Color(0xFFE57373).copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Hardware Alert",
                                tint = Color(0xFFE57373),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Screen Freeze / Squealing Audio?",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (showTroubleshooting) "Hide Guide" else "Show Guide",
                                color = Color(0xFFE57373),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { showTroubleshooting = !showTroubleshooting }
                            )
                        }
                        if (showTroubleshooting) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "If your PortaPack screen goes white/black, emits a high-pitched buzz, or freezes when using controls, your Android phone is browning out due to insufficient USB OTG current.\n\nTo fix this hardware limit:\n1. Use an externally-powered USB OTG hub or Y-cable to feed stable 5V to the PortaPack.\n2. In PortaPack Mayhem Settings -> USB, ensure it's in the correct remote mode.\n3. Keep your phone battery above 50% for maximum OTG output power.",
                                fontSize = 11.sp,
                                color = Color(0xFFEF9A9A),
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            // Main WebView Interface containing the actual MayhemHub App
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewInstance = this
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                cacheMode = WebSettings.LOAD_DEFAULT
                                allowContentAccess = true
                                allowFileAccess = true
                                setSupportMultipleWindows(false)
                                loadWithOverviewMode = true
                                useWideViewPort = true
                            }
                            
                            // Define Javascript Interface
                            addJavascriptInterface(AndroidSerialInterface(), "AndroidSerial")

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    view?.let { injectWebSerialPolyfill(it) }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    view?.let { injectWebSerialPolyfill(it) }
                                    isLoadingWebPage = false
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?
                                ) {
                                    super.onReceivedError(view, errorCode, description, failingUrl)
                                    Log.e(TAG, "WebView error: $description ($errorCode)")
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    super.onProgressChanged(view, newProgress)
                                    if (newProgress > 15) {
                                        view?.let { injectWebSerialPolyfill(it) }
                                    }
                                }

                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    Log.d(TAG, "WebView Console: [${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} (Line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()})")
                                    return true
                                }
                            }

                            loadUrl(APP_URL)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        webViewInstance = view
                    }
                )

                if (isLoadingWebPage) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Loading MayhemHub UI...",
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }

    private fun injectWebSerialPolyfill(webView: WebView) {
        val polyfill = """
            (function() {
                if (window.navigator.serial) {
                    console.log("Web Serial already exists");
                    return;
                }

                class SerialPort {
                    constructor() {
                        this.readable = null;
                        this.writable = null;
                        this._isOpen = false;
                        this._readerController = null;
                    }

                    async getInfo() {
                        try {
                            const infoStr = AndroidSerial.getPortInfo();
                            const info = JSON.parse(infoStr);
                            return {
                                usbVendorId: info.vendorId || 0x1d50,
                                usbProductId: info.productId || 0x6089
                            };
                        } catch (e) {
                            return { usbVendorId: 0x1d50, usbProductId: 0x6089 };
                        }
                    }

                    async open(options) {
                        const baudRate = options.baudRate || 115200;
                        const success = await new Promise((resolve) => {
                            window._openResolve = resolve;
                            AndroidSerial.open(baudRate);
                        });
                        if (!success) {
                            throw new Error("Failed to open serial port via Android USB");
                        }
                        this._isOpen = true;
                        this.readable = this._createReadableStream();
                        this.writable = this._createWritableStream();
                    }

                    async close() {
                        AndroidSerial.close();
                        this._isOpen = false;
                        if (this._readerController) {
                            try {
                                this._readerController.close();
                            } catch(e) {}
                            this._readerController = null;
                        }
                    }

                    _createReadableStream() {
                        const self = this;
                        return new ReadableStream({
                            start(controller) {
                                self._readerController = controller;
                            },
                            cancel() {
                                self._readerController = null;
                            }
                        });
                    }

                    _createWritableStream() {
                        return new WritableStream({
                            write(chunk) {
                                try {
                                    if (typeof AndroidSerial.writeBase64 === 'function') {
                                        let binary = '';
                                        const len = chunk.byteLength;
                                        for (let i = 0; i < len; i++) {
                                            binary += String.fromCharCode(chunk[i]);
                                        }
                                        const base64 = btoa(binary);
                                        AndroidSerial.writeBase64(base64);
                                    } else {
                                        const hex = Array.from(chunk).map(b => b.toString(16).padStart(2, '0')).join('');
                                        AndroidSerial.writeHex(hex);
                                    }
                                } catch (e) {
                                    console.error("Error writing chunk", e);
                                }
                            }
                        });
                    }
                }

                const port = new SerialPort();

                Object.defineProperty(window.navigator, 'serial', {
                    value: {
                        async getPorts() {
                            const hasDevice = AndroidSerial.hasDevice();
                            return hasDevice ? [port] : [];
                        },
                        async requestPort(options) {
                            const success = await new Promise((resolve) => {
                                window._requestPortResolve = resolve;
                                AndroidSerial.requestPort();
                            });
                            if (!success) {
                                throw new Error("USB permission denied");
                            }
                            return port;
                        },
                        addEventListener(event, callback) {
                            console.log("serial addEventListener", event);
                        },
                        removeEventListener(event, callback) {
                            console.log("serial removeEventListener", event);
                        }
                    },
                    writable: true,
                    configurable: true
                });

                window.onAndroidSerialData = function(hexData) {
                    if (port && port._readerController) {
                        try {
                            if (hexData) {
                                const matches = hexData.match(/.{1,2}/g);
                                if (matches) {
                                    const bytes = new Uint8Array(matches.map(byte => parseInt(byte, 16)));
                                    port._readerController.enqueue(bytes);
                                }
                            }
                        } catch (e) {
                            console.error("onAndroidSerialData error", e);
                        }
                    }
                };

                window.onAndroidSerialDataBase64 = function(base64Data) {
                    if (port && port._readerController) {
                        try {
                            const binaryString = atob(base64Data);
                            const len = binaryString.length;
                            const bytes = new Uint8Array(len);
                            for (let i = 0; i < len; i++) {
                                bytes[i] = binaryString.charCodeAt(i);
                            }
                            port._readerController.enqueue(bytes);
                        } catch (e) {
                            console.error("onAndroidSerialDataBase64 error", e);
                        }
                    }
                };

                window.onAndroidSerialConnected = function() {
                    console.log("Native connected event received");
                    const event = new Event('connect');
                    event.port = port;
                    navigator.serial.dispatchEvent(event);
                    if (typeof navigator.serial.onconnect === 'function') {
                        try { navigator.serial.onconnect(event); } catch(e) {}
                    }
                };

                window.onAndroidSerialDisconnected = function() {
                    console.log("Native disconnected event received");
                    const event = new Event('disconnect');
                    event.port = port;
                    navigator.serial.dispatchEvent(event);
                    if (typeof navigator.serial.ondisconnect === 'function') {
                        try { navigator.serial.ondisconnect(event); } catch(e) {}
                    }
                };
                
                console.log("Web Serial polyfill successfully injected!");
            })();
        """.trimIndent()
        webView.evaluateJavascript(polyfill, null)
    }

    inner class AndroidSerialInterface {
        @JavascriptInterface
        fun hasDevice(): Boolean {
            return usbBridge?.hasDevice() ?: false
        }

        @JavascriptInterface
        fun getPortInfo(): String {
            return usbBridge?.getPortInfo() ?: "{}"
        }

        @JavascriptInterface
        fun requestPort() {
            runOnUiThread {
                usbBridge?.requestPermission { granted ->
                    if (granted) {
                        evaluateJavascriptInWebView("if (window._requestPortResolve) window._requestPortResolve(true);")
                        checkConnectedDevices()
                    } else {
                        evaluateJavascriptInWebView("if (window._requestPortResolve) window._requestPortResolve(false);")
                    }
                }
            }
        }

        @JavascriptInterface
        fun open(baudRate: Int) {
            runOnUiThread {
                val success = usbBridge?.open(baudRate) ?: false
                if (success) {
                    evaluateJavascriptInWebView("if (window._openResolve) window._openResolve(true);")
                    updateUsbState()
                } else {
                    evaluateJavascriptInWebView("if (window._openResolve) window._openResolve(false);")
                }
            }
        }

        @JavascriptInterface
        fun writeHex(hex: String) {
            val bytes = hexToBytes(hex)
            usbBridge?.write(bytes)
        }

        @JavascriptInterface
        fun writeBase64(base64: String) {
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                usbBridge?.write(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Error in writeBase64", e)
            }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread {
                usbBridge?.close()
                updateUsbState()
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    androidx.compose.material3.Text(text = "Hello $name!", modifier = modifier)
}
