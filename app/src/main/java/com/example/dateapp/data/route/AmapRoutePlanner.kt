package com.example.dateapp.data.route

import android.content.Context
import android.util.Log
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.route.BusRouteResult
import com.amap.api.services.route.DriveRouteResult
import com.amap.api.services.route.RideRouteResult
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.WalkRouteResult
import com.example.dateapp.BuildConfig
import com.example.dateapp.data.environment.UserLocationSnapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal class AmapRoutePlanner(context: Context) {
    private val appContext = context.applicationContext

    suspend fun plan(
        origin: UserLocationSnapshot,
        destination: RouteDestination
    ): RoutePlanMetrics {
        check(BuildConfig.AMAP_API_KEY.isNotBlank()) {
            "amap.apiKey is blank"
        }

        val transportMode = chooseTransportMode(
            originLatitude = origin.latitude,
            originLongitude = origin.longitude,
            destinationLatitude = destination.latitude,
            destinationLongitude = destination.longitude
        )
        val routeMetrics = requestRouteMetrics(
            origin = origin,
            destination = destination,
            transportMode = transportMode
        )

        Log.d(
            TAG,
            "route source=amap_sdk transport=${transportMode.name} duration=${routeMetrics.durationMinutes} distance=${routeMetrics.distanceMeters} origin=${origin.latitude},${origin.longitude} destination=${destination.label}(${destination.latitude},${destination.longitude})"
        )

        return RoutePlanMetrics(
            transportMode = transportMode,
            distanceMeters = routeMetrics.distanceMeters,
            durationMinutes = routeMetrics.durationMinutes,
            sourceBadge = "高德路线"
        )
    }

    private suspend fun requestRouteMetrics(
        origin: UserLocationSnapshot,
        destination: RouteDestination,
        transportMode: RouteTransportMode
    ): RouteMetrics {
        configureAmapSearchSdk()
        val fromAndTo = RouteSearch.FromAndTo(
            LatLonPoint(origin.latitude, origin.longitude),
            LatLonPoint(destination.latitude, destination.longitude)
        )

        val routeMetrics = withTimeoutOrNull(ROUTE_SEARCH_TIMEOUT_MS) {
            suspendCancellableCoroutine<RouteMetrics?> { continuation ->
                runCatching {
                    val routeSearch = RouteSearch(appContext).apply {
                        setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                            override fun onWalkRouteSearched(result: WalkRouteResult?, code: Int) {
                                if (!continuation.isActive) return
                                if (code == AMapException.CODE_AMAP_SUCCESS) {
                                    continuation.resume(result?.paths?.firstOrNull()?.toRouteMetrics())
                                } else {
                                    Log.d(TAG, "route source=amap_sdk_walk failed code=$code")
                                    continuation.resume(null)
                                }
                            }

                            override fun onDriveRouteSearched(result: DriveRouteResult?, code: Int) {
                                if (!continuation.isActive) return
                                if (code == AMapException.CODE_AMAP_SUCCESS) {
                                    continuation.resume(result?.paths?.firstOrNull()?.toRouteMetrics())
                                } else {
                                    Log.d(TAG, "route source=amap_sdk_drive failed code=$code")
                                    continuation.resume(null)
                                }
                            }

                            override fun onBusRouteSearched(result: BusRouteResult?, code: Int) = Unit
                            override fun onRideRouteSearched(result: RideRouteResult?, code: Int) = Unit
                        })
                    }

                    when (transportMode) {
                        RouteTransportMode.WALK -> {
                            val query = RouteSearch.WalkRouteQuery(
                                fromAndTo,
                                RouteSearch.WalkDefault
                            )
                            routeSearch.calculateWalkRouteAsyn(query)
                        }

                        RouteTransportMode.DRIVE -> {
                            val query = RouteSearch.DriveRouteQuery(
                                fromAndTo,
                                RouteSearch.DrivingDefault,
                                null,
                                null,
                                ""
                            )
                            routeSearch.calculateDriveRouteAsyn(query)
                        }
                    }
                }.onFailure { throwable ->
                    if (continuation.isActive) {
                        Log.d(TAG, "route source=amap_sdk exception=${throwable.message}")
                        continuation.resume(null)
                    }
                }
            }
        }

        return routeMetrics ?: error("AMap Android route SDK returned no valid route")
    }

    private fun com.amap.api.services.route.Path.toRouteMetrics(): RouteMetrics {
        return RouteMetrics(
            distanceMeters = distance.roundToInt().coerceAtLeast(1),
            durationMinutes = (duration / 60.0).roundToInt().coerceAtLeast(1)
        )
    }

    private fun chooseTransportMode(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double
    ): RouteTransportMode {
        val directDistanceKm = RouteMath.haversineKm(
            lat1 = originLatitude,
            lon1 = originLongitude,
            lat2 = destinationLatitude,
            lon2 = destinationLongitude
        )
        return if (directDistanceKm <= RouteMath.WALKING_DISTANCE_THRESHOLD_KM) {
            RouteTransportMode.WALK
        } else {
            RouteTransportMode.DRIVE
        }
    }

    private fun configureAmapSearchSdk() {
        ServiceSettings.updatePrivacyShow(appContext, true, true)
        ServiceSettings.updatePrivacyAgree(appContext, true)
        ServiceSettings.getInstance().apply {
            setApiKey(BuildConfig.AMAP_API_KEY)
            connectionTimeOut = AMAP_SEARCH_CONNECT_TIMEOUT_MS
            soTimeOut = AMAP_SEARCH_SOCKET_TIMEOUT_MS
        }
    }

    private companion object {
        private const val TAG = "RoutePlanning"
        private const val ROUTE_SEARCH_TIMEOUT_MS = 10_000L
        private const val AMAP_SEARCH_CONNECT_TIMEOUT_MS = 8_000
        private const val AMAP_SEARCH_SOCKET_TIMEOUT_MS = 10_000
    }
}
