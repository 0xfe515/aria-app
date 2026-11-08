package com.ariateam.aria.cloud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.cloud.auth.ChatGptAuthStatus
import com.ariateam.aria.cloud.auth.DefaultChatGptAuthRepository
import com.ariateam.aria.cloud.chatgpt.ChatGptClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.ThreadLocalRandom

/** Explicit, opt-in live capability diagnostic. This test is never a fake success when not run. */
@RunWith(AndroidJUnit4::class)
class ChatGptLiveImageProbeTest {
    @Test
    fun authenticatedModelReadsRandomCodeFromSyntheticImageWhenExplicitlyOptedIn() = runBlocking {
        val optedIn = InstrumentationRegistry.getArguments().getString(ARG_OPT_IN) == "true"
        if (!optedIn) {
            Log.i(TAG, "ASSUMPTION: live image probe not run; pass $ARG_OPT_IN=true to opt in")
            assumeTrue("Live image probe requires explicit instrumentation opt-in", optedIn)
            return@runBlocking
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val auth = DefaultChatGptAuthRepository.getInstance(context)
        val status = auth.sessionState.value.status
        if (status != ChatGptAuthStatus.AUTHENTICATED) {
            Log.i(TAG, "ASSUMPTION: live image probe not run; no authenticated ChatGPT session")
            assumeTrue("An existing authenticated ChatGPT session is required", false)
            return@runBlocking
        }
        val accessToken = auth.validAccessToken()
        if (accessToken.isNullOrBlank()) {
            Log.i(TAG, "ASSUMPTION: live image probe not run; no valid current access token")
            assumeTrue("A valid existing ChatGPT session is required", false)
            return@runBlocking
        }

        val code = ThreadLocalRandom.current().nextInt(1000, 10_000).toString()
        val bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 150f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                }
                val x = (width - paint.measureText(code)) / 2f
                drawText(code, x, height * 0.72f, paint)
            }
            val jpeg = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) { "Unable to encode synthetic probe image" }
                output.toByteArray()
            }
            val imageBase64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
            val cancellationToken = GeminiCancellationToken()
            val request = async(Dispatchers.IO) {
                ChatGptClient().generateContent(
                    prompt = "이미지에 적힌 네 자리 숫자를 읽어 숫자만 답해 주세요.",
                    imageBase64 = imageBase64,
                    accessToken = accessToken,
                    cancellationToken = cancellationToken,
                )
            }
            val result = try {
                withTimeout(90_000L) { request.await() }
            } finally {
                cancellationToken.cancel()
            }
            assertTrue("Live model must return an image-based result", result is GeminiResult.Success)
            val answer = (result as GeminiResult.Success).text
            assertTrue("Response should contain the synthetic image's random code", answer.contains(code))
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val TAG = "ChatGptLiveImageProbe"
        private const val ARG_OPT_IN = "aria_live_image_probe"
    }
}
