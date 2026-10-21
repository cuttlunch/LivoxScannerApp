package com.example.livoxscanner

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class LivoxController(
    private val ethernetBinder: EthernetNetworkBinder,
    private val fallbackHostIp: String = "192.168.1.50",
    private val pointDataPort: Int = 60001,
    private val cmdPort: Int = 60002,
    private val imuPort: Int = 60003
) {
    private val seqCounter = AtomicInteger(0)
    private var controlJob: Job? = null
    private var cmdSocket: DatagramSocket? = null

    private fun buildFrame(
        packetType: Byte = 0x00,
        cmdSet: Byte,
        cmdId: Byte,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val totalLength = 9 + 2 + payload.size + 4
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0xAA.toByte())
        buffer.put(0x01.toByte())
        buffer.putShort(totalLength.toShort())
        buffer.put(packetType)
        buffer.putShort((seqCounter.getAndIncrement() and 0xFFFF).toShort())

        val headerBytes = buffer.array()
        buffer.putShort(LivoxCrc.crc16(headerBytes, 7))
        buffer.put(cmdSet)
        buffer.put(cmdId)
        if (payload.isNotEmpty()) buffer.put(payload)
        buffer.putInt(LivoxCrc.crc32(headerBytes, totalLength - 4))
        return headerBytes
    }

    fun connectAndStartLaser(scope: CoroutineScope, knownLidarIp: String? = null) {
        controlJob?.cancel()
        controlJob = scope.launch(Dispatchers.IO) {
            try {
                val network = ethernetBinder.ethernetNetwork.value ?: return@launch
                val hostIp = ethernetBinder.localEthernetIp.value ?: fallbackHostIp
                val lidarAddress = if (knownLidarIp != null) {
                    network.getByName(knownLidarIp)
                } else {
                    discoverLidarAddress()
                }

                cmdSocket = ethernetBinder.createBoundUdpSocket(cmdPort).apply { soTimeout = 1000 }

                val ipParts = hostIp.split(".").map { it.toInt().toByte() }
                val handshakePayload = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put(ipParts[0]); put(ipParts[1]); put(ipParts[2]); put(ipParts[3])
                    putShort(pointDataPort.toShort())
                    putShort(cmdPort.toShort())
                    putShort(imuPort.toShort())
                }.array()

                sendPacket(buildFrame(0x00, 0x00, 0x01, handshakePayload), lidarAddress)
                delay(150)
                sendPacket(buildFrame(0x00, 0x00, 0x04, byteArrayOf(0x01)), lidarAddress)

                while (isActive) {
                    delay(900)
                    sendPacket(buildFrame(0x00, 0x00, 0x03), lidarAddress)
                }
            } catch (e: Exception) {
                Log.e("LivoxController", "Control error: ${e.message}", e)
            } finally {
                cmdSocket?.close()
            }
        }
    }

    private fun discoverLidarAddress(): InetAddress {
        ethernetBinder.createBoundUdpSocket(55000).use { broadcastSocket ->
            val buf = ByteArray(256)
            val packet = DatagramPacket(buf, buf.size)
            while (true) {
                broadcastSocket.receive(packet)
                if (packet.length >= 15 &&
                    packet.data[0] == 0xAA.toByte() &&
                    packet.data[9] == 0x00.toByte() &&
                    packet.data[10] == 0x00.toByte()
                ) {
                    return packet.address
                }
            }
        }
    }

    private fun sendPacket(frame: ByteArray, address: InetAddress) {
        cmdSocket?.send(DatagramPacket(frame, frame.size, address, 65000))
    }
}
