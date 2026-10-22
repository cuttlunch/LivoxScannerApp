package com.example.livoxscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.Session
import com.google.ar.core.Config
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: CustomGLSurfaceView
    private lateinit var renderer: ArPointCloudRenderer
    private lateinit var poseFusionEngine: PoseFusionEngine
    private lateinit var colorizer: CameraColorizer

    private var arSession: Session? = null
    private var isScanning = false

    private lateinit var statusText: TextView
    private lateinit var voxelCountText: TextView

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize File Logger
        FileLogger.init(this)
        FileLogger.log("MainActivity onCreate started.")

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText) // e.g. top banner showing eth0: IP | AR: STATUS | voxels
        
        // Check camera permission for ARCore
        if (checkCameraPermission()) {
            setupARAndRenderer()
        } else {
            requestCameraPermission()
        }

        setupUIButtons()
        checkNetworkInterfaces()
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
                FileLogger.log("Camera permission granted.")
                setupARAndRenderer()
            } else {
                FileLogger.log("Camera permission denied.")
                Toast.makeText(this, "Camera permission is required for AR Core", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setupARAndRenderer() {
        try {
            arSession = Session(this)
            val config = Config(arSession).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
            }
            arSession?.configure(config)
            FileLogger.log("ARCore Session initialized successfully.")
        } catch (e: UnavailableApkTooOldException) {
            FileLogger.log("ARCore error: Please update Google Play Services for AR. ${e.message}")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            FileLogger.log("ARCore error: Device not compatible. ${e.message}")
        } catch (e: UnavailableSdkTooOldException) {
            FileLogger.log("ARCore error: SDK too old. ${e.message}")
        } catch (e: Exception) {
            FileLogger.log("ARCore initialization failed: ${e.message}")
        }

        poseFusionEngine = PoseFusionEngine()
        colorizer = CameraColorizer()
        renderer = ArPointCloudRenderer(poseFusionEngine, colorizer).apply {
            arSession = this@MainActivity.arSession
        }

        glSurfaceView = findViewById(R.id.glSurfaceView)
        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun setupUIButtons() {
        val btnStartScan = findViewById<Button>(R.id.btnStartScan)
        val btnStopScan = findViewById<Button>(R.id.btnStopScan)
        val btnOrbit = findViewById<Button>(R.id.btnOrbit)
        val btnCalibrate = findViewById<Button>(R.id.btnCalibrate)
        val btnClear = findViewById<Button>(R.id.btnClear)

        btnStartScan?.setOnClickListener {
            isScanning = true
            FileLogger.log("User pressed Start Scan.")
            Toast.makeText(this, "Initiating Livox Mid-40 stream...", Toast.LENGTH_SHORT).show()
            // Add your UDP socket packet listener / streaming trigger here if not already running
        }

        btnStopScan?.setOnClickListener {
            isScanning = false
            FileLogger.log("User pressed Stop Scan.")
        }

        btnOrbit?.setOnClickListener {
            renderer.followArCamera = !renderer.followArCamera
            FileLogger.log("Toggled followArCamera: ${renderer.followArCamera}")
            Toast.makeText(this, "Camera Follow: ${renderer.followArCamera}", Toast.LENGTH_SHORT).show()
        }

        btnCalibrate?.setOnClickListener {
            FileLogger.log("User pressed Calibrate.")
        }

        btnClear?.setOnClickListener {
            renderer.clearPoints()
            FileLogger.log("User cleared point cloud buffer.")
            Toast.makeText(this, "Point cloud cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkNetworkInterfaces() {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val addrs = Collections.list(networkInterface.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.indexOf(':') == -1) {
                        // IPv4 address found (e.g. eth0 / USB Ethernet adapter)
                        val hostIp = addr.hostAddress
                        FileLogger.log("Active Network Interface found: ${networkInterface.name} -> $hostIp")
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.log("Error checking network interfaces: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            arSession?.resume()
            glSurfaceView.onResume()
            FileLogger.log("AR Session and GLSurfaceView resumed.")
        } catch (e: CameraNotAvailableException) {
            FileLogger.log("Camera not available on resume: ${e.message}")
            arSession = null
        }
    }

    override fun onPause() {
        super.onPause()
        arSession?.pause()
        glSurfaceView.onPause()
        FileLogger.log("AR Session and GLSurfaceView paused.")
    }
}
