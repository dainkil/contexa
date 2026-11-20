plugins {
    // Kotlin 지원은 Android Gradle Plugin 9에 들어 있다(별도 kotlin-android 플러그인 없음).
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.contexa.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.contexa.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // 에뮬레이터에서 10.0.2.2는 이 컴퓨터의 127.0.0.1이다. server는 127.0.0.1:8090에만 붙어 있다.
            // 실제 기기는 `adb reverse tcp:8090 tcp:8090` 뒤 -PapiBaseUrl=http://127.0.0.1:8090/api/v1/ 로 빌드한다.
            val apiBaseUrl = providers.gradleProperty("apiBaseUrl")
                .getOrElse("http://10.0.2.2:8090/api/v1/")
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        }
        release {
            // 배포 주소는 아직 없다(docs/contexa_deploy_plan.md). 빌드할 때 -PapiBaseUrl=https://… 로 넣는다.
            val apiBaseUrl = providers.gradleProperty("apiBaseUrl")
                .getOrElse("https://contexa.invalid/api/v1/")
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            isMinifyEnabled = false
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
