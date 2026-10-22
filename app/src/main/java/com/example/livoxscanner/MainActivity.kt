package com.example.livoxscanner

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: ArPointCloudRenderer
    private lateinit var poseFusionEngine: PoseFusionEngine
    private lateinit var colorizer: CameraColorizer
    private lateinit var voxelFilter: VoxelGridFilter

    private var arSession: Session? = null
    private val isScanning = AtomicBoolean(false)
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var ethernetBinder: EthernetNetworkBinder
    private lateinit var livoxController: LivoxController
    private lateinit var udpClient: LivoxUdpClient

    private lateinit var statusText: TextView
    private lateinit var btnScanToggle: Button
    private lateinit var btnOrbitToggle: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private var ethIp: String = "Disconnected"

    // Master thread-safe collection of all captured points for export
    private val accumulatedPoints = ConcurrentLinkedQueue<ColoredPoint3D>()

    private var prevTouchX = 0f
    private var prevTouchY = 0f

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FileLogger.init(this)
        FileLogger.log("=== MainActivity Started ===")

        colorizer = CameraColorizer()
        voxelFilter = VoxelGridFilter()
        poseFusionEngine = PoseFusionEngine(colorizer, voxelFilter)
        renderer = ArPointCloudRenderer(poseFusionEngine, colorizer)

        ethernetBinder = EthernetNetworkBinder(this)
        livoxController = LivoxController(ethernetBinder)

        udpClient = LivoxUdpClient(
            ethernetBinder = ethernetBinder,
            pointDataPort = 60001,
            onPointsReceived = { pointList ->
                try {
                    val coloredList = pointList.map { p ->
                        ColoredPoint3D(p.x, p.y, p.z, 0, 255, 0, p.reflectivity)
                    }
                    // Accumulate points for export
                    accumulatedPoints.addAll(coloredList)
                    renderer.addColoredWorldPoints(coloredList)
                } catch (e: Exception) {
                    FileLogger.log("Point mapping error: ${e.message}")
                }
            }
        )

        buildProgrammaticUI()

        if (checkCameraPermission()) {
            setupARSession()
        } else {
            requestCameraPermission()
        }

        ethernetBinder.startMonitoring()
        startStatusLoop()
    }

    private fun buildProgrammaticUI() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        glSurfaceView = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (!renderer.followArCamera) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            prevTouchX = event.x
                            prevTouchY = event.y
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.x - prevTouchX
                            val dy = event.y - prevTouchY
                            renderer.orbitAngleY += dx * 0.4f
                            renderer.orbitAngleX = (renderer.orbitAngleX + dy * 0.4f).coerceIn(-85f, 85f)
                            prevTouchX = event.x
                            prevTouchY = event.y
                        }
                    }
                    return true
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        root.addView(
            glSurfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusText = TextView(this).apply {
            text = "• Net: Disconnected  |  AR: WAIT  |  0.0k voxels"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(40, 22, 40, 22)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1A1A24"))
                cornerRadius = 50f
            }
        }
        val topParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 48
        }
        root.addView(statusText, topParams)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 32, 16, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D912121A"))
                cornerRadius = 36f
            }
        }

        btnScanToggle = createStyledButton("Start Scan", "#2E7D32") {
            toggleScan()
        }

        btnOrbitToggle = createStyledButton("3D Orbit", "#1565C0") {
            renderer.followArCamera = !renderer.followArCamera
            val mode = if (renderer.followArCamera) "AR View" else "3D Orbit"
            FileLogger.log("Camera mode switched to: $mode")
            Toast.makeText(this, "Mode: $mode", Toast.LENGTH_SHORT).show()
        }

        val btnExport = createStyledButton("Export", "#E65100") {
            exportPointCloudsToPly()
        }

        val btnClear = createStyledButton("Clear", "#455A64") {
            accumulatedPoints.clear()
            renderer.clearPoints()
            FileLogger.log("Cleared point cloud buffer.")
            Toast.makeText(this, "Cleared points", Toast.LENGTH_SHORT).show()
        }

        bottomBar.addView(btnScanToggle)
        bottomBar.addView(btnOrbitToggle)
        bottomBar.addView(btnExport)
        bottomBar.addView(btnClear)

        val bottomParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(24, 0, 24, 36)
        }
        root.addView(bottomBar, bottomParams)

        setContentView(root)
    }

    private fun createStyledButton(
        label: String,
        hexColor: String,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = 28f
            }
            val params = LinearLayout.LayoutParams(0, 130, 1f).apply {
                setMargins(6, 0, 6, 0)
            }
            layoutParams = params
            setOnClickListener { onClick() }
        }
    }

    private fun updateButtonColor(button: Button, hexColor: String) {
        button.background = GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = 28f
        }
    }

    private fun toggleScan() {
        val nowScanning = !isScanning.get()
        isScanning.set(nowScanning)

        if (nowScanning) {
            btnScanToggle.text = "Stop Scan"
            updateButtonColor(btnScanToggle, "#C62828")
            FileLogger.log("SCAN STARTED.")

            udpClient.startListening(activityScope)
            livoxController.connectAndStartLaser(activityScope)

            Toast.makeText(this, "Livox scanning started", Toast.LENGTH_SHORT).show()
        } else {
            btnScanToggle.text = "Start Scan"
            updateButtonColor(btnScanToggle, "#2E7D32")
            FileLogger.log("SCAN STOPPED. Total Voxels=${renderer.pointCount}")

            udpClient.stopListening()

            Toast.makeText(this, "Livox scanning stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportPointCloudsToPly() {
        if (accumulatedPoints.isEmpty()) {
            Toast.makeText(this, "No points captured to export!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileName = "LivoxScan_${System.currentTimeMillis()}.ply"
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/LivoxScans")
            }

            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            if (uri == null) {
                Toast.makeText(this, "Failed to create file in Documents", Toast.LENGTH_SHORT).show()
                return
            }

            resolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write("ply\n")
                    writer.write("format ascii 1.0\n")
                    writer.write("element vertex ${accumulatedPoints.size}\n")
                    writer.write("property float x\n")
                    writer.write("property float y\n")
                    writer.write("property float z\n")
                    writer.write("property uchar red\n")
                    writer.write("property uchar green\n")
                    writer.write("property uchar blue\n")
                    writer.write("end_header\n")

                    for (p in accumulatedPoints) {
                        writer.write("${p.x} ${p.y} ${p.z} ${p.r} ${p.g} ${p.b}\n")
                    }
                }
            }

            FileLogger.log("Successfully exported ${accumulatedPoints.size} points to Documents/LivoxScans/$fileName")
            Toast.makeText(this, "Saved to Documents/LivoxScans/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            FileLogger.log("Export failed: ${e.message}")
            Toast.makeText(this, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startStatusLoop() {
        val runnable = object : Runnable {
            override fun run() {
                @Suppress("DEPRECATION")
                renderer.displayRotation = windowManager.defaultDisplay.rotation
                checkNetworkInterfaces()
                val arState = if (poseFusionEngine.isTracking) "TRACKING" else "WAIT"
                val kVoxels = String.format("%.1fk", renderer.pointCount / 1000f)
                statusText.text = "• Net: $ethIp  |  AR: $arState  |  $kVoxels voxels"
                uiHandler.postDelayed(this, 500)
            }
        }
        uiHandler.post(runnable)
    }

    private fun checkNetworkInterfaces() {
        try {
            var activeIp: String? = null
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in Collections.list(ni.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("169.254.")) {
                            activeIp = host
                            break
                        }
                    }
                }
            }
            ethIp = activeIp ?: "Connected"
        } catch (e: Exception) {
            ethIp = "Connected"
        }
    }

    private fun setupARSession() {
        try {
            val session = Session(this)
            val config = Config(session).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
            }
            session.configure(config)
            arSession = session
            renderer.arSession = session
            FileLogger.log("ARCore Session configured and attached to renderer.")
        } catch (e: Exception) {
            FileLogger.log("ARCore setup error: ${e.javaClass.simpleName} - ${e.message}")
            Toast.makeText(this, "ARCore Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                FileLogger.log("Camera permission granted by user.")
                setupARSession()
            } else {
                FileLogger.log("Camera permission DENIED by user.")
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            arSession?.resume()
            glSurfaceView.onResume()
            FileLogger.log("onResume: ARSession & GLSurfaceView resumed.")
        } catch (e: CameraNotAvailableException) {
            FileLogger.log("onResume CameraNotAvailableException: ${e.message}")
            arSession = null
        }
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        arSession?.pause()
        FileLogger.log("onPause: paused.")
    }

    override fun onDestroy() {
        super.onDestroy()
        ethernetBinder.stopMonitoring()
    }
}
