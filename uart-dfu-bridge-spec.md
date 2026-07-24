# UART/DFU Bridge — Build Specification

**Replaces:** TCPUART transparent Bridge (`com.hardcodedjoy.tcpuart`)
**Target device:** your Android 15 (API 35) phone, built entirely in Termux
**Feed this whole file to Antigravity CLI** as the task spec. Work through the phases in order — each ends with a checkpoint you verify on real hardware before moving on.

---

## 0. Assumptions (adjust and re-run if any of these are wrong)

- One combined app, not two. Serial bridging and DFU flashing both need USB host + permission handling, so they share that layer.
- Package name placeholder: `com.jkdev.uartdfubridge` — rename to whatever you actually want.
- TCP bridge keeps the exact contract your scripts already use: `socket://127.0.0.1:8080`, raw byte passthrough, no framing. Nothing in your esptool/mpremote invocations needs to change.
- DFU support targets the Arduino Uno R4 Minima specifically (VID `0x2341`, PID `0x0069` runtime / `0x0369` DFU mode). Other DFU devices are out of scope for v1 but the engine is written generically enough to extend later.
- `minSdk 26`, `targetSdk 35`, `compileSdk 35`. Since this only ever needs to run on your one phone, minSdk barely matters — 26 is just a conventional floor that keeps notification-channel code unconditional. Bump it to 35 and delete the version checks if you'd rather not carry them.

---

## 1. Why rebuild instead of patch

TCPUART is closed-source (confirmed: not in the developer's public GitHub org, no source distribution anywhere), so this is a from-scratch reimplementation, not a fork. Three concrete problems it has, from the Play Store listing and reviews:

1. **Not updated since Sep 7, 2023.** Google Play requires apps to target a reasonably recent API level to keep showing as installable on new OS versions. The exact cutoff moves over time, but an app three years stale is a textbook case of falling behind it. That's almost certainly why your Android 15 phone won't install it directly — not a hard OS incompatibility, just a distribution policy tied to a stale `targetSdkVersion`. Building and sideloading your own signed APK sidesteps this regardless of the precise mechanism.
2. **Runs as a plain background process, not a foreground service.** A reviewer reported it keeps running and draining battery after being closed, and has to be force-killed manually — classic symptom of never calling `startForeground()`. Modern Android's background execution limits then kill or throttle it unpredictably instead. Section 8 below is the fix.
3. **No DFU support, ever.** It's UART-only, so it was never going to reach the R4 Minima in bootloader mode — that board doesn't enumerate as CDC-ACM at all, it enumerates as a DFU device. This isn't a missing feature to patch in, it's a second, structurally different USB subsystem.

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  UartDfuBridgeService  (foreground service, type=connectedDevice) │
│                                                                  │
│   UsbSessionManager                                             │
│     - enumerates attached USB devices                           │
│     - requests permission, tracks attach/detach                 │
│     - hands off UsbDeviceConnection to whichever engine applies │
│         │                                                        │
│         ├──▶ SerialBridgeServer                                 │
│         │      TCP :8080 (loopback) ◀──raw bytes──▶ UsbSerialPort│
│         │      consumed by: esptool/mpremote in proot-Debian    │
│         │                                                        │
│         └──▶ DfuFlashEngine                                     │
│                control transfers ◀──▶ Arduino R4 Minima (DFU)   │
│                driven by: file picker + Flash button in the UI  │
└──────────────────────────────────────────────────────────────┘
```

Both engines are siblings under one service so USB permission handling, the foreground-notification, and device attach/detach logic are written once.

---

## 3. Project setup (Termux side, do this first)

```bash
# confirm the target platform + build-tools are actually installed —
# if your SDK was set up for an older compileSdk, API 35 won't be there yet
sdkmanager --list_installed | grep -E "platforms;android-35|build-tools;3[45]"
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

If your Gradle/AGP version predates compileSdk 35 support, bump AGP first (8.3+ recommended) and re-verify your existing aapt2 patch still applies cleanly — this is the one place your existing toolchain might need attention before any app code gets written. Treat it as its own checkpoint: get a brand-new empty Gradle Android project compiling and installing at `compileSdk/targetSdk 35` before writing a single line of USB code.

`settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // required for usb-serial-for-android
    }
}
```

`app/build.gradle.kts`:
```kotlin
android {
    namespace = "com.jkdev.uartdfubridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jkdev.uartdfubridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")
}
```

---

## 4. Manifest essentials

```xml
<uses-feature android:name="android.hardware.usb.host" android:required="true" />

<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application ...>

    <activity android:name=".MainActivity" android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
        <intent-filter>
            <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
        </intent-filter>
        <meta-data
            android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
            android:resource="@xml/device_filter" />
    </activity>

    <service
        android:name=".bridge.UartDfuBridgeService"
        android:foregroundServiceType="connectedDevice"
        android:exported="false" />

</application>
```

`res/xml/device_filter.xml` — start from the one shipped in `mik3y/usb-serial-for-android`'s `UsbSerialExamples` module (covers FTDI/CP210x/CH34x/PL2303/CDC-ACM, i.e. essentially every ESP32/ESP8266 devkit's USB-serial chip), then add the R4 Minima's two identities:

```xml
<resources>
    <!-- ... entries copied from the library's example device_filter.xml ... -->

    <!-- Arduino Uno R4 Minima -->
    <usb-device vendor-id="9025" product-id="105" />  <!-- 0x2341:0x0069 — runtime mode -->
    <usb-device vendor-id="9025" product-id="873" />  <!-- 0x2341:0x0369 — DFU mode -->
</resources>
```

If your specific ESP32-S3 board's USB-serial chip somehow isn't in the library's default filter, find its VID/PID with `dumpsys usb` — or install "OTG Guru" (same developer as TCPUART, still on the Play Store) and read it off there.

---

## 5. USB session manager (permission + attach/detach)

Two Android version gotchas baked in here that are easy to miss and will otherwise cost you a debugging session:

- `PendingIntent` for the permission callback **must** use `FLAG_MUTABLE` — the system needs to write `EXTRA_PERMISSION_GRANTED` back into it, which an immutable intent can't accept. (Every other `PendingIntent` in the app should default to `FLAG_IMMUTABLE`; this one specific case is the exception.)
- Since API 33, a dynamically-registered `BroadcastReceiver` must declare `RECEIVER_EXPORTED` or `RECEIVER_NOT_EXPORTED`. This is internal-only, so `RECEIVER_NOT_EXPORTED`.

```kotlin
class UsbSessionManager(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.jkdev.uartdfubridge.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            device ?: return
            if (granted) onDeviceReady(device) else onPermissionDenied(device)
        }
    }

    fun register() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permissionReceiver, filter)
        }
    }

    fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
        val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pi)
    }

    fun attachedDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    fun openConnection(device: UsbDevice): UsbDeviceConnection? = usbManager.openDevice(device)

    // wired up to your engine dispatch logic (Section 6/7): CDC/FTDI/CP210x/CH34x → SerialBridgeServer,
    // 0x2341:0x0369 → DfuFlashEngine
    private fun onDeviceReady(device: UsbDevice) { /* ... */ }
    private fun onPermissionDenied(device: UsbDevice) { /* surface to UI */ }
}
```

---

## 6. Serial ↔ TCP bridge

Deliberately a dumb pipe — no RFC2217, no control-line signaling over the wire. `esptool`/`mpremote`'s `socket://` transport (pyserial's raw socket handler) can't carry DTR/RTS toggles anyway, which is exactly why your existing workflow already uses `--before no-reset --after no-reset`. Don't build complexity the client side can't use.

One real feature worth adding, from the TCPUART reviews: some serial devices need DTR asserted just to talk at all. Make that a one-time toggle applied when the port opens, not a per-transfer thing.

```kotlin
class SerialBridgeServer(
    private val port: Int,
    private val serialPort: UsbSerialPort,
    private val assertDtrOnOpen: Boolean,
) : Thread("SerialBridgeServer") {

    @Volatile private var running = true
    private var serverSocket: ServerSocket? = null

    override fun run() {
        if (assertDtrOnOpen) runCatching { serialPort.dtr = true }
        val ss = ServerSocket(port, 1, InetAddress.getByName("0.0.0.0"))
        serverSocket = ss
        while (running) {
            val client = try { ss.accept() } catch (e: IOException) { break }
            handleClient(client) // one bridge session at a time, matches the original app's model
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        val pump = thread(name = "usb-to-net") {
            val buf = ByteArray(4096)
            try {
                while (running) {
                    val n = serialPort.read(buf, 500)
                    if (n > 0) socket.getOutputStream().write(buf, 0, n)
                }
            } catch (e: IOException) { /* client gone or USB detached */ }
        }
        try {
            val input = socket.getInputStream()
            val buf = ByteArray(4096)
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                serialPort.write(buf, 0, n, 2000)
            }
        } catch (e: IOException) { /* fallthrough to cleanup */ }
        finally {
            pump.interrupt()
            socket.close()
        }
    }

    fun shutdown() {
        running = false
        serverSocket?.close()
    }
}
```

Default port 8080, configurable in the UI — kept as the default specifically so your existing `socket://127.0.0.1:8080` scripts need zero changes.

---

## 7. DFU flash engine (Arduino Uno R4 Minima)

This is the genuinely uncertain part of the build, so treat it as an empirical task, not a spec to implement blind. The constants below are standard USB DFU 1.1 — accurate, stable, safe to hardcode. What's *not* certain from documentation alone is whether the Arduino/Renesas bootloader needs any non-stock quirks on top of that — one Arduino/Zephyr thread explicitly notes the R4 needs "a special dfu-util," implying it doesn't follow the base spec exactly.

**You already have the answer to that, though** — `devanlai/webdfu` is confirmed working against this exact board over WebUSB. WebUSB's `controlTransferOut`/`controlTransferIn` map almost 1:1 to Android's `UsbDeviceConnection.controlTransfer()`, so the fastest, most reliable path is: **read webdfu's actual protocol source and port its exact request sequence**, rather than re-deriving from the DFU spec and hoping. Specifically check whether it sends any `SET_ADDRESS_POINTER`/`ERASE` style prefix commands (the ST DfuSe extension pattern) before the first `DNLOAD`, or whether it's plain-spec DFU writing sequentially from offset 0 — the Renesas Flash Programmer's own memory report shows code flash starting at `0x00000000`, consistent with the simpler case, but confirm against webdfu rather than assuming.

```kotlin
object DfuRequest {
    const val DETACH = 0x00
    const val DNLOAD = 0x01
    const val UPLOAD = 0x02
    const val GETSTATUS = 0x03
    const val CLRSTATUS = 0x04
    const val GETSTATE = 0x05
    const val ABORT = 0x06
}

object DfuState {
    const val DFU_IDLE = 2
    const val DFU_DNLOAD_SYNC = 3
    const val DFU_DNBUSY = 4
    const val DFU_DNLOAD_IDLE = 5
    const val DFU_MANIFEST_SYNC = 6
    const val DFU_MANIFEST = 7
    const val DFU_MANIFEST_WAIT_RESET = 8
    const val DFU_ERROR = 10
}

private const val DFU_OUT = 0x21 // host-to-device | class | interface
private const val DFU_IN  = 0xA1 // device-to-host | class | interface

data class DfuStatus(val status: Int, val pollTimeoutMs: Int, val state: Int)

class DfuFlashEngine(private val conn: UsbDeviceConnection, private val ifaceNum: Int) {

    fun getStatus(): DfuStatus {
        val buf = ByteArray(6)
        conn.controlTransfer(DFU_IN, DfuRequest.GETSTATUS, 0, ifaceNum, buf, 6, 5000)
        val pollMs = (buf[1].toInt() and 0xFF) or
                     ((buf[2].toInt() and 0xFF) shl 8) or
                     ((buf[3].toInt() and 0xFF) shl 16)
        return DfuStatus(buf[0].toInt() and 0xFF, pollMs, buf[4].toInt() and 0xFF)
    }

    private fun waitUntilIdle() {
        while (true) {
            val s = getStatus()
            if (s.state == DfuState.DFU_ERROR) error("DFU error, bStatus=${s.status}")
            if (s.state == DfuState.DFU_DNLOAD_IDLE || s.state == DfuState.DFU_IDLE) return
            Thread.sleep(s.pollTimeoutMs.toLong().coerceAtLeast(1))
        }
    }

    /**
     * blockSize: read from the device's DFU functional descriptor (wTransferSize) at
     * runtime rather than hardcoding — parse it from the config descriptor when the
     * device attaches, don't guess a constant here.
     */
    fun flash(firmware: ByteArray, blockSize: Int, onProgress: (Int, Int) -> Unit) {
        var blockNum = 0
        var offset = 0
        while (offset < firmware.size) {
            val len = minOf(blockSize, firmware.size - offset)
            val chunk = firmware.copyOfRange(offset, offset + len)
            conn.controlTransfer(DFU_OUT, DfuRequest.DNLOAD, blockNum, ifaceNum, chunk, chunk.size, 5000)
            waitUntilIdle()
            offset += len
            blockNum++
            onProgress(offset, firmware.size)
        }
        // zero-length DNLOAD signals "that was the last block"
        conn.controlTransfer(DFU_OUT, DfuRequest.DNLOAD, blockNum, ifaceNum, ByteArray(0), 0, 5000)
        // now poll through DFU_MANIFEST_SYNC / DFU_MANIFEST — device may disappear
        // here as it resets into the new firmware; a controlTransfer failure at this
        // exact point is the expected success case, not necessarily an error
        runCatching { waitUntilIdle() }
    }
}
```

VID/PID reference for device matching:

| Mode | VID | PID | Decimal (device_filter.xml) |
|---|---|---|---|
| Runtime (normal sketch running) | 0x2341 | 0x0069 | 9025 / 105 |
| DFU (after double-tap reset) | 0x2341 | 0x0369 | 9025 / 873 |

**Checkpoint for this section specifically:** get as far as opening the DFU-mode interface and successfully reading back `getStatus()` before attempting a real flash. If that round-trips cleanly, the transport is correct and any remaining issues are in the block-write sequence — which is where porting webdfu's exact behavior matters most.

---

## 8. Foreground service (the actual bug fix)

```kotlin
class UartDfuBridgeService : Service() {

    companion object {
        const val CHANNEL_ID = "bridge_service"
        const val NOTIF_ID = 1
    }

    private var bridgeServer: SerialBridgeServer? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "USB Bridge", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Idle"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        // dispatch to SerialBridgeServer or DfuFlashEngine based on intent extras
        return START_STICKY
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UART/DFU Bridge")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_usb) // add a simple vector asset
            .setOngoing(true)
            .build()

    fun updateStatus(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        bridgeServer?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` + the matching manifest permission is what Android 14+ requires for a background USB-connected service — this is the direct fix for "app dies/drains battery after backgrounding."

---

## 9. UI (minimal, agent has latitude here)

- Device list (from `UsbSessionManager.attachedDevices()`), tap to request permission + connect
- Mode is implicit from device identity: CDC/FTDI/CP210x/CH34x → serial bridge controls (port number field, DTR toggle, Start/Stop); `2341:0369` → DFU controls (file picker via `ACTION_OPEN_DOCUMENT` for the `.bin`, Flash button, progress bar)
- A scrolling log view for status/errors — genuinely useful for debugging the DFU quirks in Section 7 against real hardware

---

## 10. Build, sign, install

```bash
./gradlew assembleRelease

# one-time key generation
keytool -genkeypair -v -keystore release.keystore -alias bridge \
  -keyalg RSA -keysize 2048 -validity 10000

# sign (apksigner ships with your existing build-tools install)
apksigner sign --ks release.keystore \
  app/build/outputs/apk/release/app-release-unsigned.apk

# install directly
adb install -r app/build/outputs/apk/release/app-release-unsigned.apk
```

Play Protect will likely show a warning dialog for an unfamiliar self-signed APK doing USB/device operations — expected for any sideloaded app, not a sign of a problem, just tap through it.

---

## 11. Phased plan — verify each before continuing

1. **Toolchain checkpoint.** Empty Gradle project, `compileSdk/targetSdk 35`, builds and installs on your phone. Confirms the SDK/aapt2/Gradle setup handles the version bump before any real code exists.
2. **Serial bridge only.** Implement Sections 5+6. Point your existing `mpremote`/`esptool` commands at it unchanged (`socket://127.0.0.1:8080`) against the ESP32-S3 and confirm it behaves identically to TCPUART for your normal workflow.
3. **Foreground service correctness.** Leave it connected, background the app, lock the screen, wait — confirm the connection survives and battery isn't climbing. This is the checkpoint that proves Section 8 actually fixed the known bug.
4. **DFU engine.** Implement Section 7. Start with the `getStatus()` round-trip checkpoint before attempting a real firmware write. Keep a known-good `.bin` on hand and a way to recover via the Renesas Flash Programmer path if a write goes bad.
5. **Polish.** DTR toggle, configurable port, app icon, final signed release build.

---

## 12. Reference material

- `mik3y/usb-serial-for-android` — serial driver layer (LGPL 2.1), `github.com/mik3y/usb-serial-for-android`
- `kai-morich/SimpleUsbTerminal` — open-source example showing the correct background-service pattern this spec is modeled on
- `devanlai/webdfu` — your own already-confirmed-working reference for R4 Minima DFU quirks; read its protocol/transfer source directly rather than re-deriving from the base spec
- Android foreground service types: `developer.android.com/develop/background-work/services/fgs/service-types`
- USB DFU 1.1 class specification — base protocol constants used in Section 7
