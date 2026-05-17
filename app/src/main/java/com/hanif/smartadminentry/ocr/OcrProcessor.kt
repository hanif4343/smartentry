package com.hanif.smartadminentry.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object OcrProcessor {

    private const val TAG = "OcrProcessor"

    suspend fun processImage(context: Context, uri: Uri): String {
        val bmp = loadBitmap(context, uri)
            ?: throw IllegalStateException("Image load failed")
        return try { processBitmap(context, bmp) }
        finally { bmp.recycle() }
    }

    suspend fun processBitmap(context: Context, bmp: Bitmap): String {
        val sized = ensureSize(bmp)
        val devanagari = runOcr(sized, useDevanagari = true)
        val latin = runOcr(sized, useDevanagari = false)
        if (sized !== bmp) sized.recycle()
        val best = if (devanagari.length >= latin.length) devanagari else latin
        if (best.length < 15) {
            val enh = enhance(bmp)
            val devEnh = runOcr(enh, useDevanagari = true)
            val latEnh = runOcr(enh, useDevanagari = false)
            enh.recycle()
            val bestEnh = if (devEnh.length >= latEnh.length) devEnh else latEnh
            return if (bestEnh.length > best.length) bestEnh else best
        }
        return best
    }

    private suspend fun runOcr(bmp: Bitmap, useDevanagari: Boolean): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = if (useDevanagari)
                TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            else
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { result ->
                    val sb = StringBuilder()
                    for (block in result.textBlocks) {
                        for (line in block.lines) sb.append(line.text).append("\n")
                        sb.append("\n")
                    }
                    cont.resume(sb.toString().trim())
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR error (dev=$useDevanagari): ${e.message}")
                    cont.resume("")
                }
        }

    fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            var s = 1; var w = opts.outWidth; var h = opts.outHeight
            while (w > 2400 || h > 2400) { s *= 2; w /= 2; h /= 2 }
            val o2 = BitmapFactory.Options().apply {
                inSampleSize = s
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, o2)
            }
        } catch (e: Exception) { Log.e(TAG, "loadBitmap: ${e.message}"); null }
    }

    private fun ensureSize(bmp: Bitmap): Bitmap {
        val min = 1080
        val s = minOf(bmp.width, bmp.height)
        if (s >= min) return bmp
        val sc = min.toFloat() / s
        return Bitmap.createScaledBitmap(bmp,
            (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
    }

    private fun enhance(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        cm.postConcat(ColorMatrix(floatArrayOf(
            1.8f,0f,0f,0f,-60f, 0f,1.8f,0f,0f,-60f,
            0f,0f,1.8f,0f,-60f, 0f,0f,0f,1f,0f
        )))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
