package com.ariateam.aria.navigation

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ResolvedDestination(val name: String, val latitude: Double, val longitude: Double) {
    val isWithinNaverBounds: Boolean
        get() = latitude in NAVER_MIN_LAT..NAVER_MAX_LAT && longitude in NAVER_MIN_LNG..NAVER_MAX_LNG

    companion object {
        const val NAVER_MIN_LAT = 31.43
        const val NAVER_MAX_LAT = 44.35
        const val NAVER_MIN_LNG = 122.37
        const val NAVER_MAX_LNG = 132.00

        fun fromCoordinates(name: String, latitude: Double, longitude: Double): ResolvedDestination? {
            val result = ResolvedDestination(name.ifBlank { "목적지" }, latitude, longitude)
            return result.takeIf { it.isWithinNaverBounds }
        }
    }

    fun withQueryLabel(query: String): ResolvedDestination = copy(name = query.trim())
}

sealed interface DestinationResolution {
    data class Resolved(val destination: ResolvedDestination) : DestinationResolution
    data object Ambiguous : DestinationResolution
    data object Unavailable : DestinationResolution
    data object NotFound : DestinationResolution
}

fun DestinationResolution.withQueryLabel(query: String): DestinationResolution = when (this) {
    is DestinationResolution.Resolved -> copy(destination = destination.withQueryLabel(query))
    DestinationResolution.Ambiguous, DestinationResolution.Unavailable, DestinationResolution.NotFound -> this
}

/** Geocoder is best-effort: callers impose a timeout and effect cancellation prevents stale handoffs. */
class AndroidDestinationResolver(context: Context) {
    private val geocoder = Geocoder(context.applicationContext, Locale.KOREAN)

    suspend fun resolve(query: String): DestinationResolution = withContext(Dispatchers.IO) {
        parseCoordinates(query)?.let { return@withContext DestinationResolution.Resolved(it) }
        if (!Geocoder.isPresent()) return@withContext DestinationResolution.Unavailable
        try {
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocodeAsync(query)
            } else {
                geocodeLegacy(query)
            }
            choose(addresses)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DestinationResolution.Unavailable
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private suspend fun geocodeAsync(query: String): List<Address> = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        continuation.invokeOnCancellation { completed.set(true) }
        geocoder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<Address>) {
                completeOnce(continuation, completed, Result.success(addresses))
            }

            override fun onError(errorMessage: String?) {
                completeOnce(
                    continuation,
                    completed,
                    Result.failure(IllegalStateException(errorMessage ?: "Geocoding failed")),
                )
            }
        })
    }

    @Suppress("DEPRECATION")
    private suspend fun geocodeLegacy(query: String): List<Address> = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val future: Future<*> = try {
            LEGACY_GEOCODER.submit {
                try {
                    val result = geocoder.getFromLocationName(query, 5).orEmpty()
                    completeOnce(continuation, completed, Result.success(result))
                } catch (error: Exception) {
                    completeOnce(continuation, completed, Result.failure(error))
                }
            }
        } catch (error: RejectedExecutionException) {
            completeOnce(continuation, completed, Result.failure(error))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation {
            completed.set(true)
            future.cancel(true)
            LEGACY_GEOCODER.purge()
        }
    }

    companion object {
        private val LEGACY_GEOCODER = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), { runnable ->
                Thread(runnable, "Aria-Geocoder").apply { isDaemon = true }
            }, ThreadPoolExecutor.AbortPolicy(),
        )
        private val COORDINATES = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[, ]\s*(-?\d+(?:\.\d+)?)\s*$""")

        fun parseCoordinates(value: String): ResolvedDestination? {
            val match = COORDINATES.matchEntire(value) ?: return null
            val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
            val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
            return ResolvedDestination.fromCoordinates(value.trim(), latitude, longitude)
        }

        fun choose(addresses: List<Address>): DestinationResolution {
            val plausible = addresses.mapNotNull { address ->
                if (!address.hasLatitude() || !address.hasLongitude()) return@mapNotNull null
                val name = listOfNotNull(address.featureName, address.thoroughfare, address.locality)
                    .firstOrNull(String::isNotBlank) ?: address.getAddressLine(0) ?: return@mapNotNull null
                ResolvedDestination.fromCoordinates(name, address.latitude, address.longitude)
            }
            return chooseCandidates(plausible)
        }

        fun chooseCandidates(plausible: List<ResolvedDestination>): DestinationResolution {
            val valid = plausible.filter(ResolvedDestination::isWithinNaverBounds)
            if (valid.isEmpty()) return DestinationResolution.NotFound
            val unique = valid.distinctBy {
                "${it.name.trim().lowercase(Locale.ROOT)}:${"%.5f".format(Locale.ROOT, it.latitude)}:${"%.5f".format(Locale.ROOT, it.longitude)}"
            }
            return if (unique.size == 1) DestinationResolution.Resolved(unique.single())
            else DestinationResolution.Ambiguous
        }

        private fun <T> completeOnce(
            continuation: CancellableContinuation<T>,
            completed: AtomicBoolean,
            result: Result<T>,
        ) {
            if (completed.compareAndSet(false, true)) continuation.resumeWith(result)
        }
    }
}
