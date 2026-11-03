package com.ariateam.aria.cloud

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

object CloudImageProcessor {
    private const val TAG = "CloudImageProcessor"

    fun compressBitmapToBase64(bitmap: Bitmap?): String? {
        if (bitmap == null || bitmap.isRecycled) return null

        var scaledBitmap: Bitmap? = null
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val maxDimension = maxOf(width, height)

            val workingBitmap = if (maxDimension > GeminiConfig.MAX_IMAGE_DIMENSION) {
                val scale = GeminiConfig.MAX_IMAGE_DIMENSION.toFloat() / maxDimension
                val targetWidth = (width * scale).toInt().coerceAtLeast(1)
                val targetHeight = (height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true).also {
                    scaledBitmap = it
                }
            } else {
                bitmap
            }

            var quality = GeminiConfig.JPEG_QUALITY
            var stream = ByteArrayOutputStream()
            workingBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            var bytes = stream.toByteArray()

            // If exceeds maximum allowed bytes, step down quality
            while (bytes.size > GeminiConfig.MAX_IMAGE_BYTES && quality > 30) {
                quality -= 15
                stream = ByteArrayOutputStream()
                workingBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                bytes = stream.toByteArray()
            }

            if (bytes.size > GeminiConfig.MAX_IMAGE_BYTES) {
                Log.w(TAG, "Compressed image size ${bytes.size} bytes exceeds limit ${GeminiConfig.MAX_IMAGE_BYTES}")
                return null
            }

            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Out of memory while compressing image for Gemini", oom)
            null
        } catch (t: Throwable) {
            Log.e(TAG, "Error compressing image for Gemini", t)
            null
        } finally {
            scaledBitmap?.let {
                if (it !== bitmap && !it.isRecycled) {
                    it.recycle()
                }
            }
        }
    }
}
