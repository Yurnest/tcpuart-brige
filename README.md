# UART / DFU Bridge for Android

An Android application that acts as a transparent bridge between an attached USB Serial/DFU device (like an ESP32 or Arduino Uno R4) and a local TCP port. It enables you to use network-based serial tools (like `telnet`, `netcat`, or `esptool` over socket) on your computer/device while your microcontroller is physically plugged into your Android phone.

## Features
- **TCP to USB Serial Bridging:** Instantly forward traffic from a TCP port to a USB Serial device.
- **DFU Flashing:** Built-in DFU-mode flashing (specifically targeted for devices like the Arduino Uno R4 Minima).
- **Auto-Reconnect:** Safely survives cable disconnections. It will gracefully suspend the TCP socket and auto-resume the bridge the moment you plug the device back in.
- **Custom Baud Rates & DTR:** Configurable baud rates and DTR pin assertions on connect.
- **Modern Material 3 UI:** Clean, modern interface with Light/Dark mode support.
- **Background Execution:** Runs as a proper Android 14+ Foreground Service (`connectedDevice`), preventing aggressive battery optimization from killing your active flash sessions.

---

## 📥 Download Latest APK
Every time code is pushed to this repository, GitHub Actions automatically builds the APK and attaches it to the "Latest Build" release.

**[⬇️ Download Latest APK](https://github.com/AmpongJkevin2/tcpuart-brige/releases/download/latest/app-debug.apk)**  

---

## 🛠️ Building Locally

You can easily compile this project yourself using the standard Android Gradle wrapper.

### Prerequisites
- **Java Development Kit (JDK) 17**
- **Android SDK** (Specifically the SDK Platform & Build-tools)

### Linux / WSL (Windows Subsystem for Linux)
1. **Set up JDK and SDK:**
   Make sure your `JAVA_HOME` and `ANDROID_HOME` environment variables are properly set.
   ```bash
   export JAVA_HOME=/path/to/your/jdk-17
   export ANDROID_HOME=/path/to/your/android-sdk
   ```
2. **Clone and Build:**
   ```bash
   git clone https://github.com/AmpongJkevin2/tcpuart-brige.git
   cd tcpuart-brige
   
   # Make the wrapper executable
   chmod +x gradlew
   
   # Build the debug APK
   ./gradlew assembleDebug
   ```
3. **Locate your APK:**
   The compiled APK will be found at `app/build/outputs/apk/debug/app-debug.apk`.

### Termux (Running directly on Android)
Building Android projects natively in Termux requires a few specific packages. 
1. Install JDK 17 and Gradle:
   ```bash
   pkg update
   pkg install openjdk-17 gradle git
   ```
2. *Note:* Because standard Android SDK build tools (like `aapt2`) are compiled for standard Linux (glibc) and not Android's native bionic libc, building directly in Termux using `./gradlew` often requires specific patched Android SDKs or tools like `termux-aapt`. 
3. If you have an environment capable of running `aapt2` (or are running a proot-distro like Ubuntu within Termux):
   ```bash
   git clone https://github.com/AmpongJkevin2/tcpuart-brige.git
   cd tcpuart-brige
   chmod +x gradlew
   ./gradlew assembleDebug
   ```

---

## 💡 Usage Examples

If you've connected an ESP32 or similar device to your phone and started the bridge on port **8080** with a Baud Rate of **115200**, you can connect to it natively using your favorite microcontroller tools over the network.

**Raw Serial Console (netcat)**
To open a raw serial console, use `netcat` (avoid using `telnet`, as telnet injects hidden negotiation bytes that will scramble your serial data):
```bash
rlwrap nc 127.0.0.1 8080
```
*(We recommend `rlwrap` to give you command history and arrow-key support).*

**esptool.py**
You can flash ESP32 boards directly over the network:
```bash
esptool.py --port socket://127.0.0.1:8080 flash_id
```

**mpremote (MicroPython)**
To connect to a MicroPython REPL via the bridge:
```bash
mpremote connect socket://127.0.0.1:8080
```

---

## 🤖 AI Disclaimer
*Transparency note: The codebase, UI overhaul, networking fixes, and documentation for this project were developed with the assistance of an AI coding agent (Google Antigravity / Gemini).*
