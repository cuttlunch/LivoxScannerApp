package com.example.livoxscanner

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.StringTokenizer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    private lateinit var zoomControlsLayout: LinearLayout

    private val uiHandler = Handler(Looper.getMainLooper())
    private var ethIp: String = "Disconnected"

    private val accumulatedPoints = ConcurrentLinkedQueue<ColoredPoint3D>()

    private var calibYawDeg = 0f
    private var calibOffsetX = 0f
    private var calibOffsetY = -0.08f // Default 8cm vertical offset (Phone mounted above Livox)
    private var calibOffsetZ = 0f

    @Volatile
    private var lastRawSample: Point3D? = null

    private var prevTouchX = 0f
    private var prevTouchY = 0f

    private val openPlyLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadPlyFileFromUri(it) }
    }

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
                    val yawRad = Math.toRadians(calibYawDeg.toDouble()).toFloat()
                    val cosY = cos(yawRad)
                    val sinY = sin(yawRad)

                    val coloredList = ArrayList<ColoredPoint3D>(pointList.size)
                    for (p in pointList) {
                        // Auto-guard: if LivoxUdpClient ever passes raw mm (> 80f), convert to meters
                        val sx = if (abs(p.x) > 80f || abs(p.y) > 80f || abs(p.z) > 80f) p.x / 1000f else p.x
                        val sy = if (abs(p.x) > 80f || abs(p.y) > 80f || abs(p.z) > 80f) p.y / 1000f else p.y
                        val sz = if (abs(p.x) > 80f || abs(p.y) > 80f || abs(p.z) > 80f) p.z / 1000f else p.z

                        // Reject dead origin returns (< 10cm from sensor)
                        val distSq = sx * sx + sy * sy + sz * sz
                        if (distSq < 0.01f) continue

                        lastRawSample = Point3D(sx, sy, sz, p.reflectivity)

                        // 1. Convert Livox (+X forward, +Y left, +Z up)
                        //    to OpenGL/ARCore Camera (-Z forward, +X right, +Y up)
                        val camX = -sy + calibOffsetX
                        val camY = sz + calibOffsetY
                        val camZ = -sx + calibOffsetZ

                        // 2. Apply Calibration Yaw rotation
                        val rotX = camX * cosY - camZ * sinY
                        val rotY = camY
                        val rotZ = camX * sinY + camZ * cosY

                        // 3. Sample live camera RGB (or fallback to reflectivity heatmap)
                        val (r, g, b) = renderer.sampleCameraRgb(rotX, rotY, rotZ)
                            ?: reflectivityToRgb(p.reflectivity)

                        // 4. Transform into ARCore World Space when tracking is active
                        val worldPt = renderer.transformCameraToWorld(rotX, rotY, rotZ)

                        coloredList.add(
                            ColoredPoint3D(
                                worldPt[0],
                                worldPt[1],
                                worldPt[2],
                                r,
                                g,
                                b,
                                p.reflectivity
                            )
                        )
                    }

                    if (coloredList.isNotEmpty()) {
                        accumulatedPoints.addAll(coloredList)
                        renderer.addColoredWorldPoints(coloredList)
                    }
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

    private fun reflectivityToRgb(refl: Int): Triple<Int, Int, Int> {
        val v = refl.coerceIn(0, 255)
        return when {
            v < 64 -> Triple(0, v * 4, 255)
            v < 128 -> Triple(0, 255, 255 - (v - 64) * 4)
            v < 192 -> Triple((v - 128) * 4, 255, 0)
            else -> Triple(255, 255 - (v - 192) * 4, 0)
        }
    }

    private fun buildProgrammaticUI() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!renderer.followArCamera) {
                    renderer.orbitZoom = (renderer.orbitZoom / detector.scaleFactor).coerceIn(-500f, -0.2f)
                    return true
                }
                return false
            }
        })

        glSurfaceView = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                scaleDetector.onTouchEvent(event)
                if (!renderer.followArCamera && !scaleDetector.isInProgress) {
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
            text = "• Net: Disconnected  |  AR: WAIT  |  0.0k pts"
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

        // Right-side Floating Zoom Controls (+ / Fit / −)
        zoomControlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(12, 16, 12, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1A1A24"))
                cornerRadius = 32f
            }
        }

        val btnZoomIn = createZoomButton("+") {
            renderer.orbitZoom = (renderer.orbitZoom * 0.65f).coerceIn(-500f, -0.2f)
            Toast.makeText(this, "Zoom In (${String.format("%.1f", -renderer.orbitZoom)}m)", Toast.LENGTH_SHORT).show()
        }
        val btnZoomReset = createZoomButton("Fit") {
            centerAndFitCloudInOrbit()
        }
        val btnZoomOut = createZoomButton("−") {
            renderer.orbitZoom = (renderer.orbitZoom * 1.45f).coerceIn(-500f, -0.2f)
            Toast.makeText(this, "Zoom Out (${String.format("%.1f", -renderer.orbitZoom)}m)", Toast.LENGTH_SHORT).show()
        }

        zoomControlsLayout.addView(btnZoomIn)
        zoomControlsLayout.addView(btnZoomReset)
        zoomControlsLayout.addView(btnZoomOut)

        val zoomParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = 28
        }
        root.addView(zoomControlsLayout, zoomParams)

        val horizontalScroll = HorizontalScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 24, 12, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D912121A"))
                cornerRadius = 36f
            }
        }

        btnScanToggle = createScrollableButton("Scan", "#2E7D32") {
            toggleScan()
        }

        btnOrbitToggle = createScrollableButton("3D Orbit", "#1565C0") {
            renderer.followArCamera = !renderer.followArCamera
            if (renderer.followArCamera) {
                try {
                    arSession?.resume()
                    btnOrbitToggle.text = "3D Orbit"
                    updateButtonColor(btnOrbitToggle, "#1565C0")
                    Toast.makeText(this, "Mode: AR Live View", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    FileLogger.log("Failed to resume AR session: ${e.message}")
                }
            } else {
                arSession?.pause()
                btnOrbitToggle.text = "AR View"
                updateButtonColor(btnOrbitToggle, "#00897B")
                centerAndFitCloudInOrbit()
            }
        }

        val btnCalibrate = createScrollableButton("Calibrate", "#455A64") {
            runCalibrationWithFeedback()
        }

        val btnOpen = createScrollableButton("Open", "#673AB7") {
            openPlyLauncher.launch(arrayOf("*/*"))
        }

        val btnExport = createScrollableButton("Export", "#E65100") {
            exportPointCloudsToPly()
        }

        val btnClear = createScrollableButton("Clear", "#37474F") {
            accumulatedPoints.clear()
            renderer.clearPoints()
            FileLogger.log("Cleared point cloud buffer.")
            Toast.makeText(this, "Cleared points", Toast.LENGTH_SHORT).show()
        }

        bottomBar.addView(btnScanToggle)
        bottomBar.addView(btnOrbitToggle)
        bottomBar.addView(btnCalibrate)
        bottomBar.addView(btnOpen)
        bottomBar.addView(btnExport)
        bottomBar.addView(btnClear)

        horizontalScroll.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val bottomParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(20, 0, 20, 36)
        }
        root.addView(horizontalScroll, bottomParams)

        setContentView(root)
    }

    /**
     * Centers the current point cloud at (0,0,0) and sets orbitZoom to fit the cloud's radius.
     */
    private fun centerAndFitCloudInOrbit() {
        if (accumulatedPoints.isEmpty()) {
            renderer.orbitZoom = -4f
            Toast.makeText(this, "Orbit View Reset", Toast.LENGTH_SHORT).show()
            return
        }

        activityScope.launch(Dispatchers.Default) {
            val snapshot = accumulatedPoints.toList()
            var sumX = 0.0
            var sumY = 0.0
            var sumZ = 0.0
            for (p in snapshot) {
                sumX += p.x
                sumY += p.y
                sumZ += p.z
            }
            val cx = (sumX / snapshot.size).toFloat()
            val cy = (sumY / snapshot.size).toFloat()
            val cz = (sumZ / snapshot.size).toFloat()

            var maxDistSq = 0f
            val centered = ArrayList<ColoredPoint3D>(snapshot.size)
            for (p in snapshot) {
                val nx = p.x - cx
                val ny = p.y - cy
                val nz = p.z - cz
                val dSq = nx * nx + ny * ny + nz * nz
                if (dSq > maxDistSq) maxDistSq = dSq
                centered.add(p.copy(x = nx, y = ny, z = nz))
            }

            val radius = sqrt(maxDistSq).coerceIn(1.0f, 100.0f)

            withContext(Dispatchers.Main) {
                renderer.clearPoints()
                renderer.addColoredWorldPoints(centered)
                renderer.orbitAngleX = 20f
                renderer.orbitAngleY = 0f
                renderer.orbitZoom = -(radius * 1.6f)
                Toast.makeText(
                    this@MainActivity,
                    "Centered ${centered.size} pts (Fit: ${String.format("%.1f", -renderer.orbitZoom)}m)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun runCalibrationWithFeedback() {
        val sample = lastRawSample
        val arTracking = poseFusionEngine.isTracking
        val pointTotal = renderer.pointCount
        val colorMode = if (renderer.useCameraRgbColor) "Camera RGB Feed" else "Reflectivity Heatmap"

        val sampleInfo = if (sample != null) {
            "Latest Valid LiDAR Point (Metric):\n" +
            "  • Forward (X): ${String.format("%.2f", sample.x)} m\n" +
            "  • Left (Y): ${String.format("%.2f", sample.y)} m\n" +
            "  • Up (Z): ${String.format("%.2f", sample.z)} m\n" +
            "  • Reflectivity: ${sample.reflectivity}"
        } else {
            "Latest Valid LiDAR Point: None yet (Tap Scan)"
        }

        val statusReport = StringBuilder().apply {
            append("✅ Metric Scaling Active (mm ➔ m)\n")
            append("• Color Mode: $colorMode\n")
            append("• Yaw Offset: ${calibYawDeg.toInt()}° | Y-Offset: ${calibOffsetY}m\n")
            append("• ARCore Tracking: ${if (arTracking) "ACTIVE (World Locked)" else "WAIT"}\n")
            append("• Rendered Points: $pointTotal\n\n")
            append(sampleInfo)
        }.toString()

        AlertDialog.Builder(this)
            .setTitle("Calibration & Color Settings")
            .setMessage(statusReport)
            .setPositiveButton("Toggle Color Mode") { _, _ ->
                renderer.useCameraRgbColor = !renderer.useCameraRgbColor
                val newMode = if (renderer.useCameraRgbColor) "Camera RGB" else "Reflectivity Heatmap"
                Toast.makeText(this, "Color Mode: $newMode", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Rotate +90°") { _, _ ->
                calibYawDeg = (calibYawDeg + 90f) % 360f
                Toast.makeText(this, "LiDAR Yaw rotated to ${calibYawDeg.toInt()}°", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun createZoomButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#37474F"))
                cornerRadius = 24f
            }
            val params = LinearLayout.LayoutParams(125, 120).apply {
                setMargins(0, 8, 0, 8)
            }
            layoutParams = params
            setOnClickListener { onClick() }
        }
    }

    private fun createScrollableButton(
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
            val params = LinearLayout.LayoutParams(220, 130).apply {
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
            btnScanToggle.text = "Stop"
            updateButtonColor(btnScanToggle, "#C62828")
            FileLogger.log("SCAN STARTED.")

            udpClient.startListening(activityScope)
            livoxController.connectAndStartLaser(activityScope)

            Toast.makeText(this, "Livox scanning started", Toast.LENGTH_SHORT).show()
        } else {
            btnScanToggle.text = "Scan"
            updateButtonColor(btnScanToggle, "#2E7D32")
            FileLogger.log("SCAN STOPPED. Total Voxels=${renderer.pointCount}")

            udpClient.stopListening()

            Toast.makeText(this, "Livox scanning stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPlyFileFromUri(uri: Uri) {
        Toast.makeText(this, "Loading PLY file...", Toast.LENGTH_SHORT).show()
        val startTime = System.currentTimeMillis()

        activityScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: return@launch
                val points = inputStream.use { parseAsciiPlyFast(it) }
                val elapsedMs = System.currentTimeMillis() - startTime

                withContext(Dispatchers.Main) {
                    if (points.isEmpty()) {
                        Toast.makeText(this@MainActivity, "No valid points found in PLY", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }

                    accumulatedPoints.clear()
                    renderer.clearPoints()
                    accumulatedPoints.addAll(points)

                    arSession?.pause()
                    renderer.followArCamera = false
                    btnOrbitToggle.text = "AR View"
                    updateButtonColor(btnOrbitToggle, "#00897B")

                    centerAndFitCloudInOrbit()

                    FileLogger.log("Loaded ${points.size} points in ${elapsedMs}ms.")
                    Toast.makeText(
                        this@MainActivity,
                        "Loaded ${points.size} pts in ${elapsedMs}ms",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                FileLogger.log("Failed to load PLY file: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error loading file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Fast PLY parser that also cleans up older millimeter-scaled PLY exports:
     * 1. Strips out repeated (0,0,0) dead sensor returns near the camera origin (< 1m when file has mm points).
     * 2. Converts coordinates > 80.0 from millimeters to meters (/ 1000f).
     */
    private fun parseAsciiPlyFast(inputStream: InputStream): List<ColoredPoint3D> {
        val reader = BufferedReader(InputStreamReader(inputStream), 65536)
        var line: String?
        var vertexCount = 0

        while (reader.readLine().also { line = it } != null) {
            val l = line!!.trim()
            if (l.startsWith("element vertex")) {
                val idx = l.lastIndexOf(' ')
                if (idx != -1) {
                    vertexCount = l.substring(idx + 1).toIntOrNull() ?: 0
                }
            } else if (l == "end_header") {
                break
            }
        }

        val rawPoints = ArrayList<ColoredPoint3D>(if (vertexCount > 0) vertexCount else 50000)
        var readCount = 0
        val maxToRead = if (vertexCount > 0) vertexCount else Int.MAX_VALUE
        var hasMillimeterScale = false

        while (readCount < maxToRead && reader.readLine().also { line = it } != null) {
            val l = line!!
            if (l.isBlank()) continue
            val tokenizer = StringTokenizer(l)
            if (tokenizer.countTokens() >= 3) {
                val x = tokenizer.nextToken().toFloatOrNull() ?: 0f
                val y = tokenizer.nextToken().toFloatOrNull() ?: 0f
                val z = tokenizer.nextToken().toFloatOrNull() ?: 0f
                var r = 0
                var g = 255
                var b = 0
                if (tokenizer.countTokens() >= 3) {
                    r = tokenizer.nextToken().toIntOrNull() ?: 0
                    g = tokenizer.nextToken().toIntOrNull() ?: 255
                    b = tokenizer.nextToken().toIntOrNull() ?: 0
                }
                if (abs(x) > 80f || abs(y) > 80f || abs(z) > 80f) {
                    hasMillimeterScale = true
                }
                rawPoints.add(ColoredPoint3D(x, y, z, r, g, b, 255))
                readCount++
            }
        }

        if (!hasMillimeterScale) return rawPoints

        // Clean up older millimeter-scaled PLY files: filter out the < 2.0m dead origin cluster and divide by 1000f
        val cleaned = ArrayList<ColoredPoint3D>(rawPoints.size)
        for (p in rawPoints) {
            val mag = abs(p.x) + abs(p.y) + abs(p.z)
            if (mag < 5.0f) continue // Skip the 0.0314 0.4008 0.3543 dead-return points
            cleaned.add(p.copy(x = p.x / 1000f, y = p.y / 1000f, z = p.z / 1000f))
        }
        return cleaned
    }

    private fun exportPointCloudsToPly() {
        if (accumulatedPoints.isEmpty()) {
            Toast.makeText(this, "No points captured to export!", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Exporting ${accumulatedPoints.size} points...", Toast.LENGTH_SHORT).show()

        activityScope.launch(Dispatchers.IO) {
            try {
                val snapshot = accumulatedPoints.toList()
                val fileName = "LivoxScan_${System.currentTimeMillis()}.ply"
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/LivoxScans")
                }

                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                if (uri == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to create file in Documents", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                resolver.openOutputStream(uri)?.use { outputStream ->
                    OutputStreamWriter(outputStream).buffered(65536).use { writer ->
                        writer.write("ply\n")
                        writer.write("format ascii 1.0\n")
                        writer.write("element vertex ${snapshot.size}\n")
                        writer.write("property float x\n")
                        writer.write("property float y\n")
                        writer.write("property float z\n")
                        writer.write("property uchar red\n")
                        writer.write("property uchar green\n")
                        writer.write("property uchar blue\n")
                        writer.write("end_header\n")

                        for (p in snapshot) {
                            writer.write("${p.x} ${p.y} ${p.z} ${p.r} ${p.g} ${p.b}\n")
                        }
                    }
                }

                FileLogger.log("Successfully exported ${snapshot.size} points to Documents/LivoxScans/$fileName")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Saved (${snapshot.size} pts): Documents/LivoxScans/$fileName",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                FileLogger.log("Export failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startStatusLoop() {
        val runnable = object : Runnable {
            override fun run() {
                @Suppress("DEPRECATION")
                renderer.displayRotation = windowManager.defaultDisplay.rotation
                checkNetworkInterfaces()
                val arState = if (!renderer.followArCamera) {
                    "ORBIT"
                } else if (poseFusionEngine.isTracking) {
                    "TRACKING"
                } else {
                    "WAIT"
                }
                val kVoxels = String.format("%.1fk", renderer.pointCount / 1000f)
                statusText.text = "• Net: $ethIp  |  AR: $arState  |  $kVoxels pts"
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
            if (renderer.followArCamera) {
                arSession?.resume()
            }
            glSurfaceView.onResume()
            FileLogger.log("onResume: GLSurfaceView resumed.")
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
