import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// Release signing. Values are looked up (in order) from:
//   1. keystore.properties in the project root (git-ignored)
//   2. Gradle properties (-P flags or ~/.gradle/gradle.properties)
//   3. Environment variables
// The keystore itself must NEVER be committed.
// ---------------------------------------------------------------------------
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String? =
    keystoreProperties.getProperty(name)
        ?: providers.gradleProperty(name).orNull
        ?: System.getenv(name)

val releaseStoreFile = secret("RELEASE_STORE_FILE")
val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).exists()

// AdMob ids: default to Google's official TEST ids. Override for production.
val admobAppId = secret("ADMOB_APP_ID") ?: "ca-app-pub-3940256099942544~3347511713"
val admobBannerId = secret("ADMOB_BANNER_ID") ?: "ca-app-pub-3940256099942544/6300978111"

android {
    namespace = "com.toasteralley.towerdefense"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.toasteralley.towerdefense"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "2.1.1"

        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBannerId\"")
        // Set to false to use the built-in placeholder banner instead of AdMob.
        buildConfigField("boolean", "USE_ADMOB", "true")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile!!)
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // No applicationIdSuffix: Play Billing products are looked up by
            // applicationId, so debug builds must share the release id to test billing.
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.billing.ktx)
    implementation(libs.play.services.ads)

    testImplementation(libs.junit)
}
