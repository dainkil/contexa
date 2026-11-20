plugins {
    // Kotlin 지원은 Android Gradle Plugin 9에 들어 있다(별도 kotlin-android 플러그인 없음).
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.contexa.android"
    // Compose 1.13이 API 37.1 이상으로 컴파일하기를 요구한다.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.contexa.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // server는 이 컴퓨터의 127.0.0.1:8090에만 붙어 있다. `adb reverse tcp:8090 tcp:8090`으로
            // 기기(에뮬레이터 포함)의 8090 포트를 이 컴퓨터로 돌려서 붙는다(reversePort 작업).
            // 에뮬레이터의 10.0.2.2는 쓰지 않는다: 에뮬레이터 37.3에서 앱의 연결이 시간 초과로 실패했다.
            val apiBaseUrl = providers.gradleProperty("apiBaseUrl")
                .getOrElse("http://127.0.0.1:8090/api/v1/")
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

// 연결된 기기의 8090 포트를 이 컴퓨터의 server로 돌린다. 기기를 다시 켜면 다시 해야 한다.
val reversePort by tasks.registering(Exec::class) {
    group = "contexa"
    description = "adb reverse tcp:8090 tcp:8090"
    executable = androidComponents.sdkComponents.adb.get().asFile.absolutePath
    args("reverse", "tcp:8090", "tcp:8090")
}

tasks.matching { it.name == "installDebug" }.configureEach { dependsOn(reversePort) }
