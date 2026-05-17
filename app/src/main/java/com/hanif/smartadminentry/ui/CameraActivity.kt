package com.hanif.smartadminentry.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hanif.smartadminentry.databinding.ActivityCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private var flashEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startCamera()

        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnFlash.setOnClickListener {
            flashEnabled = !flashEnabled
            imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            binding.btnFlash.alpha = if (flashEnabled) 1f else 0.5f
        }
        binding.btnClose.setOnClickListener { finish() }
    }

    private fun startCamera() {
        val provider = ProcessCameraProvider.getInstance(this)
        provider.addListener({
            val cameraProvider = provider.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val file = File(cacheDir, "capture_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(file).build()

        binding.btnCapture.isEnabled = false
        imageCapture.takePicture(output, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val uri = FileProvider.getUriForFile(
                        this@CameraActivity,
                        "${packageName}.provider",
                        file
                    )
                    val intent = Intent().apply { putExtra("captured_image_uri", uri) }
                    setResult(RESULT_OK, intent)
                    finish()
                }
                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(this@CameraActivity, "ছবি তুলতে পারেনি: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.btnCapture.isEnabled = true
                }
            })
    }
}
