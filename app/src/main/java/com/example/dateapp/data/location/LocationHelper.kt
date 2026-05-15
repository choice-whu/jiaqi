package com.example.dateapp.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.example.dateapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class LocationCoordinate(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
    val accuracyMeters: Float? = null,
    val locationType: Int? = null
)

object LocationHelper {

    suspend fun getCurrentLatLng(
        context: Context,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        needAddress: Boolean = false,
        preferGps: Boolean = false,
        allowCache: Boolean = true
    ): Result<LocationCoordinate> {
        if (BuildConfig.AMAP_API_KEY.isBlank()) {
            return Result.failure(IllegalStateException("AMap API key is blank"))
        }
        if (!hasLocationPermission(context)) {
            return Result.failure(SecurityException("Location permission is missing"))
        }

        return withContext(Dispatchers.IO) {
            val requestResult = withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine<Result<LocationCoordinate>> { continuation ->
                    try {
                        AMapLocationClient.updatePrivacyShow(context, true, true)
                        AMapLocationClient.updatePrivacyAgree(context, true)

                        val locationClient = AMapLocationClient(context.applicationContext)
                        val locationOption = AMapLocationClientOption().apply {
                            locationMode =
                                AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                            isNeedAddress = needAddress
                            isOnceLocation = true
                            isOnceLocationLatest = true
                            isMockEnable = BuildConfig.DEBUG
                            httpTimeOut = timeoutMillis
                            setGpsFirst(preferGps)
                            setGpsFirstTimeout(minOf(timeoutMillis, GPS_FIRST_TIMEOUT_MS))
                            setLocationCacheEnable(allowCache)
                            setWifiScan(true)
                            setOffset(true)
                        }

                        locationClient.setLocationOption(locationOption)
                        locationClient.setLocationListener { amapLocation ->
                            if (!continuation.isActive) {
                                locationClient.stopLocation()
                                locationClient.onDestroy()
                                return@setLocationListener
                            }

                            val result = if (amapLocation != null && amapLocation.errorCode == 0) {
                                Result.success(
                                    LocationCoordinate(
                                        latitude = amapLocation.latitude,
                                        longitude = amapLocation.longitude,
                                        label = listOf(
                                            amapLocation.poiName,
                                            amapLocation.aoiName,
                                            amapLocation.address,
                                            amapLocation.district,
                                            amapLocation.city
                                        )
                                            .firstOrNull { !it.isNullOrBlank() }
                                            ?.trim(),
                                        accuracyMeters = amapLocation.accuracy,
                                        locationType = amapLocation.locationType
                                    )
                                )
                            } else {
                                Result.failure(
                                    IllegalStateException(
                                        "AMap location failed: code=${amapLocation?.errorCode}, info=${amapLocation?.errorInfo}"
                                    )
                                )
                            }

                            locationClient.stopLocation()
                            locationClient.onDestroy()
                            continuation.resume(result)
                        }

                        continuation.invokeOnCancellation {
                            locationClient.stopLocation()
                            locationClient.onDestroy()
                        }

                        locationClient.startLocation()
                    } catch (throwable: Throwable) {
                        continuation.resume(Result.failure(throwable))
                    }
                }
            }

            requestResult ?: Result.failure(
                IllegalStateException("AMap location request timed out")
            )
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private const val DEFAULT_TIMEOUT_MS = 1_600L
    private const val GPS_FIRST_TIMEOUT_MS = 3_000L
}
