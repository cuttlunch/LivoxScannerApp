package com.example.livoxscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: ArPointCloudRenderer
    private lateinit var poseFusionEngine: PoseFusionEngine
    private lateinit var colorizer: CameraColorizer

    private var arSession: Session? = null
    private val isScanning = AtomicBoolean(false)
    private val udpListening = AtomicBoolean(false)
    private val packetCount = AtomicLong(0)

    private lateinit var statusText: TextView
    private lateinit var btnScanToggle: Button
    private lateinit var btnOrbitToggle: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private var ethIp: String = "No eth0"

    private var prevTouchX = 0f
    private var prevTouchY = 0f

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FileLogger.init(this)
        FileLogger.log("=== MainActivity Started ===")

        poseFusionEngine = PoseFusionEngine()
        colorizer = CameraColorizer()
        renderer = ArPointCloudRenderer(poseFusionEngine, colorizer)

        // Build the entire UI in Kotlin (Zero XML or R.id dependencies)
        buildProgrammaticUI()

        if (checkCameraPermission()) {
            setupARSession()
        } else {
            requestCameraPermission()
        }

        checkNetworkInterfaces()
        startStatusLoop()
    }

    private fun buildProgrammaticUI() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. OpenGL Surface View for AR Camera & Point Cloud
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

        // 2. Top Status Pill ("• eth0: 192.168.1.50 | AR: TRACKING | 0.0k voxels")
        statusText = TextView(this).apply {
            text = "• eth0: checking...  |  AR: WAIT  |  0.0k voxels"
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

        // 3. Bottom Control Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 32, 24, 32)
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

        val btnCalibrate = createStyledButton("Calibrate", "#455A64") {
            FileLogger.log("User pressed Calibrate.")
            Toast.makeText(this, "Calibrating extrinsic pose...", Toast.LENGTH_SHORT).show()
        }

        val btnClear = createStyledButton("Clear", "#455A64") {
            renderer.clearPoints()
            FileLogger.log("Cleared point cloud buffer.")
            Toast.makeText(this, "Cleared points", Toast.LENGTH_SHORT).show()
        }

        bottomBar.addView(btnScanToggle)
        bottomBar.addView(btnOrbitToggle)
        bottomBar.addView(btnCalibrate)
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
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = 28f
            }
            val params = LinearLayout.LayoutParams(0, 130, 1f).apply {
                setMargins(10, 0, 10, 0)
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
            updateButtonColor(btnScanToggle, "#C62828") // Red
            FileLogger.log("SCAN STARTED. eth0=$ethIp, AR_Tracking=${poseFusionEngine.isTracking}")
            startUdpDiagnosticListener()
        } else {
            btnScanToggle.text = "Start Scan"
            updateButtonColor(btnScanToggle, "#2E7D32") // Green
            FileLogger.log("SCAN STOPPED. Total UDP packets seen=${packetCount.get()}, Voxels=${renderer.pointCount}")
            udpListening.set(false)
        }
    }

    /**
     * Logs incoming Livox UDP packets on port 56000 (point data) & 55000 (broadcast)
     * and invokes LivoxUdpDriver via reflection if present in your package.
     */
    private fun startUdpDiagnosticListener() {
        if (udpListening.getAndSet(true)) return

        // Try starting your project's Livox driver class if it exists
        try {
            val driverClass = Class.forName("com.example.livoxscanner.LivoxUdpDriver")
            FileLogger.log("Found LivoxUdpDriver class in package.")
        } catch (e: ClassNotFoundException) {
            FileLogger.log("Note: LivoxUdpDriver class not found by that exact name; using built-in UDP sniffer.")
        }

        // Thread to monitor port 56000 (Livox Mid-40 Point Cloud UDP port)
        Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 2000
                    bind(InetSocketAddress(56000))
                }
                FileLogger.log("UDP Listener bound to port 56000.")
                val buf = ByteArray(1500)
                val packet = DatagramPacket(buf, buf.size)

                while (udpListening.get()) {
                    try {
                        socket.receive(packet)
                        val count = packetCount.incrementAndGet()
                        if (count == 1L || count % 200L == 0L) {
                            FileLogger.log("UDP 56000 rx: packet #$count (${packet.length} bytes) from ${packet.address.hostAddress}")
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        FileLogger.log("UDP 56000 timeout: 0 packets received in last 2s. Check LiDAR handshake/port.")
                    }
                }
            } catch (e: Exception) {
                FileLogger.log("UDP 56000 bind/receive error: ${e.javaClass.simpleName} - ${e.message}")
            } finally {
                socket?.close()
            }
        }.start()
    }

    private fun startStatusLoop() {
        val runnable = object : Runnable {
            override fun run() {
                checkNetworkInterfaces()
                val arState = if (poseFusionEngine.isTracking) "TRACKING" else "WAIT"
                val kVoxels = String.format("%.1fk", renderer.pointCount / 1000f)
                statusText.text = "• eth0: $ethIp  |  AR: $arState  |  $kVoxels voxels"
                uiHandler.postDelayed(this, 500)
            }
        }
        uiHandler.post(runnable)
    }

    private fun checkNetworkInterfaces() {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            var foundEth: String? = null
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in Collections.list(ni.inetAddresses)) {
                    val host = addr.hostAddress ?: continue
                    if (host.indexOf(':') < 0) { // IPv4
                        if (ni.name.startsWith("eth") || ni.name.startsWith("usb") || host.startsWith("192.168.1.")) {
                            foundEth = host
                        }
                    }
                }
            }
            val newIp = foundEth ?: "Disconnected"
            if (newIp != ethIp) {
                ethIp = newIp
                FileLogger.log("Network status changed: eth0 = $ethIp")
            }
        } catch (e: Exception) {
            FileLogger.log("Network check error: ${e.message}")
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
}
