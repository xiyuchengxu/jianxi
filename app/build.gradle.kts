plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.rain.laundry"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.rain.laundry"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "1.0.1"
        buildConfigField("String", "INTERNAL_VERSION", "\"0.1.17\"")
        manifestPlaceholders["internalVersion"] = "0.1.17"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("development-debug.p12")
            storePassword = "android"
            keyAlias = "simplelaundrydebug"
            keyPassword = "android"
            storeType = "PKCS12"
        }
        create("production") {
            System.getenv("JIANXI_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }?.let {
                storeFile = file(it)
                storePassword = System.getenv("JIANXI_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("JIANXI_RELEASE_KEY_ALIAS") ?: "jianxi-release"
                keyPassword = System.getenv("JIANXI_RELEASE_KEY_PASSWORD") ?: storePassword
                storeType = "PKCS12"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("optimized") {
            initWith(getByName("release"))
            // Same upgrade signature and field names as previous test builds, without debugger/tooling overhead.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
        }
        create("production") {
            initWith(getByName("optimized"))
            signingConfig = signingConfigs.getByName("production")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.alipay.sdk:alipaysdk-android:15.8.42")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
