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
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface StreamingAsrStream : AutoCloseable {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)
    fun isReady(): Boolean
    fun decode()
    fun isEndpoint(): Boolean
    fun reset()
    fun inputFinished()
    fun currentText(): String
    override fun close()
}

internal interface StreamingAsrEngine : AutoCloseable {
    fun createStream(): StreamingAsrStream
    override fun close()
}

internal class AudioCaptureException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal interface AudioCaptureSession : AutoCloseable {
    /**
     * Reads up to [buffer.size] PCM samples normalized to [-1.0f, 1.0f].
     * Returns > 0 for samples read, 0 if temporarily empty, or -1 on end-of-stream / stop.
     */
    @Throws(AudioCaptureException::class)
    fun read(buffer: FloatArray): Int

    /** Promptly unblocks an in-flight [read] when the attempt is finished or cancelled. */
    fun stopCapture()

    override fun close()
}

internal fun interface AudioCaptureSource {
    @Throws(SecurityException::class, AudioCaptureException::class)
    fun open(sampleRate: Int, chunkSamples: Int): AudioCaptureSession
}

internal class SherpaOnnxStreamingEngine(
    assetManager: AssetManager,
    config: OnlineRecognizerConfig = SherpaRecognitionDriver.buildRecognizerConfig(),
) : StreamingAsrEngine {
    private val closed = AtomicBoolean(false)
    private val recognizer: OnlineRecognizer

    init {
        SherpaRecognitionDriver.verifyBundledModelAssets(assetManager)
        recognizer = OnlineRecognizer(assetManager = assetManager, config = config)
    }

    override fun createStream(): StreamingAsrStream {
        check(!closed.get()) { "SherpaOnnxStreamingEngine is already closed" }
        val stream = recognizer.createStream()
        return SherpaOnnxStreamingStream(recognizer, stream, closed)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { recognizer.release() }
        }
    }

    private class SherpaOnnxStreamingStream(
        private val recognizer: OnlineRecognizer,
        private val stream: OnlineStream,
        private val engineClosed: AtomicBoolean,
    ) : StreamingAsrStream {
        private val streamClosed = AtomicBoolean(false)

        private fun usable(): Boolean = !streamClosed.get() && !engineClosed.get()

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            if (usable()) stream.acceptWaveform(samples, sampleRate)
        }

        override fun isReady(): Boolean = usable() && recognizer.isReady(stream)

        override fun decode() {
            if (usable()) recognizer.decode(stream)
        }

        override fun isEndpoint(): Boolean = usable() && recognizer.isEndpoint(stream)

        override fun reset() {
            if (usable()) recognizer.reset(stream)
        }

        override fun inputFinished() {
            if (usable()) stream.inputFinished()
        }

        override fun currentText(): String =
            if (usable()) recognizer.getResult(stream).text else ""

        override fun close() {
            if (streamClosed.compareAndSet(false, true)) {
                runCatching { stream.release() }
            }
        }
    }
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
        // Allocate at least 2 seconds of 16kHz mono 16-bit PCM ring buffer (64 KB) so
        // synchronous encoder decode bursts under CPU contention never drop mic frames.
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
                if (stopped.get() || released.get()) return -1
                throw AudioCaptureException("AudioRecord.read threw exception", e)
            }
            if (readCount < 0) {
                if (stopped.get() || released.get()) return -1
                throw AudioCaptureException("AudioRecord.read failed with code $readCount")
            }
            if (readCount == 0 && (stopped.get() || released.get())) return -1
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

internal class WaveAudioCaptureSource(
    private val samples: FloatArray,
) : AudioCaptureSource {
    override fun open(sampleRate: Int, chunkSamples: Int): AudioCaptureSession {
        return object : AudioCaptureSession {
            private var offset = 0
            private val stopped = AtomicBoolean(false)

            override fun read(buffer: FloatArray): Int {
                if (stopped.get() || offset >= samples.size) return -1
                val count = minOf(buffer.size, samples.size - offset)
                System.arraycopy(samples, offset, buffer, 0, count)
                offset += count
                return count
            }

            override fun stopCapture() {
                stopped.set(true)
            }

            override fun close() {
                stopped.set(true)
            }
        }
    }
}

/**
 * Fully offline Korean streaming speech recognition driver backed by bundled
 * `sherpa-onnx` and `sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile` (int8).
 *
 * All model loading, `AudioRecord` reads, and JNI stream decoding execute on a single
 * dedicated background worker thread, while all [RecognitionDriver.Listener] callbacks are
 * serialized onto the main thread with strict attempt-generation guards.
 */
internal class SherpaRecognitionDriver(
    private val audioSource: AudioCaptureSource,
    private val engineFactory: () -> StreamingAsrEngine,
    private val mainPoster: (() -> Unit) -> Unit = { action ->
        Handler(Looper.getMainLooper()).post(action)
    },
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aria-sherpa-asr").apply { isDaemon = true }
    },
    private val sampleRate: Int = SAMPLE_RATE,
    private val chunkSamples: Int = CHUNK_SAMPLES,
    private val minEndpointUtteranceMillis: Long = DEFAULT_MIN_ENDPOINT_UTTERANCE_MILLIS,
) : RecognitionDriver {

    constructor(context: Context) : this(
        audioSource = AndroidAudioRecordCaptureSource(context.applicationContext),
        engineFactory = { SherpaOnnxStreamingEngine(context.applicationContext.assets) },
    )

    private class AttemptControl(val attemptId: Long) {
        val cancelled = AtomicBoolean(false)
        val finishRequested = AtomicBoolean(false)
        @Volatile
        private var audioSession: AudioCaptureSession? = null

        fun attachAudio(session: AudioCaptureSession): Boolean {
            audioSession = session
            if (cancelled.get() || finishRequested.get()) {
                session.stopCapture()
            }
            return !cancelled.get()
        }

        fun requestFinish() {
            if (cancelled.get()) return
            if (finishRequested.compareAndSet(false, true)) {
                audioSession?.stopCapture()
            }
        }

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                audioSession?.stopCapture()
            }
        }
    }

    private val activeAttemptId = AtomicLong(0L)
    private val shutDown = AtomicBoolean(false)
    @Volatile
    private var activeListener: RecognitionDriver.Listener? = null
    @Volatile
    private var activeControl: AttemptControl? = null

    // Owned exclusively by workerExecutor thread.
    private var engine: StreamingAsrEngine? = null

    override fun start(listener: RecognitionDriver.Listener) {
        if (shutDown.get()) {
            mainPoster { listener.failed(RecognitionFailure.Unavailable) }
            return
        }
        cancelCurrentAttempt()
        val attemptId = activeAttemptId.incrementAndGet()
        val control = AttemptControl(attemptId)
        activeListener = listener
        activeControl = control

        try {
            workerExecutor.execute {
                runAttemptOnWorker(attemptId, control)
            }
        } catch (e: Exception) {
            logError("Failed to schedule ASR attempt $attemptId", e)
            deliverFailure(attemptId, control, RecognitionFailure.Unavailable)
        }
    }

    override fun finish() {
        val control = activeControl ?: return
        if (shutDown.get() || control.attemptId != activeAttemptId.get()) return
        control.requestFinish()
    }

    override fun close() {
        cancelCurrentAttempt()
    }

    override fun shutdown() {
        if (!shutDown.compareAndSet(false, true)) return
        cancelCurrentAttempt()
        runCatching {
            workerExecutor.execute {
                runCatching { engine?.close() }
                engine = null
            }
            workerExecutor.shutdown()
        }
    }

    private fun cancelCurrentAttempt() {
        activeAttemptId.incrementAndGet()
        activeListener = null
        activeControl?.cancel()
        activeControl = null
    }

    private fun isAttemptLive(attemptId: Long, control: AttemptControl): Boolean =
        !shutDown.get() && !control.cancelled.get() && activeAttemptId.get() == attemptId

    private fun runAttemptOnWorker(attemptId: Long, control: AttemptControl) {
        if (!isAttemptLive(attemptId, control)) return

        val currentEngine = try {
            engine ?: engineFactory().also { created ->
                engine = created
                logInfo("Initialized bundled local Korean Sherpa-ONNX streaming recognizer")
            }
        } catch (t: Throwable) {
            logError("Failed to initialize bundled local Korean Sherpa-ONNX recognizer", t)
            deliverFailure(attemptId, control, RecognitionFailure.Unavailable)
            return
        }

        if (!isAttemptLive(attemptId, control)) return

        val audio = try {
            audioSource.open(sampleRate, chunkSamples)
        } catch (e: SecurityException) {
            logWarn("Microphone permission denied when opening AudioCaptureSource", e)
            deliverFailure(attemptId, control, RecognitionFailure.Permission)
            return
        } catch (t: Throwable) {
            logError("Failed to open AudioCaptureSource", t)
            deliverFailure(attemptId, control, RecognitionFailure.Audio)
            return
        }

        if (!control.attachAudio(audio) || !isAttemptLive(attemptId, control)) {
            runCatching { audio.close() }
            return
        }

        val stream = try {
            currentEngine.createStream()
        } catch (t: Throwable) {
            logError("Failed to create OnlineStream", t)
            runCatching { audio.close() }
            deliverFailure(attemptId, control, RecognitionFailure.Client)
            return
        }

        try {
            deliverReady(attemptId, control)
            val buffer = FloatArray(chunkSamples)
            var utteranceSamples = 0L
            var speechStartedEmitted = false
            var speechEndedEmitted = false
            var lastPartial = ""

            while (isAttemptLive(attemptId, control)) {
                if (control.finishRequested.get()) break

                val readCount = try {
                    audio.read(buffer)
                } catch (e: AudioCaptureException) {
                    if (!isAttemptLive(attemptId, control) || control.finishRequested.get()) break
                    logWarn("Audio capture read failed during attempt $attemptId", e)
                    deliverFailure(attemptId, control, RecognitionFailure.Audio)
                    return
                }

                if (!isAttemptLive(attemptId, control)) return
                if (readCount < 0) break
                if (readCount == 0) continue

                val samples = if (readCount == buffer.size) buffer else buffer.copyOf(readCount)
                stream.acceptWaveform(samples, sampleRate)
                utteranceSamples += readCount

                while (stream.isReady() && isAttemptLive(attemptId, control)) {
                    stream.decode()
                }
                if (!isAttemptLive(attemptId, control)) return

                val text = stream.currentText().trim()
                if (text.isNotEmpty()) {
                    if (!speechStartedEmitted) {
                        speechStartedEmitted = true
                        deliverSpeechStarted(attemptId, control)
                    }
                    if (text != lastPartial) {
                        lastPartial = text
                        deliverPartial(attemptId, control, text)
                    }
                }

                if (stream.isEndpoint()) {
                    if (text.isEmpty()) {
                        // Native silence endpoint: reset decoder stream in-place and keep
                        // AudioRecord continuously listening without creating a 300ms restart gap.
                        stream.reset()
                        utteranceSamples = 0L
                        lastPartial = ""
                        continue
                    }
                    val utteranceMillis = (utteranceSamples * 1_000L) / sampleRate
                    if (utteranceMillis >= minEndpointUtteranceMillis) {
                        break
                    }
                }
            }

            if (!isAttemptLive(attemptId, control)) return

            // Release microphone promptly before draining final decoder frames.
            runCatching { audio.close() }

            // Feed 0.5s of trailing silence before inputFinished() so streaming Zipformer
            // chunk lookahead flushes the final syllables on EOF or explicit finish().
            val tailPaddingSamples = FloatArray(sampleRate / 2)
            stream.acceptWaveform(tailPaddingSamples, sampleRate)
            stream.inputFinished()
            while (stream.isReady() && isAttemptLive(attemptId, control)) {
                stream.decode()
            }
            if (!isAttemptLive(attemptId, control)) return

            val finalText = stream.currentText().trim()
            if (finalText.isNotEmpty()) {
                if (!speechStartedEmitted) {
                    speechStartedEmitted = true
                    deliverSpeechStarted(attemptId, control)
                }
                if (!speechEndedEmitted) {
                    speechEndedEmitted = true
                    deliverSpeechEnded(attemptId, control)
                }
                deliverResult(attemptId, control, finalText)
            } else if (speechStartedEmitted) {
                if (!speechEndedEmitted) {
                    speechEndedEmitted = true
                    deliverSpeechEnded(attemptId, control)
                }
                deliverFailure(attemptId, control, RecognitionFailure.NoMatch)
            } else {
                deliverFailure(attemptId, control, RecognitionFailure.NoSpeech)
            }
        } catch (t: Throwable) {
            if (isAttemptLive(attemptId, control)) {
                logError("Unexpected error during streaming ASR attempt $attemptId", t)
                deliverFailure(attemptId, control, RecognitionFailure.Client)
            }
        } finally {
            runCatching { stream.close() }
            runCatching { audio.close() }
        }
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logWarn(message: String, t: Throwable? = null) {
        runCatching { Log.w(TAG, message, t) }
    }

    private fun logError(message: String, t: Throwable? = null) {
        runCatching { Log.e(TAG, message, t) }
    }

    private fun deliverReady(attemptId: Long, control: AttemptControl) {
        mainPoster {
            if (isAttemptLive(attemptId, control)) {
                activeListener?.ready()
            }
        }
    }

    private fun deliverSpeechStarted(attemptId: Long, control: AttemptControl) {
        mainPoster {
            if (isAttemptLive(attemptId, control)) {
                activeListener?.speechStarted()
            }
        }
    }

    private fun deliverSpeechEnded(attemptId: Long, control: AttemptControl) {
        mainPoster {
            if (isAttemptLive(attemptId, control)) {
                activeListener?.speechEnded()
            }
        }
    }

    private fun deliverPartial(attemptId: Long, control: AttemptControl, text: String) {
        val candidate = text.take(VoiceLimits.MAX_TEXT_CHARS)
        mainPoster {
            if (isAttemptLive(attemptId, control)) {
                activeListener?.partial(listOf(candidate))
            }
        }
    }

    private fun deliverResult(attemptId: Long, control: AttemptControl, text: String) {
        val candidate = text.take(VoiceLimits.MAX_TEXT_CHARS)
        mainPoster {
            if (isAttemptLive(attemptId, control)) {
                activeListener?.result(listOf(candidate))
            }
        }
    }

    private fun deliverFailure(
        attemptId: Long,
        control: AttemptControl,
        failure: RecognitionFailure,
    ) {
        mainPoster {
            if (isAttemptLive(attemptId, control)) {
                activeListener?.failed(failure)
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 640 // 40ms @ 16kHz
        const val DEFAULT_MIN_ENDPOINT_UTTERANCE_MILLIS = 400L

        const val MODEL_DIR = "sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile"
        const val ENCODER_ASSET = "$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx"
        const val DECODER_ASSET = "$MODEL_DIR/decoder-epoch-99-avg-1.onnx"
        const val JOINER_ASSET = "$MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx"
        const val TOKENS_ASSET = "$MODEL_DIR/tokens.txt"
        const val BPE_MODEL_ASSET = "$MODEL_DIR/bpe.model"
        const val SAMPLE_WAV_ASSET = "$MODEL_DIR/test_wavs/0.wav"
        const val SAMPLE_TRANS_ASSET = "$MODEL_DIR/test_wavs/trans.txt"

        val REQUIRED_MODEL_ASSETS = listOf(
            ENCODER_ASSET,
            DECODER_ASSET,
            JOINER_ASSET,
            TOKENS_ASSET,
        )

        private const val TAG = "AriaSherpaDriver"

        fun verifyBundledModelAssets(assetManager: AssetManager) {
            for (assetPath in REQUIRED_MODEL_ASSETS) {
                try {
                    assetManager.open(assetPath).close()
                } catch (e: IOException) {
                    throw IllegalStateException("Missing bundled Korean ASR asset: $assetPath", e)
                }
            }
        }

        fun buildRecognizerConfig(numThreads: Int = 2): OnlineRecognizerConfig {
            return OnlineRecognizerConfig(
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
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = 5.0f,
                        minUtteranceLength = 0.0f,
                    ),
                    rule2 = EndpointRule(
                        mustContainNonSilence = true,
                        minTrailingSilence = 1.4f,
                        minUtteranceLength = 1.5f,
                    ),
                    rule3 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = 0.0f,
                        minUtteranceLength = 60.0f,
                    ),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
        }
    }
}
