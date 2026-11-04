package com.ariateam.aria.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-process state bridge coordinating foreground service statuses between
 * the main process and the :wakeword process.
 */
object AriaRuntimeStateBridge {
    private const val TAG = "AriaRuntimeBridge"
    private const val METHOD_SET_VOICE_ACTIVE = "setVoiceActive"
    private const val METHOD_SET_VISION_ACTIVE = "setVisionActive"
    private const val METHOD_GET_STATE = "getState"
    private const val EXTRA_ACTIVE = "active"
    private const val EXTRA_VOICE_ACTIVE = "voice_active"
    private const val EXTRA_VISION_ACTIVE = "vision_active"

    private val localVoiceActive = AtomicBoolean(false)
    private val localVisionActive = AtomicBoolean(false)

    fun getAuthority(context: Context): String = "${context.packageName}.runtime.provider"

    private fun getUri(context: Context): Uri =
        Uri.parse("content://${getAuthority(context)}")

    fun setVoiceActive(context: Context, active: Boolean) {
        localVoiceActive.set(active)
        try {
            val bundle = Bundle().apply { putBoolean(EXTRA_ACTIVE, active) }
            context.contentResolver.call(getUri(context), METHOD_SET_VOICE_ACTIVE, null, bundle)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to call runtime provider for setVoiceActive: ${e.message}")
        }
    }

    fun setVisionActive(context: Context, active: Boolean) {
        localVisionActive.set(active)
        try {
            val bundle = Bundle().apply { putBoolean(EXTRA_ACTIVE, active) }
            context.contentResolver.call(getUri(context), METHOD_SET_VISION_ACTIVE, null, bundle)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to call runtime provider for setVisionActive: ${e.message}")
        }
    }

    fun isVoiceActive(context: Context): Boolean {
        return try {
            val res = context.contentResolver.call(getUri(context), METHOD_GET_STATE, null, null)
            res?.getBoolean(EXTRA_VOICE_ACTIVE, localVoiceActive.get()) ?: localVoiceActive.get()
        } catch (e: Exception) {
            localVoiceActive.get()
        }
    }

    fun isVisionActive(context: Context): Boolean {
        return try {
            val res = context.contentResolver.call(getUri(context), METHOD_GET_STATE, null, null)
            res?.getBoolean(EXTRA_VISION_ACTIVE, localVisionActive.get()) ?: localVisionActive.get()
        } catch (e: Exception) {
            localVisionActive.get()
        }
    }

    // Direct methods for the host provider process
    internal fun handleCall(context: Context, method: String, extras: Bundle?): Bundle? {
        val result = Bundle()
        when (method) {
            METHOD_SET_VOICE_ACTIVE -> {
                val active = extras?.getBoolean(EXTRA_ACTIVE, false) ?: false
                localVoiceActive.set(active)
                result.putBoolean("success", true)
            }
            METHOD_SET_VISION_ACTIVE -> {
                val active = extras?.getBoolean(EXTRA_ACTIVE, false) ?: false
                localVisionActive.set(active)
                result.putBoolean("success", true)
            }
            METHOD_GET_STATE -> {
                result.putBoolean(EXTRA_VOICE_ACTIVE, localVoiceActive.get())
                result.putBoolean(EXTRA_VISION_ACTIVE, localVisionActive.get())
            }
        }
        return result
    }

    internal fun resetForTesting() {
        localVoiceActive.set(false)
        localVisionActive.set(false)
    }

    internal fun setVoiceActiveLocal(active: Boolean) {
        localVoiceActive.set(active)
    }

    internal fun setVisionActiveLocal(active: Boolean) {
        localVisionActive.set(active)
    }

    internal fun isVoiceActiveLocal(): Boolean = localVoiceActive.get()

    internal fun isVisionActiveLocal(): Boolean = localVisionActive.get()
}

/**
 * Initializes app runtime environment and hosts cross-process state for Aria.
 * Strictly lightweight: performs no heavy main-thread work, no service revival,
 * and does not touch or clear persisted user stop latches on incidental queries or creation.
 */
class AriaRuntimeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return AriaRuntimeStateBridge.handleCall(ctx, method, extras)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "AriaRuntimeProvider"
    }
}
