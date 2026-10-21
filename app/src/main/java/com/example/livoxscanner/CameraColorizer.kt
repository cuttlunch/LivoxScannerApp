package com.example.livoxscanner

import android.media.Image
import com.google.ar.core.CameraIntrinsics
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class CameraColorizer {
    private class YuvSnapshot(
        val width: Int, val height: Int,
        val fx: Float, val fy: Float, val cx: Float, val cy: Float,
        val yBuffer: ByteArray, val uBuffer: ByteArray, val vBuffer: ByteArray,
        val yRowStride: Int, val uvRowStride: Int, val uvPixelStride: Int
    )

    private val latestSnapshot = AtomicReference<YuvSnapshot?>(null)

    fun updateFromArFrame(image: Image, intrinsics: CameraIntrinsics) {
        val focalLength = intrinsics.focalLength
        val principalPoint = intrinsics.principalPoint
        val dims = intrinsics.imageDimensions
        val scaleX = image.width.toFloat() / dims[0].toFloat()
        val scaleY = image.height.toFloat() / dims[1].toFloat()

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBytes = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
        val uBytes = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
        val vBytes = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }

        latestSnapshot.set(
            YuvSnapshot(
                image.width, image.height,
                focalLength[0] * scaleX, focalLength[1] * scaleY,
                principalPoint[0] * scaleX, principalPoint[1] * scaleY,
                yBytes, uBytes, vBytes,
                yPlane.rowStride, uPlane.rowStride, uPlane.pixelStride
            )
        )
    }

    fun sampleRgb(camX: Float, camY: Float, camZ: Float): Int? {
        val snap = latestSnapshot.get() ?: return null
        val depth = -camZ
        if (depth <= 0.4f) return null

        val u = (snap.fx * (camX / depth) + snap.cx).roundToInt()
        val v = (snap.fy * (-camY / depth) + snap.cy).roundToInt()
        if (u !in 0 until snap.width || v !in 0 until snap.height) return null

        val yIdx = v * snap.yRowStride + u
        val uvIdx = (v / 2) * snap.uvRowStride + (u / 2) * snap.uvPixelStride
        if (yIdx >= snap.yBuffer.size || uvIdx >= snap.uBuffer.size || uvIdx >= snap.vBuffer.size) return null

        val y = (snap.yBuffer[yIdx].toInt() and 0xFF) - 16
        val uVal = (snap.uBuffer[uvIdx].toInt() and 0xFF) - 128
        val vVal = (snap.vBuffer[uvIdx].toInt() and 0xFF) - 128

        val y1192 = 1192 * y.coerceAtLeast(0)
        val r = ((y1192 + 1634 * vVal) shr 10).coerceIn(0, 255)
        val g = ((y1192 - 833 * vVal - 400 * uVal) shr 10).coerceIn(0, 255)
        val b = ((y1192 + 2066 * uVal) shr 10).coerceIn(0, 255)

        return (r shl 16) or (g shl 8) or b
    }
}
