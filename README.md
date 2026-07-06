# MayhemHub Android 

MayhemHub is a custom, high-performance Android companion application designed to bridge native Android USB OTG Serial connections directly with the web-based HackRF and PortaPack Mayhem firmware controller interface ([hackrf.app](https://hackrf.app)).

This app wraps the web-based remote control panel inside a custom Jetpack Compose WebView and maps the low-level Android USB Host API directly into standard WebSerial / WebUSB JavaScript APIs. This eliminates the need for special mobile browsers or root privileges to control your PortaPack Mayhem from your phone.

---

## 🚀 Key Features

* **Native USB-to-WebView Serial Bridge**: Custom high-speed USB serial bridge (`UsbSerialBridge`) communicating with the CDC ACM class of the HackRF/PortaPack.
* **Base64 Buffered Pipeline**: High-performance packet buffering and batching to avoid Web JavaScript blocking. Data chunks are aggregated on the native side over a short (12ms) sliding window and transferred to the WebView engine using Base64 chunks to prevent flooding the Web UI thread.
* **WebSerial API Injection**: Auto-injects standard `navigator.serial` polyfills and triggers native connect/disconnect events, providing out-of-the-box compatibility with the web interface.

---

## ⚡ Troubleshooting the "Squealing / White Screen" Brownout

### The Problem
When plugging a PortaPack directly into some Android phones via a simple USB OTG cable, clicking control buttons may cause:
1. **A pure white screen** on the PortaPack.
2. **A loud, high-pitched squealing or buzzing noise** coming from the PortaPack.
3. **Complete freezing** or unresponsiveness of the device.

### The Root Cause
The PortaPack with HackRF consumes significant power (**~500mA**). When active or processing radio tasks, its power consumption spikes. Many Android phones restrict USB OTG current output to **100mA - 300mA** to protect their internal batteries, causing a **power brownout (voltage drop)** on the PortaPack.

### How to Fix It
1. **Use an Externally-Powered Hub / Y-Cable**: Use a USB-C OTG Y-cable or a portable USB-C hub with a **PD Power Delivery port** plugged into a power bank or wall charger. This supplies clean, external 5V power to the HackRF/PortaPack while keeping the data lines connected to your phone.
2. **Increase Phone Battery Charge**: Keep your phone's battery above 50% (Tested on a Samsung Galaxy 23 FE, the device would not work under 23 percent battery and gave a massively degraded preformance if the portapack is low in battery) Many Android kernels aggressively throttle USB OTG power output when the phone is on low battery.
5. **Verify Mayhem Settings**: Make sure you are **not** in HackRF mode but in portapack mode.

---

## 🛠️ Getting Started & Installation

### Option 1: Quick Install via Available APK 
You can obtain a production-ready APK directly from releases:
1.  Visit https://github.com/IndulgeinDotNet/MayhemHub/releases/tag/APK
2.  Download APK
3.  Enjoy.

### Option 2: Build from Source (Gradle)
If you have exported this project as a ZIP:
1. Open the project folder in **Android Studio**.
2. Make sure you have **JDK 17+** configured.
3. Build and install the debug APK using standard Gradle:
   ```bash
   gradle :app:assembleDebug
   ```
4. Find your generated APK at:
   `app/build/outputs/apk/debug/app-debug.apk`

---

## 🔌 Connection Procedure
1. Enable **USB OTG** in your Android system settings (if your phone doesn't enable it automatically).
2. Connect your HackRF/PortaPack to your phone using a high-quality USB OTG cable (or powered Y-cable).
3. Open **MayhemHub**.
4. A system dialog will prompt you: *"Allow MayhemHub to access the USB device?"* -> tap **OK / Allow**.
5. Once the page loads, tap **Refresh** or **Connect** at the bottom of the interface to initialize the WebSerial connection and start controlling your screen!

6. <img width="364" height="814" alt="image" src="https://github.com/user-attachments/assets/256e96fd-7c53-4301-90d7-c7051a38229a" />
<img width="1080" height="2340" alt="Screenshot_20260706_124834_MayhemHub" src="https://github.com/user-attachments/assets/fb71f512-27b8-4837-af71-708800b02594" />
<img width="1080" height="2340" alt="Screenshot_20260706_124812_MayhemHub" src="https://github.com/user-attachments/assets/b7725856-2836-46ee-a9c7-13203f005f21" />


