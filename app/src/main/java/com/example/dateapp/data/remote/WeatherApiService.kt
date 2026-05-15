package com.example.dateapp.data.remote

import okhttp3.OkHttpClient
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface WeatherApiService {

    @GET("v1/forecast")
    suspend fun getCurrentWeather(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "temperature_2m,weather_code,is_day,precipitation,rain,showers,snowfall",
        @Query("timezone") timezone: String = "auto"
    ): WeatherResponse
}

data class WeatherResponse(
    val current: WeatherCurrent? = null
)

data class WeatherCurrent(
    val time: String? = null,
    @com.google.gson.annotations.SerializedName("temperature_2m")
    val temperature: Double? = null,
    @com.google.gson.annotations.SerializedName("weather_code")
    val weatherCode: Int? = null,
    @com.google.gson.annotations.SerializedName("is_day")
    val isDay: Int? = null,
    val precipitation: Double? = null,
    val rain: Double? = null,
    val showers: Double? = null,
    val snowfall: Double? = null
)

object WeatherNetworkModule {

    private const val WEATHER_BASE_URL = "https://api.open-meteo.com/"

    val apiService: WeatherApiService by lazy {
        retrofit2.Retrofit.Builder()
            .baseUrl(WEATHER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(WeatherApiService::class.java)
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(6, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }
}
