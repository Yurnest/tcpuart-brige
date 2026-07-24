package com.jkdev.uartdfubridge.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.jkdev.uartdfubridge.R
import com.jkdev.uartdfubridge.dfu.DfuFlashEngine
import java.io.IOException

class UartDfuBridgeService : Service() {

    enum class State {
        IDLE, CONNECTED, DISCONNECTED, RECONNECTING, FLASHING
    }

    companion object {
        const val CHANNEL_ID = "bridge_service"
        const val EVENT_CHANNEL_ID = "event_service"
        const val NOTIF_ID = 1

        const val EXTRA_ACTION = "action"
        const val ACTION_STOP = "stop"

        var logCallback: ((String) -> Unit)? = null
        var progressCallback: ((Int, Int) -> Unit)? = null
        var statusCallback: ((String, State) -> Unit)? = null
    }

    private var usbSessionManager: UsbSessionManager? = null
    private var bridgeServer: SerialBridgeServer? = null
    private var dfuEngine: DfuFlashEngine? = null
    private var usbConn: UsbDeviceConnection? = null
    private var claimedIface: UsbInterface? = null

    var currentState: State = State.IDLE
        private set
    var currentStatusText: String = "Idle"
        private set

    // Reconnect state
    private var targetVid: Int = -1
    private var targetPid: Int = -1
    private var lastPort: Int = 8080
    private var lastBaud: Int = 115200
    private var lastDtr: Boolean = true
    private var reconnectAttempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device?.vendorId == targetVid && device.productId == targetPid) {
                    handleDisconnect()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ServiceHolder.instance = this
        val nm = getSystemService(NotificationManager::class.java)
        
        // Status channel (ongoing)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_LOW
        ))
        
        // Event channel (disconnect/reconnect popups)
        nm.createNotificationChannel(NotificationChannel(
            EVENT_CHANNEL_ID, getString(R.string.notif_channel_events), NotificationManager.IMPORTANCE_HIGH
        ))

        usbSessionManager = UsbSessionManager(this).also { it.register() }
        
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        registerReceiver(detachReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(currentStatusText)
        if (intent?.getStringExtra(EXTRA_ACTION) == ACTION_STOP) {
            stopBridge()
            stopSelf()
        }
        return START_STICKY
    }

    fun startSerialBridge(device: UsbDevice, tcpPort: Int, assertDtr: Boolean, baudRate: Int) {
        stopEverything()
        cancelReconnect()
        
        targetVid = device.vendorId
        targetPid = device.productId
        lastPort = tcpPort
        lastBaud = baudRate
        lastDtr = assertDtr

        val conn = usbSessionManager?.openConnection(device) ?: run {
            log("Cannot open USB connection")
            updateStatus("Failed to open", State.DISCONNECTED)
            return
        }
        usbConn = conn

        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null || driver.ports.isEmpty()) {
            log("No serial driver found for ${device.productName}")
            conn.close()
            updateStatus("No driver", State.DISCONNECTED)
            return
        }

        val serialPort = driver.ports[0]
        try {
            serialPort.open(conn)
            serialPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            log("Serial open failed: ${e.message}")
            conn.close()
            updateStatus("Open failed", State.DISCONNECTED)
            return
        }

        bridgeServer = SerialBridgeServer(tcpPort, serialPort, assertDtr, ::log).also {
            it.isDaemon = true
            it.start()
        }
        reconnectAttempts = 0
        updateStatus("Bridge :$tcpPort — ${device.productName ?: "USB Serial"}", State.CONNECTED)
        
        // Custom thread to detect serial read failure (device yanked) if broadcast misses it
        Thread {
            while (bridgeServer?.isAlive == true) {
                Thread.sleep(500)
            }
            if (currentState == State.CONNECTED) {
                handler.post { handleDisconnect() }
            }
        }.start()
    }

    private fun handleDisconnect() {
        if (currentState != State.CONNECTED) return
        stopEverything()
        updateStatus("Disconnected", State.DISCONNECTED)
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("notify_disconnect", true)) {
            sendEventNotification(getString(R.string.notif_disconnected_title), "Device unplugged")
        }

        if (prefs.getBoolean("auto_reconnect", true)) {
            startReconnectLoop()
        }
    }

    private fun startReconnectLoop() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val maxAttempts = prefs.getString("reconnect_max", "5")?.toIntOrNull() ?: 5
        val intervalSec = prefs.getString("reconnect_interval", "5")?.toLongOrNull() ?: 5L
        
        reconnectRunnable = object : Runnable {
            override fun run() {
                if (maxAttempts in 1..reconnectAttempts) {
                    log("Max reconnect attempts reached.")
                    updateStatus("Disconnected", State.DISCONNECTED)
                    return
                }
                
                reconnectAttempts++
                updateStatus("Reconnecting (attempt $reconnectAttempts)", State.RECONNECTING)
                
                val device = usbSessionManager?.attachedDevices()?.find { 
                    it.vendorId == targetVid && it.productId == targetPid 
                }
                
                if (device != null) {
                    if (usbSessionManager?.hasPermission(device) == true) {
                        log("Device found, reconnecting...")
                        if (prefs.getBoolean("notify_reconnect", true)) {
                            sendEventNotification(getString(R.string.notif_reconnected_title), "Auto-reconnect successful")
                        }
                        startSerialBridge(device, lastPort, lastDtr, lastBaud)
                        return
                    } else {
                        log("Device found but lacks permission. Waiting...")
                    }
                }
                handler.postDelayed(this, intervalSec * 1000)
            }
        }
        handler.postDelayed(reconnectRunnable!!, intervalSec * 1000)
    }

    fun triggerReconnect() {
        cancelReconnect()
        reconnectAttempts = 0
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val intervalSec = prefs.getString("reconnect_interval", "5")?.toLongOrNull() ?: 5L
        
        // Check immediately
        val device = usbSessionManager?.attachedDevices()?.find { 
            it.vendorId == targetVid && it.productId == targetPid 
        }
        
        if (device != null && usbSessionManager?.hasPermission(device) == true) {
            log("Manual reconnecting...")
            startSerialBridge(device, lastPort, lastDtr, lastBaud)
        } else {
            updateStatus("Waiting for device...", State.RECONNECTING)
            startReconnectLoop()
        }
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    fun startDfuFlash(device: UsbDevice, firmware: ByteArray) {
        stopEverything()
        cancelReconnect()
        
        targetVid = device.vendorId
        targetPid = device.productId

        val conn = usbSessionManager?.openConnection(device) ?: run {
            log("Cannot open USB connection")
            updateStatus("Failed to open", State.IDLE)
            return
        }
        usbConn = conn

        var ifaceIdx = -1
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 0xFE && iface.interfaceSubclass == 0x01) {
                ifaceIdx = i
                break
            }
        }
        if (ifaceIdx < 0) {
            log("No DFU interface found on device")
            conn.close()
            updateStatus("No DFU interface", State.IDLE)
            return
        }

        val iface = device.getInterface(ifaceIdx)
        if (!conn.claimInterface(iface, true)) {
            log("Cannot claim DFU interface")
            conn.close()
            updateStatus("Claim failed", State.IDLE)
            return
        }
        claimedIface = iface
        dfuEngine = DfuFlashEngine(conn, ifaceIdx)

        updateStatus("Preparing to flash...", State.FLASHING)
        Thread(Runnable {
            try {
                val engine = dfuEngine!!
                val status = engine.getStatus()
                log("DFU getStatus OK: state=${status.state}, status=0x${status.status.toString(16)}")

                val blockSize = engine.readTransferSize().also {
                    log("DFU wTransferSize = $it bytes")
                }

                updateStatus("Flashing firmware…", State.FLASHING)
                engine.flash(firmware, blockSize) { written, total ->
                    val pct = (written * 100L / total).toInt()
                    progressCallback?.invoke(written, total)
                    updateStatus("Flashing $pct% ($written/$total)", State.FLASHING)
                }
                log("DFU flash complete — device resetting into new firmware")
                updateStatus("Flash complete", State.IDLE)
            } catch (e: Exception) {
                log("DFU flash error: ${e.message}")
                updateStatus("Flash error", State.IDLE)
            } finally {
                claimedIface?.let { usbConn?.releaseInterface(it) }
                usbConn?.close()
                usbConn = null
                claimedIface = null
                dfuEngine = null
            }
        }, "DfuFlashThread").start()
    }

    fun stopBridge() {
        cancelReconnect()
        stopEverything()
        updateStatus("Idle", State.IDLE)
        targetVid = -1
        targetPid = -1
    }

    private fun stopEverything() {
        bridgeServer?.shutdown()
        bridgeServer = null
        claimedIface?.let { usbConn?.releaseInterface(it) }
        claimedIface = null
        usbConn?.close()
        usbConn = null
        dfuEngine = null
    }

    private fun startForegroundCompat(status: String) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UART/DFU Bridge")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_usb)
            .setOngoing(true)
            .build()

    private fun updateStatus(text: String, state: State) {
        currentStatusText = text
        currentState = state
        statusCallback?.invoke(text, state)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
    
    private fun sendEventNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, EVENT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_info)
            .setAutoCancel(true)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun log(msg: String) {
        logCallback?.invoke(msg)
    }

    override fun onDestroy() {
        ServiceHolder.instance = null
        usbSessionManager?.unregister()
        unregisterReceiver(detachReceiver)
        stopEverything()
        cancelReconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
