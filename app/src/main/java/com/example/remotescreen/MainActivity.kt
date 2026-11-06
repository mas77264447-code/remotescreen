package com.example.remotescreen

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    // يعيد نتيجة طلب الإذن (RESULT_OK + Intent) ثم نمررها للخدمة
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                // Android 8+ يتطلب startForegroundService
                startForegroundService(intent)
                status.text = "يعمل"
            } else {
                status.text = "تم رفض الإذن"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.txtStatus)
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            status.text = "متوقف"
        }
    }
}
