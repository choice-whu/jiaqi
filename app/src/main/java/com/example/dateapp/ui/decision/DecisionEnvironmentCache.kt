package com.example.dateapp.ui.decision

import android.os.SystemClock
import com.example.dateapp.data.environment.DecisionEnvironmentRepository
import com.example.dateapp.data.environment.DecisionEnvironmentSnapshot
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class DecisionEnvironmentCache(
    private val environmentRepository: DecisionEnvironmentRepository,
    private val decisionWeatherLabelProvider: (DecisionEnvironmentSnapshot) -> String,
    private val fastTimeoutMs: Long,
    private val memoryTtlMs: Long,
    private val fallbackTtlMs: Long
) {
    private var cachedSnapshot: DecisionEnvironmentSnapshot? = null
    private var capturedAtMs: Long = 0L

    val latestSnapshot: DecisionEnvironmentSnapshot?
        get() = cachedSnapshot

    suspend fun prewarm(timeoutMs: Long) {
        val warmedSnapshot = withTimeoutOrNull(timeoutMs) {
            environmentRepository.getEnvironmentSnapshot()
        } ?: environmentRepository.getCachedOrFallbackEnvironmentSnapshot()
        cache(warmedSnapshot)
    }

    suspend fun getFastSnapshot(): DecisionEnvironmentSnapshot {
        cachedSnapshot
            ?.takeIf { snapshot ->
                val age = SystemClock.elapsedRealtime() - capturedAtMs
                val ttl = if (snapshot.hasUsableWeather()) memoryTtlMs else fallbackTtlMs
                age <= ttl
            }
            ?.let { return it.withFreshCurrentTime() }

        val repositoryCachedEnvironment = environmentRepository.getCachedOrFallbackEnvironmentSnapshot()
        val fallbackEnvironment = cachedSnapshot
            ?.let { snapshot ->
                if (!snapshot.hasUsableWeather() && repositoryCachedEnvironment.hasUsableWeather()) {
                    repositoryCachedEnvironment
                } else {
                    snapshot
                }
            }
            ?: repositoryCachedEnvironment

        val environment = (withTimeoutOrNull(fastTimeoutMs) {
            environmentRepository.getEnvironmentSnapshot()
        } ?: fallbackEnvironment).withFreshCurrentTime()
        cache(environment)
        return environment
    }

    fun activeEnvironmentKey(): String {
        return environmentKey(cachedSnapshot)
    }

    private fun cache(snapshot: DecisionEnvironmentSnapshot) {
        cachedSnapshot = snapshot
        capturedAtMs = SystemClock.elapsedRealtime()
    }

    private fun environmentKey(snapshot: DecisionEnvironmentSnapshot?): String {
        if (snapshot == null) {
            return "unknown"
        }
        val latBucket = (snapshot.latitude * 100).toInt()
        val lonBucket = (snapshot.longitude * 100).toInt()
        val hourBucket = snapshot.currentTime.hour / 3
        val weatherBucket = decisionWeatherLabelProvider(snapshot)
        return "$latBucket:$lonBucket:$hourBucket:$weatherBucket"
    }

    private fun DecisionEnvironmentSnapshot.withFreshCurrentTime(): DecisionEnvironmentSnapshot {
        val currentTime = ZonedDateTime.now(appZoneId)
        return copy(
            currentTime = currentTime,
            currentTimeLabel = currentTime.format(fullTimeFormatter)
        )
    }

    private fun DecisionEnvironmentSnapshot.hasUsableWeather(): Boolean {
        return weatherCondition != "天气未知" &&
            weatherSource != "fallback" &&
            weatherSource != "timeout_fallback"
    }

    companion object {
        private val appZoneId = ZoneId.of("Asia/Shanghai")
        private val fullTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
