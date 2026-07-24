package com.jkdev.uartdfubridge.bridge

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Bridges a USB serial port to a TCP server socket on loopback.
 *
 * Design: deliberately a dumb byte pipe — no RFC 2217, no control-line signaling.
 * esptool/mpremote's socket:// transport is raw bytes only; --before no-reset
 * --after no-reset is already the required invocation pattern, so no DTR/RTS
 * manipulation is needed per-transfer. The one useful addition is asserting DTR
 * once on open for devices that require it to talk at all.
 *
 * Accepts one client at a time (matches the original TCPUART model).
 */
class SerialBridgeServer(
    private val port: Int,
    private val serialPort: UsbSerialPort,
    private val assertDtrOnOpen: Boolean,
    private val onLog: (String) -> Unit = {},
) : Thread("SerialBridgeServer") {

    @Volatile
    private var running = true
    private var serverSocket: ServerSocket? = null
    private var activeClient: Socket? = null

    override fun run() {
        if (assertDtrOnOpen) {
            runCatching { serialPort.dtr = true }
        }
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
            }
        } catch (e: IOException) {
            onLog("Bridge: failed to bind :$port — ${e.message}")
            return
        }
        serverSocket = ss
        onLog("Bridge: listening on TCP :$port")
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) onLog("Bridge: accept error — ${e.message}")
                break
            }
            activeClient = client
            onLog("Bridge: client connected from ${client.remoteSocketAddress}")
            handleClient(client)
            activeClient = null
            onLog("Bridge: client disconnected")
        }
        onLog("Bridge: stopped")
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true

        // USB → TCP pump
        val usbToNet = thread(name = "usb-to-net") {
            val buf = ByteArray(4096)
            try {
                while (running) {
                    val n = serialPort.read(buf, 500)
                    if (n > 0) socket.getOutputStream().write(buf, 0, n)
                }
            } catch (e: IOException) {
                // client gone or USB detached — let the main thread clean up
            }
        }

        // TCP → USB pump (main)
        try {
            val input = socket.getInputStream()
            val buf = ByteArray(4096)
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                serialPort.write(if (n == buf.size) buf else buf.copyOfRange(0, n), 2000)
            }
        } catch (e: IOException) {
            // fallthrough
        } finally {
            usbToNet.interrupt()
            runCatching { socket.close() }
        }
    }

    fun shutdown() {
        running = false
        runCatching { activeClient?.close() }
        runCatching { serverSocket?.close() }
    }
}
