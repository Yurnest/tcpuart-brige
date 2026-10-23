package com.jkdev.uartdfubridge

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.jkdev.uartdfubridge.bridge.ServiceHolder
import com.jkdev.uartdfubridge.bridge.UartDfuBridgeService
import com.jkdev.uartdfubridge.bridge.UsbSessionManager
import com.jkdev.uartdfubridge.ui.DeviceAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var usbSession: UsbSessionManager
    private lateinit var deviceAdapter: DeviceAdapter

    private var selectedDevice: UsbDevice? = null
    private var firmwareBytes: ByteArray? = null

    // UI refs
    private lateinit var chipStatus: Chip
    private lateinit var tvStatusDetail: TextView
    private lateinit var btnReconnect: MaterialButton
    private lateinit var rvDevices: RecyclerView
    private lateinit var panelSerial: MaterialCardView
    private lateinit var etPort: TextInputEditText
    private lateinit var etBaud: TextInputEditText
    private lateinit var cbDtr: MaterialSwitch
    private lateinit var btnStartBridge: MaterialButton
    private lateinit var btnStopBridge: MaterialButton
    private lateinit var panelDfu: MaterialCardView
    private lateinit var btnPickFile: MaterialButton
    private lateinit var tvFileName: TextView
    private lateinit var progressDfu: LinearProgressIndicator
    private lateinit var btnFlash: MaterialButton
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleFileSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySettings() // Theme & font size
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        usbSession = UsbSessionManager(this)
        
        chipStatus = findViewById(R.id.chipStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        btnReconnect = findViewById(R.id.btnReconnect)
        rvDevices = findViewById(R.id.rvDevices)
        panelSerial = findViewById(R.id.panelSerial)
        etPort = findViewById(R.id.etPort)
        etBaud = findViewById(R.id.etBaud)
        cbDtr = findViewById(R.id.cbDtr)
        btnStartBridge = findViewById(R.id.btnStartBridge)
        btnStopBridge = findViewById(R.id.btnStopBridge)
        panelDfu = findViewById(R.id.panelDfu)
        btnPickFile = findViewById(R.id.btnPickFile)
        tvFileName = findViewById(R.id.tvFileName)
        progressDfu = findViewById(R.id.progressDfu)
        btnFlash = findViewById(R.id.btnFlash)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)

        findViewById<MaterialButton>(R.id.btnClearLog).setOnClickListener {
            tvLog.text = ""
        }

        findViewById<TextView>(R.id.tvCredits).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/AmpongJkevin2")))
        }

        setupRecyclerView()
        setupButtons()
        setupServiceCallbacks()
        loadDefaults()
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceList()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            refreshDeviceList()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun applySettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        
        // Theme
        when (prefs.getString("theme", "system")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        
        // Font size (applied to the activity context)
        val fontScale = when (prefs.getString("font_size", "normal")) {
            "small" -> 0.85f
            "large" -> 1.15f
            "xlarge" -> 1.3f
            else -> 1.0f
        }
        val config = resources.configuration
        config.fontScale = fontScale
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }


    private fun loadDefaults() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        etPort.setText(prefs.getString("default_port", "8080"))
        etBaud.setText("250000")
        cbDtr.isChecked = prefs.getBoolean("assert_dtr", true)
    }


    
    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { device ->
            selectedDevice = device
            if (usbSession.hasPermission(device)) {
                onDeviceSelected(device)
            } else {
                usbSession.requestPermission(device)
            }
        }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        usbSession.onDeviceReady = { device ->
            if (device == selectedDevice) onDeviceSelected(device)
        }
        usbSession.onPermissionDenied = {
            appendLog(getString(R.string.permission_denied))
        }
    }

    private fun refreshDeviceList() {
        val devices = usbSession.attachedDevices()
        deviceAdapter.submitList(devices)
        if (devices.isEmpty()) {
            panelSerial.visibility = View.GONE
            panelDfu.visibility = View.GONE
            selectedDevice = null
        }
    }

    private fun onDeviceSelected(device: UsbDevice) {
        if (usbSession.isDfuDevice(device)) {
            panelDfu.visibility = View.VISIBLE
            panelSerial.visibility = View.GONE
        } else {
            panelSerial.visibility = View.VISIBLE
            panelDfu.visibility = View.GONE
        }
    }

    private fun setupButtons() {
        btnStartBridge.setOnClickListener {
            val device = selectedDevice ?: return@setOnClickListener
            val port = etPort.text.toString().toIntOrNull() ?: 8080
            val baud = etBaud.text.toString().toIntOrNull() ?: 115200
            val dtr = cbDtr.isChecked
            startService()
            ServiceHolder.execute { it.startSerialBridge(device, port, dtr, baud) }
            btnStartBridge.isEnabled = false
            btnStopBridge.isEnabled = true
        }

        btnStopBridge.setOnClickListener {
            ServiceHolder.instance?.stopBridge()
            btnStartBridge.isEnabled = true
            btnStopBridge.isEnabled = false
        }

        btnPickFile.setOnClickListener {
            filePicker.launch("*/*")
        }

        btnFlash.setOnClickListener {
            val device = selectedDevice ?: return@setOnClickListener
            val fw = firmwareBytes ?: return@setOnClickListener
            progressDfu.visibility = View.VISIBLE
            progressDfu.progress = 0
            startService()
            ServiceHolder.instance?.startDfuFlash(device, fw)
        }
        
        btnReconnect.setOnClickListener {
            ServiceHolder.instance?.triggerReconnect()
        }
    }

    private fun startService() {
        val intent = Intent(this, UartDfuBridgeService::class.java)
        startForegroundService(intent)
    }

    private fun setupServiceCallbacks() {
        UartDfuBridgeService.logCallback = { msg ->
            runOnUiThread { appendLog(msg) }
        }
        UartDfuBridgeService.statusCallback = { status, state ->
            runOnUiThread { 
                tvStatusDetail.text = status
                updateStatusChip(state)
            }
        }
        UartDfuBridgeService.progressCallback = { current, total ->
            runOnUiThread {
                progressDfu.max = total
                progressDfu.progress = current
            }
        }
        // Force an initial update if service is running
        ServiceHolder.instance?.let {
            tvStatusDetail.text = it.currentStatusText
            updateStatusChip(it.currentState)
        }
    }

    private fun updateStatusChip(state: UartDfuBridgeService.State) {
        val (text, colorRes) = when(state) {
            UartDfuBridgeService.State.IDLE -> "Idle" to R.color.status_idle
            UartDfuBridgeService.State.CONNECTED -> "Active" to R.color.status_connected
            UartDfuBridgeService.State.DISCONNECTED -> "Disconnected" to R.color.status_disconnected
            UartDfuBridgeService.State.RECONNECTING -> "Reconnecting" to R.color.status_reconnecting
            UartDfuBridgeService.State.FLASHING -> "Flashing" to R.color.status_flashing
        }
        chipStatus.text = text
        chipStatus.setChipBackgroundColorResource(colorRes)
        
        btnReconnect.visibility = if (state == UartDfuBridgeService.State.DISCONNECTED) View.VISIBLE else View.GONE
        
        if (state == UartDfuBridgeService.State.IDLE || state == UartDfuBridgeService.State.DISCONNECTED) {
            btnStartBridge.isEnabled = true
            btnStopBridge.isEnabled = false
            progressDfu.visibility = View.INVISIBLE
        } else if (state == UartDfuBridgeService.State.CONNECTED) {
            btnStartBridge.isEnabled = false
            btnStopBridge.isEnabled = true
        }
    }

    private fun handleFileSelected(uri: Uri) {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) tvFileName.text = cursor.getString(nameIdx)
            }
        }
        try {
            contentResolver.openInputStream(uri)?.use {
                firmwareBytes = it.readBytes()
                btnFlash.isEnabled = true
                appendLog("Loaded firmware: ${firmwareBytes?.size} bytes")
            }
        } catch (e: Exception) {
            appendLog("Error reading file: ${e.message}")
        }
    }

    private fun appendLog(msg: String) {
        tvLog.append("$msg\\n")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
