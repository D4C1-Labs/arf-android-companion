package com.flipper.psadecrypt

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BLE GATT client for Flipper Zero serial service.
 * Supports two independent channels:
 *   - Custom data (fe65 RX / fe66 TX) for PSA BF offload
 *   - RPC serial  (fe61 RX / fe62 TX / fe63 overflow) for Protobuf RPC (file manager, etc.)
 */
@SuppressLint("MissingPermission")
class FlipperBleClient(private val context: Context) {
    companion object {
        private const val TAG = "FlipperBLE"
        private const val MAX_MTU = 512

        // Flipper serial service UUID (0xFE60)
        val SERIAL_SERVICE_UUID: UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")

        // Custom data characteristics for PSA offload
        val CUSTOM_DATA_RX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e65fe0000") // fe65 — write to Flipper
        val CUSTOM_DATA_TX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e66fe0000") // fe66 — notifications from Flipper

        // RPC serial characteristics (naming from phone's perspective)
        val RPC_DATA_TX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000") // fe62 — phone writes RPC to Flipper (write + write-no-response)
        val RPC_DATA_RX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000") // fe61 — Flipper pushes RPC responses to phone (indicate)
        val RPC_OVERFLOW_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e63fe0000") // fe63 — overflow control (notify)

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onDataReceived(data: ByteArray)
        fun onLog(msg: String) {}
    }

    /** Listener for RPC serial channel (fe61 indications, fe63 overflow) */
    interface RpcListener {
        fun onRpcDataReceived(data: ByteArray)
        fun onOverflowUpdate(remainingBuffer: Int)
    }

    var listener: Listener? = null
    var rpcListener: RpcListener? = null

    private var gatt: BluetoothGatt? = null
    private var customRxChar: BluetoothGattCharacteristic? = null // fe65
    private var customTxChar: BluetoothGattCharacteristic? = null // fe66
    private var rpcWriteChar: BluetoothGattCharacteristic? = null    // fe62 — phone writes RPC to Flipper
    private var rpcIndicateChar: BluetoothGattCharacteristic? = null // fe61 — Flipper sends RPC responses (indicate)
    private var rpcOverflowChar: BluetoothGattCharacteristic? = null // fe63
    private val handler = Handler(Looper.getMainLooper())

    /** Negotiated MTU (payload = mtu - 3 for GATT overhead) */
    var negotiatedMtu: Int = 23
        private set
    val maxWriteSize: Int get() = (negotiatedMtu - 3).coerceAtLeast(20)

    /** Overflow buffer: remaining bytes Flipper can accept on fe62 */
    @Volatile
    var rpcBufferRemaining: Int = 0

    /** Locally consume buffer after sending data (fe63 notifications set the absolute value) */
    fun consumeBuffer(bytes: Int) {
        rpcBufferRemaining = (rpcBufferRemaining - bytes).coerceAtLeast(0)
    }

    val isConnected: Boolean
        get() = gatt != null && customRxChar != null

    val isRpcAvailable: Boolean
        get() = gatt != null && rpcWriteChar != null && rpcIndicateChar != null

    // --- BLE write queue (Android allows only one outstanding write at a time) ---
    private data class WriteJob(val char: BluetoothGattCharacteristic, val data: ByteArray)
    private val writeQueue = ConcurrentLinkedQueue<WriteJob>()
    @Volatile
    private var writeInProgress = false

    private fun log(msg: String) {
        Log.d(TAG, msg)
        handler.post { listener?.onLog(msg) }
    }

    fun connect(device: BluetoothDevice) {
        log("Connecting to ${device.name} (${device.address})")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun refreshGattCache(gatt: BluetoothGatt) {
        try {
            val method = gatt.javaClass.getMethod("refresh")
            val result = method.invoke(gatt) as Boolean
            log("GATT cache refresh: $result")
        } catch (e: Exception) {
            log("GATT cache refresh failed: ${e.message}")
        }
    }

    fun disconnect() {
        val g = gatt ?: return
        gatt = null
        customRxChar = null
        customTxChar = null
        rpcWriteChar = null
        rpcIndicateChar = null
        rpcOverflowChar = null
        writeQueue.clear()
        writeInProgress = false
        rpcBufferRemaining = 0
        g.disconnect()
        g.close()
        handler.post { listener?.onDisconnected() }
    }

    /** Send data to Flipper via custom data RX characteristic (fe65) */
    fun send(data: ByteArray): Boolean {
        val char = customRxChar ?: return false
        return enqueueWrite(char, data)
    }

    /**
     * Send raw bytes to Flipper via RPC serial (fe62).
     * Automatically chunks data to fit MTU.
     */
    fun sendRpc(data: ByteArray): Boolean {
        val char = rpcWriteChar ?: return false
        val chunkSize = maxWriteSize
        var offset = 0
        while (offset < data.size) {
            val end = (offset + chunkSize).coerceAtMost(data.size)
            val chunk = data.copyOfRange(offset, end)
            if (!enqueueWrite(char, chunk)) return false
            offset = end
        }
        return true
    }

    private fun enqueueWrite(char: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val g = gatt ?: return false
        writeQueue.add(WriteJob(char, data))
        if (!writeInProgress) {
            drainWriteQueue(g)
        }
        return true
    }

    private fun drainWriteQueue(g: BluetoothGatt) {
        val job = writeQueue.poll()
        if (job == null) {
            writeInProgress = false
            return
        }
        writeInProgress = true
        job.char.value = job.data
        // Use WRITE_TYPE_DEFAULT (with response) when characteristic supports it,
        // so we get onCharacteristicWrite callback reliably for queue drain.
        val hasWrite = (job.char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        job.char.writeType = if (hasWrite) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val ok = g.writeCharacteristic(job.char)
        if (!ok) {
            log("writeCharacteristic failed, queue size=${writeQueue.size}")
            writeInProgress = false
        }
    }

    // --- Descriptor write queue (Android allows only one at a time) ---
    private val pendingDescriptorWrites = ConcurrentLinkedQueue<BluetoothGattDescriptor>()
    @Volatile
    private var descriptorWriteInProgress = false

    private fun enqueueDescriptorWrite(gatt: BluetoothGatt, desc: BluetoothGattDescriptor) {
        pendingDescriptorWrites.add(desc)
        if (!descriptorWriteInProgress) {
            drainDescriptorQueue(gatt)
        }
    }

    private fun drainDescriptorQueue(gatt: BluetoothGatt) {
        val desc = pendingDescriptorWrites.poll()
        if (desc == null) {
            descriptorWriteInProgress = false
            return
        }
        descriptorWriteInProgress = true
        val ok = gatt.writeDescriptor(desc)
        if (!ok) {
            log("writeDescriptor failed for ${desc.uuid}")
            descriptorWriteInProgress = false
            drainDescriptorQueue(gatt) // try next
        }
    }

    /**
     * Enable notifications or indications on a characteristic.
     * Automatically detects whether to use NOTIFY or INDICATE based on properties.
     */
    private fun enableNotificationsOrIndications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, name: String) {
        val localOk = gatt.setCharacteristicNotification(char, true)
        log("setCharacteristicNotification($name): $localOk")
        val desc = char.getDescriptor(CCCD_UUID)
        if (desc != null) {
            val props = char.properties
            val hasIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            val hasNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            desc.value = when {
                hasIndicate -> {
                    log("Enabling INDICATE on $name")
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                hasNotify -> {
                    log("Enabling NOTIFY on $name")
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                else -> {
                    log("WARNING: $name has neither notify nor indicate property")
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
            }
            enqueueDescriptorWrite(gatt, desc)
        } else {
            log("WARNING: CCCD descriptor not found on $name")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log("Connection state: status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected, requesting MTU $MAX_MTU")
                gatt.requestMtu(MAX_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected (status=$status)")
                this@FlipperBleClient.gatt = null
                customRxChar = null
                customTxChar = null
                rpcWriteChar = null
                rpcIndicateChar = null
                rpcOverflowChar = null
                writeQueue.clear()
                writeInProgress = false
                rpcBufferRemaining = 0
                handler.post { listener?.onDisconnected() }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                log("MTU negotiated: $mtu (max write: ${mtu - 3} bytes)")
            } else {
                log("MTU negotiation failed (status=$status), using default")
            }
            // Now clear cache and discover services
            refreshGattCache(gatt)
            Thread.sleep(200)
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status")
                return
            }

            // Log all discovered services for debugging
            for (svc in gatt.services) {
                log("  Service: ${svc.uuid}")
                for (ch in svc.characteristics) {
                    val descCount = ch.descriptors.size
                    log("    Char: ${ch.uuid} props=0x${Integer.toHexString(ch.properties)} descs=$descCount")
                }
            }

            val service = gatt.getService(SERIAL_SERVICE_UUID)
            if (service == null) {
                log("ERROR: Serial service not found (expected $SERIAL_SERVICE_UUID)")
                disconnect()
                return
            }

            log("Found serial service")

            // Discover PSA custom characteristics (fe65/fe66)
            customRxChar = service.getCharacteristic(CUSTOM_DATA_RX_UUID)
            customTxChar = service.getCharacteristic(CUSTOM_DATA_TX_UUID)

            if (customRxChar == null) log("WARNING: Custom data RX (fe65) not found")
            if (customTxChar == null) log("WARNING: Custom data TX (fe66) not found")

            // Discover RPC characteristics (fe61/fe62/fe63)
            rpcWriteChar = service.getCharacteristic(RPC_DATA_TX_UUID)   // fe62 — we write to this
            rpcIndicateChar = service.getCharacteristic(RPC_DATA_RX_UUID) // fe61 — indications from Flipper
            rpcOverflowChar = service.getCharacteristic(RPC_OVERFLOW_UUID)

            if (rpcWriteChar != null) log("Found RPC write (fe62)")
            else log("WARNING: RPC write (fe62) not found")
            if (rpcIndicateChar != null) log("Found RPC indicate (fe61)")
            else log("WARNING: RPC indicate (fe61) not found")
            if (rpcOverflowChar != null) log("Found RPC overflow (fe63)")
            else log("WARNING: RPC overflow (fe63) not found")

            // Enable notifications/indications on all characteristics that push data to us.
            // Descriptor writes are queued and executed sequentially.
            // The last descriptor write completion triggers onConnected.
            var subsToEnable = 0

            customTxChar?.let {
                subsToEnable++
                enableNotificationsOrIndications(gatt, it, "fe66")
            }
            rpcIndicateChar?.let {
                subsToEnable++
                enableNotificationsOrIndications(gatt, it, "fe61")
            }
            rpcOverflowChar?.let {
                subsToEnable++
                enableNotificationsOrIndications(gatt, it, "fe63")
            }

            if (subsToEnable == 0) {
                log("No notification/indication chars found, reporting connected anyway")
                handler.post { listener?.onConnected() }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val charUuid = descriptor.characteristic?.uuid
            log("onDescriptorWrite: char=$charUuid status=$status (${if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "FAIL"})")

            // Continue draining descriptor queue; report connected when queue is empty
            if (pendingDescriptorWrites.isEmpty()) {
                descriptorWriteInProgress = false
                // Read initial overflow buffer value
                rpcOverflowChar?.let { gatt.readCharacteristic(it) }
                log("All notifications enabled — ready")
                handler.post { listener?.onConnected() }
            } else {
                drainDescriptorQueue(gatt)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == RPC_OVERFLOW_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value
                if (data != null && data.size >= 4) {
                    rpcBufferRemaining = ByteBuffer.wrap(data).int
                    log("Initial RPC buffer: $rpcBufferRemaining bytes")
                    rpcListener?.onOverflowUpdate(rpcBufferRemaining)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Write failed on ${characteristic.uuid}, status=$status")
            }
            // Drain next write in queue
            drainWriteQueue(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            if (data.isEmpty()) return

            when (characteristic.uuid) {
                CUSTOM_DATA_TX_UUID -> {
                    handler.post { listener?.onDataReceived(data) }
                }
                RPC_DATA_RX_UUID -> { // fe61 — RPC indications from Flipper
                    rpcListener?.onRpcDataReceived(data)
                }
                RPC_OVERFLOW_UUID -> {
                    if (data.size >= 4) {
                        rpcBufferRemaining = ByteBuffer.wrap(data).int
                        rpcListener?.onOverflowUpdate(rpcBufferRemaining)
                    }
                }
            }
        }
    }
}
