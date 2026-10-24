package com.ariateam.aria.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface AudioCaptureSession : AutoCloseable {
    /**
     * Reads up to [buffer.size] PCM samples normalized to [-1.0f, 1.0f].
     * Returns > 0 for samples read, 0 if temporarily empty, or -1 on end-of-stream / stop.
     */
    @Throws(AudioCaptureException::class)
    fun read(buffer: FloatArray): Int

    /** Promptly unblocks an in-flight [read] when stopped or cancelled. */
    fun stopCapture()

    override fun close()
}

internal class AudioCaptureException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal fun interface AudioCaptureSource {
    @Throws(SecurityException::class, AudioCaptureException::class)
    fun open(sampleRate: Int, chunkSamples: Int): AudioCaptureSession
}

internal interface KwsStream : AutoCloseable {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)
    fun isReady(): Boolean
    fun decode()
    fun getKeyword(): String
    fun reset()
    override fun close()
}

internal interface KwsEngine : AutoCloseable {
    fun createStream(): KwsStream
    override fun close()
}

internal class AndroidAudioRecordCaptureSource(
    private val context: Context,
) : AudioCaptureSource {
    @SuppressLint("MissingPermission")
    override fun open(sampleRate: Int, chunkSamples: Int): AudioCaptureSession {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            throw AudioCaptureException("Invalid AudioRecord min buffer size: $minBufferBytes")
        }
        val minRingBufferBytes = sampleRate * 2 * 2
        val targetBufferBytes = maxOf(minBufferBytes * 2, minRingBufferBytes, chunkSamples * 2 * 4)
        val record = createAudioRecord(sampleRate, targetBufferBytes)
            ?: throw AudioCaptureException("Unable to initialize AudioRecord at ${sampleRate}Hz")

        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            runCatching { record.release() }
            throw AudioCaptureException("AudioRecord.startRecording failed", e)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { record.release() }
            throw AudioCaptureException("AudioRecord did not enter RECORDSTATE_RECORDING")
        }
        return AndroidAudioRecordSession(record, chunkSamples)
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(sampleRate: Int, bufferSizeInBytes: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            val candidate = try {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeInBytes,
                )
            } catch (_: IllegalArgumentException) {
                null
            } ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            runCatching { candidate.release() }
        }
        return null
    }

    private class AndroidAudioRecordSession(
        private val record: AudioRecord,
        chunkSamples: Int,
    ) : AudioCaptureSession {
        private val stopped = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private val pcmBuffer = ShortArray(chunkSamples)

        override fun read(buffer: FloatArray): Int {
            if (stopped.get() || released.get()) return -1
            val toRead = minOf(buffer.size, pcmBuffer.size)
            val readCount = try {
                record.read(pcmBuffer, 0, toRead, AudioRecord.READ_BLOCKING)
            } catch (e: Exception) {
                if (stopped.get()) return -1
                throw AudioCaptureException("AudioRecord.read failed", e)
            }
            if (readCount < 0) {
                if (stopped.get()) return -1
                throw AudioCaptureException("AudioRecord.read returned error code: $readCount")
            }
            for (i in 0 until readCount) {
                buffer[i] = pcmBuffer[i] / 32768.0f
            }
            return readCount
        }

        override fun stopCapture() {
            if (stopped.compareAndSet(false, true)) {
                runCatching { record.stop() }
            }
        }

        override fun close() {
            stopCapture()
            if (released.compareAndSet(false, true)) {
                runCatching { record.release() }
            }
        }
    }
}

internal class SherpaKwsEngine(
    assetManager: AssetManager,
    config: KeywordSpotterConfig = SherpaWakeWordDetector.buildSpotterConfig(),
) : KwsEngine {
    private val spotter: KeywordSpotter
    private val closed = AtomicBoolean(false)

    init {
        SherpaWakeWordDetector.verifyBundledModelAssets(assetManager)
        spotter = KeywordSpotter(assetManager = assetManager, config = config)
    }

    override fun createStream(): KwsStream {
        check(!closed.get()) { "SherpaKwsEngine is closed" }
        val stream = spotter.createStream()
        return object : KwsStream {
            private val streamClosed = AtomicBoolean(false)
            private fun usable(): Boolean = !streamClosed.get() && !closed.get()

            override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
                if (usable()) stream.acceptWaveform(samples, sampleRate)
            }

            override fun isReady(): Boolean = usable() && spotter.isReady(stream)

            override fun decode() {
                if (usable()) spotter.decode(stream)
            }

            override fun getKeyword(): String {
                if (!usable()) return ""
                val res = spotter.getResult(stream)
                return res.keyword
            }

            override fun reset() {
                if (usable()) spotter.reset(stream)
            }

            override fun close() {
                if (streamClosed.compareAndSet(false, true)) {
                    runCatching { stream.release() }
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { spotter.release() }
        }
    }
}

/**
 * On-device Keyword Spotting detector backed by sherpa-onnx KeywordSpotter.
 * Runs audio reading and decoding on a dedicated background thread.
 * Promptly releases the microphone upon wake word detection so the command recognition engine can acquire it.
 */
internal class SherpaWakeWordDetector internal constructor(
    private val engine: KwsEngine,
    private val audioSource: AudioCaptureSource,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AriaKwsAudio").apply { isDaemon = true }
    },
    private val mainPoster: (Runnable) -> Unit = { command ->
        Handler(Looper.getMainLooper()).post(command)
    },
) : WakeWordDetector {

    constructor(
        context: Context,
        config: KeywordSpotterConfig = buildSpotterConfig(),
    ) : this(
        engine = SherpaKwsEngine(context.applicationContext.assets, config),
        audioSource = AndroidAudioRecordCaptureSource(context.applicationContext),
    )

    private val activeAttempt = AtomicLong(0)
    private val released = AtomicBoolean(false)
    @Volatile private var currentAudioSession: AudioCaptureSession? = null

    override val isAvailable: Boolean
        get() = !released.get()

    override fun start(listener: WakeWordDetector.Listener) {
        if (released.get()) {
            listener.onError(IllegalStateException("SherpaWakeWordDetector is already released"))
            return
        }
        stop()

        val attemptId = activeAttempt.incrementAndGet()
        val audioSession = try {
            audioSource.open(SAMPLE_RATE, CHUNK_SAMPLES)
        } catch (t: Throwable) {
            listener.onError(t)
            return
        }
        currentAudioSession = audioSession

        val stream = try {
            engine.createStream()
        } catch (t: Throwable) {
            audioSession.close()
            currentAudioSession = null
            listener.onError(t)
            return
        }

        executor.execute {
            val floatBuffer = FloatArray(CHUNK_SAMPLES)
            try {
                while (activeAttempt.get() == attemptId && !released.get()) {
                    val read = audioSession.read(floatBuffer)
                    if (read < 0) break
                    if (read == 0) continue

                    val samplesToFeed = if (read == floatBuffer.size) floatBuffer else floatBuffer.copyOf(read)
                    stream.acceptWaveform(samplesToFeed, SAMPLE_RATE)

                    while (stream.isReady() && activeAttempt.get() == attemptId && !released.get()) {
                        stream.decode()
                        val keyword = stream.getKeyword()
                        if (keyword.isNotBlank()) {
                            stream.reset()
                            // Immediately stop and release microphone before triggering callback
                            audioSession.stopCapture()
                            audioSession.close()
                            if (activeAttempt.compareAndSet(attemptId, 0)) {
                                currentAudioSession = null
                                mainPoster(Runnable {
                                    listener.onWakeWordDetected(keyword)
                                })
                            }
                            return@execute
                        }
                    }
                }
            } catch (t: Throwable) {
                if (activeAttempt.get() == attemptId && !released.get()) {
                    audioSession.close()
                    if (activeAttempt.compareAndSet(attemptId, 0)) {
                        currentAudioSession = null
                        mainPoster(Runnable {
                            listener.onError(t)
                        })
                    }
                }
            } finally {
                runCatching { stream.close() }
                runCatching { audioSession.close() }
            }
        }
    }

    override fun stop() {
        activeAttempt.incrementAndGet()
        val session = currentAudioSession
        currentAudioSession = null
        session?.stopCapture()
        session?.close()
    }

    override fun release() {
        if (released.compareAndSet(false, true)) {
            stop()
            runCatching { engine.close() }
            runCatching { executor.shutdownNow() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 640 // 40ms @ 16kHz
        const val DEFAULT_KEYWORDS_SCORE = 1.5f
        const val DEFAULT_KEYWORDS_THRESHOLD = 0.25f
        const val DEFAULT_NUM_THREADS = 2

        const val MODEL_DIR = "sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile"
        const val ENCODER_ASSET = "$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx"
        const val DECODER_ASSET = "$MODEL_DIR/decoder-epoch-99-avg-1.onnx"
        const val JOINER_ASSET = "$MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx"
        const val TOKENS_ASSET = "$MODEL_DIR/tokens.txt"
        const val KEYWORDS_ASSET = "$MODEL_DIR/keywords.txt"

        val REQUIRED_MODEL_ASSETS = listOf(
            ENCODER_ASSET,
            DECODER_ASSET,
            JOINER_ASSET,
            TOKENS_ASSET,
            KEYWORDS_ASSET,
        )

        private const val TAG = "SherpaWakeWordDetector"

        fun verifyBundledModelAssets(assetManager: AssetManager) {
            for (assetPath in REQUIRED_MODEL_ASSETS) {
                try {
                    assetManager.open(assetPath).close()
                } catch (e: IOException) {
                    throw IllegalStateException("Missing bundled KWS asset: $assetPath", e)
                }
            }
        }

        fun buildSpotterConfig(
            keywordsScore: Float = DEFAULT_KEYWORDS_SCORE,
            keywordsThreshold: Float = DEFAULT_KEYWORDS_THRESHOLD,
            numThreads: Int = DEFAULT_NUM_THREADS,
        ): KeywordSpotterConfig {
            return KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = ENCODER_ASSET,
                        decoder = DECODER_ASSET,
                        joiner = JOINER_ASSET,
                    ),
                    tokens = TOKENS_ASSET,
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                    modelType = "",
                ),
                keywordsFile = KEYWORDS_ASSET,
                keywordsScore = keywordsScore,
                keywordsThreshold = keywordsThreshold,
            )
        }

        /**
         * Checks assets and native library availability; safely creates a detector or returns null.
         */
        fun createIfAvailable(
            context: Context,
            keywordsScore: Float = DEFAULT_KEYWORDS_SCORE,
            keywordsThreshold: Float = DEFAULT_KEYWORDS_THRESHOLD,
        ): SherpaWakeWordDetector? {
            val assetManager = context.applicationContext.assets
            for (assetPath in REQUIRED_MODEL_ASSETS) {
                try {
                    assetManager.open(assetPath).close()
                } catch (_: Exception) {
                    Log.i(TAG, "Bundled KWS asset missing ($assetPath); falling back to default Android recognizer")
                    return null
                }
            }

            return try {
                val config = buildSpotterConfig(keywordsScore, keywordsThreshold)
                SherpaWakeWordDetector(context, config)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to instantiate Sherpa KeywordSpotter: ${t.message}; falling back to default Android recognizer", t)
                null
            }
        }
    }
}
