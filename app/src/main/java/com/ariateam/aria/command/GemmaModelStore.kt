package com.ariateam.aria.command

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Single owner in :wakeword. DownloadManager survives UI/process recreation. */
class GemmaModelStore(private val context: Context, private val changed: (String) -> Unit) : AutoCloseable {
    private val preferences = context.getSharedPreferences("gemma_download", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val verifier = Executors.newSingleThreadExecutor()
    private var verifying = false
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    private val root get() = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
    private val pending get() = File(root, "gemma.pending")
    val modelFile get() = File(root, "gemma-e2b.litertlm")
    private val marker get() = File(root, "gemma.verified")
    var status: String = "모델 미설치"
        private set
    fun installed(): Boolean = runCatching {
        modelFile.length() == SIZE && marker.readText() == SHA256
    }.getOrDefault(false)

    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            refresh()
            main.postDelayed(this, 2_000)
        }
    }

    init { main.post(poll) }

    private fun update(value: String) {
        if (closed || status == value) return
        status = value
        changed(value)
    }

    fun start(mobileData: Boolean) {
        if (closed || verifying || installed() || preferences.getLong("id", -1) >= 0) return
        try {
            check(root.usableSpace > SIZE + 256L * 1024 * 1024) { "space" }
            pending.delete()
            modelFile.delete()
            marker.delete()
            val request = DownloadManager.Request(Uri.parse(URL))
                .setTitle("Aria Gemma 4 E2B (2.59 GB)")
                .setDescription("초기 모델 다운로드 · 설치 후 오프라인 사용")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(mobileData)
                .setAllowedOverRoaming(false)
                .setAllowedNetworkTypes(if (mobileData) DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE else DownloadManager.Request.NETWORK_WIFI)
                .setDestinationUri(Uri.fromFile(pending))
            val id = manager.enqueue(request)
            preferences.edit().putLong("id", id).commit()
            update("다운로드 대기 중")
        } catch (_: Exception) {
            update("다운로드 시작 실패 · 저장 공간과 네트워크를 확인해 주세요")
        }
    }

    fun cancel(deleteInstalled: Boolean = false) {
        generation++
        val id = preferences.getLong("id", -1)
        if (id >= 0) manager.remove(id)
        preferences.edit().remove("id").commit()
        runCatching {
            pending.delete()
            if (deleteInstalled) {
                marker.delete()
                modelFile.delete()
            }
        }
        update(if (installed()) "설치됨 · 오프라인 사용 가능" else "모델 미설치")
    }

    private fun refresh() {
        if (verifying) return
        if (installed()) { update("설치됨 · 오프라인 사용 가능"); return }
        val id = preferences.getLong("id", -1)
        if (id < 0) return
        try {
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) {
                    preferences.edit().remove("id").commit()
                    update("다운로드 없음 · 다시 시도해 주세요")
                    return
                }
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> verify()
                    DownloadManager.STATUS_FAILED -> {
                        manager.remove(id)
                        preferences.edit().remove("id").commit()
                        pending.delete()
                        update("다운로드 실패 · 다시 시도해 주세요")
                    }
                    else -> {
                        val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        update("다운로드 ${bytes * 100 / SIZE}% · Wi-Fi 기본")
                    }
                }
            }
        } catch (_: Exception) { update("다운로드 상태 확인 실패") }
    }

    private fun verify() {
        verifying = true
        val ticket = generation
        update("다운로드 파일 검증 중")
        verifier.execute {
            val valid = runCatching {
                check(pending.length() == SIZE)
                val digest = MessageDigest.getInstance("SHA-256")
                pending.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        check(!closed && generation == ticket)
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) } == SHA256
            }.getOrDefault(false)
            main.post {
                verifying = false
                if (closed || generation != ticket) return@post
                preferences.edit().remove("id").commit()
                if (valid && pending.renameTo(modelFile)) {
                    runCatching { marker.writeText(SHA256) }.onFailure { modelFile.delete() }
                    update(if (installed()) "설치됨 · 오프라인 사용 가능" else "모델 설치 실패")
                } else {
                    pending.delete()
                    update("파일 검증 실패 · 다시 다운로드해 주세요")
                }
                // Keep the verified file; removing this DownloadManager row would delete its source.
            }
        }
    }

    override fun close() {
        closed = true
        generation++
        main.removeCallbacks(poll)
        verifier.shutdown()
    }

    companion object {
        const val REVISION = "6e5c4f1e395deb959c494953478fa5cec4b8008f"
        const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        const val SIZE = 2588147712L
        const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$REVISION/gemma-4-E2B-it.litertlm"
    }
}
