package com.example.dateapp.data.remote

import com.example.dateapp.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object AiNetworkModule {

    private const val FALLBACK_BASE_URL = "https://www.xyxyapi.com/"
    private const val FALLBACK_MODEL = "gpt-5.4-mini"
    private const val FALLBACK_DECISION_MODEL = "gpt-5.4-mini"
    private const val JSON_CONTENT_TYPE = "application/json"

    val defaultModel: String
        get() = BuildConfig.AI_MODEL.trim().ifBlank { FALLBACK_MODEL }

    val decisionModel: String
        get() = BuildConfig.AI_DECISION_MODEL.trim().ifBlank { FALLBACK_DECISION_MODEL }

    val apiService: AiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(normalizedBaseUrl())
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AiApiService::class.java)
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    private val authInterceptor = Interceptor { chain ->
        val apiKey = BuildConfig.AI_API_KEY.trim()
        val request = chain.request()
            .newBuilder()
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", JSON_CONTENT_TYPE)
            .build()
        chain.proceed(request)
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        redactHeader("Authorization")
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private fun normalizedBaseUrl(): String {
        return BuildConfig.AI_BASE_URL.trim().ifBlank { FALLBACK_BASE_URL }.let { baseUrl ->
            if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        }
    }
}
