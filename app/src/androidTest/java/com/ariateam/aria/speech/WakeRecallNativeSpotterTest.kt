package com.ariateam.aria.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WakeRecallNativeSpotterTest {
    @Test fun provisionedSpotterInitializesAndAcceptsSilence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = SherpaKwsEngine(context.assets)
        try {
            val stream = engine.createStream()
            try {
                assertNotNull(stream)
                stream.acceptWaveform(FloatArray(16_000), 16_000)
                if (stream.isReady()) stream.decode()
                stream.getKeyword()
            } finally {
                stream.close()
            }
        } finally {
            engine.close()
        }
    }
}
