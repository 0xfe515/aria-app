package com.ariateam.aria.command

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.ariateam.aria.speech.ILanguageCallback
import com.ariateam.aria.speech.ILanguageService

/** Main-thread client. A timed-out native request remains busy until it returns or is released. */
class LanguageClient(private val context: Context, private val changed: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var remote: ILanguageService? = null
    private var bound = false
    private var connection: ServiceConnection? = null
    private var epoch = 0L
    private var busy = false
    private var sequence = 0L
    private var startedAt = 0L
    private var pending: ((String?) -> Unit)? = null
    var ready = false
        private set
    private var timeout: Runnable? = null

    fun prepare(path: String) {
        if (bound) return
        val ticket = ++epoch
        startedAt = android.os.SystemClock.elapsedRealtime()
        changed("Gemma 준비 중")
        busy = true
        arm(60_000) { changed("Gemma 준비 시간 초과 · 기본 명령 사용 가능"); ready = false }
        val callback = object : ILanguageCallback.Stub() {
            override fun onResult(requestId: Long, result: String, error: String) {
                main.post {
                    if (epoch != ticket) return@post
                    if (requestId == 0L) {
                        busy = false
                        val expired = timeout == null
                        clearTimeout()
                        ready = error.isEmpty() && !expired
                        changed(if (ready) "Gemma 사용 가능 · 준비 ${android.os.SystemClock.elapsedRealtime() - startedAt}ms" else "Gemma 준비 실패 · 듣기를 다시 시작해 주세요")
                    } else if (requestId == sequence) {
                        busy = false
                        clearTimeout()
                        val reply = pending
                        pending = null
                        if (reply != null && error.isEmpty()) changed("Gemma 사용 가능 · 해석 ${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
                        reply?.invoke(result.takeIf { error.isEmpty() })
                    }
                }
            }
        }
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (epoch != ticket) return
                remote = ILanguageService.Stub.asInterface(binder)
                runCatching { remote?.prepare(path, callback) }.onFailure { failed() }
            }
            override fun onServiceDisconnected(name: ComponentName) { if (epoch == ticket) failed() }
            override fun onBindingDied(name: ComponentName) { if (epoch == ticket) failed() }
            override fun onNullBinding(name: ComponentName) { if (epoch == ticket) failed() }
        }
        connection = serviceConnection
        bound = runCatching {
            context.bindService(Intent(context, LanguageService::class.java), serviceConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        }.getOrDefault(false)
        if (!bound) failed()
        resultCallback = callback
    }

    private var resultCallback: ILanguageCallback? = null

    fun interpret(prompt: String, reply: (String?) -> Unit) {
        if (!ready || busy) { reply(null); return }
        busy = true
        pending = reply
        sequence++
        startedAt = android.os.SystemClock.elapsedRealtime()
        arm(15_000) { val old = pending; pending = null; old?.invoke(null) }
        runCatching { remote?.interpret(sequence, prompt, resultCallback) }.onFailure { failed() }
    }

    private fun failed() {
        ready = false
        busy = false
        clearTimeout()
        val reply = pending
        pending = null
        changed("Gemma 연결 오류 · 기본 명령 사용 가능")
        reply?.invoke(null)
        disconnect()
    }

    private fun arm(delay: Long, action: () -> Unit) {
        clearTimeout()
        val task = Runnable { timeout = null; action() }
        timeout = task
        main.postDelayed(task, delay)
    }

    private fun clearTimeout() { timeout?.let(main::removeCallbacks); timeout = null }

    private fun disconnect() {
        epoch++
        runCatching { remote?.release() }
        remote = null
        if (bound) connection?.let { runCatching { context.unbindService(it) } }
        bound = false
        connection = null
    }

    fun close() {
        ready = false
        busy = false
        pending = null
        clearTimeout()
        disconnect()
    }
}
