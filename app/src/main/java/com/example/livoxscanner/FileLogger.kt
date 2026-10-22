package com.example.livoxscanner

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private var logFile: File? = null

    fun init(context: Context) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = context.getExternalFilesDir(null)
        logFile = File(dir, "LivoxScanLog_$timeStamp.txt")
    }

    fun log(message: String) {
        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val formattedMessage = "[$timeStamp] $message\n"

        // Keeps standard Logcat logging active too
        android.util.Log.d("LivoxApp", message)

        logFile?.let { file ->
            try {
                FileWriter(file, true).use { writer ->
                    writer.append(formattedMessage)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
