package com.ariateam.aria.cloud.auth

import android.content.Context
import android.content.Intent
import android.net.Uri

interface ChatGptBrowserLauncher {
    fun openBrowser(context: Context?, url: String): Boolean
}

class DefaultChatGptBrowserLauncher : ChatGptBrowserLauncher {
    override fun openBrowser(context: Context?, url: String): Boolean {
        if (context == null) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

interface ChatGptClock {
    fun currentTimeMillis(): Long
    fun currentEpochSec(): Long = currentTimeMillis() / 1000L
}

class SystemChatGptClock : ChatGptClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
