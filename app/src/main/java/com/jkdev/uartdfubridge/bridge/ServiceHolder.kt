package com.jkdev.uartdfubridge.bridge

import android.os.Handler
import android.os.Looper

/**
 * Holds a reference to the running [UartDfuBridgeService] so that [com.jkdev.uartdfubridge.MainActivity]
 * can call into it directly without a full AIDL binder.
 *
 * Enqueues actions if the service is not yet fully started.
 */
object ServiceHolder {
    private val handler = Handler(Looper.getMainLooper())
    private val pendingActions = mutableListOf<(UartDfuBridgeService) -> Unit>()

    @Volatile
    var instance: UartDfuBridgeService? = null
        set(value) {
            field = value
            if (value != null) {
                handler.post {
                    val actions = pendingActions.toList()
                    pendingActions.clear()
                    actions.forEach { it(value) }
                }
            }
        }
        
    fun execute(action: (UartDfuBridgeService) -> Unit) {
        val svc = instance
        if (svc != null) {
            action(svc)
        } else {
            pendingActions.add(action)
        }
    }
}
