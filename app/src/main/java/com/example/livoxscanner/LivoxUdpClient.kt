package com.example.livoxscanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LivoxUdpClient(
    private val ethernetBinder: EthernetNetworkBinder,
    private val pointDataPort: Int = 60001,
    private val onPointsReceived: (List<Point3D>) -> Unit
) {
    private var listenJob: Job? = null
    private var socket: DatagramSocket? = null

    fun startListening(scope: CoroutineScope) {
        if (listenJob?.isActive == true) return

        listenJob = scope.launch(Dispatchers.IO) {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(pointDataPort))
                    soTimeout = 2000
                }
                FileLogger.log("LivoxUdpClient listening on UDP port $pointDataPort")

                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    try {
                        socket?.receive(packet)
                        if (packet.length > 18) {
                            val parsed = parseLivoxPacket(packet.data, packet.length)
                            if (parsed.isNotEmpty()) {
                                onPointsReceived(parsed)
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Keep listening while active
                    }
                }
            } catch (e: Exception) {
                FileLogger.log("LivoxUdpClient error: ${e.message}")
            } finally {
                socket?.close()
                socket = null
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        socket?.close()
        socket = null
    }

    /**
     * Livox Mid-40 UDP Point Packet Structure (Protocol v1):
     * Bytes 0..17 (18 bytes total): Header
     *   - Byte 0: Version (1)
     *   - Byte 1: Slot ID
     *   - Byte 2: LiDAR ID
     *   - Byte 3: Reserved
     *   - Bytes 4..7: Status Code (uint32)
     *   - Byte 8: Timestamp Type
     *   - Byte 9: Data Type (0 = Cartesian 13B/pt, 2 = Extended Cartesian 14B/pt)
     *   - Bytes 10..17: Timestamp (uint64 nanoseconds)
     * Bytes 18..End: 100 Points
     */
    private fun parseLivoxPacket(data: ByteArray, length: Int): List<Point3D> {
        val bb = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val version = bb.get(0).toInt() and 0xFF
        if (version != 1) return emptyList()

        val dataType = bb.get(9).toInt() and 0xFF
        val headerSize = 18
        val pointStride = when (dataType) {
            0 -> 13 // Standard Cartesian: int32 x, int32 y, int32 z, uint8 reflectivity (1300 + 18 = 1318 bytes)
            2 -> 14 // Extended Cartesian: int32 x, int32 y, int32 z, uint8 reflectivity, uint8 tag (1400 + 18 = 1418 bytes)
            else -> {
                // Fallback auto-detection by packet length
                if (length >= 1418) 14 else 13
            }
        }

        val numPoints = (length - headerSize) / pointStride
        if (numPoints <= 0) return emptyList()

        val points = ArrayList<Point3D>(numPoints)

        for (i in 0 until numPoints) {
            val offset = headerSize + i * pointStride
            if (offset + 13 > length) break

            val rawXmm = bb.getInt(offset)
            val rawYmm = bb.getInt(offset + 4)
            val rawZmm = bb.getInt(offset + 8)
            val refl = bb.get(offset + 12).toInt() and 0xFF

            // Filter out (0,0,0) no-return points and extreme noise (> 150 meters)
            if (rawXmm == 0 && rawYmm == 0 && rawZmm == 0) continue

            val xMeters = rawXmm / 1000f
            val yMeters = rawYmm / 1000f
            val zMeters = rawZmm / 1000f

            // Reject corrupt or out-of-bounds readings (< 10cm or > 120m)
            val distSq = xMeters * xMeters + yMeters * yMeters + zMeters * zMeters
            if (distSq < 0.01f || distSq > 14400f) continue

            points.add(Point3D(xMeters, yMeters, zMeters, refl))
        }

        return points
    }
}
