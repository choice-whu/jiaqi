package com.example.dateapp.data.remote

import com.example.dateapp.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface AmapApiService {

    @GET("v3/place/text")
    suspend fun placeTextSearch(
        @Query("keywords") keywords: String,
        @Query("city") city: String,
        @Query("citylimit") cityLimit: Boolean = true,
        @Query("offset") offset: Int = 5,
        @Query("key") key: String = BuildConfig.AMAP_API_KEY
    ): AmapPlaceSearchResponse

    @GET("v3/geocode/geo")
    suspend fun geocode(
        @Query("address") address: String,
        @Query("city") city: String,
        @Query("key") key: String = BuildConfig.AMAP_API_KEY
    ): AmapGeocodeResponse

    @GET("v3/direction/walking")
    suspend fun walkingRoute(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("key") key: String = BuildConfig.AMAP_API_KEY
    ): AmapRouteResponse

    @GET("v3/direction/driving")
    suspend fun drivingRoute(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("strategy") strategy: Int = 0,
        @Query("extensions") extensions: String = "base",
        @Query("key") key: String = BuildConfig.AMAP_API_KEY
    ): AmapRouteResponse
}

data class AmapGeocodeResponse(
    val status: String? = null,
    val info: String? = null,
    val geocodes: List<AmapGeocodeItem>? = null
)

data class AmapGeocodeItem(
    val formatted_address: String? = null,
    val location: String? = null
)

data class AmapPlaceSearchResponse(
    val status: String? = null,
    val info: String? = null,
    val pois: List<AmapPoiItem>? = null
)

data class AmapPoiItem(
    val name: String? = null,
    val address: String? = null,
    val location: String? = null,
    val pname: String? = null,
    val cityname: String? = null,
    val adname: String? = null
)

data class AmapRouteResponse(
    val status: String? = null,
    val info: String? = null,
    val route: AmapRouteBody? = null
)

data class AmapRouteBody(
    val paths: List<AmapRoutePath>? = null
)

data class AmapRoutePath(
    val distance: String? = null,
    val duration: String? = null
)

object AmapNetworkModule {

    private const val AMAP_BASE_URL = "https://restapi.amap.com/"

    val apiService: AmapApiService by lazy {
        Retrofit.Builder()
            .baseUrl(AMAP_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AmapApiService::class.java)
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }
}
