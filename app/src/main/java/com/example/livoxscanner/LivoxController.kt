fun connectAndStartLaser(scope: CoroutineScope, knownLidarIp: String? = null) {
    controlJob?.cancel()
    controlJob = scope.launch(Dispatchers.IO) {
        try {
            val network = ethernetBinder.ethernetNetwork.value
            
            // DYNAMICALLY FETCH THE PHONE'S REAL ACTIVE IP (Wi-Fi or Ethernet)
            val hostIp = getActiveLocalIp() ?: fallbackHostIp
            
            val lidarAddress = if (knownLidarIp != null) {
                if (network != null) network.getByName(knownLidarIp) else InetAddress.getByName(knownLidarIp)
            } else {
                discoverLidarAddress(network)
            }

            cmdSocket = ethernetBinder.createBoundUdpSocket(cmdPort).apply { soTimeout = 1000 }

            val ipParts = hostIp.split(".").map { it.toInt().toByte() }
            val handshakePayload = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(ipParts[0]); put(ipParts[1]); put(ipParts[2]); put(ipParts[3]) // Tells LiDAR to stream to your PHONE'S actual IP
                putShort(pointDataPort.toShort())
                putShort(cmdPort.toShort())
                putShort(imuPort.toShort())
            }.array()

            FileLogger.log("Connecting to LiDAR at ${lidarAddress.hostAddress} using actual host IP $hostIp")
            sendPacket(buildFrame(0x00, 0x00, 0x01, handshakePayload), lidarAddress)
            delay(150)
            sendPacket(buildFrame(0x00, 0x00, 0x04, byteArrayOf(0x01)), lidarAddress)

            while (isActive) {
                delay(900)
                sendPacket(buildFrame(0x00, 0x00, 0x03), lidarAddress)
            }
        } catch (e: Exception) {
            FileLogger.log("Livox Control error: ${e.message}")
        } finally {
            cmdSocket?.close()
        }
    }
}

// Helper to fetch the phone's genuine active IPv4 address on the local network
private fun getActiveLocalIp(): String? {
    try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (ni in interfaces) {
            if (!ni.isUp || ni.isLoopback) continue
            for (addr in Collections.list(ni.inetAddresses)) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: continue
                    if (!host.startsWith("169.254.")) { // Skip link-local
                        return host
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return null
}
