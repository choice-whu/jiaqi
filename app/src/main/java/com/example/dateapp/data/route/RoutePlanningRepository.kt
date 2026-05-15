package com.example.dateapp.data.route

import android.content.Context
import android.util.Log
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.geocoder.GeocodeAddress
import com.amap.api.services.geocoder.GeocodeQuery
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.example.dateapp.BuildConfig
import com.example.dateapp.data.environment.DecisionEnvironmentRepository
import com.example.dateapp.data.environment.UserLocationSnapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class RouteTargetRequest(
    val title: String,
    val category: String,
    val displayLocation: String?,
    val searchKeyword: String?,
    val latitude: Double?,
    val longitude: Double?,
    val sourceLabel: String,
    val tag: String?
)

enum class RouteTransportMode(
    val label: String,
    val emoji: String
) {
    WALK("\u6b65\u884c", "\uD83D\uDEB6"),
    DRIVE("\u6253\u8f66", "\uD83D\uDE95")
}

data class TimelineRoutePlan(
    val title: String,
    val category: String,
    val sourceLabel: String,
    val tag: String?,
    val originLabel: String,
    val destinationLabel: String,
    val transportMode: RouteTransportMode,
    val durationMinutes: Int,
    val durationLabel: String,
    val distanceMeters: Int,
    val distanceLabel: String,
    val arrivalLabel: String,
    val previewImageUrl: String?,
    val sourceBadge: String,
    val originLatitude: Double,
    val originLongitude: Double,
    val destinationLatitude: Double,
    val destinationLongitude: Double
)

data class NearbyMallContext(
    val name: String,
    val distanceMeters: Int,
    val label: String
)

data class RouteResolvedPlace(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val resolutionSource: String,
    val directDistanceMeters: Int
)

class RoutePlanningRepository(
    context: Context,
    private val environmentRepository: DecisionEnvironmentRepository
) {
    private val appContext = context.applicationContext
    private val amapRoutePlanner = AmapRoutePlanner(appContext)
    private val fallbackEstimator = RouteFallbackEstimator()

    suspend fun planRoute(target: RouteTargetRequest): TimelineRoutePlan {
        val origin = runCatching {
            environmentRepository.getFreshLocationSnapshot()
        }.getOrElse { throwable ->
            Log.d(TAG, "route source=origin_fallback reason=${throwable.message}")
            emergencyOrigin
        }

        val destination = runCatching {
            resolveDestinationPrecisely(
                target = target,
                origin = origin
            )
        }.getOrElse { throwable ->
            Log.d(TAG, "route source=destination_fallback reason=${throwable.message}")
            resolveDestinationWithoutAmap(
                target = target,
                origin = origin
            )
        }

        val routePlanMetrics = runCatching {
            amapRoutePlanner.plan(
                origin = origin,
                destination = destination
            )
        }.getOrElse { throwable ->
            Log.d(TAG, "route source=fallback reason=${throwable.message}")
            fallbackEstimator.estimate(
                origin = origin,
                destination = destination,
                reason = throwable.message
            )
        }

        return buildRoutePlan(
            target = target,
            origin = origin,
            destination = destination,
            transportMode = routePlanMetrics.transportMode,
            distanceMeters = routePlanMetrics.distanceMeters,
            durationMinutes = routePlanMetrics.durationMinutes,
            sourceBadge = routePlanMetrics.sourceBadge,
            previewImageUrl = null
        )
    }

    suspend fun estimateDirectDistanceMeters(
        target: RouteTargetRequest,
        origin: UserLocationSnapshot
    ): Int? {
        return runCatching {
            val destination = resolveDestinationPrecisely(
                target = target,
                origin = origin
            )
            val distanceMeters = (RouteMath.haversineKm(
                lat1 = origin.latitude,
                lon1 = origin.longitude,
                lat2 = destination.latitude,
                lon2 = destination.longitude
            ) * 1000.0).roundToInt()
            Log.d(
                TAG,
                "route source=distance_probe title=${target.title} distance=$distanceMeters destination=${destination.label}"
            )
            distanceMeters
        }.getOrElse { throwable ->
            Log.d(TAG, "route source=distance_probe_failed title=${target.title} reason=${throwable.message}")
            null
        }
    }

    suspend fun resolvePlaceForDecision(
        target: RouteTargetRequest,
        origin: UserLocationSnapshot
    ): RouteResolvedPlace? {
        return runCatching {
            val destination = resolveDestinationPrecisely(
                target = target,
                origin = origin
            )
            val distanceMeters = (RouteMath.haversineKm(
                lat1 = origin.latitude,
                lon1 = origin.longitude,
                lat2 = destination.latitude,
                lon2 = destination.longitude
            ) * 1000.0).roundToInt()

            Log.d(
                TAG,
                "route source=decision_place title=${target.title} distance=$distanceMeters destination=${destination.label} resolution=${destination.resolutionSource}"
            )

            RouteResolvedPlace(
                label = destination.label,
                latitude = destination.latitude,
                longitude = destination.longitude,
                resolutionSource = destination.resolutionSource,
                directDistanceMeters = distanceMeters
            )
        }.getOrElse { throwable ->
            Log.d(TAG, "route source=decision_place_failed title=${target.title} reason=${throwable.message}")
            null
        }
    }

    suspend fun detectNearbyMall(origin: UserLocationSnapshot): NearbyMallContext? {
        configureAmapSearchSdk()
        val mallPois = withTimeoutOrNull(NEARBY_MALL_TOTAL_TIMEOUT_MS) {
            buildList {
                RoutePlaceCatalog.mallSearchQueries.forEach { query ->
                    addAll(
                        searchPoiWithAmapSdk(
                            query = query,
                            origin = origin,
                            radiusMeters = NEARBY_MALL_RADIUS_METERS,
                            pageSize = NEARBY_MALL_PAGE_SIZE,
                            timeoutMillis = NEARBY_MALL_QUERY_TIMEOUT_MS
                        )
                    )
                }
            }
        }.orEmpty()

        val mall = mallPois
            .filter { poi ->
                val distance = poi.distance
                distance in 1..NEARBY_MALL_RADIUS_METERS && RoutePlaceCatalog.isShoppingMallPoi(poi)
            }
            .distinctBy { poi -> poi.title.orEmpty().trim() }
            .minByOrNull { poi -> poi.distance }
            ?: return null

        val mallName = mall.title?.trim().orEmpty()
        if (mallName.isEmpty()) {
            return null
        }

        val context = NearbyMallContext(
            name = mallName,
            distanceMeters = mall.distance,
            label = if (mall.distance < 120) {
                mallName
            } else {
                "$mallName ${mall.distance}m"
            }
        )
        Log.d(TAG, "mall context source=amap_sdk name=${context.name} distance=${context.distanceMeters}")
        return context
    }

    private suspend fun resolveDestinationPrecisely(
        target: RouteTargetRequest,
        origin: UserLocationSnapshot
    ): RouteDestination {
        resolveDestinationWithAmapSdk(target, origin)?.let { return it }
        resolveDestinationWithAmapGeocodeSdk(target)?.let { return it }
        if (target.category == "play") {
            resolveDestinationWithFallbackLandmark(target)?.let { return it }
        }

        val directLatitude = target.latitude
        val directLongitude = target.longitude
        if (
            directLatitude != null &&
            directLongitude != null &&
            RouteMath.haversineKm(origin.latitude, origin.longitude, directLatitude, directLongitude) >= MIN_ROUTE_DISTANCE_KM
        ) {
            return RouteDestination(
                label = target.displayLocation ?: target.searchKeyword ?: target.title,
                latitude = directLatitude,
                longitude = directLongitude,
                resolutionSource = "direct_coordinates"
            )
        }

        resolveDestinationWithFallbackLandmark(target)?.let { return it }

        error("Destination could not be resolved by AMap SDK: ${buildDestinationMatchSeed(target)}")
    }

    private fun resolveDestinationWithFallbackLandmark(target: RouteTargetRequest): RouteDestination? {
        val entry = RoutePlaceCatalog.fallbackLandmarks.entries.firstOrNull { entry ->
            val keyword = entry.key
            buildDestinationMatchSeed(target).contains(keyword, ignoreCase = true)
        } ?: return null

        val coordinate = entry.value
        return RouteDestination(
            label = target.displayLocation ?: target.searchKeyword ?: target.title,
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            resolutionSource = "fallback_landmark"
        )
    }

    private suspend fun resolveDestinationWithAmapSdk(
        target: RouteTargetRequest,
        origin: UserLocationSnapshot
    ): RouteDestination? {
        val queries = buildDestinationQueries(target)

        queries.forEach { query ->
            val pois = searchPoiWithAmapSdk(query, origin)
            val poi = selectBestPoi(pois, target)
            val poiLocation = poi?.latLonPoint
            if (poi != null && poiLocation != null) {
                Log.d(
                    TAG,
                    "route poi source=amap_sdk query=$query name=${poi.title} distance=${poi.distance} lat=${poiLocation.latitude} lon=${poiLocation.longitude}"
                )
                return RouteDestination(
                    label = buildPoiLabel(poi, query, target),
                    latitude = poiLocation.latitude,
                    longitude = poiLocation.longitude,
                    resolutionSource = "amap_sdk_poi"
                )
            }
        }

        return null
    }

    private suspend fun searchPoiWithAmapSdk(
        query: String,
        origin: UserLocationSnapshot,
        radiusMeters: Int = POI_SEARCH_RADIUS_METERS,
        pageSize: Int = POI_PAGE_SIZE,
        timeoutMillis: Long = POI_SEARCH_TIMEOUT_MS
    ): List<PoiItem> {
        configureAmapSearchSdk()
        val originPoint = LatLonPoint(origin.latitude, origin.longitude)
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    val poiQuery = PoiSearch.Query(query, "", AMAP_CITY).apply {
                        this.pageSize = pageSize
                        pageNum = 0
                        cityLimit = true
                        setDistanceSort(true)
                        location = originPoint
                    }
                    val poiSearch = PoiSearch(appContext, poiQuery).apply {
                        bound = PoiSearch.SearchBound(originPoint, radiusMeters, true)
                        setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                            override fun onPoiSearched(result: PoiResult?, code: Int) {
                                if (!continuation.isActive) return
                                if (code == AMapException.CODE_AMAP_SUCCESS) {
                                    continuation.resume(result?.pois.orEmpty())
                                } else {
                                    Log.d(TAG, "route poi source=amap_sdk query=$query failed code=$code")
                                    continuation.resume(emptyList())
                                }
                            }

                            override fun onPoiItemSearched(item: PoiItem?, code: Int) = Unit
                        })
                    }
                    poiSearch.searchPOIAsyn()
                }.onFailure { throwable ->
                    if (continuation.isActive) {
                        Log.d(TAG, "route poi source=amap_sdk query=$query exception=${throwable.message}")
                        continuation.resume(emptyList())
                    }
                }
            }
        } ?: emptyList()
    }

    private suspend fun resolveDestinationWithAmapGeocodeSdk(
        target: RouteTargetRequest
    ): RouteDestination? {
        configureAmapSearchSdk()
        val queries = buildDestinationQueries(target)
        queries.forEach { query ->
            val addresses: List<GeocodeAddress> = withTimeoutOrNull(GEOCODE_SEARCH_TIMEOUT_MS) {
                suspendCancellableCoroutine<List<GeocodeAddress>> { continuation ->
                    runCatching {
                        val geocodeSearch = GeocodeSearch(appContext).apply {
                            setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                                override fun onGeocodeSearched(result: GeocodeResult?, code: Int) {
                                    if (!continuation.isActive) return
                                    if (code == AMapException.CODE_AMAP_SUCCESS) {
                                        continuation.resume(result?.geocodeAddressList.orEmpty())
                                    } else {
                                        Log.d(TAG, "route geocode source=amap_sdk query=$query failed code=$code")
                                        continuation.resume(emptyList())
                                    }
                                }

                                override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) = Unit
                            })
                        }
                        geocodeSearch.getFromLocationNameAsyn(GeocodeQuery(query, AMAP_CITY))
                    }.onFailure { throwable ->
                        if (continuation.isActive) {
                            Log.d(TAG, "route geocode source=amap_sdk query=$query exception=${throwable.message}")
                            continuation.resume(emptyList())
                        }
                    }
                }
            }.orEmpty()

            val address = addresses.firstOrNull { address ->
                address.latLonPoint != null && isGeocodeAddressSpecificEnough(address, query, target)
            }
            val point = address?.latLonPoint
            if (address != null && point != null) {
                Log.d(
                    TAG,
                    "route geocode source=amap_sdk query=$query address=${address.formatAddress} lat=${point.latitude} lon=${point.longitude}"
                )
                return RouteDestination(
                    label = address.formatAddress?.trim().orEmpty().ifBlank { target.title },
                    latitude = point.latitude,
                    longitude = point.longitude,
                    resolutionSource = "amap_sdk_geocode"
                )
            }
        }

        return null
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

    private fun buildDestinationQueries(target: RouteTargetRequest): List<String> {
        val isBroadNearbyTarget = listOfNotNull(target.searchKeyword, target.displayLocation)
            .any { it.contains("当前位置") || it.contains("附近") }
        return buildList {
            if (!isBroadNearbyTarget) {
                addAll(expandDestinationQuery(target.searchKeyword))
                add(
                    listOfNotNull(target.displayLocation, target.searchKeyword)
                        .joinToString(" ")
                        .takeIf { it.isNotBlank() }
                )
            }
            addAll(expandDestinationQuery(target.title))
            if (isBroadNearbyTarget || target.title.contains("附近")) {
                if (target.category == "meal") {
                    add("餐厅")
                    add("美食")
                } else {
                    add("公园")
                    add("景点")
                }
            }
        }.filterNotNull()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun expandDestinationQuery(rawText: String?): List<String> {
        val text = rawText?.trim().orEmpty()
        if (text.isBlank()) return emptyList()

        val bracketText = Regex("[（(]([^）)]+)[）)]")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        val bracketArea = bracketText
            .removeSuffix("店")
            .removeSuffix("门店")
            .trim()
        val withoutBracket = text.replace(Regex("[（(].*?[）)]"), "").trim()
        val beforeMiddleDot = withoutBracket.substringBefore("·").substringBefore("•").trim()
        val punctuationCollapsed = text
            .replace(Regex("[·•（）()\\[\\]【】_-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return buildList {
            add(text)
            add(punctuationCollapsed)
            add(withoutBracket)
            add(beforeMiddleDot)
            if (beforeMiddleDot.isNotBlank() && bracketText.isNotBlank()) {
                add("$beforeMiddleDot $bracketText")
            }
            if (beforeMiddleDot.isNotBlank() && bracketArea.isNotBlank()) {
                add("$beforeMiddleDot $bracketArea")
            }
        }
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun selectBestPoi(
        pois: List<PoiItem>,
        target: RouteTargetRequest
    ): PoiItem? {
        if (pois.isEmpty()) {
            return null
        }

        val matchingSignals = buildList {
            add(target.title)
            add(target.searchKeyword)
            add(target.displayLocation)
            addAll(expandDestinationQuery(target.title))
            addAll(expandDestinationQuery(target.searchKeyword))
        }.filterNotNull()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() && !it.contains("当前位置") && !it.contains("附近") }
            .distinct()
        val brandSignals = extractBrandSignals(target)
        val branchSignals = extractBranchSignals(target)
        val targetSeed = buildDestinationMatchSeed(target)

        return pois
            .filter { it.latLonPoint != null }
            .filterNot(RoutePlaceCatalog::isBadDestinationPoi)
            .filterNot { poi -> target.category == "play" && RoutePlaceCatalog.isBadPlayDestinationPoi(poi) }
            .filter { poi ->
                val compatible = RoutePlaceCatalog.isPoiTypeCompatibleWithTarget(poi, targetSeed, target.category)
                if (!compatible) {
                    Log.d(
                        TAG,
                        "route poi source=type_mismatch target=${target.title} poi=${poi.title} type=${poi.typeDes}"
                    )
                }
                compatible
            }
            .filter { poi -> isPoiNameSimilarEnough(poi, target, matchingSignals) }
            .mapNotNull { poi ->
                val name = poi.title.orEmpty().lowercase(Locale.ROOT)
                val address = buildString {
                    append(poi.snippet.orEmpty())
                    append(' ')
                    append(poi.adName.orEmpty())
                    append(' ')
                    append(poi.cityName.orEmpty())
                    append(' ')
                    append(poi.typeDes.orEmpty())
                }.lowercase(Locale.ROOT)
                val searchable = "$name $address"
                if (brandSignals.isNotEmpty() && brandSignals.none { signal ->
                        name.contains(signal) || signal.contains(name)
                    }
                ) {
                    return@mapNotNull null
                }
                if (branchSignals.isNotEmpty() && branchSignals.none { searchable.contains(it) }) {
                    return@mapNotNull null
                }
                val distanceScore = if (poi.distance > 0) {
                    (20_000 - poi.distance).coerceAtLeast(0) / 1_000
                } else {
                    0
                }

                val textScore = matchingSignals.sumOf { signal ->
                    when {
                        name == signal -> 24
                        name.contains(signal) -> 16
                        signal.contains(name) && name.isNotBlank() -> 12
                        address.contains(signal) -> 5
                        else -> 0
                    }
                }
                if (textScore <= 0 && branchSignals.isEmpty()) {
                    null
                } else {
                    poi to (distanceScore + textScore)
                }
            }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun isPoiNameSimilarEnough(
        poi: PoiItem,
        target: RouteTargetRequest,
        matchingSignals: List<String>
    ): Boolean {
        if (target.category != "play") {
            return true
        }

        val normalizedPoiName = normalizePoiMatchText(poi.title.orEmpty())
        val normalizedPoiText = normalizePoiMatchText(
            listOfNotNull(poi.title, poi.snippet, poi.typeDes).joinToString(" ")
        )
        val normalizedSignals = matchingSignals
            .map(::normalizePoiMatchText)
            .filter { it.length >= 2 && it !in weakPoiMatchSignals }
            .distinct()

        if (normalizedSignals.isEmpty()) {
            return true
        }

        val similar = normalizedSignals.any { signal ->
            normalizedPoiName == signal ||
                normalizedPoiName.contains(signal) ||
                (
                    normalizedPoiName.length >= 3 &&
                        normalizedPoiName !in weakPoiMatchSignals &&
                        signal.contains(normalizedPoiName)
                    ) ||
                normalizedPoiText.contains(signal)
        }
        if (!similar) {
            Log.d(
                TAG,
                "route poi source=name_mismatch target=${target.title} poi=${poi.title} type=${poi.typeDes}"
            )
        }
        return similar
    }

    private fun isGeocodeAddressSpecificEnough(
        address: GeocodeAddress,
        query: String,
        target: RouteTargetRequest
    ): Boolean {
        if (target.category != "play") {
            return true
        }

        val label = address.formatAddress?.trim().orEmpty()
        val normalizedLabel = normalizePoiMatchText(label)
        val genericCityLabels = setOf("", "湖北省", "湖北省武汉市", "武汉市")
        if (label in genericCityLabels || normalizedLabel in genericCityLabels) {
            Log.d(
                TAG,
                "route geocode source=generic_reject query=$query address=$label"
            )
            return false
        }

        val strongSignals = listOfNotNull(query, target.title, target.searchKeyword, target.displayLocation)
            .map(::normalizePoiMatchText)
            .filter { signal -> signal.length >= 3 && signal !in weakPoiMatchSignals }
            .distinct()
        if (strongSignals.isEmpty()) {
            return true
        }

        val similar = strongSignals.any { signal ->
            normalizedLabel.contains(signal) || signal.contains(normalizedLabel)
        }
        if (!similar && normalizedLabel.length <= 5) {
            Log.d(
                TAG,
                "route geocode source=name_mismatch query=$query target=${target.title} address=$label"
            )
            return false
        }
        return true
    }

    private fun normalizePoiMatchText(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace("武汉市", "")
            .replace("武汉", "")
            .replace(Regex("[\\s\\p{Punct}（）()【】\\[\\]「」『』《》“”‘’、，。！？；：·•-]+"), "")
            .trim()
    }

    private fun extractBrandSignals(target: RouteTargetRequest): List<String> {
        return listOfNotNull(target.title, target.searchKeyword)
            .flatMap { text ->
                val withoutBracket = text.replace(Regex("[（(].*?[）)]"), "").trim()
                val beforeMiddleDot = withoutBracket.substringBefore("·").substringBefore("•").trim()
                listOf(withoutBracket, beforeMiddleDot)
            }
            .map { value ->
                value
                    .replace(Regex("[^\\p{IsHan}A-Za-z0-9]+"), "")
                    .replace("武汉", "")
                    .trim()
            }
            .filter { signal ->
                signal.length >= 2 &&
                    RoutePlaceCatalog.genericBrandStopWords.none { stopWord -> signal.contains(stopWord) }
            }
            .distinct()
    }

    private fun extractBranchSignals(target: RouteTargetRequest): List<String> {
        val texts = listOfNotNull(target.title, target.searchKeyword, target.displayLocation)
        val bracketSignals = texts.flatMap { text ->
            Regex("[（(]([^）)]+)[）)]").findAll(text).map { match ->
                normalizeBranchSignal(match.groupValues[1])
            }.toList()
        }
        val middleDotSignals = texts.flatMap { text ->
            Regex("[·•]([^（(]+)").findAll(text).map { match ->
                normalizeBranchSignal(match.groupValues[1])
            }.toList()
        }
        val landmarkSignals = RoutePlaceCatalog.fallbackLandmarks.keys.filter { keyword ->
            texts.any { it.contains(keyword) }
        }
        return (bracketSignals + middleDotSignals + landmarkSignals)
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun normalizeBranchSignal(value: String): String {
        return value
            .replace("武汉", "")
            .replace("门店", "")
            .removeSuffix("店")
            .removeSuffix("味道")
            .replace(Regex("\\s+"), "")
            .trim()
    }

    private fun buildRoutePlan(
        target: RouteTargetRequest,
        origin: UserLocationSnapshot,
        destination: RouteDestination,
        transportMode: RouteTransportMode,
        distanceMeters: Int,
        durationMinutes: Int,
        sourceBadge: String,
        previewImageUrl: String?
    ): TimelineRoutePlan {
        val arrivalTime = ZonedDateTime.now(WUHAN_ZONE)
            .plusMinutes(durationMinutes.toLong())
            .format(ARRIVAL_FORMATTER)

        return TimelineRoutePlan(
            title = target.title,
            category = target.category,
            sourceLabel = target.sourceLabel,
            tag = target.tag,
            originLabel = origin.label,
            destinationLabel = destination.label,
            transportMode = transportMode,
            durationMinutes = durationMinutes,
            durationLabel = "${transportMode.label}\u7ea6 ${durationMinutes} \u5206\u949f",
            distanceMeters = distanceMeters,
            distanceLabel = RouteMath.formatDistance(distanceMeters),
            arrivalLabel = arrivalTime,
            previewImageUrl = previewImageUrl,
            sourceBadge = sourceBadge,
            originLatitude = origin.latitude,
            originLongitude = origin.longitude,
            destinationLatitude = destination.latitude,
            destinationLongitude = destination.longitude
        )
    }

    private fun resolveDestinationWithoutAmap(
        target: RouteTargetRequest,
        origin: UserLocationSnapshot
    ): RouteDestination {
        val directLatitude = target.latitude
        val directLongitude = target.longitude
        if (
            directLatitude != null &&
            directLongitude != null &&
            RouteMath.haversineKm(origin.latitude, origin.longitude, directLatitude, directLongitude) >= MIN_ROUTE_DISTANCE_KM
        ) {
            return RouteDestination(
                label = target.displayLocation ?: target.searchKeyword ?: target.title,
                latitude = directLatitude,
                longitude = directLongitude,
                resolutionSource = "direct_coordinates"
            )
        }

        val matchSeed = buildDestinationMatchSeed(target)

        RoutePlaceCatalog.fallbackLandmarks.entries.firstOrNull { entry ->
            val keyword = entry.key
            matchSeed.contains(keyword, ignoreCase = true)
        }?.let { entry ->
            val coordinate = entry.value
            return RouteDestination(
                label = target.displayLocation ?: target.searchKeyword ?: target.title,
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
                resolutionSource = "fallback_landmark"
            )
        }

        val categoryFallback = if (target.category == "meal") {
            RoutePlaceCatalog.fallbackLandmarks["江汉路"]
        } else {
            RoutePlaceCatalog.fallbackLandmarks["东湖"]
        }
        if (categoryFallback != null) {
            return RouteDestination(
                label = target.displayLocation ?: target.searchKeyword ?: target.title,
                latitude = categoryFallback.latitude,
                longitude = categoryFallback.longitude,
                resolutionSource = "category_anchor"
            )
        }

        error("Destination could not be resolved without AMap SDK: $matchSeed")
    }

    private fun buildDestinationMatchSeed(target: RouteTargetRequest): String {
        return listOfNotNull(target.searchKeyword, target.displayLocation, target.title)
            .joinToString(" ")
    }

    private fun buildPoiLabel(
        poi: PoiItem,
        query: String,
        target: RouteTargetRequest
    ): String {
        val name = poi.title?.trim().orEmpty()
        val district = listOfNotNull(
            poi.cityName?.trim()?.takeIf { it.isNotEmpty() && it != "\u6b66\u6c49\u5e02" },
            poi.adName?.trim()?.takeIf { it.isNotEmpty() }
        ).joinToString("")
        val address = poi.snippet?.trim().orEmpty()
        return when {
            name.isNotBlank() && address.isNotBlank() -> "$name · ${if (district.isNotBlank()) district else address}"
            name.isNotBlank() && district.isNotBlank() -> "$name · $district"
            name.isNotBlank() -> name
            address.isNotBlank() -> address
            else -> target.displayLocation ?: query
        }
    }

    companion object {
        private const val TAG = "RoutePlanning"
        private const val AMAP_CITY = "武汉"
        private const val POI_SEARCH_RADIUS_METERS = 50_000
        private const val POI_PAGE_SIZE = 10
        private const val POI_SEARCH_TIMEOUT_MS = 8_000L
        private const val NEARBY_MALL_RADIUS_METERS = 700
        private const val NEARBY_MALL_PAGE_SIZE = 8
        private const val NEARBY_MALL_QUERY_TIMEOUT_MS = 900L
        private const val NEARBY_MALL_TOTAL_TIMEOUT_MS = 1_600L
        private const val GEOCODE_SEARCH_TIMEOUT_MS = 8_000L
        private const val AMAP_SEARCH_CONNECT_TIMEOUT_MS = 8_000
        private const val AMAP_SEARCH_SOCKET_TIMEOUT_MS = 10_000
        private const val MIN_ROUTE_DISTANCE_KM = 0.08
        private val WUHAN_ZONE = ZoneId.of("Asia/Shanghai")
        private val ARRIVAL_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
        private val weakPoiMatchSignals = setOf(
            "公园",
            "景点",
            "博物馆",
            "博物院",
            "美术馆",
            "艺术馆",
            "街区",
            "夜游",
            "慢逛",
            "散步",
            "打卡"
        )
        private val emergencyOrigin = UserLocationSnapshot(
            label = "武汉市江汉区",
            latitude = 30.5928,
            longitude = 114.3055,
            source = "route_origin_fallback"
        )

    }
}
