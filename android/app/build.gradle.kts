import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations

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

    signingConfigs {
        // 배포 서명 키는 레포 밖에 둔다(docs/contexa_deploy_plan.md 5절). 잃어버리면 같은 앱으로 업데이트를 낼 수 없다.
        // 키 파일: CONTEXA_KEYSTORE(기본 ~/.contexa/contexa-release.jks), 비밀번호: CONTEXA_KEYSTORE_PASSWORD.
        // 둘 중 하나라도 없으면 release는 서명 없이 만들어진다.
        val keystore = file(
            providers.environmentVariable("CONTEXA_KEYSTORE")
                .getOrElse("${System.getProperty("user.home")}/.contexa/contexa-release.jks")
        )
        val password = providers.environmentVariable("CONTEXA_KEYSTORE_PASSWORD").orNull
        if (keystore.isFile && password != null) {
            create("release") {
                storeFile = keystore
                storePassword = password
                keyAlias = "contexa"
                keyPassword = password
            }
        }
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
            // 데모 서버가 생기기 전에 만든 APK는 contexa.invalid로 가므로 공개하지 않는다.
            val apiBaseUrl = providers.gradleProperty("apiBaseUrl")
                .getOrElse("https://contexa.invalid/api/v1/")
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            signingConfig = signingConfigs.findByName("release")
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
    implementation(libs.compose.material.icons.core)
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

/**
 * 연결된 기기마다 8090 포트를 이 컴퓨터의 server로 돌린다(adb reverse). 기기를 다시 켜면 다시 해야 한다.
 * 휴대폰과 에뮬레이터가 함께 연결돼 있어도 모두 돌린다. ANDROID_SERIAL이 있으면 그 기기만 돌린다.
 */
abstract class ReversePort @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input
    abstract val adb: Property<String>

    @get:Input
    abstract val port: Property<Int>

    @TaskAction
    fun run() {
        val serials = System.getenv("ANDROID_SERIAL")?.takeIf { it.isNotBlank() }?.let(::listOf) ?: connected()
        if (serials.isEmpty()) throw GradleException("연결된 기기가 없습니다. 에뮬레이터를 켜거나 휴대폰을 USB로 연결하세요(USB 디버깅 허용).")
        val tcp = "tcp:${port.get()}"
        serials.forEach { serial ->
            exec.exec { commandLine(adb.get(), "-s", serial, "reverse", tcp, tcp) }
            logger.lifecycle("$serial: $tcp → 이 컴퓨터 $tcp")
        }
    }

    /** "adb devices"에서 쓸 수 있는 기기(상태가 device)만. unauthorized는 휴대폰에서 디버깅을 허용해야 한다. */
    private fun connected(): List<String> {
        val out = ByteArrayOutputStream()
        exec.exec {
            commandLine(adb.get(), "devices")
            standardOutput = out
        }
        val rows = out.toString().lines().drop(1).map { it.trim().split(Regex("\\s+")) }.filter { it.size >= 2 }
        rows.filter { it[1] == "unauthorized" }.forEach { logger.warn("${it[0]}: 휴대폰 화면에서 USB 디버깅을 허용해 주세요") }
        return rows.filter { it[1] == "device" }.map { it[0] }
    }
}

val reversePort = tasks.register<ReversePort>("reversePort") {
    group = "contexa"
    description = "연결된 기기마다 adb reverse tcp:8090 tcp:8090"
    adb.set(androidComponents.sdkComponents.adb.map { it.asFile.absolutePath })
    port.set(8090)
}

tasks.matching { it.name == "installDebug" }.configureEach { dependsOn(reversePort) }
