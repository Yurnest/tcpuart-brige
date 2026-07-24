package com.jkdev.uartdfubridge.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Handles USB device enumeration, permission requests, and attach/detach events.
 *
 * Two API-level gotchas handled here:
 * - PendingIntent for permission callback MUST use FLAG_MUTABLE so the system can write
 *   EXTRA_PERMISSION_GRANTED back into it.
 * - Since API 33, dynamically-registered receivers must declare RECEIVER_NOT_EXPORTED
 *   (this is an internal-only broadcast).
 */
class UsbSessionManager(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.jkdev.uartdfubridge.USB_PERMISSION"

        /** Arduino Uno R4 Minima vendor ID (0x2341) in decimal */
        const val VID_ARDUINO = 9025

        /** Arduino Uno R4 Minima DFU-mode product ID (0x0369) in decimal */
        const val PID_R4_DFU = 873

        /** Arduino Uno R4 Minima runtime-mode product ID (0x0069) in decimal */
        const val PID_R4_RUNTIME = 105
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Called when permission is granted and the device is ready to open. */
    var onDeviceReady: ((UsbDevice) -> Unit)? = null

    /** Called when the user denies USB permission. */
    var onPermissionDenied: ((UsbDevice) -> Unit)? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            device ?: return
            if (granted) onDeviceReady?.invoke(device)
            else onPermissionDenied?.invoke(device)
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

    fun unregister() {
        runCatching { context.unregisterReceiver(permissionReceiver) }
    }

    fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
        val pi = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(device, pi)
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun attachedDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    fun openConnection(device: UsbDevice): UsbDeviceConnection? = usbManager.openDevice(device)

    /**
     * Returns true if this device is the Arduino Uno R4 Minima in DFU mode.
     */
    fun isDfuDevice(device: UsbDevice): Boolean =
        device.vendorId == VID_ARDUINO && device.productId == PID_R4_DFU

    /**
     * Returns true if this device looks like a serial/CDC device (non-DFU).
     * The library's UsbSerialProber handles the actual driver matching.
     */
    fun isSerialDevice(device: UsbDevice): Boolean = !isDfuDevice(device)
}
