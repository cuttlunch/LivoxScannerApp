package com.example.livoxscanner

import android.opengl.Matrix
import com.google.ar.core.Pose
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.math.abs

data class Point3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val reflectivity: Int
)

data class ColoredPoint3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val r: Int,
    val g: Int,
    val b: Int,
    val reflectivity: Int
)

class PoseFusionEngine(
    private val colorizer: CameraColorizer,
    private val voxelFilter: VoxelGridFilter,
    private val offsetX: Float = 0.0f,
    private val offsetY: Float = 0.06f,
    private val offsetZ: Float = -0.04f
) {
    private val poseHistory = ConcurrentSkipListMap<Long, Pose>()

    @Volatile var isTracking = false
    @Volatile var rollDeg: Float = 0f
        private set
    @Volatile var pitchDeg: Float = 0f
        private set
    @Volatile var yawDeg: Float = 0f
        private set

    private val boreSightMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    @Synchronized
    fun setBoreSightAngles(roll: Float, pitch: Float, yaw: Float) {
        rollDeg = roll; pitchDeg = pitch; yawDeg = yaw
        Matrix.setIdentityM(boreSightMatrix, 0)
        Matrix.rotateM(boreSightMatrix, 0, roll, 0f, 0f, 1f)
        Matrix.rotateM(boreSightMatrix, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(boreSightMatrix, 0, yaw, 0f, 1f, 0f)
    }

    fun recordPose(timestampNs: Long, pose: Pose) {
        poseHistory[timestampNs] = pose
        poseHistory.headMap(timestampNs - 1_000_000_000L).clear()
    }

    fun transformAndColorize(
        rawPoints: List<Point3D>,
        packetArrivalTimeNs: Long = System.nanoTime()
    ): List<ColoredPoint3D> {
        if (!isTracking || poseHistory.isEmpty()) return emptyList()
        val targetTimeNs = packetArrivalTimeNs - 2_000_000L
        val worldPose = getInterpolatedPose(targetTimeNs) ?: return emptyList()

        val rot = FloatArray(16)
        synchronized(this) { System.arraycopy(boreSightMatrix, 0, rot, 0, 16) }

        val acceptedPoints = ArrayList<ColoredPoint3D>()
        val localCamPoint = FloatArray(3)
        val worldPoint = FloatArray(3)

        for (p in rawPoints) {
            if (p.x < 0.8f || p.x > 50.0f) continue
            val rawCamX = -p.y
            val rawCamY =  p.z
            val rawCamZ = -p.x

            val calCamX = rot[0] * rawCamX + rot[4] * rawCamY + rot[8]  * rawCamZ + offsetX
            val calCamY = rot[1] * rawCamX + rot[5] * rawCamY + rot[9]  * rawCamZ + offsetY
            val calCamZ = rot[2] * rawCamX + rot[6] * rawCamY + rot[10] * rawCamZ + offsetZ

            localCamPoint[0] = calCamX; localCamPoint[1] = calCamY; localCamPoint[2] = calCamZ
            worldPose.transformPoint(localCamPoint, 0, worldPoint, 0)
            val wx = worldPoint[0]; val wy = worldPoint[1]; val wz = worldPoint[2]

            if (!voxelFilter.tryInsert(wx, wy, wz)) continue

            val packedRgb = colorizer.sampleRgb(calCamX, calCamY, calCamZ)
            val r: Int; val g: Int; val b: Int
            if (packedRgb != null) {
                r = (packedRgb shr 16) and 0xFF
                g = (packedRgb shr 8) and 0xFF
                b = packedRgb and 0xFF
            } else {
                val intensity = (p.reflectivity * 2).coerceIn(30, 220)
                r = intensity / 2; g = intensity; b = intensity
            }
            acceptedPoints.add(ColoredPoint3D(wx, wy, wz, r, g, b, p.reflectivity))
        }
        return acceptedPoints
    }

    private fun getInterpolatedPose(targetTimeNs: Long): Pose? {
        val floorEntry = poseHistory.floorEntry(targetTimeNs)
        val ceilingEntry = poseHistory.ceilingEntry(targetTimeNs)
        return when {
            floorEntry == null && ceilingEntry == null -> null
            floorEntry == null -> ceilingEntry.value
            ceilingEntry == null -> floorEntry.value
            floorEntry.key == ceilingEntry.key -> floorEntry.value
            else -> {
                val dt = (ceilingEntry.key - floorEntry.key).toFloat()
                if (abs(dt) < 1f) return floorEntry.value
                val fraction = ((targetTimeNs - floorEntry.key).toFloat() / dt).coerceIn(0f, 1f)
                Pose.makeInterpolated(floorEntry.value, ceilingEntry.value, fraction)
            }
        }
    }
}
