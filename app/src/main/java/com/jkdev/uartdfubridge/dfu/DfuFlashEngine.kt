package com.jkdev.uartdfubridge.dfu

import android.hardware.usb.UsbDeviceConnection

// ─── Standard USB DFU 1.1 class request codes ───────────────────────────────

object DfuRequest {
    const val DETACH    = 0x00
    const val DNLOAD    = 0x01
    const val UPLOAD    = 0x02
    const val GETSTATUS = 0x03
    const val CLRSTATUS = 0x04
    const val GETSTATE  = 0x05
    const val ABORT     = 0x06
}

object DfuState {
    const val APP_IDLE              = 0
    const val APP_DETACH            = 1
    const val DFU_IDLE              = 2
    const val DFU_DNLOAD_SYNC       = 3
    const val DFU_DNBUSY            = 4
    const val DFU_DNLOAD_IDLE       = 5
    const val DFU_MANIFEST_SYNC     = 6
    const val DFU_MANIFEST          = 7
    const val DFU_MANIFEST_WAIT_RESET = 8
    const val DFU_UPLOAD_IDLE       = 9
    const val DFU_ERROR             = 10
}

object DfuStatus {
    const val OK               = 0x00
    const val ERR_TARGET        = 0x01
    const val ERR_FILE          = 0x02
    const val ERR_WRITE         = 0x03
    const val ERR_ERASE         = 0x04
    const val ERR_CHECK_ERASED  = 0x05
    const val ERR_PROG          = 0x06
    const val ERR_VERIFY        = 0x07
    const val ERR_ADDRESS       = 0x08
    const val ERR_NOTDONE       = 0x09
    const val ERR_FIRMWARE      = 0x0A
    const val ERR_VENDOR        = 0x0B
    const val ERR_USBR          = 0x0C
    const val ERR_POR           = 0x0D
    const val ERR_UNKNOWN       = 0x0E
    const val ERR_STALLEDPKT    = 0x0F
}

// bmRequestType values
private const val DFU_OUT = 0x21  // host-to-device | class | interface
private const val DFU_IN  = 0xA1  // device-to-host | class | interface

data class DfuStatusResponse(
    val status: Int,
    val pollTimeoutMs: Int,
    val state: Int,
)

/**
 * Implements the USB DFU 1.1 download sequence for the Arduino Uno R4 Minima.
 *
 * The R4 Minima uses plain DFU 1.1 (not the ST DfuSe extension). Code flash
 * starts at 0x00000000 so blocks are written sequentially from block 0 without
 * any SET_ADDRESS_POINTER prefix — consistent with webdfu's implementation
 * which is confirmed-working against this board.
 *
 * blockSize should be read from the device's DFU functional descriptor
 * (wTransferSize) at runtime via [readTransferSize]; do not hardcode.
 */
class DfuFlashEngine(
    private val conn: UsbDeviceConnection,
    private val ifaceNum: Int,
) {

    /**
     * Reads the DFU functional descriptor to find wTransferSize.
     * Falls back to 64 if the descriptor cannot be parsed.
     *
     * Call this once after opening the interface, before calling [flash].
     */
    fun readTransferSize(): Int {
        // DFU functional descriptor type = 0x21
        val buf = ByteArray(255)
        val len = conn.controlTransfer(
            0x80,  // device-to-host | standard | device
            0x06,  // GET_DESCRIPTOR
            0x2100, // descriptor type 0x21 (DFU functional), index 0
            0,
            buf, buf.size, 5000
        )
        if (len >= 9) {
            // bytes 6-7 are wTransferSize (little-endian)
            val low  = buf[6].toInt() and 0xFF
            val high = buf[7].toInt() and 0xFF
            val size = low or (high shl 8)
            if (size in 8..65536) return size
        }
        // Fallback: try reading interface descriptor from config
        return 64
    }

    fun getStatus(): DfuStatusResponse {
        val buf = ByteArray(6)
        val result = conn.controlTransfer(DFU_IN, DfuRequest.GETSTATUS, 0, ifaceNum, buf, 6, 5000)
        if (result < 6) error("GETSTATUS failed: $result bytes returned")
        val pollMs = (buf[1].toInt() and 0xFF) or
                     ((buf[2].toInt() and 0xFF) shl 8) or
                     ((buf[3].toInt() and 0xFF) shl 16)
        return DfuStatusResponse(buf[0].toInt() and 0xFF, pollMs, buf[4].toInt() and 0xFF)
    }

    fun clearStatus() {
        conn.controlTransfer(DFU_OUT, DfuRequest.CLRSTATUS, 0, ifaceNum, null, 0, 5000)
    }

    fun abort() {
        conn.controlTransfer(DFU_OUT, DfuRequest.ABORT, 0, ifaceNum, null, 0, 5000)
    }

    /**
     * Polls GETSTATUS until the device reaches DFU_IDLE or DFU_DNLOAD_IDLE,
     * respecting the device's advertised bwPollTimeout between polls.
     */
    private fun waitUntilIdle() {
        while (true) {
            val s = getStatus()
            when (s.state) {
                DfuState.DFU_ERROR -> {
                    clearStatus()
                    error("DFU error during download: bStatus=0x${s.status.toString(16)}")
                }
                DfuState.DFU_DNLOAD_IDLE,
                DfuState.DFU_IDLE -> return
                else -> Thread.sleep(s.pollTimeoutMs.toLong().coerceAtLeast(1))
            }
        }
    }

    /**
     * Flashes [firmware] to the device using sequential DNLOAD blocks.
     *
     * [blockSize] must come from [readTransferSize] — the device's DFU functional
     * descriptor wTransferSize field.
     *
     * [onProgress] is called with (bytesWritten, totalBytes) after each block.
     *
     * A zero-length DNLOAD at the end signals "last block" to the bootloader.
     * The device resets into the new firmware; a controlTransfer failure during
     * the final manifest phase is the expected success case.
     */
    fun flash(firmware: ByteArray, blockSize: Int, onProgress: (Int, Int) -> Unit) {
        // Ensure we start from DFU_IDLE
        val initialStatus = getStatus()
        if (initialStatus.state == DfuState.DFU_ERROR) {
            clearStatus()
        } else if (initialStatus.state != DfuState.DFU_IDLE) {
            abort()
            waitUntilIdle()
        }

        var blockNum = 0
        var offset = 0
        while (offset < firmware.size) {
            val len = minOf(blockSize, firmware.size - offset)
            val chunk = firmware.copyOfRange(offset, offset + len)
            val result = conn.controlTransfer(
                DFU_OUT, DfuRequest.DNLOAD,
                blockNum, ifaceNum,
                chunk, chunk.size, 5000
            )
            if (result < 0) error("DNLOAD block $blockNum failed: $result")
            waitUntilIdle()
            offset += len
            blockNum++
            onProgress(offset, firmware.size)
        }

        // Zero-length DNLOAD = end of firmware
        conn.controlTransfer(DFU_OUT, DfuRequest.DNLOAD, blockNum, ifaceNum, ByteArray(0), 0, 5000)

        // Poll through DFU_MANIFEST_SYNC / DFU_MANIFEST.
        // The device may disappear mid-poll as it resets — that's the success case.
        runCatching { waitUntilIdle() }
    }
}
