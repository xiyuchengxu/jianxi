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
        versionCode = 29
        versionName = "4.1.0"
        buildConfigField("String", "INTERNAL_VERSION", "\"0.1.28\"")
        manifestPlaceholders["internalVersion"] = "0.1.28"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("production") {
            System.getenv("JIANXI_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }?.let {
                storeFile = file(it)
                storePassword = System.getenv("JIANXI_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("JIANXI_RELEASE_KEY_ALIAS") ?: "jianxi-release"
                keyPassword = System.getenv("JIANXI_RELEASE_KEY_PASSWORD") ?: storePassword
                storeType = "PKCS12"
            }
        }
        configureEach {
            // Include JAR certificates for installers that inspect v1, while retaining modern signatures.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("production")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("production") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

val verifyProductionSigning by tasks.registering {
    group = "verification"
    description = "Require production signing credentials before packaging a distributable APK."
    doLast {
        val signing = android.signingConfigs.getByName("production")
        check(signing.storeFile?.isFile == true &&
            !signing.storePassword.isNullOrBlank() &&
            !signing.keyAlias.isNullOrBlank() &&
            !signing.keyPassword.isNullOrBlank()) {
            "Configure the production signing environment variables described in RELEASE_SIGNING.md."
        }
    }
}
tasks.matching { it.name == "packageProduction" || it.name == "packageRelease" }.configureEach {
    dependsOn(verifyProductionSigning)
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
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
