package com.example.livoxscanner

object LivoxCrc {
    private const val CRC16_SEED = 0x4C49
    private const val CRC32_SEED = 0x564F580A

    private val crc16Table = IntArray(256).apply {
        for (i in 0..255) {
            var crc = i
            for (j in 0..7) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
            }
            this[i] = crc and 0xFFFF
        }
    }

    private val crc32Table = IntArray(256).apply {
        for (i in 0..255) {
            var crc = i
            for (j in 0..7) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor -0x12477ce0 else crc ushr 1
            }
            this[i] = crc
        }
    }

    fun crc16(data: ByteArray, length: Int): Short {
        var crc = CRC16_SEED
        for (i in 0 until length) {
            val index = (crc xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = (crc ushr 8) xor crc16Table[index]
        }
        return crc.toShort()
    }

    fun crc32(data: ByteArray, length: Int): Int {
        var crc = CRC32_SEED xor -0x1
        for (i in 0 until length) {
            val index = (crc xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = (crc ushr 8) xor crc32Table[index]
        }
        return crc xor -0x1
    }
}
