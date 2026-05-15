import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun readLocalProperty(name: String, defaultValue: String = ""): String {
    return localProperties.getProperty(name, defaultValue)
}

fun String.asBuildConfigField(): String {
    return "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

val amapApiKey = readLocalProperty(
    name = "amap.apiKey",
    defaultValue = ""
)
val amapSdkBundleVersion = "11.1.200_loc11.1.200_sea9.7.4"

android {
    namespace = "com.example.dateapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.dateapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "AI_BASE_URL",
            readLocalProperty("ai.baseUrl", "https://www.xyxyapi.com/").asBuildConfigField()
        )
        buildConfigField(
            "String",
            "AI_API_KEY",
            readLocalProperty("ai.apiKey").asBuildConfigField()
        )
        buildConfigField(
            "String",
            "AI_MODEL",
            readLocalProperty("ai.model", "gpt-5.4-mini").asBuildConfigField()
        )
        buildConfigField(
            "String",
            "AI_DECISION_MODEL",
            readLocalProperty("ai.decisionModel", "gpt-5.4-mini").asBuildConfigField()
        )
        buildConfigField(
            "String",
            "AMAP_API_KEY",
            amapApiKey.asBuildConfigField()
        )
        manifestPlaceholders["AMAP_API_KEY"] = amapApiKey

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.0")

    implementation("androidx.compose.ui:ui:1.9.5")
    implementation("androidx.compose.ui:ui-graphics:1.9.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.5")
    implementation("androidx.compose.foundation:foundation:1.9.5")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.amap.api:3dmap-location-search:$amapSdkBundleVersion")
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.9.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.9.5")
}
