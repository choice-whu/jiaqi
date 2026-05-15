package com.example.dateapp.data.route

import android.content.Context
import android.os.SystemClock
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
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

data class DecisionPoiCandidate(
    val displayName: String,
    val routeLabel: String,
    val routeKeyword: String,
    val category: String,
    val tag: String?,
    val typeDescription: String?,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val directDistanceMeters: Int,
    val distanceLabel: String,
    val isOpenNow: Boolean?,
    val openingHours: String?,
    val source: String = "amap_sdk_poi"
)

private data class DecisionSearchOrigin(
    val origin: UserLocationSnapshot,
    val sourceSuffix: String,
    val distancePrefix: String? = null
)

data class RouteResolvedPlace(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val resolutionSource: String,
    val directDistanceMeters: Int,
    val isOpenNow: Boolean? = null,
    val openingHours: String? = null
)

private data class PoiOpeningStatus(
    val isOpenNow: Boolean?,
    val label: String?
)

private data class CachedPoiSearch(
    val createdAtMs: Long,
    val pois: List<PoiItem>
)

class RoutePlanningRepository(
    context: Context,
    private val environmentRepository: DecisionEnvironmentRepository
) {
    private val appContext = context.applicationContext
    private val amapRoutePlanner = AmapRoutePlanner(appContext)
    private val fallbackEstimator = RouteFallbackEstimator()
    private val amapSearchMutex = Mutex()
    private val poiSearchCache = linkedMapOf<String, CachedPoiSearch>()
    private var lastAmapSearchAtMs: Long = 0L

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
        origin: UserLocationSnapshot,
        currentTime: ZonedDateTime = ZonedDateTime.now(WUHAN_ZONE)
    ): RouteResolvedPlace? {
        return runCatching {
            val destination = resolveDestinationPrecisely(
                target = target,
                origin = origin,
                requireOpenNow = true,
                currentTime = currentTime
            )
            if (!isDecisionDestinationPreciseEnough(destination, target)) {
                error(
                    "Decision destination is not precise enough: title=${target.title}, label=${destination.label}, source=${destination.resolutionSource}"
                )
            }
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
                directDistanceMeters = distanceMeters,
                isOpenNow = destination.isOpenNow,
                openingHours = destination.openingHours
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

    suspend fun searchDecisionPoiCandidates(
        category: String,
        topicId: String?,
        topicLabel: String?,
        topicHint: String?,
        origin: UserLocationSnapshot,
        currentTime: ZonedDateTime = ZonedDateTime.now(WUHAN_ZONE),
        avoidNames: List<String> = emptyList(),
        limit: Int = DECISION_POI_LIMIT
    ): List<DecisionPoiCandidate> {
        val normalizedCategory = if (category == "meal") "meal" else "play"
        val radiusMeters = if (normalizedCategory == "meal") {
            DECISION_MEAL_RADIUS_METERS
        } else {
            DECISION_PLAY_RADIUS_METERS
        }
        val topicText = listOfNotNull(topicLabel, topicHint).joinToString(" ")
        val queries = buildDecisionPoiQueries(
            category = normalizedCategory,
            topicId = topicId,
            topicLabel = topicLabel
        )
        val searchOrigins = buildDecisionSearchOrigins(
            category = normalizedCategory,
            origin = origin
        )

        val candidates = mutableListOf<DecisionPoiCandidate>()
        queries.forEach { query ->
            searchOrigins.forEach { searchOrigin ->
                val pois = searchPoiWithAmapSdk(
                    query = query,
                    origin = searchOrigin.origin,
                    radiusMeters = radiusMeters,
                    pageSize = DECISION_POI_PAGE_SIZE,
                    timeoutMillis = DECISION_POI_SEARCH_TIMEOUT_MS
                )
                candidates += selectDecisionPois(
                    pois = pois,
                    query = query,
                    category = normalizedCategory,
                    topicText = topicText,
                    origin = searchOrigin.origin,
                    currentTime = currentTime,
                    avoidNames = avoidNames,
                    limit = limit,
                    sourceSuffix = searchOrigin.sourceSuffix,
                    distancePrefix = searchOrigin.distancePrefix
                )
                if (candidates.distinctBy { normalizePoiMatchText(it.displayName) }.size >= limit) {
                    return@forEach
                }
            }
        }

        val deduped = candidates
            .distinctBy { normalizePoiMatchText(it.displayName) }
            .sortedWith(
                compareBy<DecisionPoiCandidate> { it.directDistanceMeters }
                    .thenBy { it.displayName.length }
            )
            .take(limit)

        Log.d(
            TAG,
            "route decision_poi source=amap_sdk category=$normalizedCategory topic=${topicLabel.orEmpty()} origins=${searchOrigins.joinToString(limit = 3) { it.origin.label }} queries=${queries.take(4)} count=${deduped.size} names=${deduped.joinToString(limit = 6) { it.displayName }}"
        )
        return deduped
    }

    private fun buildDecisionSearchOrigins(
        category: String,
        origin: UserLocationSnapshot
    ): List<DecisionSearchOrigin> {
        if (category != "meal") {
            return listOf(
                DecisionSearchOrigin(
                    origin = origin,
                    sourceSuffix = "current"
                )
            )
        }

        val currentToDiningCenterMeters = (RouteMath.haversineKm(
            lat1 = origin.latitude,
            lon1 = origin.longitude,
            lat2 = hudaMixcDiningCenter.latitude,
            lon2 = hudaMixcDiningCenter.longitude
        ) * 1000.0).roundToInt()
        val currentOrigin = DecisionSearchOrigin(
            origin = origin,
            sourceSuffix = "current"
        )
        val campusDiningOrigin = DecisionSearchOrigin(
            origin = hudaMixcDiningCenter,
            sourceSuffix = "huda_mixc",
            distancePrefix = "湖大-万象城"
        )

        return if (currentToDiningCenterMeters <= DINING_CENTER_NEARBY_METERS) {
            listOf(currentOrigin, campusDiningOrigin)
        } else {
            listOf(campusDiningOrigin, currentOrigin)
        }
    }

    private suspend fun resolveDestinationPrecisely(
        target: RouteTargetRequest,
        origin: UserLocationSnapshot,
        requireOpenNow: Boolean = false,
        currentTime: ZonedDateTime = ZonedDateTime.now(WUHAN_ZONE)
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

        resolveDestinationWithAmapSdk(
            target = target,
            origin = origin,
            requireOpenNow = requireOpenNow,
            currentTime = currentTime
        )?.let { return it }
        resolveDestinationWithAmapGeocodeSdk(target)?.let { return it }
        if (target.category == "play") {
            resolveDestinationWithFallbackLandmark(target)?.let { return it }
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
        origin: UserLocationSnapshot,
        requireOpenNow: Boolean = false,
        currentTime: ZonedDateTime = ZonedDateTime.now(WUHAN_ZONE)
    ): RouteDestination? {
        val queries = buildDestinationQueries(target)
        var closedPrimaryDetected = false

        queries.forEach { query ->
            val pois = searchPoiWithAmapSdk(query, origin)
            if (requireOpenNow && hasClosedPrimaryPoi(pois, target, currentTime)) {
                closedPrimaryDetected = true
                return@forEach
            }
            if (closedPrimaryDetected) {
                return@forEach
            }
            val poi = selectBestPoi(
                pois = pois,
                target = target,
                requireOpenNow = requireOpenNow,
                currentTime = currentTime
            )
            val poiLocation = poi?.latLonPoint
            if (poi != null && poiLocation != null) {
                val openingStatus = poi.openingStatusAt(currentTime)
                Log.d(
                    TAG,
                    "route poi source=amap_sdk query=$query name=${poi.title} distance=${poi.distance} open=${openingStatus.isOpenNow ?: "unknown"} hours=${openingStatus.label.orEmpty()} lat=${poiLocation.latitude} lon=${poiLocation.longitude}"
                )
                return RouteDestination(
                    label = buildPoiLabel(poi, query, target),
                    latitude = poiLocation.latitude,
                    longitude = poiLocation.longitude,
                    resolutionSource = "amap_sdk_poi",
                    isOpenNow = openingStatus.isOpenNow,
                    openingHours = openingStatus.label
                )
            }
        }

        if (closedPrimaryDetected) {
            Log.d(TAG, "route poi source=closed_primary_block title=${target.title}")
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
        val cacheKey = buildPoiSearchCacheKey(query, origin, radiusMeters, pageSize)
        getCachedPoiSearch(cacheKey)?.let { cachedPois ->
            Log.d(TAG, "route poi source=cache query=$query count=${cachedPois.size}")
            return cachedPois
        }

        val originPoint = LatLonPoint(origin.latitude, origin.longitude)
        return withTimeoutOrNull(timeoutMillis) {
            amapSearchMutex.withLock {
                getCachedPoiSearch(cacheKey)?.let { cachedPois ->
                    Log.d(TAG, "route poi source=cache_after_wait query=$query count=${cachedPois.size}")
                    return@withLock cachedPois
                }

                throttleAmapSearchIfNeeded()
                val pois = suspendCancellableCoroutine { continuation ->
                    runCatching {
                        val poiQuery = PoiSearch.Query(query, "", AMAP_CITY).apply {
                            this.pageSize = pageSize
                            pageNum = 0
                            cityLimit = true
                            setDistanceSort(true)
                            setExtensions("all")
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
                putCachedPoiSearch(cacheKey, pois)
                pois
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

    private fun buildDecisionPoiQueries(
        category: String,
        topicId: String?,
        topicLabel: String?
    ): List<String> {
        val topicQueries = when (topicId) {
            "craft_workshop" -> listOf("DIY手作", "手工DIY", "手作体验")
            "pottery_diy" -> listOf("陶艺DIY", "陶艺手作", "拉坯体验")
            "tufting_diy" -> listOf("tufting", "簇绒DIY", "地毯DIY")
            "silver_fragrance_diy" -> listOf("银饰DIY", "香薰DIY", "调香体验")
            "baking_diy" -> listOf("烘焙DIY", "蛋糕DIY", "甜品DIY")
            "immersive_theater" -> listOf("沉浸式剧场", "小剧场", "话剧")
            "talkshow_small_theater" -> listOf("脱口秀", "小剧场", "喜剧")
            "pet_cafe" -> listOf("猫咖", "狗咖", "宠物咖啡")
            "indoor_sport" -> listOf("射箭馆", "攀岩馆", "保龄球", "滑冰场", "VR体验")
            "retro_arcade" -> listOf("电玩城", "街机", "VR体验")
            "boardgame_escape" -> listOf("桌游", "密室逃脱", "剧本杀")
            "art_exhibition" -> listOf("艺术展", "美术馆", "艺术空间")
            "small_gallery" -> listOf("画廊", "艺术空间", "展览馆")
            "vintage_photo" -> listOf("写真馆", "自拍馆", "大头贴", "证件照写真", "胶片写真", "照相馆")
            "vintage_record_shop" -> listOf("唱片店", "黑胶", "中古店")
            "toy_blindbox_shop" -> listOf("潮玩", "盲盒", "玩具店")
            "lifestyle_boutique" -> listOf("生活方式集合店", "买手店", "主理人店", "香水体验", "植物店", "文创小店")
            "creative_block_shop" -> listOf("创意园", "文创店", "艺术空间")
            "city_viewpoint" -> listOf("观景台", "城市阳台", "黄鹤楼", "江滩观景")
            "riverside_walk" -> listOf("江滩", "码头", "江边")
            "lakeside_walk" -> listOf("湖边", "绿道", "栈道")
            "historic_architecture" -> listOf("历史街区", "老租界", "近代建筑", "文创街区")
            "small_park_corner" -> listOf("公园", "游园", "花园")
            "garden_plants" -> listOf("花市", "植物园", "花园", "植物店")
            "old_street_corner" -> listOf("老街", "历史街区", "文创街区")
            "public_art" -> listOf("公共艺术", "雕塑", "艺术装置")
            "non_hot_museum" -> listOf("博物馆", "纪念馆", "展馆")
            "temple_heritage" -> listOf("寺庙", "古迹", "教堂")
            "foreign_food" -> listOf("泰餐", "越南粉", "东南亚菜", "墨西哥菜")
            "japanese_korean" -> listOf("日料", "寿司", "日式拉面", "韩餐", "韩式烤肉")
            "western_brunch" -> listOf("西餐", "brunch", "汉堡", "披萨")
            "bistro_bar" -> listOf("bistro", "小酒馆", "精酿餐吧")
            "hotpot_bbq" -> listOf("火锅", "烤肉", "烧烤")
            "dessert_coffee" -> listOf("咖啡", "甜品", "面包", "茶饮")
            "local_snack" -> listOf("热干面", "豆皮", "汤包", "牛肉粉")
            "snack_street" -> listOf("小吃", "小吃店", "特色小吃")
            "late_supper" -> listOf("宵夜", "烧烤", "砂锅")
            "claypot_homecooking" -> listOf("砂锅", "私房菜", "家常菜")
            "student_friendly_chain" -> listOf("老乡鸡", "尊宝比萨", "袁记云饺", "塔斯汀", "简餐")
            "light_food" -> listOf("轻食", "茶馆", "新中式茶饮")
            else -> emptyList()
        }

        val fallbackQueries = if (topicQueries.isNotEmpty()) {
            emptyList()
        } else if (category == "meal") {
            listOf("餐厅", "美食", "咖啡", "甜品")
        } else {
            listOf("景点", "展览", "手作", "电玩城", "咖啡", "公园")
        }

        return buildList {
            topicLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(topicQueries)
            addAll(fallbackQueries)
        }
            .map { it.trim() }
            .filter { it.length >= 2 }
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
        target: RouteTargetRequest,
        requireOpenNow: Boolean = false,
        currentTime: ZonedDateTime = ZonedDateTime.now(WUHAN_ZONE)
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
            .filter { poi ->
                val openStatus = poi.openingStatusAt(currentTime)
                val acceptable = !requireOpenNow || openStatus.isOpenNow != false
                if (!acceptable) {
                    Log.d(
                        TAG,
                        "route poi source=closed_reject target=${target.title} poi=${poi.title} hours=${openStatus.label.orEmpty()} now=${currentTime.toLocalTime()}"
                    )
                }
                acceptable
            }
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

    private fun selectDecisionPois(
        pois: List<PoiItem>,
        query: String,
        category: String,
        topicText: String,
        origin: UserLocationSnapshot,
        currentTime: ZonedDateTime,
        avoidNames: List<String>,
        limit: Int,
        sourceSuffix: String = "current",
        distancePrefix: String? = null
    ): List<DecisionPoiCandidate> {
        if (pois.isEmpty()) {
            return emptyList()
        }

        val topicSeed = query
        val avoidSignals = avoidNames
            .map(::normalizePoiMatchText)
            .filter { it.length >= 2 }
        return pois
            .asSequence()
            .filter { it.latLonPoint != null }
            .filter { poi ->
                val concrete = RoutePlaceCatalog.isConcreteDecisionPoi(poi, category)
                if (!concrete) {
                    Log.d(
                        TAG,
                        "route decision_poi source=not_concrete_reject query=$query poi=${poi.title} type=${poi.typeDes} address=${poi.snippet}"
                    )
                }
                concrete
            }
            .filterNot(RoutePlaceCatalog::isBadDestinationPoi)
            .filterNot { poi -> category == "play" && RoutePlaceCatalog.isBadPlayDestinationPoi(poi) }
            .filterNot { poi ->
                val rejected = category == "meal" && RoutePlaceCatalog.isLowDateMealPoi(poi)
                if (rejected) {
                    Log.d(TAG, "route decision_poi source=low_date_meal_reject query=$query poi=${poi.title}")
                }
                rejected
            }
            .filterNot { poi -> isGenericPoiCandidate(poi) }
            .filter { poi ->
                val typeCompatible = RoutePlaceCatalog.isPoiTypeCompatibleWithTarget(poi, topicSeed, category)
                if (!typeCompatible) {
                    Log.d(
                        TAG,
                        "route decision_poi source=type_mismatch query=$query poi=${poi.title} type=${poi.typeDes}"
                    )
                }
                typeCompatible
            }
            .filter { poi ->
                val openingStatus = poi.openingStatusAt(currentTime)
                val acceptable = openingStatus.isOpenNow != false
                if (!acceptable) {
                    Log.d(
                        TAG,
                        "route decision_poi source=closed_reject query=$query poi=${poi.title} hours=${openingStatus.label.orEmpty()} now=${currentTime.toLocalTime()}"
                    )
                }
                acceptable
            }
            .filter { poi ->
                val normalizedName = normalizePoiMatchText(poi.title.orEmpty())
                val repeated = avoidSignals.any { signal ->
                    normalizedName.contains(signal) || signal.contains(normalizedName)
                }
                if (repeated) {
                    Log.d(TAG, "route decision_poi source=avoid_reject query=$query poi=${poi.title}")
                }
                !repeated
            }
            .mapNotNull { poi ->
                val point = poi.latLonPoint ?: return@mapNotNull null
                val distanceMeters = if (poi.distance > 0) {
                    poi.distance
                } else {
                    (RouteMath.haversineKm(
                        lat1 = origin.latitude,
                        lon1 = origin.longitude,
                        lat2 = point.latitude,
                        lon2 = point.longitude
                    ) * 1000.0).roundToInt()
                }
                if (category == "meal" && distanceMeters > DECISION_MEAL_MAX_DISTANCE_METERS) {
                    return@mapNotNull null
                }
                if (category == "play" && distanceMeters > DECISION_PLAY_MAX_DISTANCE_METERS) {
                    return@mapNotNull null
                }

                val openingStatus = poi.openingStatusAt(currentTime)
                val name = poi.title?.trim().orEmpty()
                if (name.isBlank()) {
                    return@mapNotNull null
                }
                Log.d(
                    TAG,
                    "route decision_poi source=verified query=$query category=$category poi=$name type=${poi.typeDes.orEmpty()} address=${poi.snippet.orEmpty()} distance=$distanceMeters origin=${origin.label}"
                )
                DecisionPoiCandidate(
                    displayName = name,
                    routeLabel = buildPoiLabel(
                        poi = poi,
                        query = query,
                        target = RouteTargetRequest(
                            title = name,
                            category = category,
                            displayLocation = name,
                            searchKeyword = name,
                            latitude = null,
                            longitude = null,
                            sourceLabel = "AI探索",
                            tag = null
                        )
                    ),
                    routeKeyword = name,
                    category = category,
                    tag = inferPoiTag(
                        poi = poi,
                        category = category,
                        query = query
                    ),
                    typeDescription = poi.typeDes?.trim()?.takeIf { it.isNotEmpty() },
                    address = poi.snippet?.trim()?.takeIf { it.isNotEmpty() },
                    latitude = point.latitude,
                    longitude = point.longitude,
                    directDistanceMeters = distanceMeters,
                    distanceLabel = distancePrefix?.let { prefix ->
                        "$prefix${RouteMath.formatDistance(distanceMeters)}"
                    } ?: RouteMath.formatDistance(distanceMeters),
                    isOpenNow = openingStatus.isOpenNow,
                    openingHours = openingStatus.label,
                    source = "amap_sdk_poi_$sourceSuffix"
                )
            }
            .sortedBy { candidate ->
                candidate.directDistanceMeters + if (candidate.isOpenNow == true) 0 else 500
            }
            .take(limit)
            .toList()
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

    private fun hasClosedPrimaryPoi(
        pois: List<PoiItem>,
        target: RouteTargetRequest,
        currentTime: ZonedDateTime
    ): Boolean {
        val signals = primaryPoiMatchSignals(target)
        if (signals.isEmpty()) {
            return false
        }

        val closedPrimary = pois.firstOrNull { poi ->
            val name = normalizePoiMatchText(poi.title.orEmpty())
            if (name.isBlank()) {
                return@firstOrNull false
            }
            val isPrimaryMatch = signals.any { signal ->
                name == signal ||
                    (
                        signal.length >= 3 &&
                            name.length <= signal.length + PRIMARY_POI_ALIAS_EXTRA_CHARS &&
                            (name.contains(signal) || signal.contains(name))
                        )
            }
            isPrimaryMatch && poi.openingStatusAt(currentTime).isOpenNow == false
        } ?: return false

        val status = closedPrimary.openingStatusAt(currentTime)
        Log.d(
            TAG,
            "route poi source=closed_primary_reject target=${target.title} poi=${closedPrimary.title} hours=${status.label.orEmpty()} now=${currentTime.toLocalTime()}"
        )
        return true
    }

    private fun primaryPoiMatchSignals(target: RouteTargetRequest): List<String> {
        val directSignals = buildList {
            add(target.title)
            add(target.searchKeyword)
            add(target.displayLocation)
            addAll(expandDestinationQuery(target.title))
            addAll(expandDestinationQuery(target.searchKeyword))
        }
            .filterNotNull()
            .map(::normalizePoiMatchText)
            .filter { signal -> signal.length >= 2 && signal !in weakPoiMatchSignals }

        val brandSignals = extractBrandSignals(target)
            .map(::normalizePoiMatchText)
            .filter { signal -> signal.length >= 2 && signal !in weakPoiMatchSignals }

        return (directSignals + brandSignals).distinct()
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
        if (isGenericAddressLabel(label)) {
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

    private fun isGenericPoiCandidate(poi: PoiItem): Boolean {
        val title = poi.title?.trim().orEmpty()
        val address = poi.snippet?.trim().orEmpty()
        val type = poi.typeDes.orEmpty()
        val normalizedTitle = normalizePoiMatchText(title)
        val genericByTitle = isGenericAddressLabel(title) ||
            genericPoiTitles.any { generic -> normalizedTitle == normalizePoiMatchText(generic) }
        val administrative = type.contains("地名地址信息") ||
            type.contains("行政地名") ||
            type.contains("道路名") ||
            type.contains("普通地名")
        val tooBroad = administrative && (
            address.isBlank() ||
                genericPoiTypeKeywords.any { keyword -> type.contains(keyword) }
            )
        if (genericByTitle || tooBroad) {
            Log.d(TAG, "route poi source=generic_reject poi=$title type=$type address=$address")
        }
        return genericByTitle || tooBroad
    }

    private fun isGenericAddressLabel(label: String): Boolean {
        val text = label.trim()
        if (text.isBlank()) {
            return true
        }
        val normalized = normalizePoiMatchText(text)
        val genericCityLabels = setOf(
            "",
            "湖北省",
            "湖北省武汉市",
            "武汉市",
            "江岸区",
            "江汉区",
            "硚口区",
            "汉阳区",
            "武昌区",
            "青山区",
            "洪山区",
            "东西湖区",
            "汉南区",
            "蔡甸区",
            "江夏区",
            "黄陂区",
            "新洲区",
            "武汉经济技术开发区",
            "东湖高新区"
        ).map(::normalizePoiMatchText).toSet()
        if (normalized in genericCityLabels) {
            return true
        }
        if (Regex("^(湖北省)?武汉市?[^号]{0,8}(区|县|街道|镇|乡)$").matches(text)) {
            return true
        }
        return false
    }

    private fun inferPoiTag(
        poi: PoiItem,
        category: String,
        query: String
    ): String? {
        val text = listOf(poi.title.orEmpty(), poi.typeDes.orEmpty(), query).joinToString(" ").lowercase(Locale.ROOT)
        return if (category == "meal") {
            when {
                text.contains("咖啡") -> "咖啡"
                text.contains("甜品") || text.contains("蛋糕") || text.contains("烘焙") -> "甜品"
                text.contains("火锅") -> "火锅"
                text.contains("烤肉") || text.contains("烧烤") -> "烤肉"
                text.contains("日料") || text.contains("寿司") || text.contains("拉面") -> "日料"
                text.contains("韩") -> "韩餐"
                text.contains("小吃") || text.contains("热干面") || text.contains("豆皮") -> "小吃"
                text.contains("酒") || text.contains("bistro") -> "小酒馆"
                else -> "餐饮"
            }
        } else {
            when {
                text.contains("手作") || text.contains("diy") || text.contains("陶艺") || text.contains("香薰") || text.contains("银饰") -> "手作"
                text.contains("展览") || text.contains("展馆") || text.contains("展陈") ||
                    text.contains("看展") || text.contains("美术") || text.contains("画廊") ||
                    text.contains("艺术") -> "展览"
                text.contains("剧场") || text.contains("剧院") || text.contains("脱口秀") -> "演出"
                text.contains("电玩") || text.contains("街机") || text.contains("vr") -> "电玩"
                text.contains("密室") || text.contains("桌游") -> "互动"
                text.contains("公园") || text.contains("花园") || text.contains("江滩") || text.contains("湖") -> "散步"
                text.contains("书") -> "书店"
                text.contains("潮玩") || text.contains("盲盒") || text.contains("玩具") -> "潮玩"
                text.contains("咖啡") || text.contains("甜品") -> "小店"
                else -> "游玩"
            }
        }
    }

    private suspend fun throttleAmapSearchIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        val waitMillis = AMAP_SEARCH_MIN_INTERVAL_MS - (now - lastAmapSearchAtMs)
        if (waitMillis > 0) {
            delay(waitMillis)
        }
        lastAmapSearchAtMs = SystemClock.elapsedRealtime()
    }

    private fun buildPoiSearchCacheKey(
        query: String,
        origin: UserLocationSnapshot,
        radiusMeters: Int,
        pageSize: Int
    ): String {
        val latBucket = (origin.latitude * 100.0).roundToInt()
        val lonBucket = (origin.longitude * 100.0).roundToInt()
        return listOf(query.trim(), latBucket, lonBucket, radiusMeters, pageSize).joinToString("|")
    }

    private fun getCachedPoiSearch(cacheKey: String): List<PoiItem>? {
        val cached = poiSearchCache[cacheKey] ?: return null
        if (SystemClock.elapsedRealtime() - cached.createdAtMs > POI_SEARCH_CACHE_TTL_MS) {
            poiSearchCache.remove(cacheKey)
            return null
        }
        return cached.pois
    }

    private fun putCachedPoiSearch(
        cacheKey: String,
        pois: List<PoiItem>
    ) {
        poiSearchCache[cacheKey] = CachedPoiSearch(
            createdAtMs = SystemClock.elapsedRealtime(),
            pois = pois
        )
        while (poiSearchCache.size > POI_SEARCH_CACHE_MAX_ENTRIES) {
            val firstKey = poiSearchCache.keys.firstOrNull() ?: break
            poiSearchCache.remove(firstKey)
        }
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

    private fun isDecisionDestinationPreciseEnough(
        destination: RouteDestination,
        target: RouteTargetRequest
    ): Boolean {
        if (target.sourceLabel != "AI探索") {
            return true
        }
        if (isGenericAddressLabel(destination.label)) {
            Log.d(
                TAG,
                "route source=decision_generic_address_reject title=${target.title} label=${destination.label} resolution=${destination.resolutionSource}"
            )
            return false
        }
        if (destination.resolutionSource == "amap_sdk_poi" || destination.resolutionSource == "direct_coordinates") {
            return true
        }
        if (destination.resolutionSource == "fallback_landmark" || destination.resolutionSource == "category_anchor") {
            Log.d(
                TAG,
                "route source=decision_generic_reject title=${target.title} label=${destination.label} resolution=${destination.resolutionSource}"
            )
            return false
        }
        if (destination.resolutionSource != "amap_sdk_geocode") {
            return false
        }

        val normalizedLabel = normalizePoiMatchText(destination.label)
        val fullSignals = listOfNotNull(
            target.title,
            target.searchKeyword,
            target.displayLocation
        )
            .map(::normalizePoiMatchText)
            .filter { signal -> signal.length >= 3 && signal !in weakPoiMatchSignals }
            .distinct()
        val precise = fullSignals.any { signal ->
            normalizedLabel.contains(signal)
        }
        if (!precise) {
            Log.d(
                TAG,
                "route source=decision_geocode_reject title=${target.title} label=${destination.label} signals=${fullSignals.joinToString(limit = 4)}"
            )
        }
        if (!precise) {
            return false
        }
        if (target.sourceLabel == "AI探索" && targetLooksLikeSpecificPlace(target)) {
            Log.d(
                TAG,
                "route source=decision_geocode_specific_reject title=${target.title} label=${destination.label}"
            )
            return false
        }
        return true
    }

    private fun targetLooksLikeSpecificPlace(target: RouteTargetRequest): Boolean {
        val text = buildDestinationMatchSeed(target).lowercase(Locale.ROOT)
        return listOf(
            "店",
            "工作室",
            "体验",
            "diy",
            "馆",
            "空间",
            "艺术中心",
            "咖啡",
            "剧场",
            "影院",
            "密室",
            "桌游",
            "手作",
            "陶艺",
            "香薰",
            "银饰",
            "簇绒",
            "tufting",
            "潮玩",
            "盲盒",
            "唱片",
            "中古",
            "买手",
            "餐厅",
            "烤肉",
            "火锅",
            "酒馆"
        ).any(text::contains)
    }

    private fun PoiItem.openingStatusAt(currentTime: ZonedDateTime): PoiOpeningStatus {
        val rawOpeningHours = poiExtension
            ?.opentime
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return PoiOpeningStatus(isOpenNow = null, label = null)
        val normalized = rawOpeningHours
            .replace("：", ":")
            .replace("－", "-")
            .replace("—", "-")
            .replace("–", "-")

        if (containsAny(normalized, "24小时", "全天", "00:00-24:00", "0:00-24:00")) {
            return PoiOpeningStatus(isOpenNow = true, label = rawOpeningHours)
        }
        if (containsAny(normalized, "暂停营业", "停止营业", "已停业", "休息中", "不营业")) {
            return PoiOpeningStatus(isOpenNow = false, label = rawOpeningHours)
        }

        val dayOpen = isOpeningTextForCurrentDay(normalized, currentTime.dayOfWeek.value)
        if (dayOpen == false) {
            return PoiOpeningStatus(isOpenNow = false, label = rawOpeningHours)
        }

        val ranges = Regex("(\\d{1,2}:\\d{2})\\s*(?:-|~|～|至|到)\\s*(\\d{1,2}:\\d{2})")
            .findAll(normalized)
            .mapNotNull { match ->
                val start = parseOpeningMinutes(match.groupValues[1])
                val end = parseOpeningMinutes(match.groupValues[2])
                if (start != null && end != null) {
                    start to end
                } else {
                    null
                }
            }
            .toList()

        if (ranges.isEmpty()) {
            return when {
                containsAny(normalized, "营业中", "正在营业") -> PoiOpeningStatus(isOpenNow = true, label = rawOpeningHours)
                containsAny(normalized, "已打烊", "打烊") -> PoiOpeningStatus(isOpenNow = false, label = rawOpeningHours)
                else -> PoiOpeningStatus(isOpenNow = null, label = rawOpeningHours)
            }
        }

        val now = currentTime.toLocalTime().toOpeningMinutes()
        val openNow = ranges.any { (start, end) ->
            when {
                start == end -> true
                end > start -> now in start until end
                else -> now >= start || now < end
            }
        }
        return PoiOpeningStatus(isOpenNow = openNow, label = rawOpeningHours)
    }

    private fun isOpeningTextForCurrentDay(
        text: String,
        currentDay: Int
    ): Boolean? {
        val rangeMatches = Regex("(?:周|星期)([一二三四五六日天])\\s*(?:-|~|～|至|到)\\s*(?:周|星期)?([一二三四五六日天])")
            .findAll(text)
            .mapNotNull { match ->
                val start = chineseWeekdayToIndex(match.groupValues[1])
                val end = chineseWeekdayToIndex(match.groupValues[2])
                if (start != null && end != null) {
                    start to end
                } else {
                    null
                }
            }
            .toList()
        if (rangeMatches.isNotEmpty()) {
            return rangeMatches.any { (start, end) -> currentDay in weekdayRange(start, end) }
        }

        if (text.contains("工作日")) {
            return currentDay in 1..5
        }
        if (text.contains("周末")) {
            return currentDay in 6..7
        }

        val explicitDays = Regex("(?:周|星期)([一二三四五六日天])")
            .findAll(text)
            .mapNotNull { match -> chineseWeekdayToIndex(match.groupValues[1]) }
            .toSet()
        return explicitDays.takeIf { it.isNotEmpty() }?.contains(currentDay)
    }

    private fun weekdayRange(start: Int, end: Int): Set<Int> {
        return if (start <= end) {
            (start..end).toSet()
        } else {
            ((start..7) + (1..end)).toSet()
        }
    }

    private fun chineseWeekdayToIndex(value: String): Int? {
        return when (value) {
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "日", "天" -> 7
            else -> null
        }
    }

    private fun parseOpeningMinutes(value: String): Int? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (minute !in 0..59 || hour !in 0..24) return null
        if (hour == 24 && minute != 0) return null
        return hour * 60 + minute
    }

    private fun LocalTime.toOpeningMinutes(): Int {
        return hour * 60 + minute
    }

    private fun containsAny(
        text: String,
        vararg keywords: String
    ): Boolean {
        return keywords.any(text::contains)
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
        private const val POI_SEARCH_CACHE_TTL_MS = 8 * 60 * 1000L
        private const val POI_SEARCH_CACHE_MAX_ENTRIES = 80
        private const val AMAP_SEARCH_MIN_INTERVAL_MS = 360L
        private const val DECISION_POI_LIMIT = 8
        private const val DECISION_POI_PAGE_SIZE = 12
        private const val DECISION_POI_SEARCH_TIMEOUT_MS = 2_800L
        private const val DECISION_MEAL_RADIUS_METERS = 4_500
        private const val DECISION_PLAY_RADIUS_METERS = 18_000
        private const val DECISION_MEAL_MAX_DISTANCE_METERS = 4_200
        private const val DECISION_PLAY_MAX_DISTANCE_METERS = 28_000
        private const val DINING_CENTER_NEARBY_METERS = 5_500
        private const val NEARBY_MALL_RADIUS_METERS = 700
        private const val NEARBY_MALL_PAGE_SIZE = 8
        private const val NEARBY_MALL_QUERY_TIMEOUT_MS = 900L
        private const val NEARBY_MALL_TOTAL_TIMEOUT_MS = 1_600L
        private const val GEOCODE_SEARCH_TIMEOUT_MS = 8_000L
        private const val AMAP_SEARCH_CONNECT_TIMEOUT_MS = 8_000
        private const val AMAP_SEARCH_SOCKET_TIMEOUT_MS = 10_000
        private const val MIN_ROUTE_DISTANCE_KM = 0.08
        private const val PRIMARY_POI_ALIAS_EXTRA_CHARS = 3
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
        private val genericPoiTitles = setOf(
            "武汉",
            "武汉市",
            "江岸区",
            "江汉区",
            "硚口区",
            "汉阳区",
            "武昌区",
            "青山区",
            "洪山区",
            "江夏区",
            "光谷",
            "街道口",
            "江汉路",
            "楚河汉街",
            "武汉天地",
            "汉口里",
            "广埠屯",
            "中南路"
        )
        private val genericPoiTypeKeywords = listOf(
            "行政地名",
            "区县级地名",
            "乡镇级地名",
            "道路名",
            "普通地名",
            "商圈"
        )
        private val emergencyOrigin = UserLocationSnapshot(
            label = "武大与湖大之间",
            latitude = 30.5609,
            longitude = 114.3552,
            source = "route_origin_fallback"
        )
        private val hudaMixcDiningCenter = UserLocationSnapshot(
            label = "湖北大学-武昌万象城附近",
            latitude = 30.5760,
            longitude = 114.3435,
            source = "huda_mixc_dining_anchor"
        )

    }
}
