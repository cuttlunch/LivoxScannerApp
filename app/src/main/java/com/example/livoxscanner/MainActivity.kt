package com.example.livoxscanner

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Config
import com.google.ar.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private val colorizer = CameraColorizer()
    private val voxelFilter = VoxelGridFilter(voxelSizeMeters = 0.02f)
    private val fusionEngine = PoseFusionEngine(colorizer, voxelFilter)
    private val renderer = ArPointCloudRenderer(fusionEngine, colorizer)

    private var arSession: Session? = null
    private lateinit var ethernetBinder: EthernetNetworkBinder
    private lateinit var livoxClient: LivoxUdpClient
    private lateinit var livoxController: LivoxController

    private var isScanning = false
    private var prevX = 0f
    private var prevY = 0f

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.livoxscanner.USB_PERMISSION"
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ACTION_USB_PERMISSION == intent?.action) {
                synchronized(this) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        Toast.makeText(context, "USB Accessory Permission Granted!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "USB Permission Denied", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }

        checkAndRequestUsbPermission()

        val prefs = getSharedPreferences("livox_calib", Context.MODE_PRIVATE)
        fusionEngine.setBoreSightAngles(
            roll = prefs.getFloat("roll", 0f),
            pitch = prefs.getFloat("pitch", 0f),
            yaw = prefs.getFloat("yaw", 0f)
        )

        ethernetBinder = EthernetNetworkBinder(this).also { it.startMonitoring() }
        livoxController = LivoxController(ethernetBinder)

        livoxClient = LivoxUdpClient(ethernetBinder, pointDataPort = 60001) { rawPoints ->
            val coloredPoints = fusionEngine.transformAndColorize(rawPoints, System.nanoTime())
            if (coloredPoints.isNotEmpty()) {
                renderer.addColoredWorldPoints(coloredPoints)
            }
        }

        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
        }

        setContentView(buildScannerHud())
    }

    private fun checkAndRequestUsbPermission() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList ?: return
        if (deviceList.isEmpty()) return

        for (device in deviceList.values) {
            if (!usbManager.hasPermission(device)) {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                val filter = IntentFilter(ACTION_USB_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
                } else {
                    registerReceiver(usbReceiver, filter)
                }
                usbManager.requestPermission(device, permissionIntent)
            }
        }
    }

    private fun buildScannerHud(): View {
        val root = FrameLayout(this)
        root.addView(glSurfaceView)

        val statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(36, 20, 36, 20)
            background = roundedBg(Color.parseColor("#B312141C"), 40f)
            text = "eth0: Waiting...  |  ARCore: INIT  |  0 pts"
        }
        root.addView(
            statusText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = 42 }
        )

        lifecycleScope.launch {
            while (isActive) {
                val ethIp = ethernetBinder.localEthernetIp.value ?: "Disconnected"
                val arState = if (fusionEngine.isTracking) "TRACKING" else "WAIT"
                val ptsK = String.format("%.1fk", renderer.pointCount / 1000f)
                statusText.text = "● eth0: $ethIp   |   AR: $arState   |   $ptsK voxels"
                delay(200)
            }
        }

        val bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 16, 24, 42)
        }

        val calibCard = buildCalibrationCard()
        calibCard.visibility = View.GONE
        bottomContainer.addView(calibCard)

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
            background = roundedBg(Color.parseColor("#CC12141C"), 32f)
        }

    val btnScan = styledButton("Start Scan", Color.parseColor("#00C853")).apply {
            setOnClickListener {
                if (!isScanning) {
                    isScanning = true
                    text = "Stop Scan"
                    background = roundedBg(Color.parseColor("#D50000"), 24f)
                    
                    if (ethernetBinder.localEthernetIp.value == null) {
                        Toast.makeText(context, "Using static IP fallback (192.168.1.50)", Toast.LENGTH_SHORT).show()
                    }
                    
                    livoxClient.startListening(lifecycleScope)
                    livoxController.connectAndStartLaser(lifecycleScope)
                    Toast.makeText(context, "Initiating Livox Mid-40 stream...", Toast.LENGTH_SHORT).show()
                } else {
                    isScanning = false
                    text = "Start Scan"
                    background = roundedBg(Color.parseColor("#00C853"), 24f)
                    livoxClient.stopListening()
                }
            }
        }

        val btnOrbit = styledButton("3D Orbit", Color.parseColor("#2962FF")).apply {
            setOnClickListener {
                renderer.followArCamera = !renderer.followArCamera
                text = if (renderer.followArCamera) "3D Orbit" else "AR POV"
            }
        }

        val btnCalib = styledButton("Calibrate", Color.parseColor("#455A64")).apply {
            setOnClickListener {
                calibCard.visibility = if (calibCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        val btnClear = styledButton("Clear", Color.parseColor("#455A64")).apply {
            setOnClickListener {
                renderer.clearPoints()
                voxelFilter.clear()
            }
        }

        val btnShare = styledButton("Share .PLY", Color.parseColor("#AA00FF")).apply {
            setOnClickListener { exportAndSharePly() }
        }

        buttonBar.addView(btnScan)
        buttonBar.addView(btnOrbit)
        buttonBar.addView(btnCalib)
        buttonBar.addView(btnClear)
        buttonBar.addView(btnShare)

        bottomContainer.addView(buttonBar)
        root.addView(
            bottomContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        return root
    }

    private fun buildCalibrationCard(): View {
        val prefs = getSharedPreferences("livox_calib", Context.MODE_PRIVATE)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = roundedBg(Color.parseColor("#D9161922"), 32f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
        }

        val title = TextView(this).apply {
            text = "Bore-Sight Calibration (Clears voxels on adjust)"
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 13f
            setPadding(0, 0, 0, 12)
        }
        card.addView(title)

        fun createAngleRow(label: String, initialAngle: Float, onAngleChanged: (Float) -> Unit): SeekBar {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val valueLabel = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                width = 230
                text = "$label: ${String.format("%+.1f°", initialAngle)}"
            }
            val seekBar = SeekBar(this).apply {
                max = 200
                progress = ((initialAngle + 10f) * 10f).toInt().coerceIn(0, 200)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val deg = (progress - 100) / 10f
                        valueLabel.text = "$label: ${String.format("%+.1f°", deg)}"
                        if (fromUser) {
                            onAngleChanged(deg)
                            renderer.clearPoints()
                            voxelFilter.clear()
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            row.addView(valueLabel)
            row.addView(seekBar)
            card.addView(row)
            return seekBar
        }

        var rollBar: SeekBar? = null
        var pitchBar: SeekBar? = null
        var yawBar: SeekBar? = null

        rollBar = createAngleRow("Roll (Z)", fusionEngine.rollDeg) { newRoll ->
            fusionEngine.setBoreSightAngles(newRoll, fusionEngine.pitchDeg, fusionEngine.yawDeg)
            prefs.edit().putFloat("roll", newRoll).apply()
        }
        pitchBar = createAngleRow("Pitch (X)", fusionEngine.pitchDeg) { newPitch ->
            fusionEngine.setBoreSightAngles(fusionEngine.rollDeg, newPitch, fusionEngine.yawDeg)
            prefs.edit().putFloat("pitch", newPitch).apply()
        }
        yawBar = createAngleRow("Yaw (Y)", fusionEngine.yawDeg) { newYaw ->
            fusionEngine.setBoreSightAngles(fusionEngine.rollDeg, fusionEngine.pitchDeg, newYaw)
            prefs.edit().putFloat("yaw", newYaw).apply()
        }

        val btnReset = Button(this).apply {
            text = "Reset Angles to 0.0°"
            setTextColor(Color.WHITE)
            textSize = 12f
            background = roundedBg(Color.parseColor("#37474F"), 18f)
            setOnClickListener {
                rollBar?.progress = 100; pitchBar?.progress = 100; yawBar?.progress = 100
                fusionEngine.setBoreSightAngles(0f, 0f, 0f)
                prefs.edit().clear().apply()
                renderer.clearPoints()
                voxelFilter.clear()
            }
        }
        card.addView(btnReset)
        return card
    }

    private fun styledButton(label: String, bgColor: Int): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(24, 16, 24, 16)
            background = roundedBg(bgColor, 24f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(8, 0, 8, 0) }
        }
    }

    private fun roundedBg(color: Int, radiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(color)
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            if (arSession == null) {
                arSession = Session(this).apply {
                    val config = Config(this)
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    configure(config)
                }
                renderer.arSession = arSession
            }
            arSession?.resume()
        }
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        arSession?.pause()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!renderer.followArCamera && event.action == MotionEvent.ACTION_MOVE) {
            renderer.orbitAngleY += (event.x - prevX) * 0.4f
            renderer.orbitAngleX += (event.y - prevY) * 0.4f
        }
        prevX = event.x
        prevY = event.y
        return true
    }

    private fun exportAndSharePly() {
        val snapshot = renderer.recordedColoredPoints.toList()
        if (snapshot.isEmpty()) {
            Toast.makeText(this, "No points to export!", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val fileName = "livox_scan_${System.currentTimeMillis()}.ply"
            val file = File(getExternalFilesDir(null), fileName)
            val elapsedMs = PlyBinaryExporter.exportBinaryPly(file, snapshot)
            val sizeMb = String.format("%.2f", file.length() / (1024f * 1024f))
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Saved $sizeMb MB in ${elapsedMs}ms!", Toast.LENGTH_SHORT).show()
                sharePlyFile(file)
            }
        }
    }

    private fun sharePlyFile(file: File) {
        val fileUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, "Livox Mid-40 3D Scan: ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share 3D Scan (.ply)"))
    }

    override fun onDestroy() {
        super.onDestroy()
        livoxClient.stopListening()
        ethernetBinder.stopMonitoring()
        arSession?.close()
        runCatching { unregisterReceiver(usbReceiver) }
    }
}
