package io.github.jeiel85.findmywifephone.locate

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Why [OneFix.take] returned no location. */
enum class NoFix { PERMISSION, LOCATION_OFF, NO_FIX }

/** One location fix for the owner's own "내 위치 보내기" (app in the foreground). */
object OneFix {
    private const val TIMEOUT_MS = 25_000L
    /** No new fix in time: fall back to the last known one if it is at most this old (its time is shown). */
    private const val LAST_KNOWN_MAX_AGE_MS = 10 * 60_000L

    fun hasPermission(context: Context) = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    suspend fun take(context: Context): Result<Location> {
        if (!hasPermission(context)) return Result.failure(NoFixException(NoFix.PERMISSION))
        if (context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
            return Result.failure(NoFixException(NoFix.LOCATION_OFF))
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val fresh = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await() }.getOrNull()
        }
        cts.cancel()
        val loc = fresh ?: runCatching { client.lastLocation.await() }.getOrNull()
            ?.takeIf { System.currentTimeMillis() - it.time < LAST_KNOWN_MAX_AGE_MS }
        return loc?.let { Result.success(it) } ?: Result.failure(NoFixException(NoFix.NO_FIX))
    }
}

class NoFixException(val reason: NoFix) : Exception(reason.name)
