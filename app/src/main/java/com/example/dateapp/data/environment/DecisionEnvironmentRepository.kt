package com.example.dateapp.data.environment

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.dateapp.data.location.LocationHelper
import com.example.dateapp.data.WuhanKnowledgeConfig
import com.example.dateapp.data.remote.WeatherApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

data class DecisionEnvironmentSnapshot(
    val currentTime: ZonedDateTime,
    val currentTimeLabel: String,
    val weatherCondition: String,
    val userLocationLabel: String,
    val latitude: Double,
    val longitude: Double,
    val locationSource: String,
    val weatherSource: String
)

data class UserLocationSnapshot(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val source: String
)

class DecisionEnvironmentRepository(
    private val appContext: Context,
    private val weatherApiService: WeatherApiService
) {

    private var cachedLocationSnapshot: UserLocationSnapshot? = null
    private var cachedLocationCapturedAtMs: Long = 0L
    private var cachedEnvironmentSnapshot: DecisionEnvironmentSnapshot? = null
    private var cachedEnvironmentCapturedAtMs: Long = 0L
    private var cachedWeatherSnapshot: WeatherSnapshot? = null
    private var cachedWeatherCapturedAtMs: Long = 0L

    suspend fun getCurrentLocationSnapshot(): UserLocationSnapshot {
        cachedLocationSnapshot
            ?.takeIf { SystemClock.elapsedRealtime() - cachedLocationCapturedAtMs <= locationCacheTtlMillis }
            ?.let { return it }

        val location = withContext(Dispatchers.IO) {
            fetchLocationSnapshot()
        }
        return UserLocationSnapshot(
            label = location.label,
            latitude = location.latitude,
            longitude = location.longitude,
            source = location.source
        ).also(::cacheLocation)
    }

    suspend fun getFreshLocationSnapshot(): UserLocationSnapshot {
        val location = withContext(Dispatchers.IO) {
            fetchLocationSnapshot(timeoutMillis = routeLocationTimeoutMillis)
        }
        return UserLocationSnapshot(
            label = location.label,
            latitude = location.latitude,
            longitude = location.longitude,
            source = location.source
        ).also(::cacheLocation)
    }

    suspend fun getEnvironmentSnapshot(): DecisionEnvironmentSnapshot {
        val currentTime = ZonedDateTime.now(wuhanZone)
        cachedEnvironmentSnapshot
            ?.takeIf { cachedSnapshot ->
                val age = SystemClock.elapsedRealtime() - cachedEnvironmentCapturedAtMs
                val ttl = if (cachedSnapshot.hasUsableWeather()) {
                    environmentCacheTtlMillis
                } else {
                    fallbackEnvironmentCacheTtlMillis
                }
                age <= ttl
            }
            ?.let { cachedSnapshot ->
                return cachedSnapshot.copy(
                    currentTime = currentTime,
                    currentTimeLabel = currentTime.format(timeFormatter)
                )
            }

        val seedLocation = cachedLocationSnapshot ?: UserLocationSnapshot(
            label = fallbackLocation.label,
            latitude = fallbackLocation.latitude,
            longitude = fallbackLocation.longitude,
            source = fallbackLocation.source
        )
        val (location, weather) = coroutineScope {
            val weatherDeferred = async(Dispatchers.IO) {
                fetchWeatherSnapshot(seedLocation.latitude, seedLocation.longitude)
            }
            val locationSnapshot = getCurrentLocationSnapshot()
            locationSnapshot to weatherDeferred.await()
        }

        val snapshot = DecisionEnvironmentSnapshot(
            currentTime = currentTime,
            currentTimeLabel = currentTime.format(timeFormatter),
            weatherCondition = weather.description,
            userLocationLabel = location.label,
            latitude = location.latitude,
            longitude = location.longitude,
            locationSource = location.source,
            weatherSource = weather.source
        )

        Log.d(
            TAG,
            "environment time=${snapshot.currentTimeLabel} location=${snapshot.userLocationLabel} lat=${snapshot.latitude} lon=${snapshot.longitude} locationSource=${snapshot.locationSource} weather=${snapshot.weatherCondition} weatherSource=${snapshot.weatherSource}"
        )

        return snapshot.also(::cacheEnvironment)
    }

    fun getCachedOrFallbackEnvironmentSnapshot(): DecisionEnvironmentSnapshot {
        val currentTime = ZonedDateTime.now(wuhanZone)
        cachedEnvironmentSnapshot?.let { cachedSnapshot ->
            val refreshedSnapshot = cachedSnapshot.copy(
                currentTime = currentTime,
                currentTimeLabel = currentTime.format(timeFormatter)
            )
            if (refreshedSnapshot.hasUsableWeather()) {
                return refreshedSnapshot
            }

            val cachedWeather = lastKnownWeatherSnapshot(reason = "cached environment fallback")
            return cachedWeather?.let { weather ->
                refreshedSnapshot.copy(
                    weatherCondition = weather.description,
                    weatherSource = weather.source
                )
            } ?: refreshedSnapshot
        }

        return fallbackEnvironmentSnapshot(currentTime)
    }

    private suspend fun fetchLocationSnapshot(
        timeoutMillis: Long = amapLocationTimeoutMillis
    ): LocationSnapshot {
        val helperResult = LocationHelper.getCurrentLatLng(
            context = appContext,
            timeoutMillis = timeoutMillis,
            needAddress = timeoutMillis > amapLocationTimeoutMillis,
            preferGps = timeoutMillis > amapLocationTimeoutMillis,
            allowCache = timeoutMillis <= amapLocationTimeoutMillis
        )
        helperResult.getOrNull()?.let { coordinate ->
            val location = LocationSnapshot(
                label = coordinate.label ?: currentLocationLabel,
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
                source = buildString {
                    append("amap_helper")
                    coordinate.accuracyMeters?.let { append("_acc_${it.toInt()}m") }
                    coordinate.locationType?.let { append("_type_$it") }
                }
            )
            Log.d(
                TAG,
                "location source=amap_helper label=${location.label} lat=${location.latitude} lon=${location.longitude}"
            )
            return location
        }

        Log.d(
            TAG,
            "location source=amap_helper unavailable reason=${helperResult.exceptionOrNull()?.message}"
        )

        fetchLocationFromSystem()?.let { location ->
            Log.d(
                TAG,
                "location source=${location.source} label=${location.label} lat=${location.latitude} lon=${location.longitude}"
            )
            return location
        }

        Log.d(TAG, "location source=fallback label=${fallbackLocation.label}")
        return fallbackLocation
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchLocationFromSystem(): LocationSnapshot? {
        if (!hasLocationPermission()) {
            Log.d(TAG, "location source=system unavailable because location permission is missing")
            return null
        }

        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }

        val lastKnownLocation = providers.asSequence()
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                    ?.let { provider to it }
            }
            .sortedByDescending { (_, location) -> location.time }
            .firstOrNull()

        if (lastKnownLocation != null) {
            return LocationSnapshot(
                label = currentLocationLabel,
                latitude = lastKnownLocation.second.latitude,
                longitude = lastKnownLocation.second.longitude,
                source = "system_last_known_${lastKnownLocation.first.lowercase()}"
            )
        }

        for (provider in providers) {
            val currentLocation = fetchCurrentLocation(locationManager, provider)
            if (currentLocation != null) {
                return LocationSnapshot(
                    label = currentLocationLabel,
                    latitude = currentLocation.latitude,
                    longitude = currentLocation.longitude,
                    source = "system_current_${provider.lowercase()}"
                )
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchCurrentLocation(
        locationManager: LocationManager,
        provider: String
    ): Location? {
        if (!hasLocationPermission()) {
            return null
        }

        val providerEnabled = runCatching {
            provider == LocationManager.PASSIVE_PROVIDER || locationManager.isProviderEnabled(provider)
        }.getOrDefault(false)
        if (!providerEnabled) {
            return null
        }

        return withTimeoutOrNull(systemLocationTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val cancellationSignal = CancellationSignal()

                continuation.invokeOnCancellation {
                    cancellationSignal.cancel()
                }

                runCatching {
                    LocationManagerCompat.getCurrentLocation(
                        locationManager,
                        provider,
                        cancellationSignal,
                        ContextCompat.getMainExecutor(appContext)
                    ) { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                }.onFailure {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    private suspend fun fetchWeatherSnapshot(latitude: Double, longitude: Double): WeatherSnapshot {
        cachedWeatherSnapshot
            ?.takeIf { SystemClock.elapsedRealtime() - cachedWeatherCapturedAtMs <= weatherCacheTtlMillis }
            ?.let { weatherSnapshot ->
                Log.d(TAG, "weather source=memory description=${weatherSnapshot.description}")
                return weatherSnapshot.copy(source = "memory")
            }

        val weatherSnapshot = withTimeoutOrNull(weatherRequestTimeoutMillis) {
            runCatching {
                val currentWeather = weatherApiService.getCurrentWeather(
                    latitude = latitude,
                    longitude = longitude
                ).current ?: error("Weather response did not contain current data")

                val weatherCode = currentWeather.weatherCode ?: -1
                val temperature = currentWeather.temperature
                val description = buildWeatherDescription(
                    weatherCode = weatherCode,
                    temperature = temperature,
                    precipitation = currentWeather.precipitation,
                    rain = currentWeather.rain,
                    showers = currentWeather.showers,
                    snowfall = currentWeather.snowfall
                )
                Log.d(TAG, "weather source=open-meteo description=$description")

                WeatherSnapshot(
                    description = description,
                    source = "open-meteo"
                ).also(::cacheWeather)
            }.getOrElse { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                Log.d(TAG, "weather source=fallback because ${throwable.message}")
                lastKnownWeatherSnapshot(reason = throwable.message ?: "request failed") ?: WeatherSnapshot(
                    description = "\u5929\u6c14\u672a\u77e5",
                    source = "fallback"
                )
            }
        }

        return weatherSnapshot ?: run {
            Log.d(TAG, "weather source=fallback because request timed out")
            lastKnownWeatherSnapshot(reason = "request timed out") ?: WeatherSnapshot(
                description = "\u5929\u6c14\u672a\u77e5",
                source = "timeout_fallback"
            )
        }
    }

    private fun lastKnownWeatherSnapshot(reason: String): WeatherSnapshot? {
        cachedWeatherSnapshot
            ?.takeIf { it.description != "\u5929\u6c14\u672a\u77e5" }
            ?.let { weatherSnapshot ->
                Log.d(
                    TAG,
                    "weather source=cache description=${weatherSnapshot.description} because $reason"
                )
                return weatherSnapshot.copy(source = "cache")
            }

        val cachedSnapshot = cachedEnvironmentSnapshot ?: return null
        if (
            cachedSnapshot.weatherCondition == "\u5929\u6c14\u672a\u77e5" ||
            cachedSnapshot.weatherSource == "fallback" ||
            cachedSnapshot.weatherSource == "timeout_fallback"
        ) {
            return null
        }

        Log.d(
            TAG,
            "weather source=cache description=${cachedSnapshot.weatherCondition} because $reason"
        )
        return WeatherSnapshot(
            description = cachedSnapshot.weatherCondition,
            source = "cache"
        )
    }

    private fun buildWeatherDescription(
        weatherCode: Int,
        temperature: Double?,
        precipitation: Double?,
        rain: Double?,
        showers: Double?,
        snowfall: Double?
    ): String {
        val weatherText = when (weatherCode) {
            0 -> "\u6674\u6717"
            1 -> "\u5927\u81f4\u6674"
            2 -> "\u591a\u4e91"
            3 -> "\u9634\u5929"
            45, 48 -> "\u6709\u96fe"
            51, 53, 55 -> "\u6bdb\u6bdb\u96e8"
            56, 57 -> "\u51bb\u96e8"
            61, 63, 65 -> "\u4e0b\u96e8"
            66, 67 -> "\u5f3a\u964d\u96e8"
            71, 73, 75 -> "\u4e0b\u96ea"
            77 -> "\u96ea\u7c92"
            80, 81, 82 -> "\u9635\u96e8"
            85, 86 -> "\u9635\u96ea"
            95 -> "\u96f7\u9635\u96e8"
            96, 99 -> "\u5f3a\u96f7\u9635\u96e8"
            else -> "\u5929\u6c14\u5e73\u7a33"
        }

        val precipitationAmount = listOfNotNull(precipitation, rain, showers, snowfall)
            .maxOrNull()
            ?: 0.0

        return buildList {
            add(
                temperature?.let { value ->
                    "$weatherText ${String.format(Locale.US, "%.1f", value)}\u00b0C"
                } ?: weatherText
            )
            if (precipitationAmount >= 0.1) {
                add("\u964d\u6c34 ${String.format(Locale.US, "%.1f", precipitationAmount)}mm")
            }
            temperature?.let { value ->
                when {
                    value >= 35.0 -> add("\u9ad8\u6e29")
                    value >= 30.0 -> add("\u504f\u70ed")
                    value <= 5.0 -> add("\u4f4e\u6e29")
                    value <= 12.0 -> add("\u504f\u51b7")
                }
            }
        }.joinToString(" \u00b7 ")
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun cacheLocation(locationSnapshot: UserLocationSnapshot) {
        cachedLocationSnapshot = locationSnapshot
        cachedLocationCapturedAtMs = SystemClock.elapsedRealtime()
    }

    private fun cacheEnvironment(snapshot: DecisionEnvironmentSnapshot) {
        cachedEnvironmentSnapshot = snapshot
        cachedEnvironmentCapturedAtMs = SystemClock.elapsedRealtime()
        if (
            snapshot.weatherCondition != "\u5929\u6c14\u672a\u77e5" &&
            snapshot.weatherSource != "fallback" &&
            snapshot.weatherSource != "timeout_fallback"
        ) {
            cacheWeather(
                WeatherSnapshot(
                    description = snapshot.weatherCondition,
                    source = snapshot.weatherSource
                )
            )
        }
        cacheLocation(
            UserLocationSnapshot(
                label = snapshot.userLocationLabel,
                latitude = snapshot.latitude,
                longitude = snapshot.longitude,
                source = snapshot.locationSource
            )
        )
    }

    private fun fallbackEnvironmentSnapshot(currentTime: ZonedDateTime): DecisionEnvironmentSnapshot {
        val location = cachedLocationSnapshot ?: UserLocationSnapshot(
            label = fallbackLocation.label,
            latitude = fallbackLocation.latitude,
            longitude = fallbackLocation.longitude,
            source = fallbackLocation.source
        )
        val cachedWeather = lastKnownWeatherSnapshot(reason = "environment fallback")

        return DecisionEnvironmentSnapshot(
            currentTime = currentTime,
            currentTimeLabel = currentTime.format(timeFormatter),
            weatherCondition = cachedWeather?.description ?: "\u5929\u6c14\u672a\u77e5",
            userLocationLabel = location.label,
            latitude = location.latitude,
            longitude = location.longitude,
            locationSource = location.source,
            weatherSource = cachedWeather?.source ?: "fallback"
        )
    }

    private fun cacheWeather(weatherSnapshot: WeatherSnapshot) {
        cachedWeatherSnapshot = weatherSnapshot
        cachedWeatherCapturedAtMs = SystemClock.elapsedRealtime()
    }

    private fun DecisionEnvironmentSnapshot.hasUsableWeather(): Boolean {
        return weatherCondition != "\u5929\u6c14\u672a\u77e5" &&
            weatherSource != "fallback" &&
            weatherSource != "timeout_fallback"
    }

    private data class LocationSnapshot(
        val label: String,
        val latitude: Double,
        val longitude: Double,
        val source: String
    )

    private data class WeatherSnapshot(
        val description: String,
        val source: String
    )

    companion object {
        private const val TAG = "DecisionContext"
        private const val amapLocationTimeoutMillis = 1_600L
        private const val routeLocationTimeoutMillis = 6_000L
        private const val systemLocationTimeoutMillis = 900L
        private const val weatherRequestTimeoutMillis = 4_500L
        private const val locationCacheTtlMillis = 5 * 60 * 1000L
        private const val environmentCacheTtlMillis = 2 * 60 * 1000L
        private const val fallbackEnvironmentCacheTtlMillis = 8_000L
        private const val weatherCacheTtlMillis = 45 * 60 * 1000L

        private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val wuhanZone = WuhanKnowledgeConfig.zoneId
        private const val currentLocationLabel = "\u5f53\u524d\u4f4d\u7f6e\u9644\u8fd1"

        private val fallbackLocation = LocationSnapshot(
            label = WuhanKnowledgeConfig.EMERGENCY_LABEL,
            latitude = WuhanKnowledgeConfig.EMERGENCY_LAT,
            longitude = WuhanKnowledgeConfig.EMERGENCY_LNG,
            source = "wuda_huda_midpoint_fallback"
        )
    }
}
