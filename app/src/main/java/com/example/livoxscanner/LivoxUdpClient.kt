package com.example.livoxscanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Point3D(val x: Float, val y: Float, val z: Float, val reflectivity: Int)

data class ColoredPoint3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val r: Int,
    val g: Int,
    val b: Int,
    val reflectivity: Int
)

class LivoxUdpClient(
    private val ethernetBinder: EthernetNetworkBinder,
    private val pointDataPort: Int = 60001,
    private val onPointsReceived: (List<Point3D>) -> Unit
) {
    private var isScanning = false
    private var dataSocket: DatagramSocket? = null

    fun startListening(scope: CoroutineScope) {
        if (isScanning) return
        isScanning = true
        scope.launch(Dispatchers.IO) {
            try {
                dataSocket = ethernetBinder.createBoundUdpSocket(pointDataPort).apply {
                    receiveBufferSize = 4 * 1024 * 1024
                }
                val buffer = ByteArray(1500)
                while (isActive && isScanning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    dataSocket?.receive(packet)
                    if (packet.length >= 1318) {
                        val points = parseCartesianPacket(packet.data, packet.length)
                        if (points.isNotEmpty()) onPointsReceived(points)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                dataSocket?.close()
            }
        }
    }

    private fun parseCartesianPacket(data: ByteArray, length: Int): List<Point3D> {
        val bb = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val version = bb.get().toInt() and 0xFF
        if (version != 5) return emptyList()
        bb.get(); bb.get(); bb.int; bb.get()
        val dataType = bb.get().toInt() and 0xFF
        bb.long
        if (dataType != 0) return emptyList()

        val points = ArrayList<Point3D>(100)
        for (i in 0 until 100) {
            val rawX = bb.int
            val rawY = bb.int
            val rawZ = bb.int
            val refl = bb.get().toInt() and 0xFF
            if (rawX != 0 || rawY != 0 || rawZ != 0) {
                // Divided by 1000f to convert millimeters to meters for OpenGL space
                points.add(Point3D(rawX / 1000f, rawY / 1000f, rawZ / 1000f, refl))
            }
        }
        return points
    }

    fun stopListening() {
        isScanning = false
        dataSocket?.close()
    }
}
