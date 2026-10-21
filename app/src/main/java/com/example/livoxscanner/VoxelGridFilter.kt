package com.example.livoxscanner

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

class VoxelGridFilter(private val voxelSizeMeters: Float = 0.02f) {
    private val occupiedVoxels = ConcurrentHashMap.newKeySet<Long>(500_000)

    fun tryInsert(worldX: Float, worldY: Float, worldZ: Float): Boolean {
        val ix = floor(worldX / voxelSizeMeters).toLong() and 0x1FFFFFL
        val iy = floor(worldY / voxelSizeMeters).toLong() and 0x1FFFFFL
        val iz = floor(worldZ / voxelSizeMeters).toLong() and 0x1FFFFFL
        val voxelKey = (ix shl 42) or (iy shl 21) or iz
        return occupiedVoxels.add(voxelKey)
    }

    fun clear() {
        occupiedVoxels.clear()
    }
}
