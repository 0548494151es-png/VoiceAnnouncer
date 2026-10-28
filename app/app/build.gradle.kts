plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// מספר הגרסה מגיע מ-GitHub Actions (-Pvc=מספר הריצה), כך שכל בנייה חדשה גבוהה מהקודמת
val vc: Int = (project.findProperty("vc") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.voiceannouncer.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voiceannouncer.app"
        minSdk = 26
        targetSdk = 34
        versionCode = vc
        versionName = "1.$vc"
    }

    // חתימה קבועה - חובה כדי שעדכון מתוך האפליקציה יצליח להתקין מעל הגרסה הקודמת
    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "voiceann123"
            keyAlias = "voiceann"
            keyPassword = "voiceann123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
