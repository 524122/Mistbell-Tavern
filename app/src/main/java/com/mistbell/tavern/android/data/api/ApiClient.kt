package com.mistbell.tavern.android.data.api

import android.content.Context
import com.mistbell.tavern.android.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val PREFS = "mistbell_android"
    private const val KEY_SERVER_URL = "server_url"
    private const val DEFAULT_URL = "http://10.0.2.2:3000/"

    // 可被 setServerUrl 重置，多线程并发访问：必须 @Volatile + 同步块，
    // 否则 check-then-act 竞态会重复创建实例或读到半初始化引用
    @Volatile
    private var retrofit: Retrofit? = null

    @Volatile
    private var api: TavernApi? = null

    val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    fun getApi(context: Context): TavernApi {
        api?.let { return it }
        return synchronized(this) {
            api?.let { return it }

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val baseUrl = normalizeUrl(prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL)

            val logging =
                HttpLoggingInterceptor().apply {
                    // BODY 级会把完整请求头（含 Authorization 密钥）与请求体打进 logcat，
                    // 仅在 debug 构建保留 BASIC 级别的诊断信息，release 一律关闭
                    level =
                        if (BuildConfig.DEBUG) {
                            HttpLoggingInterceptor.Level.BASIC
                        } else {
                            HttpLoggingInterceptor.Level.NONE
                        }
                }

            val client =
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(logging)
                    .build()

            retrofit =
                Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()

            api = retrofit!!.create(TavernApi::class.java)
            api!!
        }
    }

    fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return normalizeUrl(prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL)
    }

    fun setServerUrl(
        context: Context,
        url: String,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_URL, normalizeUrl(url)).apply()
        // Reset retrofit to use new URL（与 getApi 的锁一致，避免重置与创建竞态）
        synchronized(this) {
            retrofit = null
            api = null
        }
    }

    private fun normalizeUrl(url: String): String {
        var result = url.trim()
        if (!result.startsWith("http://") && !result.startsWith("https://")) {
            result = "http://$result"
        }
        if (!result.endsWith("/")) result += "/"
        return result
    }
}
