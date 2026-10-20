package com.ariateam.aria.command

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.ariateam.aria.speech.ILanguageCallback
import com.ariateam.aria.speech.ILanguageService

internal object LanguageLimits {
    const val PREPARE_TIMEOUT_MILLIS = 60_000L
    const val COLD_INTERPRET_TIMEOUT_MILLIS = 30_000L
    const val WARM_INTERPRET_TIMEOUT_MILLIS = 20_000L
    const val MAX_UTTERANCE_CHARS = 120
    const val MAX_PROMPT_CHARS = 320
    const val MAX_RESPONSE_CHARS = 256
    const val MIN_COMPATIBLE_CONTEXT_TOKENS = 2048
    const val MAX_CONTEXT_TOKENS = 2048
    const val MAX_OUTPUT_TOKENS = 32
    const val MIN_CPU_THREADS = 2
    const val MAX_CPU_THREADS = 4
}

sealed interface LanguageOutcome {
    data class Success(val output: String, val elapsedMillis: Long) : LanguageOutcome
    data class Failure(val reason: FailureReason, val detail: String = "") : LanguageOutcome
}

enum class FailureReason {
    ResponseTimeout,
    PreparationTimeout,
    Busy,
    NotReady,
    InferenceError,
    MemoryError,
    ConnectionError,
}

fun interface ScheduledLanguageTask {
    fun cancel()
}

interface LanguageScheduler {
    fun now(): Long
    fun schedule(delayMillis: Long, action: () -> Unit): ScheduledLanguageTask
}

interface LanguageTransport {
    interface Listener {
        fun onConnected(ticket: Long)
        fun onResult(ticket: Long, requestId: Long, result: String, error: String)
        fun onDisconnected(ticket: Long, reason: String = "disconnected")
        fun onReleased(ticket: Long)
    }

    fun connect(ticket: Long, path: String, listener: Listener): Boolean
    fun prepare(ticket: Long, path: String)
    fun interpret(ticket: Long, requestId: Long, prompt: String)
    fun release(ticket: Long)
}

internal class MainLanguageScheduler(
    private val main: Handler = Handler(Looper.getMainLooper()),
) : LanguageScheduler {
    override fun now(): Long = SystemClock.elapsedRealtime()
    override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledLanguageTask {
        val task = Runnable { action() }
        main.postDelayed(task, delayMillis)
        return ScheduledLanguageTask { main.removeCallbacks(task) }
    }
}

internal class AndroidLanguageTransport(
    private val context: Context,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : LanguageTransport {
    private class Session(
        val ticket: Long,
        val listener: LanguageTransport.Listener,
        val callback: ILanguageCallback,
        val connection: ServiceConnection,
        var binder: IBinder? = null,
        var remote: ILanguageService? = null,
        var deathRecipient: IBinder.DeathRecipient? = null,
        var bound: Boolean = false,
        var retiring: Boolean = false,
        var releaseConfirmed: Boolean = false,
    )

    private val sessions = mutableMapOf<Long, Session>()

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    override fun connect(ticket: Long, path: String, listener: LanguageTransport.Listener): Boolean {
        val callback = object : ILanguageCallback.Stub() {
            override fun onResult(requestId: Long, result: String, error: String) {
                runOnMain {
                    val session = sessions[ticket]
                    if (session != null && !session.retiring) {
                        listener.onResult(ticket, requestId, result, error)
                    }
                }
            }
        }
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runOnMain {
                    val session = sessions[ticket] ?: return@runOnMain
                    if (session.retiring) {
                        runCatching { ILanguageService.Stub.asInterface(binder).release() }
                        if (!binder.isBinderAlive() || !binder.pingBinder()) {
                            completeRetirement(session)
                        } else {
                            attachDeathWatcher(session, binder)
                        }
                        return@runOnMain
                    }
                    session.binder = binder
                    session.remote = ILanguageService.Stub.asInterface(binder)
                    attachDeathWatcher(session, binder)
                    listener.onConnected(ticket)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                runOnMain {
                    val session = sessions[ticket] ?: return@runOnMain
                    if (session.retiring) {
                        val b = session.binder
                        if (b == null || !b.isBinderAlive() || !b.pingBinder()) {
                            completeRetirement(session)
                        }
                    } else {
                        listener.onDisconnected(ticket, "service_disconnected")
                    }
                }
            }

            override fun onBindingDied(name: ComponentName) {
                runOnMain {
                    val session = sessions[ticket] ?: return@runOnMain
                    session.binder = null
                    if (session.retiring) {
                        completeRetirement(session)
                    } else {
                        listener.onDisconnected(ticket, "binding_died")
                    }
                }
            }

            override fun onNullBinding(name: ComponentName) {
                runOnMain {
                    val session = sessions[ticket] ?: return@runOnMain
                    session.binder = null
                    if (session.retiring) {
                        completeRetirement(session)
                    } else {
                        listener.onDisconnected(ticket, "null_binding")
                    }
                }
            }
        }
        val session = Session(
            ticket = ticket,
            listener = listener,
            callback = callback,
            connection = serviceConnection,
            bound = true,
        )
        sessions[ticket] = session
        val boundOk = runCatching {
            context.bindService(
                Intent(context, LanguageService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        }.getOrDefault(false)
        if (!boundOk) {
            session.retiring = true
            session.bound = false
            sessions.remove(ticket)
            runCatching { context.unbindService(serviceConnection) }
            return false
        }
        return true
    }

    private fun attachDeathWatcher(session: Session, binder: IBinder) {
        val recipient = IBinder.DeathRecipient {
            runOnMain {
                val current = sessions[session.ticket] ?: return@runOnMain
                current.binder = null
                if (current.retiring) {
                    completeRetirement(current)
                } else {
                    current.listener.onDisconnected(current.ticket, "binding_died")
                }
            }
        }
        session.deathRecipient = recipient
        val linked = runCatching { binder.linkToDeath(recipient, 0); true }.getOrDefault(false)
        if (!linked) {
            session.deathRecipient = null
            if (session.retiring) {
                completeRetirement(session)
            } else {
                session.listener.onDisconnected(session.ticket, "binding_died")
            }
        }
    }

    override fun prepare(ticket: Long, path: String) {
        val session = checkNotNull(sessions[ticket]?.takeIf { !it.retiring }) { "No active session for $ticket" }
        val service = checkNotNull(session.remote) { "Service not connected for $ticket" }
        service.prepare(path, session.callback)
    }

    override fun interpret(ticket: Long, requestId: Long, prompt: String) {
        val session = checkNotNull(sessions[ticket]?.takeIf { !it.retiring }) { "No active session for $ticket" }
        val service = checkNotNull(session.remote) { "Service not connected for $ticket" }
        service.interpret(requestId, prompt, session.callback)
    }

    override fun release(ticket: Long) {
        val session = sessions[ticket] ?: return
        session.retiring = true
        val remote = session.remote
        session.remote = null
        if (session.bound) {
            session.bound = false
            runCatching { context.unbindService(session.connection) }
        }
        runCatching { remote?.release() }
        val binder = session.binder
        if (binder == null || !binder.isBinderAlive() || !binder.pingBinder()) {
            completeRetirement(session)
        } else {
            pollUntilDead(session)
        }
    }

    private fun pollUntilDead(session: Session) {
        if (session.releaseConfirmed) return
        val binder = session.binder
        if (binder == null || !binder.isBinderAlive() || !binder.pingBinder()) {
            completeRetirement(session)
            return
        }
        main.postDelayed({ pollUntilDead(session) }, 50L)
    }

    private fun completeRetirement(session: Session) {
        if (session.releaseConfirmed) return
        session.releaseConfirmed = true
        sessions.remove(session.ticket)
        val binder = session.binder
        val recipient = session.deathRecipient
        session.binder = null
        session.deathRecipient = null
        if (binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        if (session.bound) {
            session.bound = false
            runCatching { context.unbindService(session.connection) }
        }
        session.listener.onReleased(session.ticket)
    }
}

/**
 * Serialized main-thread lifecycle client for the isolated `:language` process.
 * Timeouts and initialization errors release and unbind the dedicated process and gate any
 * replacement bind until the retiring `:language` binder is confirmed dead.
 */
class LanguageClient internal constructor(
    private val transport: LanguageTransport,
    private val scheduler: LanguageScheduler,
    private val prepareTimeoutMillis: Long = LanguageLimits.PREPARE_TIMEOUT_MILLIS,
    private val coldInterpretTimeoutMillis: Long = LanguageLimits.COLD_INTERPRET_TIMEOUT_MILLIS,
    private val warmInterpretTimeoutMillis: Long = LanguageLimits.WARM_INTERPRET_TIMEOUT_MILLIS,
    private val changed: (String) -> Unit,
) {
    constructor(context: Context, changed: (String) -> Unit) : this(
        transport = AndroidLanguageTransport(context),
        scheduler = MainLanguageScheduler(),
        changed = changed,
    )

    private var bound = false
    private var epoch = 0L
    private var retiringTicket = 0L
    private var pendingPreparePath: String? = null
    private var sequence = 0L
    private var activeRequestId = 0L
    private var startedAt = 0L
    private var currentPath = ""
    private var pending: ((LanguageOutcome) -> Unit)? = null
    private var timeout: ScheduledLanguageTask? = null

    var ready = false
        private set
    var busy = false
        private set
    var warmed = false
        private set
    var lastStatus = "Gemma 대기"
        private set

    val preparing: Boolean
        get() = (bound && !ready && busy) || pendingPreparePath != null

    private val transportListener = object : LanguageTransport.Listener {
        override fun onConnected(ticket: Long) {
            if (epoch != ticket || !bound) return
            runCatching { transport.prepare(ticket, currentPath) }.onFailure {
                if (epoch == ticket) failConnection(ticket, "prepare_ipc_failed")
            }
        }

        override fun onResult(ticket: Long, requestId: Long, result: String, error: String) {
            if (epoch != ticket || !bound) return
            if (requestId == 0L) {
                if (ready || !busy || activeRequestId != 0L) return
                val elapsed = (scheduler.now() - startedAt).coerceAtLeast(0L)
                clearTimeout()
                if (error.isEmpty()) {
                    busy = false
                    ready = true
                    emitStatus("Gemma 사용 가능 · 준비 ${elapsed}ms")
                } else {
                    disconnect(ticket)
                    emitStatus(
                        if (error == "memory") {
                            "Gemma 메모리 부족 · 기본 명령 사용 가능"
                        } else {
                            "Gemma 준비 실패 · 다음 명령 시 다시 준비"
                        }
                    )
                }
            } else if (requestId == activeRequestId) {
                val elapsed = (scheduler.now() - startedAt).coerceAtLeast(0L)
                clearTimeout()
                busy = false
                activeRequestId = 0L
                val reply = pending
                pending = null
                if (error.isEmpty()) {
                    warmed = true
                    emitStatus("Gemma 사용 가능 · 해석 ${elapsed}ms")
                    reply?.invoke(LanguageOutcome.Success(result, elapsed))
                } else if (error == "memory") {
                    disconnect(ticket)
                    emitStatus("Gemma 메모리 부족 · 기본 명령 사용 가능")
                    reply?.invoke(LanguageOutcome.Failure(FailureReason.MemoryError, error))
                } else if (error == "uninitialized" || error == "busy") {
                    disconnect(ticket)
                    emitStatus("Gemma 해석 오류 · 다음 명령 시 다시 준비")
                    reply?.invoke(LanguageOutcome.Failure(FailureReason.InferenceError, error))
                } else {
                    emitStatus("Gemma 해석 오류 · 기본 명령 사용 가능")
                    reply?.invoke(LanguageOutcome.Failure(FailureReason.InferenceError, error))
                }
            }
        }

        override fun onDisconnected(ticket: Long, reason: String) {
            failConnection(ticket, reason)
        }

        override fun onReleased(ticket: Long) {
            if (retiringTicket != ticket) return
            retiringTicket = 0L
            val nextPath = pendingPreparePath ?: return
            pendingPreparePath = null
            startPrepare(nextPath)
        }
    }

    fun prepare(path: String) {
        if (bound) return
        if (retiringTicket != 0L) {
            pendingPreparePath = path
            emitStatus("Gemma 이전 프로세스 정리 중 · 완료 후 준비")
            return
        }
        startPrepare(path)
    }

    private fun startPrepare(path: String) {
        val ticket = ++epoch
        currentPath = path
        bound = true
        ready = false
        busy = true
        warmed = false
        activeRequestId = 0L
        pending = null
        startedAt = scheduler.now()
        emitStatus("Gemma 준비 중 · 최대 ${prepareTimeoutMillis / 1_000}초")
        arm(ticket, prepareTimeoutMillis) { onPrepareTimeout(ticket) }
        val connected = runCatching { transport.connect(ticket, path, transportListener) }.getOrDefault(false)
        if (!connected && epoch == ticket) {
            failConnection(ticket, "bind_failed", releaseRequired = false)
        }
    }

    fun interpret(prompt: String, reply: (String?) -> Unit) {
        interpretOutcome(prompt) { outcome ->
            reply((outcome as? LanguageOutcome.Success)?.output)
        }
    }

    fun interpretOutcome(prompt: String, reply: (LanguageOutcome) -> Unit) {
        if (!ready) {
            reply(LanguageOutcome.Failure(FailureReason.NotReady))
            return
        }
        if (busy) {
            reply(LanguageOutcome.Failure(FailureReason.Busy))
            return
        }
        val ticket = epoch
        val requestId = ++sequence
        val deadlineMillis = if (warmed) warmInterpretTimeoutMillis else coldInterpretTimeoutMillis
        busy = true
        activeRequestId = requestId
        pending = reply
        startedAt = scheduler.now()
        emitStatus("Gemma 해석 중 · 최대 ${deadlineMillis / 1_000}초")
        arm(ticket, deadlineMillis) { onInterpretTimeout(ticket, requestId) }
        runCatching { transport.interpret(ticket, requestId, prompt) }.onFailure {
            if (epoch == ticket && activeRequestId == requestId) {
                failConnection(ticket, "interpret_ipc_failed")
            }
        }
    }

    private fun onPrepareTimeout(ticket: Long) {
        if (epoch != ticket || !bound || ready) return
        disconnect(ticket)
        emitStatus("Gemma 준비 시간 초과 · 다음 명령 시 다시 준비")
    }

    private fun onInterpretTimeout(ticket: Long, requestId: Long) {
        if (epoch != ticket || activeRequestId != requestId) return
        val reply = pending
        pending = null
        activeRequestId = 0L
        disconnect(ticket)
        emitStatus("Gemma 응답 시간 초과 · 다음 명령 시 다시 준비")
        reply?.invoke(LanguageOutcome.Failure(FailureReason.ResponseTimeout))
    }

    private fun failConnection(ticket: Long, reason: String, releaseRequired: Boolean = true) {
        if (epoch != ticket) return
        val reply = pending
        pending = null
        activeRequestId = 0L
        disconnect(ticket, releaseRequired = releaseRequired)
        emitStatus("Gemma 연결 오류 · 다음 명령 시 다시 준비")
        reply?.invoke(LanguageOutcome.Failure(FailureReason.ConnectionError, reason))
    }

    private fun arm(ticket: Long, delayMillis: Long, action: () -> Unit) {
        clearTimeout()
        timeout = scheduler.schedule(delayMillis) {
            if (epoch == ticket) {
                timeout = null
                action()
            }
        }
    }

    private fun clearTimeout() {
        timeout?.cancel()
        timeout = null
    }

    private fun disconnect(ticket: Long, releaseRequired: Boolean = true) {
        clearTimeout()
        val wasBound = bound
        ready = false
        busy = false
        bound = false
        warmed = false
        activeRequestId = 0L
        if (epoch == ticket) {
            epoch++
        }
        if (wasBound && ticket != 0L) {
            if (releaseRequired) {
                retiringTicket = ticket
            }
            transport.release(ticket)
            if (!releaseRequired && retiringTicket == ticket) {
                retiringTicket = 0L
            }
        }
    }

    private fun emitStatus(value: String) {
        lastStatus = value
        changed(value)
    }

    fun close() {
        val ticket = epoch
        pendingPreparePath = null
        pending = null
        activeRequestId = 0L
        disconnect(ticket)
    }
}
