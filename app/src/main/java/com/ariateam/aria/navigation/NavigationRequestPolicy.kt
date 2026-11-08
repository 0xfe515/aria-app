package com.ariateam.aria.navigation

/** Pure delivery guard used after suspending destination resolution and before consuming a request. */
object NavigationRequestPolicy {
    fun mayHandle(requestId: Long, consumedId: Long, currentRequestId: Long, isVisible: Boolean): Boolean =
        requestId > 0L && requestId > consumedId && requestId == currentRequestId && isVisible
}
