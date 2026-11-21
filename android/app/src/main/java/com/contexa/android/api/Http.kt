package com.contexa.android.api

import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * server와 주고받는 JSON 규칙.
 *  - 모르는 필드는 무시한다(server가 필드를 더해도 앱이 깨지지 않게).
 *  - null인 값은 보내지 않고, 오지 않은 값은 null로 본다.
 */
val contexaJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val log = Logger.getLogger("Contexa")

/**
 * server로 가는 모든 요청이 지나는 클라이언트.
 *
 * @param token 지금 로그인 토큰. 없으면 null.
 * @param onUnauthorized 토큰을 보냈는데 401이 왔을 때(만료·폐기) 부른다.
 *   토큰 없이 받은 401(로그인 실패 등)은 화면이 직접 보여 주므로 부르지 않는다.
 */
fun buildHttpClient(token: () -> String?, onUnauthorized: () -> Unit): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val current = token()
            val request = chain.request().newBuilder()
                .header("Accept", "application/json")
                .apply { if (!current.isNullOrEmpty()) header("Authorization", "Bearer $current") }
                .build()
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                // 연결 실패의 실제 이유를 남긴다(Android에서는 logcat, 테스트에서는 표준 오류로 간다).
                log.log(Level.WARNING, "요청 실패: ${request.method} ${request.url.encodedPath}", e)
                throw e
            }
            if (response.code == 401 && !current.isNullOrEmpty()) onUnauthorized()
            response
        })
        .build()

/** @param baseUrl "…/api/v1/"처럼 /로 끝나야 한다. */
fun buildRetrofit(baseUrl: String, client: OkHttpClient): Retrofit =
    Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(contexaJson.asConverterFactory("application/json".toMediaType()))
        .build()
