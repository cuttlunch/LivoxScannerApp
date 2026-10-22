package com.example.livoxscanner

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArPointCloudRenderer(
    private val fusionEngine: PoseFusionEngine,
    private val colorizer: CameraColorizer
) : GLSurfaceView.Renderer {

    var arSession: Session? = null
    private var cameraTextureId = -1
    private var frameCounter = 0

    @Volatile var displayRotation: Int = Surface.ROTATION_0
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var lastAppliedRotation = -1
    private var lastAppliedWidth = -1
    private var lastAppliedHeight = -1

    private val maxPoints = 1_500_000
    private val pointBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(maxPoints * 6 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    @Volatile var pointCount = 0
        private set

    // Latest ARCore camera pose so incoming LiDAR points can be anchored in 3D World Space
    @Volatile var latestCameraPose: Pose? = null
        private set

    val recordedColoredPoints = CopyOnWriteArrayList<ColoredPoint3D>()

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val vPMatrix = FloatArray(16)

    @Volatile var followArCamera = true
    @Volatile var orbitAngleX = 20f
    @Volatile var orbitAngleY = 0f
    @Volatile var orbitZoom = -6f
    @Volatile var pointSize = 8.0f

    private var pointProgram = 0
    private var bgProgram = 0

    // Normalized Device Coordinates (NDC) for full-screen quad
    private val quadCoords: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
            ))
            position(0)
        }

    // Dynamically transformed UV coordinates populated by ARCore
    private val transformedTexCoords: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
            ))
            position(0)
        }

    private val bgVertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val bgFragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform float uPointSize;
        attribute vec3 aPosition;
        attribute vec3 aColor;
        varying vec3 vColor;
        void main() {
            gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            gl_PointSize = uPointSize;
            vColor = aColor;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec3 vColor;
        void main() {
            gl_FragColor = vec4(vColor, 1.0);
        }
    """.trimIndent()

    /**
     * Transforms a point from local ARCore Camera Space (-Z forward, +X right, +Y up)
     * into ARCore World Space if tracking is active, or returns it directly if not tracking.
     */
    fun transformCameraToWorld(camX: Float, camY: Float, camZ: Float): FloatArray {
        val pose = latestCameraPose
        return if (followArCamera && fusionEngine.isTracking && pose != null) {
            pose.transformPoint(floatArrayOf(camX, camY, camZ))
        } else {
            floatArrayOf(camX, camY, camZ)
        }
    }

    @Synchronized
    fun addColoredWorldPoints(points: List<ColoredPoint3D>) {
        for (p in points) {
            if (pointCount < maxPoints) {
                val base = pointCount * 6
                pointBuffer.put(base, p.x)
                pointBuffer.put(base + 1, p.y)
                pointBuffer.put(base + 2, p.z)
                pointBuffer.put(base + 3, p.r / 255f)
                pointBuffer.put(base + 4, p.g / 255f)
                pointBuffer.put(base + 5, p.b / 255f)
                pointCount++
            }
        }
    }

    @Synchronized
    fun clearPoints() {
        pointCount = 0
        pointBuffer.clear()
        recordedColoredPoints.clear()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.08f, 1.0f)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val bgVShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, bgVertexShaderCode); GLES20.glCompileShader(it)
        }
        val bgFShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, bgFragmentShaderCode); GLES20.glCompileShader(it)
        }
        bgProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, bgVShader); GLES20.glAttachShader(it, bgFShader); GLES20.glLinkProgram(it)
        }

        val vShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, vertexShaderCode); GLES20.glCompileShader(it)
        }
        val fShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, fragmentShaderCode); GLES20.glCompileShader(it)
        }
        pointProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vShader); GLES20.glAttachShader(it, fShader); GLES20.glLinkProgram(it)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        val ratio = width.toFloat() / height.toFloat()
        Matrix.frustumM(projectionMatrix, 0, -ratio * 0.1f, ratio * 0.1f, -0.1f, 0.1f, 0.1f, 200f)
    }

    @Synchronized
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (followArCamera) {
            val session = arSession
            if (session != null) {
                if (cameraTextureId != -1) session.setCameraTextureName(cameraTextureId)

                if (viewportWidth > 0 && viewportHeight > 0) {
                    if (displayRotation != lastAppliedRotation ||
                        viewportWidth != lastAppliedWidth ||
                        viewportHeight != lastAppliedHeight
                    ) {
                        session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
                        lastAppliedRotation = displayRotation
                        lastAppliedWidth = viewportWidth
                        lastAppliedHeight = viewportHeight
                    }
                }

                try {
                    val frame = session.update()
                    val camera = frame.camera

                    if (frame.hasDisplayGeometryChanged()) {
                        quadCoords.position(0)
                        transformedTexCoords.position(0)
                        frame.transformCoordinates2d(
                            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                            quadCoords,
                            Coordinates2d.TEXTURE_NORMALIZED,
                            transformedTexCoords
                        )
                    }

                    // 1. Draw Upright Camera Feed
                    GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                    GLES20.glUseProgram(bgProgram)
                    val bgPosLoc = GLES20.glGetAttribLocation(bgProgram, "aPosition")
                    val bgTexLoc = GLES20.glGetAttribLocation(bgProgram, "aTexCoord")

                    quadCoords.position(0)
                    GLES20.glEnableVertexAttribArray(bgPosLoc)
                    GLES20.glVertexAttribPointer(bgPosLoc, 2, GLES20.GL_FLOAT, false, 0, quadCoords)

                    transformedTexCoords.position(0)
                    GLES20.glEnableVertexAttribArray(bgTexLoc)
                    GLES20.glVertexAttribPointer(bgTexLoc, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoords)

                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(bgProgram, "sTexture"), 0)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glDisableVertexAttribArray(bgPosLoc)
                    GLES20.glDisableVertexAttribArray(bgTexLoc)
                    GLES20.glEnable(GLES20.GL_DEPTH_TEST)

                    if (camera.trackingState == TrackingState.TRACKING) {
                        fusionEngine.isTracking = true
                        latestCameraPose = camera.pose
                        fusionEngine.recordPose(System.nanoTime(), camera.pose)
                        if (frameCounter++ % 2 == 0) {
                            try {
                                frame.acquireCameraImage().use { image ->
                                    colorizer.updateFromArFrame(image, camera.imageIntrinsics)
                                }
                            } catch (_: Exception) {}
                        }
                        camera.getViewMatrix(viewMatrix, 0)
                        camera.getProjectionMatrix(projectionMatrix, 0, 0.05f, 150.0f)
                    } else {
                        fusionEngine.isTracking = false
                        // Fallback camera view matrix when ARCore is still in WAIT state
                        // so you can STILL see live LiDAR points in front of you immediately!
                        Matrix.setIdentityM(viewMatrix, 0)
                        if (viewportWidth > 0 && viewportHeight > 0) {
                            val ratio = viewportWidth.toFloat() / viewportHeight.toFloat()
                            Matrix.perspectiveM(projectionMatrix, 0, 60f, ratio, 0.05f, 150f)
                        }
                    }
                } catch (_: Exception) {
                    // Ignore transient session pause exceptions
                }
            }
        } else {
            // 3D Orbit Mode (Camera feed is off, pure 3D inspection)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            if (viewportWidth > 0 && viewportHeight > 0) {
                val ratio = viewportWidth.toFloat() / viewportHeight.toFloat()
                Matrix.perspectiveM(projectionMatrix, 0, 60f, ratio, 0.05f, 250f)
            }
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, orbitZoom, 0f, 0f, 0f, 0f, 1f, 0f)
            val rot = FloatArray(16)
            Matrix.setIdentityM(rot, 0)
            Matrix.rotateM(rot, 0, orbitAngleX, 1f, 0f, 0f)
            Matrix.rotateM(rot, 0, orbitAngleY, 0f, 1f, 0f)
            val temp = FloatArray(16)
            Matrix.multiplyMM(temp, 0, viewMatrix, 0, rot, 0)
            System.arraycopy(temp, 0, viewMatrix, 0, 16)
        }

        Matrix.multiplyMM(vPMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // 2. Draw Point Cloud Overlay
        if (pointCount > 0) {
            GLES20.glUseProgram(pointProgram)
            val strideBytes = 6 * 4

            val posHandle = GLES20.glGetAttribLocation(pointProgram, "aPosition")
            GLES20.glEnableVertexAttribArray(posHandle)
            pointBuffer.position(0)
            GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, strideBytes, pointBuffer)

            val colorHandle = GLES20.glGetAttribLocation(pointProgram, "aColor")
            GLES20.glEnableVertexAttribArray(colorHandle)
            pointBuffer.position(3)
            GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, strideBytes, pointBuffer)

            val mvpHandle = GLES20.glGetUniformLocation(pointProgram, "uMVPMatrix")
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, vPMatrix, 0)

            val sizeHandle = GLES20.glGetUniformLocation(pointProgram, "uPointSize")
            GLES20.glUniform1f(sizeHandle, pointSize)

            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)

            GLES20.glDisableVertexAttribArray(posHandle)
            GLES20.glDisableVertexAttribArray(colorHandle)
        }
    }
}
