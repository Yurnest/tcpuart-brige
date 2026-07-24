package com.jkdev.uartdfubridge.ui

import android.hardware.usb.UsbDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jkdev.uartdfubridge.bridge.UsbSessionManager
import com.jkdev.uartdfubridge.databinding.ItemDeviceBinding

class DeviceAdapter(
    private val onDeviceSelected: (UsbDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private var devices: List<UsbDevice> = emptyList()
    private var selectedDevice: UsbDevice? = null

    inner class ViewHolder(val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.binding.tvDeviceName.text = device.productName ?: "Unknown Device"
        
        val type = if (device.vendorId == UsbSessionManager.VID_ARDUINO &&
            device.productId == UsbSessionManager.PID_R4_DFU) "DFU Mode" else "Serial Mode"
            
        holder.binding.tvDeviceDesc.text = "VID:0x%04X PID:0x%04X — %s".format(device.vendorId, device.productId, type)
        
        holder.binding.ivSelected.visibility = if (device == selectedDevice) View.VISIBLE else View.INVISIBLE
        
        holder.itemView.setOnClickListener { 
            selectedDevice = device
            notifyDataSetChanged()
            onDeviceSelected(device) 
        }
    }

    override fun getItemCount(): Int = devices.size

    fun submitList(newDevices: List<UsbDevice>) {
        devices = newDevices
        if (selectedDevice != null && !devices.contains(selectedDevice)) {
            selectedDevice = null
        }
        notifyDataSetChanged()
    }
}
